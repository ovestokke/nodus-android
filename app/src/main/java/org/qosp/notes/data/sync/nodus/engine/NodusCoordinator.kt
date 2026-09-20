package org.qosp.notes.data.sync.nodus.engine

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.storage.*

internal data class NodusSyncProgress(
    val completedRequests: Int,
    val hasMore: Boolean,
    val blocked: List<String> = emptyList(),
    val projections: ProjectionProgress? = null,
    val retryAtMillis: Long? = null,
    val queueContinuation: NodusQueueContinuation? = null,
    val examinedRows: Int = 0,
    val queueScanComplete: Boolean = false
)

/** Local row IDs only. Per-stream end flags survive bounded calls; both streams wrap
 * to zero together once the scan completes. Activation requires callers to durably
 * persist/pass the complete continuation (including end flags and Retry-After).
 */
internal data class NodusQueueContinuation(val intentRowId: Long = 0, val operationRowId: Long = 0, val retryAtMillis: Long? = null,
    val intentStreamEnded: Boolean = false, val operationStreamEnded: Boolean = false
) {
    init { require(intentRowId >= 0 && operationRowId >= 0) }
}

/** Network entry points serialize per connection. Local planning uses only Room transactions.
 */
internal class NodusCoordinator(
    private val db: AppDatabase,
    private val connectionId: String,
    private val configuration: () -> NodusConfiguration?,
    private val transport: NodusEngineTransport,
    private val byteStore: NodusByteStore? = null,
    private val projectionAllowed: suspend () -> Boolean = {true},
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao get() = db.nodusDao
    val planning = NodusPlanning(db, connectionId)
    val graphs = NodusGraphEncoding(db, connectionId)
    private val projection = NodusProjection(db, connectionId, byteStore, projectionAllowed)
    private val mutex get() = NodusConnectionLocks.get(connectionId)

    suspend fun pullChanges(maxPages: Int = 1, projectionAfter: String = "0"): NodusSyncProgress = mutex.withLock {
        require(maxPages in 1..20)
        val config = checkedConfiguration() ?: return@withLock NodusSyncProgress(0, true, listOf("configuration_mismatch"))
        val discovery = capabilities(config)
        if (discovery != null) return@withLock discovery
        var lastProjection: ProjectionProgress? = null
        val blocks = mutableListOf<String>()
        repeat(maxPages) { index ->
            val cursor = dao.cursor(connectionId, "v2", NodusStream.CHANGES)
            val after = cursor?.cursor ?: "0"
            val query = mutableMapOf("after" to after, "limit" to "100")
            cursor?.until?.let { query["until"] = it }
            val current = checkedConfiguration() ?: return@withLock NodusSyncProgress(index, true, listOf("configuration_mismatch"))
            val result = transport.get(current, "/api/v2/changes", query)
            if (result.status != 200) return@withLock readFailure(result, index)
            val page = decode(V2Changes.serializer(), result)
            val snapshots = page.events.map { event ->
                when (event) {
                    is V2Event.Note -> NodusSnapshot(connectionId, NodusResourceType.NOTE, event.resource.id, event.revision, NodusJson.encode(V2Note.serializer(), event.resource).toByteArray(), event.resource.state == NoteState.PURGED)
                    is V2Event.Tag -> NodusSnapshot(connectionId, NodusResourceType.TAG, event.resource.id, event.revision, NodusJson.encode(V2Tag.serializer(), event.resource).toByteArray(), event.resource.deleted)
                    is V2Event.Notebook -> NodusSnapshot(connectionId, NodusResourceType.NOTEBOOK, event.resource.id, event.revision, NodusJson.encode(V2Notebook.serializer(), event.resource).toByteArray(), event.resource.deleted)
                }
            }
            db.withTransaction {
                require(sameIdentity(current, checkedConfiguration())) { "Configuration changed during read" }
                dao.storePage(connectionId, NodusStream.CHANGES, after, cursor?.until, page.cursor, page.until, page.hasMore, snapshots, emptyList())
            }
            lastProjection = projection.project(100, projectionAfter)
            blocks += lastProjection!!.blocked.map { "${it.wireId}:${it.reason}" }
            if (!page.hasMore) return@withLock NodusSyncProgress(index + 1, false, blocks, lastProjection)
        }
        NodusSyncProgress(maxPages, true, blocks, lastProjection)
    }

    suspend fun projectPending(afterRevision: String = "0", limit: Int = 100): ProjectionProgress = mutex.withLock {
        projection.project(limit, afterRevision)
    }

    suspend fun discoverConflicts(refresh: Boolean = false, maxPages: Int = 1): NodusSyncProgress = mutex.withLock {
        require(maxPages in 1..20)
        val config = checkedConfiguration() ?: return@withLock NodusSyncProgress(0, true, listOf("configuration_mismatch"))
        capabilities(config)?.let { return@withLock it }
        if (refresh) {
            val old = dao.cursor(connectionId, "v2", NodusStream.CONFLICTS)
            dao.restartConflictDiscovery(connectionId, old?.cursor ?: "0", old?.until)
        }
        repeat(maxPages) { index ->
            val old = dao.cursor(connectionId, "v2", NodusStream.CONFLICTS)
            val query = mutableMapOf("after" to (old?.cursor ?: "0"), "limit" to "10")
            old?.until?.let { query["until"] = it }
            val current = checkedConfiguration() ?: return@withLock NodusSyncProgress(index, true, listOf("configuration_mismatch"))
            val response = transport.get(current, "/api/v2/conflicts", query)
            if (response.status != 200) return@withLock readFailure(response, index)
            val page = decode(V2Conflicts.serializer(), response)
            db.withTransaction {
                require(sameIdentity(current, checkedConfiguration()))
                dao.storePage(connectionId, NodusStream.CONFLICTS, old?.cursor ?: "0", old?.until, page.cursor, page.until, page.hasMore, emptyList(),
                    page.conflicts.map { NodusConflictRecord(connectionId, it.id, it.state.name.lowercase(), it.parent, NodusJson.encode(V2Conflict.serializer(), it).toByteArray()) })
            }
            if (!page.hasMore) return@withLock NodusSyncProgress(index + 1, false)
        }
        NodusSyncProgress(maxPages, true)
    }

    suspend fun applyConflict(mappingId: String, conflictId: String, freshExpectedRevision: String): String {
        requireDecimal(freshExpectedRevision)
        return planning.enqueueResolution(mappingId, conflictId, freshExpectedRevision)
    }
    suspend fun discardConflict(mappingId: String, conflictId: String): String = planning.enqueueResolution(mappingId, conflictId, null)

    /** Sending and scanning have separate bounds; every examined blocked row advances its
     * local continuation. Successful sends may revisit unprepared dependencies, within the
     * same examined-row budget. End-of-stream wraps only via the returned zero continuation.
     */
    suspend fun drain(maxOperations: Int = 20, maxExaminedRows: Int = 200,
        continuation: NodusQueueContinuation = NodusQueueContinuation()
    ): NodusSyncProgress = mutex.withLock {
        require(maxOperations in 1..100 && maxExaminedRows in 2..2000)
        var intentRow = continuation.intentRowId
        var operationRow = continuation.operationRowId
        var intentEnd = continuation.intentStreamEnded
        var operationEnd = continuation.operationStreamEnded
        var examined = 0
        var attempts = 0
        var completed = 0
        var deferred: NodusIntent? = null
        val blocks = mutableListOf<String>()
        fun progress(more: Boolean = true, retryAt: Long? = null) = NodusSyncProgress(completed, more, blocks.distinct(),
            retryAtMillis = retryAt, queueContinuation = if (intentEnd && operationEnd) NodusQueueContinuation(retryAtMillis = retryAt)
                else NodusQueueContinuation(intentRow, operationRow, retryAt, intentEnd, operationEnd),
            examinedRows = examined, queueScanComplete = intentEnd && operationEnd)
        continuation.retryAtMillis?.let { if (now() < it) return@withLock progress(retryAt = it) }
        val initial = checkedConfiguration()
        if (initial == null) {
            while (examined < maxExaminedRows) {
                val row = dao.nextPendingOperation(connectionId, operationRow)
                if (row == null) { operationEnd = true; break }
                operationRow = row.scanRowId; examined++
                if (dao.latestEvidence(connectionId, row.operation.operationId)?.errorCode != "manual:credential_epoch_changed") {
                    unknown(row.operation, null, "manual:credential_epoch_changed")
                }
            }
            blocks += "configuration_mismatch"
            return@withLock progress()
        }
        capabilities(initial)?.let { return@withLock it }
        while (attempts < maxOperations && examined < maxExaminedRows) {
            if (!intentEnd) {
                val row = dao.nextUnpreparedIntent(connectionId, intentRow)
                if (row == null) intentEnd = true
                else {
                    intentRow = row.scanRowId; examined++
                    if (planning.materialize(row.intent)) operationEnd = false
                    else {
                        deferred = row.intent
                        blocks += "${row.intent.intentId}:unprepared_dependency_epoch_or_private"
                    }
                }
            }
            if (examined >= maxExaminedRows) break
            if (operationEnd) {
                if (intentEnd) return@withLock progress(dao.hasQueueWork(connectionId))
                continue
            }
            val row = dao.nextPendingOperation(connectionId, operationRow)
            if (row == null) {
                operationEnd = true
                if (intentEnd) return@withLock progress(dao.hasQueueWork(connectionId))
                continue
            }
            operationRow = row.scanRowId; examined++
            val operation = row.operation
            val config = checkedConfiguration()
            if (config == null || config.credentialEpoch != operation.credentialEpoch || config.deviceId != operation.deviceId) {
                if (dao.latestEvidence(connectionId, operation.operationId)?.errorCode != "manual:credential_epoch_changed") unknown(operation, null, "manual:credential_epoch_changed")
                blocks += "${operation.operationId}:credential_epoch_changed"
                continue
            }
            if (operation.contentType != "application/json") { blocks += "${operation.operationId}:binary_transfer_deferred"; continue }
            val code = dao.latestEvidence(connectionId, operation.operationId)?.errorCode
            if (code?.startsWith("manual:") == true) { blocks += "${operation.operationId}:$code"; continue }
            val retryAt = code?.takeIf { it.startsWith("retry_at:") }?.removePrefix("retry_at:")?.toLongOrNull()
            if (retryAt != null && now() < retryAt) return@withLock progress(retryAt = retryAt)
            val dependency = dependencyBlock(operation)
            if (dependency != null) { blocks += "${operation.operationId}:$dependency"; continue }
            dao.markAttempt(connectionId, operation.operationId)
            attempts++
            val result = try {
                transport.send(config, operation)
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) { unknown(operation, null, "retry_at:${now() + 1000}") }
                throw cancel
            } catch (_: Exception) {
                val deadline = now() + 1000
                unknown(operation, null, "retry_at:$deadline")
                blocks += "transport_unknown"
                return@withLock progress(retryAt = deadline)
            }
            val outcome = runCatching { NodusOutcome.classify(result.status, wireString(result.body), operation.path.endsWith("/discard")) }
                .getOrElse { NodusOutcome(OutcomeKind.UNKNOWN) }
            when (outcome.kind) {
                OutcomeKind.SUCCESS, OutcomeKind.DISCARDED, OutcomeKind.DURABLE_CONFLICT, OutcomeKind.TERMINAL_CONFLICT -> {
                    val kind = if (outcome.kind in setOf(OutcomeKind.SUCCESS, OutcomeKind.DISCARDED)) NodusEvidenceKind.RECEIPT else NodusEvidenceKind.CONFLICT
                    val conflictId = (outcome.error?.conflictId as? WireField.Present)?.value
                    try {
                        db.withTransaction {
                            dao.recordOutcome(NodusEvidence(connectionId, newNodusId(), operation.operationId, operation.credentialEpoch, kind, result.status, result.body, conflictId, null))
                            dao.retire(connectionId, operation.operationId)
                        }
                    } catch (_: Exception) {
                        unknown(operation, result, "manual:invalid_receipt")
                        blocks += "invalid_receipt"
                        return@withLock progress()
                    }
                    completed++
                    if (kind == NodusEvidenceKind.CONFLICT) blocks += "${operation.operationId}:durable_conflict"
                    // Revisit only the just-examined proven dependent, without rewinding
                    // either scan past unrelated blocked entries. The revisit is budgeted.
                    val candidate = deferred
                    if (candidate != null && examined < maxExaminedRows) {
                        val draft = decodeDraft(candidate)
                        if (!draft.privateOnly && (draft.predecessor == operation.operationId ||
                                draft.itemExpectation?.operationIds?.contains(operation.operationId) == true)) {
                            examined++
                            if (planning.materialize(candidate)) { deferred = null; operationEnd = false }
                        }
                    }
                }
                OutcomeKind.RATE_LIMITED, OutcomeKind.UNAVAILABLE -> {
                    val deadline = retryDeadline(result.retryAfter)
                    unknown(operation, result, "retry_at:$deadline")
                    return@withLock progress(retryAt = deadline)
                }
                else -> {
                    unknown(operation, result, "manual:${outcome.kind.name.lowercase()}")
                    blocks += outcome.kind.name.lowercase()
                    return@withLock progress()
                }
            }
        }
        progress()
    }

    private suspend fun dependencyBlock(operation: NodusOutbox): String? {
        if (operation.path.startsWith("/api/v2/conflicts/") && operation.path.endsWith("/discard")) return null
        val mapping = requireNotNull(dao.mapping(connectionId, operation.mappingId))
        val (type, wireId) = resourceTarget(mapping)
        if (type == NodusResourceType.BLOB && dao.blob(connectionId, wireId)?.reservationOperationId != null &&
            !dao.hasPublicBlobReference(connectionId, wireId)) return "private_blob_source"
        if (type == NodusResourceType.NOTE) {
            val root = dao.mappingByWire(connectionId, type, wireId)
            if (root?.localRowDetached == true) return "private_or_detached_note"
            if (root?.localRowId?.let { db.noteDao.getById(it).first()?.isLocalOnly } == true) return "private_local_fork"
        }
        suspend fun verifySource(id: String): String? {
            val blob = dao.blob(connectionId, id) ?: return "blob_source_missing"
            val store = byteStore ?: return "blob_verification_required"
            return try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    store.verify(store.idFromUri(requireNotNull(blob.sourceUri)), requireNotNull(blob.size), requireNotNull(blob.sha256))
                }
                null
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { "blob_source_missing_or_corrupt" }
        }
        if (type == NodusResourceType.BLOB && dao.blob(connectionId, wireId)?.reservationOperationId == operation.operationId) {
            verifySource(wireId)?.let { return it }
        }
        val fields = NodusJson.format.parseToJsonElement(wireString(requireNotNull(operation.body))).jsonObject
        suspend fun live(type: NodusResourceType, id: String): Boolean = dao.snapshot(connectionId, type, id)?.tombstone == false
        fields["tagIds"]?.jsonArray?.forEach { if (!live(NodusResourceType.TAG, it.jsonPrimitive.content)) return "organization_pending" }
        fields["notebookId"]?.takeIf { it != JsonNull }?.let { if (!live(NodusResourceType.NOTEBOOK, it.jsonPrimitive.content)) return "organization_pending" }
        val blobIds = buildList {
            fields["blobId"]?.jsonPrimitive?.content?.let(::add)
            fields["attachments"]?.jsonArray?.forEach { add(it.jsonObject.getValue("blobId").jsonPrimitive.content) }
        }
        for (id in blobIds) {
            val transfer = dao.blob(connectionId, id) ?: return "blob_not_ready"
            if (transfer.state !in setOf(NodusReadiness.READY, NodusReadiness.AVAILABLE)) return "blob_not_ready"
            transfer.uploadOperationId?.let { operationId ->
                if (dao.latestEvidence(connectionId, operationId)?.kind != NodusEvidenceKind.RECEIPT || dao.outbox(connectionId, operationId)?.state != NodusOutboxState.RETIRED) return "blob_upload_unknown"
            }
            val metadata = dao.snapshot(connectionId, NodusResourceType.BLOB, id) ?: return "blob_not_ready"
            if (NodusJson.decode(V2Blob.serializer(), wireString(metadata.body)).state != BlobState.READY) return "blob_not_ready"
            verifySource(id)?.let { return it }
        }
        return null
    }

    private suspend fun unknown(operation: NodusOutbox, response: NodusHttpResult?, code: String) {
        dao.recordOutcome(NodusEvidence(connectionId, newNodusId(), operation.operationId, operation.credentialEpoch, NodusEvidenceKind.UNKNOWN,
            response?.status, response?.body, null, code))
    }
    private suspend fun checkedConfiguration(): NodusConfiguration? {
        val config = configuration() ?: return null
        val connection = dao.connection(connectionId) ?: return null
        return config.takeIf { it.origin.toString() == connection.origin && it.deviceId == connection.deviceId && it.credentialEpoch == connection.credentialEpoch }
    }
    private fun sameIdentity(a: NodusConfiguration, b: NodusConfiguration?) = b != null && a.origin == b.origin && a.deviceId == b.deviceId && a.credentialEpoch == b.credentialEpoch
    private suspend fun capabilities(config: NodusConfiguration): NodusSyncProgress? {
        val response = transport.get(config, "/api/v2/capabilities")
        if (response.status != 200) return readFailure(response, 0)
        val capabilities=decode(V2Capabilities.serializer(), response)
        if(capabilities.realmId!=dao.connection(connectionId)?.realmId) return NodusSyncProgress(0,true,listOf("realm_mismatch"))
        return null
    }
    private fun readFailure(result: NodusHttpResult, completed: Int): NodusSyncProgress {
        val kind = NodusOutcome.classify(result.status, wireString(result.body)).kind
        return NodusSyncProgress(completed, true, listOf(kind.name.lowercase()), retryAtMillis = if (kind in setOf(OutcomeKind.RATE_LIMITED, OutcomeKind.UNAVAILABLE)) retryDeadline(result.retryAfter) else null)
    }
    private fun retryDeadline(value: String?): Long {
        val digits = value?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val seconds = if (digits == null) 60 else (digits.toLongOrNull() ?: Long.MAX_VALUE).coerceAtLeast(1)
        val current = now()
        return if (seconds > (Long.MAX_VALUE - current) / 1000) Long.MAX_VALUE else current + seconds * 1000
    }
    private fun <T> decode(serializer: KSerializer<T>, response: NodusHttpResult): T = NodusJson.decode(serializer, wireString(response.body))

}

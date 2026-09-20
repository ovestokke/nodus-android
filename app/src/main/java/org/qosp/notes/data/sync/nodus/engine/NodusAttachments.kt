package org.qosp.notes.data.sync.nodus.engine

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.storage.*
import java.io.InputStream

internal data class NodusAttachmentProgress(val ready: Boolean, val blocked: String? = null, val retryAtMillis: Long? = null)

/** Isolated byte orchestration. Capture journals the reference before local I/O; no URI is sent.
 * Ref creation remains protected by a durable pending intent throughout reserve/upload.
 */
internal class NodusAttachments(private val db: AppDatabase, private val connectionId: String,
    private val configuration: () -> NodusConfiguration?, private val bytes: NodusByteStore,
    private val json: NodusEngineTransport, private val binary: NodusBinaryTransport,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao get() = db.nodusDao
    private val planning = NodusPlanning(db, connectionId)

    suspend fun capture(noteMappingId: String, localKey: String, original: Attachment, mediaType: String,
        reuseBlobId: String? = null, openSource: () -> InputStream
    ): NodusAttachmentTransfer = captureLocks.getOrPut(connectionId) { Mutex() }.withLock {
        val ref = journal(noteMappingId, localKey, original, reuseBlobId)
        var blob = requireNotNull(dao.blob(connectionId, ref.blobId))
        val mapping=requireNotNull(dao.mappingByWire(connectionId,NodusResourceType.ATTACHMENT,ref.attachmentId,ref.noteId))
        val frozen=dao.firstMappingIntent(connectionId,mapping.mappingId) ?: ref.operationId?.let { dao.intent(connectionId,it) }
        if(frozen!=null && decodeDraft(frozen).credentialEpoch!=configuration()?.credentialEpoch) {
            block(ref,"credential_epoch_changed")
            return@withLock requireNotNull(dao.attachment(connectionId,ref.noteId,ref.attachmentId))
        }
        if (ref.state == NodusReadiness.ERROR || (blob.sourceUri != null && blob.size != null) || blob.state == NodusReadiness.ERROR || reuseBlobId != null) return@withLock ref
        try {
            requireMediaType(mediaType)
            val sourceId = bytes.idFromUri(requireNotNull(blob.sourceUri))
            val stored = withContext(Dispatchers.IO) { if (bytes.file(sourceId).isFile) bytes.inspect(sourceId) else bytes.install(openSource, id = sourceId) }
            db.withTransaction {
                blob = requireNotNull(dao.blob(connectionId, ref.blobId))
                require(blob.size == null)
                val captured = blob.copy(size = stored.size, sha256 = stored.sha256, sourceUri = bytes.file(stored.id).toURI().toString())
                val map = requireNotNull(dao.mappingByWire(connectionId, NodusResourceType.BLOB, ref.blobId))
                val reservation = planning.enqueue(map.mappingId) { V2BlobReserve(it.deviceId, it.requestId, stored.size, stored.sha256, mediaType) }
                dao.storeReadiness(captured.copy(reservationOperationId = reservation), listOf(ref.copy(sourceUri = captured.sourceUri)))
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { block(ref, "capture_interrupted") }; throw cancel
        } catch (error: Exception) { block(ref, if (error.message == "quota_exceeded") "quota_exceeded" else "source_inaccessible_invalid_or_oversized") }
        requireNotNull(dao.attachment(connectionId, ref.noteId, ref.attachmentId))
    }

    internal suspend fun journal(noteMappingId: String, localKey: String, original: Attachment,
        reuseBlobId: String? = null, validateMembership: Boolean = true, initial:Boolean=false): NodusAttachmentTransfer {
        return db.withTransaction {
            val root = requireNotNull(dao.mapping(connectionId, noteMappingId))
            require(root.resourceType == NodusResourceType.NOTE && !root.localRowDetached)
            val existing = dao.mappingByLocalKey(connectionId, NodusResourceType.ATTACHMENT, localKey, root.wireId)
            if (existing != null) return@withTransaction requireNotNull(dao.attachment(connectionId, root.wireId, existing.wireId))
            // The caller commits visible local metadata before handing us its explicit
            // lifetime key. This is membership validation, never wire identity matching.
            val local = requireNotNull(root.localRowId?.let { db.noteDao.getById(it).first() })
            require(!local.isLocalOnly && (!validateMembership || original in local.attachments)) { "Shared local attachment metadata must be committed before capture" }
            val limitExceeded = dao.childMappings(connectionId, root.wireId).count { it.resourceType == NodusResourceType.ATTACHMENT } >= 1000
            val blob = if (reuseBlobId == null) planning.allocate(NodusResourceType.BLOB, newNodusId(), null)
                else requireNotNull(dao.mappingByWire(connectionId, NodusResourceType.BLOB, reuseBlobId))
            val mapping = planning.allocate(NodusResourceType.ATTACHMENT, localKey, null, root.wireId)
            val frozen = runCatching { V2AttachmentInput(mapping.wireId, blob.wireId, AttachmentKind.valueOf(original.type.name), WireField.Present(original.description), WireField.Present(original.fileName)) }.getOrNull()
            if (limitExceeded || frozen == null) {
                val code = if (limitExceeded) "lifetime_reference_limit" else "invalid_attachment_metadata"
                val blocked = NodusAttachmentTransfer(connectionId, root.wireId, mapping.wireId, blob.wireId, original.path, NodusReadiness.ERROR, code)
                val state = dao.blob(connectionId, blob.wireId) ?: NodusBlobTransfer(connectionId, blob.wireId, null, null, null, NodusReadiness.ERROR, code)
                dao.storeReadiness(state, listOf(blocked))
                return@withTransaction blocked
            }
            val intentId = if(initial) null else planning.enqueue(mapping.mappingId) { V2AttachmentCreate(it.deviceId, it.requestId, it.revision,
                frozen.id, frozen.blobId, frozen.kind, frozen.description, frozen.fileName) }
            val transfer = NodusAttachmentTransfer(connectionId, root.wireId, mapping.wireId, blob.wireId, original.path,
                NodusReadiness.LOCAL, null, NodusJson.encode(V2AttachmentInput.serializer(), frozen).toByteArray(),
                intentId?.takeIf { dao.outbox(connectionId, it) != null })
            val state = dao.blob(connectionId, blob.wireId) ?: NodusBlobTransfer(connectionId, blob.wireId, null, null, bytes.file(newNodusId()).toURI().toString(), NodusReadiness.LOCAL, null)
            dao.storeReadiness(state, listOf(transfer))
            transfer
        }
    }

    /** At most reserve + upload + reference writes and bounded metadata/capability reads. */
    suspend fun advance(noteId: String, attachmentId: String): NodusAttachmentProgress = NodusConnectionLocks.get(connectionId).withLock {
        var ref = requireNotNull(dao.attachment(connectionId, noteId, attachmentId))
        var blob = requireNotNull(dao.blob(connectionId, ref.blobId))
        val root = requireNotNull(dao.mappingByWire(connectionId, NodusResourceType.NOTE, noteId))
        val canonical = dao.snapshot(connectionId, NodusResourceType.NOTE, noteId)?.let { NodusJson.decode(V2Note.serializer(), wireString(it.body)) }
        if (canonical?.state == NoteState.PURGED || canonical?.attachments?.any { it.id == attachmentId && it.deleted } == true) return@withLock NodusAttachmentProgress(false, "reference_or_note_deleted")
        val local = root.localRowId?.let { db.noteDao.getById(it).first() }
        if (root.localRowDetached || local == null || local.isLocalOnly) return@withLock NodusAttachmentProgress(false, "private_or_detached_note")
        readDeadline(blob)?.let { return@withLock it }
        if (ref.state == NodusReadiness.ERROR) return@withLock NodusAttachmentProgress(false, ref.errorCode)
        if (blob.state == NodusReadiness.ERROR) return@withLock NodusAttachmentProgress(false, blob.errorCode)
        val config = checkedConfiguration() ?: return@withLock NodusAttachmentProgress(false, "configuration_mismatch")
        val refMapping=dao.mappingByWire(connectionId,NodusResourceType.ATTACHMENT,attachmentId,noteId)
        val captured=refMapping?.let { dao.firstMappingIntent(connectionId,it.mappingId) } ?: ref.operationId?.let { dao.intent(connectionId,it) }
        if(captured!=null && decodeDraft(captured).credentialEpoch!=config.credentialEpoch) {
            for(id in listOfNotNull(blob.reservationOperationId,blob.uploadOperationId,ref.operationId,captured.intentId).distinct()) {
                dao.outbox(connectionId,id)?.takeIf { it.state!=NodusOutboxState.RETIRED }?.let { unknown(it,null,"manual:credential_epoch_changed") }
            }
            return@withLock NodusAttachmentProgress(false,"credential_epoch_changed")
        }
        try {
            val capabilities = read(config, "/api/v2/capabilities")
            require(capabilities.status == 200)
            require(NodusJson.decode(V2Capabilities.serializer(), wireString(capabilities.body)).realmId==dao.connection(connectionId)?.realmId) { "realm_mismatch" }
            val source = requireNotNull(blob.sourceUri)
            val fileId = bytes.idFromUri(source)
            withContext(Dispatchers.IO) { bytes.verify(fileId, requireNotNull(blob.size), requireNotNull(blob.sha256)) }
            if (blob.reservationOperationId != null) {
                send(requireNotNull(dao.outbox(connectionId, blob.reservationOperationId)), noteId)?.let { return@withLock it }
            }
            val response = read(requireNotNull(checkedConfiguration()), "/api/v2/blobs/${blob.blobId}")
            require(response.status == 200)
            val metadata = NodusJson.decode(V2Blob.serializer(), wireString(response.body))
            require(metadata.id == blob.blobId && metadata.size == blob.size && metadata.sha256 == blob.sha256)
            blob.reservationOperationId?.let { id ->
                val request = requireNotNull(dao.outbox(connectionId, id))
                require(metadata.mediaType == NodusJson.decode(V2BlobReserve.serializer(), wireString(requireNotNull(request.body))).mediaType)
            }
            db.withTransaction {
                dao.storeBlobMetadata(NodusSnapshot(connectionId, NodusResourceType.BLOB, metadata.id, metadata.revision, response.body, false))
                blob = requireNotNull(dao.blob(connectionId, ref.blobId))
                if (blob.uploadOperationId == null && metadata.state != BlobState.READY) {
                    val map = requireNotNull(dao.mappingByWire(connectionId, NodusResourceType.BLOB, blob.blobId))
                    val connection = requireNotNull(dao.connection(connectionId))
                    val id = newNodusId()
                    val generation = dao.recordLocalChange(connectionId, map.mappingId, dao.tracking(connectionId, map.mappingId)?.generation ?: 0,
                        id, "binary-source:$fileId".toByteArray())
                    dao.prepare(NodusOutbox(connectionId, id, id, map.mappingId, generation, "v2", "PUT", "/api/v2/blobs/${blob.blobId}/content",
                        "application/octet-stream", null, fileId, blob.sha256, blob.size, connection.deviceId, newNodusId(), connection.credentialEpoch, NodusOutboxState.PREPARED))
                    blob = blob.copy(state = NodusReadiness.UPLOADING, uploadOperationId = id)
                    dao.storeReadiness(blob, emptyList())
                }
            }
            blob.uploadOperationId?.let { id -> send(requireNotNull(dao.outbox(connectionId, id)), noteId)?.let { return@withLock it } }
            // A prior unknown upload must have replayed its own receipt, not merely observed ready metadata.
            db.withTransaction {
                blob = requireNotNull(dao.blob(connectionId, ref.blobId))
                if (blob.uploadOperationId != null) require(dao.latestEvidence(connectionId, blob.uploadOperationId!!)?.kind == NodusEvidenceKind.RECEIPT)
                else require(metadata.state == BlobState.READY)
                dao.storeReadiness(blob.copy(state = NodusReadiness.READY, errorCode = null), emptyList())
            }
            // Refresh authoritative ready metadata before permitting any graph sender to create refs.
            val readyResponse = read(requireNotNull(checkedConfiguration()), "/api/v2/blobs/${blob.blobId}")
            val ready = NodusJson.decode(V2Blob.serializer(), wireString(readyResponse.body))
            require(readyResponse.status == 200 && ready.id == blob.blobId && ready.state == BlobState.READY && ready.size == blob.size && ready.sha256 == blob.sha256)
            dao.storeBlobMetadata(NodusSnapshot(connectionId, NodusResourceType.BLOB, ready.id, ready.revision, readyResponse.body, false))
            val aggregate=ref.operationId?.let { dao.intent(connectionId,it) }?.takeIf { it.mappingId==root.mappingId }
            if(aggregate!=null) {
                val acknowledged=dao.outbox(connectionId,aggregate.intentId)?.state==NodusOutboxState.RETIRED && dao.latestEvidence(connectionId,aggregate.intentId)?.kind==NodusEvidenceKind.RECEIPT
                dao.storeReadiness(blob.copy(state=NodusReadiness.READY),listOf(ref.copy(state=if(acknowledged) NodusReadiness.AVAILABLE else NodusReadiness.READY)))
                return@withLock NodusAttachmentProgress(true)
            }
            val mapping = requireNotNull(dao.mappingByWire(connectionId, NodusResourceType.ATTACHMENT, ref.attachmentId, ref.noteId))
            val intent = requireNotNull(dao.firstMappingIntent(connectionId, mapping.mappingId))
            if (!planning.materialize(intent)) return@withLock NodusAttachmentProgress(false, "reference_dependency_or_epoch")
            ref = requireNotNull(dao.attachment(connectionId, noteId, attachmentId))
            blob = requireNotNull(dao.blob(connectionId, ref.blobId))
            dao.storeReadiness(blob, listOf(ref.copy(operationId = intent.intentId)))
            send(requireNotNull(dao.outbox(connectionId, intent.intentId)), noteId)?.let { return@withLock it }
            dao.storeReadiness(blob.copy(state = NodusReadiness.AVAILABLE), listOf(ref.copy(sourceUri = blob.sourceUri, state = NodusReadiness.AVAILABLE, operationId = intent.intentId)))
            NodusAttachmentProgress(true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (retry: RetryableRead) {
            deferRead(ref.blobId, retry.deadline)
        }
        catch (_: java.io.IOException) {
            val current = requireNotNull(dao.blob(connectionId, ref.blobId))
            dao.storeReadiness(current.copy(errorCode = "transport_read_interrupted"), emptyList())
            NodusAttachmentProgress(false, "transport_read_interrupted")
        }
        catch (_: Exception) { block(ref, "source_or_transfer_validation_failed"); NodusAttachmentProgress(false, "source_or_transfer_validation_failed") }
    }

    suspend fun download(blobId: String): NodusAttachmentProgress = NodusConnectionLocks.get(connectionId).withLock {
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(blobId)) return@withLock NodusAttachmentProgress(false, "invalid_blob_id")
        dao.blob(connectionId, blobId)?.let { readDeadline(it)?.let { progress -> return@withLock progress } }
        val config = checkedConfiguration() ?: return@withLock NodusAttachmentProgress(false, "configuration_mismatch")
        try {
            val capabilities = read(config, "/api/v2/capabilities")
            require(capabilities.status == 200)
            require(NodusJson.decode(V2Capabilities.serializer(), wireString(capabilities.body)).realmId==dao.connection(connectionId)?.realmId) { "realm_mismatch" }
            val response = read(config, "/api/v2/blobs/$blobId")
            require(response.status == 200)
            val metadata = NodusJson.decode(V2Blob.serializer(), wireString(response.body))
            require(metadata.id == blobId && metadata.state == BlobState.READY)
            db.withTransaction {
                if (dao.mappingByWire(connectionId, NodusResourceType.BLOB, blobId) == null) dao.addMappings(connectionId, listOf(NodusMapping(connectionId, newNodusId(), NodusResourceType.BLOB, "", blobId, newNodusId(), null)))
                dao.storeBlobMetadata(NodusSnapshot(connectionId, NodusResourceType.BLOB, blobId, metadata.revision, response.body, false))
                val previous = dao.blob(connectionId, blobId)
                dao.storeReadiness(previous?.copy(size = metadata.size, sha256 = metadata.sha256, state = NodusReadiness.DOWNLOADING, errorCode = null)
                    ?: NodusBlobTransfer(connectionId, blobId, metadata.size, metadata.sha256, null, NodusReadiness.DOWNLOADING, null), emptyList())
            }
            val old = requireNotNull(dao.blob(connectionId, blobId))
            if (old.sourceUri != null) {
                withContext(Dispatchers.IO) { bytes.verify(bytes.idFromUri(old.sourceUri), metadata.size, metadata.sha256) }
                dao.storeReadiness(old.copy(state = NodusReadiness.AVAILABLE, errorCode = null), emptyList())
                return@withLock NodusAttachmentProgress(true)
            }
            val stored = binary.download(config, blobId).use { result ->
                require(result.status == 200 && result.length == metadata.size && result.mediaType == "application/octet-stream")
                require(result.disposition == "attachment" && (result.encoding == null || result.encoding == "identity"))
                withContext(Dispatchers.IO) { bytes.install({ result.stream }, metadata.size, metadata.sha256) }
            }
            require(checkedConfiguration()?.credentialEpoch == config.credentialEpoch)
            dao.storeReadiness(old.copy(sourceUri = bytes.file(stored.id).toURI().toString(), state = NodusReadiness.AVAILABLE, errorCode = null), emptyList())
            NodusAttachmentProgress(true)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { dao.blob(connectionId, blobId)?.let { dao.storeReadiness(it.copy(state = NodusReadiness.ERROR, errorCode = "download_interrupted"), emptyList()) } }
            throw cancel
        } catch (retry: RetryableRead) {
            deferRead(blobId, retry.deadline)
        } catch (_: Exception) {
            val failed = dao.blob(connectionId, blobId) ?: NodusBlobTransfer(connectionId, blobId, null, null, null, NodusReadiness.ERROR, "download_validation_failed")
            dao.storeReadiness(failed.copy(state = NodusReadiness.ERROR, errorCode = "download_validation_failed"), emptyList())
            NodusAttachmentProgress(false, "download_validation_failed")
        }
    }

    private class RetryableRead(val deadline: Long) : Exception()
    private suspend fun read(config: NodusConfiguration, path: String): NodusHttpResult {
        val response = json.get(config, path)
        if (response.status == 429 || response.status == 503) {
            val seconds = response.retryAfter?.toLongOrNull()?.coerceAtLeast(1) ?: 60
            throw RetryableRead(if (seconds > (Long.MAX_VALUE - now()) / 1000) Long.MAX_VALUE else now() + seconds * 1000)
        }
        return response
    }
    private fun readDeadline(blob: NodusBlobTransfer): NodusAttachmentProgress? {
        val deadline = blob.errorCode?.takeIf { it.startsWith("read_retry_at:") }?.substringAfter(':')?.toLongOrNull() ?: return null
        return if (now() < deadline) NodusAttachmentProgress(false, "retry_after", deadline) else null
    }
    private suspend fun deferRead(blobId: String, deadline: Long): NodusAttachmentProgress {
        val blob = dao.blob(connectionId, blobId) ?: NodusBlobTransfer(connectionId, blobId, null, null, null, NodusReadiness.LOCAL, null)
        dao.storeReadiness(blob.copy(errorCode = "read_retry_at:$deadline"), emptyList())
        return NodusAttachmentProgress(false, "retry_after", deadline)
    }

    private suspend fun send(operation: NodusOutbox, noteId: String): NodusAttachmentProgress? {
        if (operation.state == NodusOutboxState.RETIRED) return if (dao.latestEvidence(connectionId, operation.operationId)?.kind == NodusEvidenceKind.RECEIPT) null else NodusAttachmentProgress(false, "retained_conflict")
        val config = checkedConfiguration()
        if (config == null || config.credentialEpoch != operation.credentialEpoch || config.deviceId != operation.deviceId) {
            unknown(operation, null, "manual:credential_epoch_changed")
            return NodusAttachmentProgress(false, "credential_epoch_changed")
        }
        val code = dao.latestEvidence(connectionId, operation.operationId)?.errorCode
        if (code?.startsWith("manual:") == true) return NodusAttachmentProgress(false, code)
        val deadline = code?.takeIf { it.startsWith("retry_at:") }?.substringAfter(':')?.toLongOrNull()
        if (deadline != null && now() < deadline) return NodusAttachmentProgress(false, "retry_after", deadline)
        // Reservation and reference requests are separate sends too: do not reuse the
        // verification performed before intervening metadata reads or upload attempts.
        val mapping = requireNotNull(dao.mapping(connectionId, operation.mappingId))
        val blobId = when (mapping.resourceType) {
            NodusResourceType.BLOB -> mapping.wireId
            NodusResourceType.ATTACHMENT -> requireNotNull(dao.attachment(connectionId, mapping.parentId, mapping.wireId)).blobId
            else -> error("Unexpected attachment operation")
        }
        val blob = requireNotNull(dao.blob(connectionId, blobId))
        if (operation.contentType != "application/octet-stream") withContext(Dispatchers.IO) {
            bytes.verify(bytes.idFromUri(requireNotNull(blob.sourceUri)), requireNotNull(blob.size), requireNotNull(blob.sha256))
        }
        val source = if (operation.contentType == "application/octet-stream") withContext(Dispatchers.IO) {
            bytes.verify(requireNotNull(operation.sourceFileId), requireNotNull(operation.sourceSize), requireNotNull(operation.sourceSha256))
        } else null
        suspend fun eligible(): Boolean {
            val root = dao.mappingByWire(connectionId, NodusResourceType.NOTE, noteId) ?: return false
            val local = root.localRowId?.let { db.noteDao.getById(it).first() } ?: return false
            return !root.localRowDetached && !local.isLocalOnly && dao.hasPublicBlobReference(connectionId, blobId)
        }
        if (!eligible()) return NodusAttachmentProgress(false, "private_or_detached_note")
        dao.markAttempt(connectionId, operation.operationId)
        if (!eligible()) return NodusAttachmentProgress(false, "private_or_detached_note")
        val response = try { if (source == null) json.send(config, operation) else binary.upload(config, operation, source) }
        catch (cancel: CancellationException) { withContext(NonCancellable) { unknown(operation, null, "retry_at:${now()+1000}") }; throw cancel }
        catch (_: IllegalArgumentException) { unknown(operation, null, "manual:invalid_transport"); return NodusAttachmentProgress(false, "invalid_transport") }
        catch (_: Exception) { unknown(operation, null, "retry_at:${now()+1000}"); return NodusAttachmentProgress(false, "unknown_outcome", now()+1000) }
        val outcome = runCatching { NodusOutcome.classify(response.status, wireString(response.body)) }.getOrElse { NodusOutcome(OutcomeKind.UNKNOWN) }
        if (outcome.kind == OutcomeKind.SUCCESS) {
            try {
                db.withTransaction {
                    dao.recordOutcome(NodusEvidence(connectionId, newNodusId(), operation.operationId, operation.credentialEpoch, NodusEvidenceKind.RECEIPT, response.status, response.body, null, null))
                    dao.retire(connectionId, operation.operationId)
                }
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) { unknown(operation, response, "retry_at:${now()+1000}") }
                throw cancel
            } catch (_: Exception) {
                unknown(operation, response, "manual:invalid_receipt")
                return NodusAttachmentProgress(false, "invalid_receipt")
            }
            return null
        }
        if (outcome.kind in setOf(OutcomeKind.DURABLE_CONFLICT, OutcomeKind.TERMINAL_CONFLICT)) {
            db.withTransaction {
                dao.recordOutcome(NodusEvidence(connectionId, newNodusId(), operation.operationId, operation.credentialEpoch, NodusEvidenceKind.CONFLICT, response.status, response.body, (outcome.error?.conflictId as? WireField.Present)?.value, null))
                dao.retire(connectionId, operation.operationId)
            }
        } else if (outcome.kind in setOf(OutcomeKind.RATE_LIMITED, OutcomeKind.UNAVAILABLE)) {
            val seconds = response.retryAfter?.toLongOrNull()?.coerceAtLeast(1) ?: 60
            val retry = if (seconds > (Long.MAX_VALUE-now())/1000) Long.MAX_VALUE else now()+seconds*1000
            unknown(operation, response, "retry_at:$retry")
            return NodusAttachmentProgress(false, "retry_after", retry)
        } else unknown(operation, response, "manual:${outcome.kind.name.lowercase()}")
        return NodusAttachmentProgress(false, outcome.kind.name.lowercase())
    }
    private suspend fun unknown(op: NodusOutbox, response: NodusHttpResult?, code: String) = dao.recordOutcome(NodusEvidence(connectionId, newNodusId(), op.operationId,
        op.credentialEpoch, NodusEvidenceKind.UNKNOWN, response?.status, response?.body, null, code))
    private suspend fun block(ref: NodusAttachmentTransfer, code: String) {
        val blob = requireNotNull(dao.blob(connectionId, ref.blobId))
        val current = requireNotNull(dao.attachment(connectionId, ref.noteId, ref.attachmentId))
        dao.storeReadiness(blob.copy(state = NodusReadiness.ERROR, errorCode = code), listOf(current.copy(state = NodusReadiness.ERROR, errorCode = code)))
    }
    private suspend fun checkedConfiguration(): NodusConfiguration? {
        val value = configuration() ?: return null
        val stored = dao.connection(connectionId) ?: return null
        return value.takeIf { it.origin.toString() == stored.origin && it.deviceId == stored.deviceId && it.credentialEpoch == stored.credentialEpoch }
    }
    private companion object { val captureLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>() }
}

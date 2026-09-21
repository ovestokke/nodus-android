package org.qosp.notes.data.sync.nodus.engine

import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.storage.*
import java.util.UUID

internal fun newNodusId(): String = UUID.randomUUID().toString()
internal fun decimalCompare(a: String, b: String): Int = if (a.length == b.length) a.compareTo(b) else a.length.compareTo(b.length)
internal fun wireString(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).also { require(it.toByteArray().contentEquals(bytes)) }
internal fun resourceTarget(mapping: NodusMapping): Pair<NodusResourceType, String> = if (mapping.parentId.isNotEmpty()) NodusResourceType.NOTE to mapping.parentId else mapping.resourceType to mapping.wireId

@Serializable
internal data class NodusDraft(
    val method: String,
    val path: String,
    val body: ByteArray,
    val deviceId: String,
    val requestId: String,
    val credentialEpoch: String,
    val predecessor: String?,
    val privateOnly: Boolean = false,
    val itemExpectation: NodusItemExpectation? = null,
    val dependencies: List<String> = emptyList()
)

internal data class MutationIdentity(val deviceId: String, val requestId: String, val revision: String)
internal enum class LifecycleAction { RESTORE, PURGE }

/** Local-only transactions never acquire the coordinator's network mutex or perform I/O. */
internal class NodusPlanning(private val db: AppDatabase, private val connectionId: String) {
    private val dao get() = db.nodusDao

    suspend fun allocate(type: NodusResourceType, localKey: String, localRowId: Long?, parentId: String = ""): NodusMapping = db.withTransaction {
        val found = dao.mappingByLocalKey(connectionId, type, localKey, parentId)
        if (found != null) {
            require(found.localRowId == localRowId)
            found
        } else NodusMapping(connectionId, newNodusId(), type, parentId, newNodusId(), localKey, localRowId).also {
            dao.addMappings(connectionId, listOf(it))
        }
    }

    suspend fun enqueue(
        mappingId: String, localNote: Note? = null, notebooks: List<Notebook> = emptyList(),
        lifecycle: LifecycleAction? = null, dependencies: List<String> = emptyList(), build: (MutationIdentity) -> WireInput
    ): String = db.withTransaction {
        val connection = requireNotNull(dao.connection(connectionId))
        val mapping = requireNotNull(dao.mapping(connectionId, mappingId))
        val (type, wireId) = resourceTarget(mapping)
        val root = dao.mappingByWire(connectionId, type, wireId)
        val base = root?.let { dao.tracking(connectionId, it.mappingId) }
        val note = if (type == NodusResourceType.NOTE && base?.baseBody != null) NodusJson.decode(V2Note.serializer(), wireString(base.baseBody)) else null
        val revision = if (mapping.resourceType == NodusResourceType.ITEM) note?.items?.firstOrNull { it.id == mapping.wireId }?.revision ?: base?.baseRevision ?: "0" else base?.baseRevision ?: "0"
        val requestId = newNodusId()
        val input = build(MutationIdentity(connection.deviceId, requestId, revision))
        val (method, suffix) = route(mapping, input, lifecycle)
        val path = "/api/v2/${type.name.lowercase()}s/$wireId$suffix"
        val json = encodeInput(input)
        val fields = NodusJson.format.parseToJsonElement(json).jsonObject
        val recent = if ("expectedRevision" in fields) dao.recentResourceIntents(connectionId, type, wireId) else emptyList()
        val itemAction = mapping.resourceType == NodusResourceType.ITEM && input !is V2Append
        suspend fun covered(intent: NodusIntent): Boolean {
            val operation = dao.outbox(connectionId, intent.intentId) ?: return false
            if (operation.state != NodusOutboxState.RETIRED) return false
            val acknowledged = dao.latestEvidence(connectionId, operation.operationId)?.resourceRevision
            if (acknowledged != null) return decimalCompare(acknowledged, base?.baseRevision ?: "0") <= 0
            val conflicts = dao.operationConflicts(connectionId, operation.operationId)
            return conflicts.isNotEmpty() && conflicts.all { conflict ->
                val applied = dao.resolutionReceipt(connectionId, "/api/v2/conflicts/${conflict.conflictId}/apply")?.resourceRevision
                applied != null && decimalCompare(applied, base?.baseRevision ?: "0") <= 0
            }
        }
        val expectation = if (itemAction) NodusItemExpectation(base?.baseRevision ?: "0", note?.kind, note?.items ?: emptyList(),
            recent.asReversed().filter { affectsItemExpectation(decodeDraft(it), mapping.wireId) && !covered(it) }.map { it.intentId },
            recent.size <= 100 || covered(recent.last())) else null
        val previous = if (itemAction) null else recent.firstOrNull()
        // Use an acknowledged own predecessor, never a newly observed remote revision, for
        // deferred offline chains. Independent existing items retain their own expectations.
        val predecessor = previous?.takeIf {
            val op = dao.outbox(connectionId, it.intentId)
            op == null || op.state != NodusOutboxState.RETIRED || base?.baseRevision == null ||
                dao.operationConflicts(connectionId, op.operationId).isNotEmpty() ||
                dao.latestEvidence(connectionId, op.operationId)?.resourceRevision?.let { acknowledged -> decimalCompare(acknowledged, base.baseRevision) > 0 } == true
        }?.intentId
        val draft = NodusDraft(method, path, json.toByteArray(), connection.deviceId, requestId, connection.credentialEpoch, predecessor, localNote?.isLocalOnly == true, expectation, dependencies.distinct())
        val id = newNodusId()
        val generation = dao.tracking(connectionId, mappingId)?.generation ?: 0
        dao.recordLocalChange(connectionId, mappingId, generation, id,
            NodusJson.encode(NodusDraft.serializer(), draft).toByteArray(), localNote, notebooks)
        if (predecessor == null && !draft.privateOnly) materialize(requireNotNull(dao.intent(connectionId, id)))
        id
    }

    suspend fun enqueueResolution(mappingId: String, conflictId: String, expectedRevision: String?, historical: Boolean = false): String = db.withTransaction {
        val conflict = requireNotNull(dao.conflict(connectionId, conflictId))
        require(conflict.state == "pending")
        val mapping = requireNotNull(dao.mapping(connectionId, mappingId))
        if (historical) {
            require(expectedRevision == null && isStoredHistoricalConflict(conflict.body))
        } else {
            val evidence = NodusJson.decode(V2Conflict.serializer(), wireString(conflict.body))
            require(evidence.state == ConflictState.PENDING)
            val operation = evidence.operation as? V2Proposal.Native ?: error("historical_operation")
            val target = resourceTarget(mapping)
            val parts = operation.path.split('/')
            require(parts[3] == target.first.name.lowercase() + "s" && parts[4] == target.second)
            if (mapping.parentId.isNotEmpty()) {
                val collection = when (mapping.resourceType) {
                    NodusResourceType.ITEM -> "items"
                    NodusResourceType.REMINDER -> "reminders"
                    NodusResourceType.ATTACHMENT -> "attachments"
                    else -> error("Invalid child mapping")
                }
                require(parts.size >= 6 && parts[5] == collection)
                if (parts.size == 6) {
                    val fields = NodusJson.format.parseToJsonElement(NodusJson.encode(V2Proposal.serializer(), operation)).jsonObject.getValue("input").jsonObject
                    require(fields[if (collection == "items") "itemId" else "id"] == JsonPrimitive(mapping.wireId))
                } else require(parts[6] == mapping.wireId)
            }
        }
        val connection = requireNotNull(dao.connection(connectionId))
        val request = newNodusId()
        val input: WireInput = if (expectedRevision == null) V2Discard(connection.deviceId, request) else V2Apply(connection.deviceId, request, expectedRevision)
        val draft = NodusDraft("POST", "/api/v2/conflicts/$conflictId/${if (expectedRevision == null) "discard" else "apply"}", encodeInput(input).toByteArray(), connection.deviceId, request, connection.credentialEpoch, null)
        val id = newNodusId()
        dao.recordLocalChange(connectionId, mappingId, dao.tracking(connectionId, mappingId)?.generation ?: 0, id, NodusJson.encode(NodusDraft.serializer(), draft).toByteArray())
        materialize(requireNotNull(dao.intent(connectionId, id)))
        id
    }

    /** Returns false for dependency/epoch/private blocks. Original intent is never erased. */
    suspend fun materialize(intent: NodusIntent): Boolean = db.withTransaction {
        require(intent.connectionId == connectionId)
        if (dao.outbox(connectionId, intent.intentId) != null) return@withTransaction true
        val draft = decodeDraft(intent)
        val connection = requireNotNull(dao.connection(connectionId))
        if (draft.privateOnly || draft.credentialEpoch != connection.credentialEpoch || draft.deviceId != connection.deviceId) return@withTransaction false
        for (id in draft.dependencies) {
            val dependency = dao.outbox(connectionId, id) ?: return@withTransaction false
            if (dependency.state != NodusOutboxState.RETIRED || dao.latestEvidence(connectionId, id)?.kind != NodusEvidenceKind.RECEIPT || dao.operationConflicts(connectionId, id).isNotEmpty()) return@withTransaction false
        }
        var bytes = draft.body
        if (draft.itemExpectation != null) {
            val mapping = requireNotNull(dao.mapping(connectionId, intent.mappingId))
            val revision = resolveItemExpectation(dao, connectionId, mapping.wireId, draft.itemExpectation)
                ?: if (!draft.itemExpectation.completeHistory) unchangedItemAfterAuthoritativeRead(dao, connectionId, mapping, intent.intentId, draft.itemExpectation) else null
            if (revision == null) return@withTransaction false
            val fields = NodusJson.format.parseToJsonElement(wireString(bytes)).jsonObject.toMutableMap()
            fields["expectedRevision"] = JsonPrimitive(revision)
            bytes = JsonObject(fields).toString().toByteArray()
        }
        if (draft.predecessor != null) {
            val predecessor = dao.outbox(connectionId, draft.predecessor) ?: return@withTransaction false
            if (predecessor.state != NodusOutboxState.RETIRED) return@withTransaction false
            val revision = dao.latestEvidence(connectionId, predecessor.operationId)?.resourceRevision ?: return@withTransaction false
            val fields = NodusJson.format.parseToJsonElement(wireString(bytes)).jsonObject.toMutableMap()
            fields["expectedRevision"] = JsonPrimitive(revision)
            bytes = JsonObject(fields).toString().toByteArray()
        }
        dao.prepare(NodusOutbox(connectionId, intent.intentId, intent.intentId, intent.mappingId, intent.generation,
            "v2", draft.method, draft.path, "application/json", bytes, null, null, null,
            draft.deviceId, draft.requestId, draft.credentialEpoch, NodusOutboxState.PREPARED))
        true
    }

    private fun route(mapping: NodusMapping, input: WireInput, lifecycle: LifecycleAction?): Pair<String, String> {
        val allowed = when (input) {
            is V2OrganizationCreate, is V2OrganizationEdit, is V2OrganizationDelete -> setOf(NodusResourceType.TAG, NodusResourceType.NOTEBOOK)
            is V2BlobReserve -> setOf(NodusResourceType.BLOB)
            is V2Append, is V2ItemEdit, is V2Toggle -> setOf(NodusResourceType.ITEM)
            is V2ReminderCreate, is V2ReminderEdit -> setOf(NodusResourceType.REMINDER)
            is V2AttachmentCreate, is V2AttachmentEdit -> setOf(NodusResourceType.ATTACHMENT)
            is V2ChildDelete -> setOf(NodusResourceType.ITEM, NodusResourceType.REMINDER, NodusResourceType.ATTACHMENT)
            else -> setOf(NodusResourceType.NOTE)
        }
        require(mapping.resourceType in allowed)
        val child = when (mapping.resourceType) {
            NodusResourceType.ITEM -> "/items/${mapping.wireId}"
            NodusResourceType.REMINDER -> "/reminders/${mapping.wireId}"
            NodusResourceType.ATTACHMENT -> "/attachments/${mapping.wireId}"
            else -> ""
        }
        return when (input) {
            is V2CreateNote, is V2OrganizationCreate, is V2BlobReserve -> "PUT" to ""
            is V2EditNote, is V2OrganizationEdit -> "PATCH" to ""
            is V2Content -> "POST" to "/content"
            is V2Trash -> "POST" to "/trash"
            is V2Lifecycle -> "POST" to if (requireNotNull(lifecycle) == LifecycleAction.RESTORE) "/restore" else "/purge"
            is V2Append -> "POST" to "/items"
            is V2ItemEdit, is V2ReminderEdit, is V2AttachmentEdit -> "PATCH" to child
            is V2Toggle -> "PATCH" to "$child/checked"
            is V2Order -> "POST" to "/order"
            is V2ReminderCreate -> "POST" to "/reminders"
            is V2AttachmentCreate -> "POST" to "/attachments"
            is V2AttachmentOrder -> "POST" to "/attachments/order"
            is V2ChildDelete -> "DELETE" to child
            is V2OrganizationDelete -> "DELETE" to ""
            else -> error("Use explicit conflict resolution entry points")
        }
    }
}

internal fun decodeDraft(intent: NodusIntent): NodusDraft = NodusJson.decode(NodusDraft.serializer(), wireString(intent.body))

internal fun encodeInput(input: WireInput): String = when (input) {
    is V2CreateNote -> NodusJson.encode(V2CreateNote.serializer(), input)
    is V2EditNote -> NodusJson.encode(V2EditNote.serializer(), input)
    is V2Content -> NodusJson.encode(V2Content.serializer(), input)
    is V2Trash -> NodusJson.encode(V2Trash.serializer(), input)
    is V2Lifecycle -> NodusJson.encode(V2Lifecycle.serializer(), input)
    is V2Append -> NodusJson.encode(V2Append.serializer(), input)
    is V2ItemEdit -> NodusJson.encode(V2ItemEdit.serializer(), input)
    is V2Toggle -> NodusJson.encode(V2Toggle.serializer(), input)
    is V2Order -> NodusJson.encode(V2Order.serializer(), input)
    is V2ChildDelete -> NodusJson.encode(V2ChildDelete.serializer(), input)
    is V2ReminderCreate -> NodusJson.encode(V2ReminderCreate.serializer(), input)
    is V2ReminderEdit -> NodusJson.encode(V2ReminderEdit.serializer(), input)
    is V2AttachmentCreate -> NodusJson.encode(V2AttachmentCreate.serializer(), input)
    is V2AttachmentEdit -> NodusJson.encode(V2AttachmentEdit.serializer(), input)
    is V2AttachmentOrder -> NodusJson.encode(V2AttachmentOrder.serializer(), input)
    is V2OrganizationCreate -> NodusJson.encode(V2OrganizationCreate.serializer(), input)
    is V2OrganizationEdit -> NodusJson.encode(V2OrganizationEdit.serializer(), input)
    is V2OrganizationDelete -> NodusJson.encode(V2OrganizationDelete.serializer(), input)
    is V2BlobReserve -> NodusJson.encode(V2BlobReserve.serializer(), input)
    is V2Apply -> NodusJson.encode(V2Apply.serializer(), input)
    is V2Discard -> NodusJson.encode(V2Discard.serializer(), input)
    else -> error("Evidence-only input cannot be sent")
}

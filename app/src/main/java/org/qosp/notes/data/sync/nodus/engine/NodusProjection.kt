package org.qosp.notes.data.sync.nodus.engine

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.model.NoteTask
import org.qosp.notes.data.model.Reminder as LocalReminder
import org.qosp.notes.data.model.NoteColor as LocalColor
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.storage.*
import java.time.Instant

internal data class ProjectionBlock(val resourceType: NodusResourceType, val wireId: String, val reason: String)
internal data class ProjectionProgress(val examined: Int, val applied: Int, val blocked: List<ProjectionBlock>, val nextRevision: String)

/** Canonical snapshots are the durable projection queue. An unapplied graph never advances
 * its base revision; absent rows/pages are not deletion or reminder-delivery instructions.
 */
internal class NodusProjection(private val db: AppDatabase, private val connectionId: String, private val bytes: NodusByteStore? = null, private val allowed: suspend () -> Boolean = {true}) {
    private val dao get() = db.nodusDao

    suspend fun project(limit: Int = 100, afterRevision: String = "0"): ProjectionProgress {
        require(limit in 1..100); requireDecimal(afterRevision)
        val snapshots = dao.projectionPage(connectionId, afterRevision, limit)
        var applied = 0
        val blocked = mutableListOf<ProjectionBlock>()
        val verifiedBlobs = mutableMapOf<String, NodusVerifiedFile>()
        // Dependencies are independent resources, not names. Do not invent placeholder tags.
        snapshots.sortedBy { if (it.resourceType in setOf(NodusResourceType.TAG, NodusResourceType.NOTEBOOK)) 0 else 1 }.forEach { snapshot ->
            // Hash outside Room write transactions: local editing and durable ingestion do not wait for byte I/O.
            val proofs = verifyBytes(snapshot, verifiedBlobs)
            db.withTransaction {
            if (!allowed()) { blocked += ProjectionBlock(snapshot.resourceType,snapshot.wireId,"inactive_connection"); return@withTransaction }
            val map = dao.mappingByWire(connectionId, snapshot.resourceType, snapshot.wireId)
            val base = map?.let { dao.tracking(connectionId, it.mappingId) }
            if (base?.baseRevision != null && decimalCompare(base.baseRevision, snapshot.revision) >= 0) return@withTransaction
            val reason = dirtyReason(snapshot.resourceType, snapshot.wireId, snapshot.revision)
            if (reason != null) {
                blocked += ProjectionBlock(snapshot.resourceType, snapshot.wireId, reason)
                return@withTransaction
            }
            val byteFailure = if (proofs.first != null) proofs.first else if (proofs.second.values.any { bytes?.isCurrent(it) != true }) "projection:byte_replaced" else null
            if (byteFailure != null) {
                recordByteFailure(snapshot, byteFailure)
                blocked += ProjectionBlock(snapshot.resourceType, snapshot.wireId, byteFailure)
                return@withTransaction
            }
            val failure = when (snapshot.resourceType) {
                NodusResourceType.NOTE -> projectNote(snapshot, map, proofs.second)
                NodusResourceType.TAG, NodusResourceType.NOTEBOOK -> projectOrganization(snapshot, map)
                else -> "unsupported_snapshot"
            }
            if (failure == null) applied++ else {
                if (failure.startsWith("projection:")) recordByteFailure(snapshot, failure)
                blocked += ProjectionBlock(snapshot.resourceType, snapshot.wireId, failure)
            }
            }
        }
        return ProjectionProgress(snapshots.size, applied, blocked, snapshots.lastOrNull()?.revision ?: afterRevision)
    }

    private suspend fun verifyBytes(snapshot: NodusSnapshot, cache: MutableMap<String, NodusVerifiedFile>): Pair<String?, Map<String, NodusVerifiedFile>> {
        if (snapshot.resourceType != NodusResourceType.NOTE) return null to emptyMap()
        val note = NodusJson.decode(V2Note.serializer(), wireString(snapshot.body))
        if (note.state == NoteState.PURGED || note.attachments.none { !it.deleted }) return null to emptyMap()
        val store = bytes ?: return "projection:byte_verification_required" to emptyMap()
        val result = mutableMapOf<String, NodusVerifiedFile>()
        for (id in note.attachments.filterNot { it.deleted }.map { it.blobId }.distinct()) {
            val blob = dao.blob(connectionId, id) ?: return "projection:missing_blob_bytes" to emptyMap()
            if (blob.state !in setOf(NodusReadiness.AVAILABLE, NodusReadiness.READY) && blob.errorCode?.startsWith("projection:") != true) return "projection:missing_blob_bytes" to emptyMap()
            try {
                result[id] = cache[id]?.takeIf { store.isCurrent(it) } ?: withContext(Dispatchers.IO) { store.verified(store.idFromUri(requireNotNull(blob.sourceUri)), requireNotNull(blob.size), requireNotNull(blob.sha256)) }.also { cache[id] = it }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { return "projection:byte_missing_or_corrupt" to emptyMap() }
        }
        return null to result
    }

    private suspend fun recordByteFailure(snapshot: NodusSnapshot, code: String) {
        val note = NodusJson.decode(V2Note.serializer(), wireString(snapshot.body))
        for (ref in note.attachments.filterNot { it.deleted }) {
            val blob = dao.blob(connectionId, ref.blobId) ?: NodusBlobTransfer(connectionId, ref.blobId, null, null, null, NodusReadiness.ERROR, code)
            val old = dao.attachment(connectionId, note.id, ref.id)
            dao.storeReadiness(blob.copy(errorCode = code), listOf(old?.copy(state = NodusReadiness.ERROR, errorCode = code)
                ?: NodusAttachmentTransfer(connectionId, note.id, ref.id, ref.blobId, null, NodusReadiness.ERROR, code)))
        }
    }

    suspend fun dirtyReason(type: NodusResourceType, wireId: String, incomingRevision: String): String? {
        for (capture in dao.captures(connectionId).filter { it.state in setOf("PENDING", "COMPILING", "BLOCKED") }) {
            val before = org.qosp.notes.data.sync.nodus.integration.localJson.decodeFromString(org.qosp.notes.data.sync.nodus.integration.LocalGraph.serializer(), capture.beforeBody)
            val after = org.qosp.notes.data.sync.nodus.integration.localJson.decodeFromString(org.qosp.notes.data.sync.nodus.integration.LocalGraph.serializer(), capture.afterBody)
            val row = dao.mappingByWire(connectionId, type, wireId)?.localRowId
            val changed = when (type) {
                NodusResourceType.NOTE -> before.notes.firstOrNull { it.id == row } != after.notes.firstOrNull { it.id == row }
                NodusResourceType.TAG -> before.tags.firstOrNull { it.id == row } != after.tags.firstOrNull { it.id == row }
                NodusResourceType.NOTEBOOK -> before.notebooks.firstOrNull { it.id == row } != after.notebooks.firstOrNull { it.id == row }
                else -> false
            }
            if (changed) return "uncompiled_local_capture"
        }
        if (type == NodusResourceType.NOTE && dao.hasBlockedAttachment(connectionId, wireId)) return "blocked_local_attachment"
        val unsettled = dao.unsettledIntents(connectionId, type, wireId)
        if (unsettled.size > 100) return "pending_work_bound"
        for (intent in unsettled) {
            val operations = dao.intentOperations(connectionId, intent.intentId)
            if (operations.isEmpty() || operations.size > 100) return "local_draft"
            for (operation in operations) {
                if (operation.state != NodusOutboxState.RETIRED) return "pending_or_unknown_operation"
                for (evidence in dao.operationConflicts(connectionId, operation.operationId)) {
                    val apply = dao.resolutionReceipt(connectionId, "/api/v2/conflicts/${evidence.conflictId}/apply") ?: return "conflicted_local_draft"
                    val receipt = NodusJson.decode(V2Receipt.serializer(), wireString(requireNotNull(apply.body)))
                    if (decimalCompare(incomingRevision, receipt.revision) < 0) return "awaiting_applied_snapshot"
                }
            }
        }
        val acknowledged = dao.maximumAcknowledgedRevision(connectionId, type, wireId)
        if (acknowledged != null && decimalCompare(incomingRevision, acknowledged) < 0) return "awaiting_acknowledged_snapshot"
        return null
    }

    private suspend fun projectOrganization(snapshot: NodusSnapshot, existing: NodusMapping?): String? {
        val tag = if (snapshot.resourceType == NodusResourceType.TAG) NodusJson.decode(V2Tag.serializer(), wireString(snapshot.body)) else null
        val book = if (snapshot.resourceType == NodusResourceType.NOTEBOOK) NodusJson.decode(V2Notebook.serializer(), wireString(snapshot.body)) else null
        if (existing?.localRowDetached == true) return "detached_local_mapping"
        val name = tag?.name ?: requireNotNull(book).name
        val deleted = tag?.deleted ?: requireNotNull(book).deleted
        var localId = existing?.localRowId
        if (deleted) {
            if (localId != null) {
                val inUse = if (tag != null) dao.localTagReferences(localId) else dao.localNotebookReferences(localId)
                if (inUse != 0) return "local_resource_in_use"
                if (tag != null) db.tagDao.delete(Tag(name, localId)) else db.notebookDao.delete(Notebook(name, localId))
            }
        } else {
            if (localId == null) localId = if (tag != null) db.tagDao.insert(Tag(name)) else db.notebookDao.insert(Notebook(name))
            else if (tag != null) dao.projectTag(Tag(name, localId)) else dao.projectNotebook(Notebook(name, localId))
        }
        val mapping = if (existing != null && existing.localRowId == null && localId != null) dao.bindFirstLocalRow(existing, localId)
            else existing ?: incomingMapping(snapshot.resourceType, snapshot.wireId, "", localId)
        dao.recordProjectionBase(NodusTracking(connectionId, mapping.mappingId, dao.tracking(connectionId, mapping.mappingId)?.generation ?: 0, snapshot.revision, snapshot.body))
        return null
    }

    private suspend fun projectNote(snapshot: NodusSnapshot, existing: NodusMapping?, proofs: Map<String, NodusVerifiedFile>): String? {
        val remote = NodusJson.decode(V2Note.serializer(), wireString(snapshot.body))
        if (existing?.localRowDetached == true) return "detached_local_mapping"
        val local = existing?.localRowId?.let { db.noteDao.getById(it).first() }
        if (local?.isLocalOnly == true) return "private_local_fork"
        if (remote.state == NoteState.PURGED) {
            // A clean projection is hidden, not treated as a local retention/byte-GC command.
            if (local != null) db.noteDao.update(local.copy(isDeleted = true, isHidden = true).toEntity())
            val root = existing ?: incomingMapping(NodusResourceType.NOTE, remote.id, "", null)
            dao.recordProjectionBase(NodusTracking(connectionId, root.mappingId, dao.tracking(connectionId, root.mappingId)?.generation ?: 0, remote.revision, snapshot.body))
            return null
        }
        val all = dao.childMappings(connectionId, remote.id)
        if (all.any { map -> map.localRowDetached && when (map.resourceType) {
            NodusResourceType.ITEM -> remote.items.any { it.id == map.wireId && !it.deleted }
            NodusResourceType.REMINDER -> remote.reminders.any { it.id == map.wireId && !it.deleted }
            NodusResourceType.ATTACHMENT -> remote.attachments.any { it.id == map.wireId && !it.deleted }
            else -> false
        } }) return "detached_child_mapping"
        suspend fun mapped(type: NodusResourceType, wireId: String, parentId: String = "") = dao.mappingByWire(connectionId, type, wireId, parentId)
        val tags = mutableListOf<Tag>()
        for (id in remote.tagIds) {
            val map = mapped(NodusResourceType.TAG, id) ?: return "missing_tag"
            val row = map.localRowId?.let { db.tagDao.getById(it).first() } ?: return "missing_tag"
            val canonical = dao.snapshot(connectionId, NodusResourceType.TAG, id) ?: return "missing_tag"
            if (canonical.tombstone) return "deleted_tag_dependency"
            tags += row
        }
        val notebook = remote.notebookId?.let { id ->
            val map = mapped(NodusResourceType.NOTEBOOK, id) ?: return "missing_notebook"
            val row = map.localRowId?.let { db.notebookDao.getById(it).first() } ?: return "missing_notebook"
            if (dao.snapshot(connectionId, NodusResourceType.NOTEBOOK, id)?.tombstone != false) return "missing_notebook"
            row
        }
        val attachments = mutableListOf<Attachment>()
        val attachmentSources = mutableMapOf<String, String>()
        val newAttachmentMappings = mutableListOf<NodusMapping>()
        for (attachment in remote.attachments.filterNot { it.deleted }.sortedBy { it.position }) {
            if (mapped(NodusResourceType.BLOB, attachment.blobId) == null) return "missing_blob_mapping"
            var blob = dao.blob(connectionId, attachment.blobId) ?: return "missing_blob_bytes"
            val proof = proofs[attachment.blobId] ?: return "projection:byte_unverified"
            if (bytes?.isCurrent(proof) != true) return "projection:byte_replaced"
            if (blob.errorCode?.startsWith("projection:") == true) {
                blob = blob.copy(state = NodusReadiness.AVAILABLE, errorCode = null)
                dao.storeReadiness(blob, emptyList())
            }
            if (blob.state !in setOf(NodusReadiness.AVAILABLE, NodusReadiness.READY) || blob.sourceUri.isNullOrBlank()) return "blob_not_available"
            val source = blob.sourceUri
            attachmentSources[attachment.id] = source
            val refMapping = mapped(NodusResourceType.ATTACHMENT, attachment.id, remote.id) ?: NodusMapping(connectionId, newNodusId(), NodusResourceType.ATTACHMENT, remote.id, attachment.id, newNodusId(), null).also { newAttachmentMappings += it }
            attachments += Attachment(Attachment.Type.valueOf(attachment.kind.name), source, attachment.description, attachment.fileName, refMapping.localKey)
        }
        // Everything necessary to materialize the graph has been validated before any writes.
        dao.addMappings(connectionId, newAttachmentMappings)
        val localId = local?.id ?: db.noteDao.insert(Note().toEntity())
        val root = if (existing != null && existing.localRowId == null) dao.bindFirstLocalRow(existing, localId)
            else existing ?: incomingMapping(NodusResourceType.NOTE, remote.id, "", localId)
        require(root.localRowId == localId) { "Detached projection requires explicit recovery" }
        var nextTaskId = maxOf(local?.taskList?.maxOfOrNull { it.id } ?: -1, all.filter { it.resourceType == NodusResourceType.ITEM && it.parentId == remote.id }.mapNotNull { it.localRowId }.maxOrNull() ?: -1) + 1
        val tasks = mutableListOf<NoteTask>()
        for (item in remote.items.sortedBy { it.position }) {
            var map = mapped(NodusResourceType.ITEM, item.id, remote.id) ?: incomingMapping(NodusResourceType.ITEM, item.id, remote.id, if (item.deleted) null else nextTaskId++)
            if (!item.deleted && map.localRowId == null) map = dao.bindFirstLocalRow(map, nextTaskId++)
            if (!item.deleted) tasks += NoteTask(requireNotNull(map.localRowId), item.text, item.checked,map.localKey)
            dao.recordProjectionBase(NodusTracking(connectionId, map.mappingId, dao.tracking(connectionId, map.mappingId)?.generation ?: 0, item.revision, NodusJson.encode(Item.serializer(), item).toByteArray()))
        }
        val reminderRows = mutableListOf<LocalReminder>()
        for (reminder in remote.reminders) {
            var map = mapped(NodusResourceType.REMINDER, reminder.id, remote.id)
            val projected = map?.let { dao.tracking(connectionId, it.mappingId) }
            val oldIntent = projected?.baseBody?.let { NodusJson.decode(V2Reminder.serializer(), wireString(it)) }
            val materialChange = oldIntent != reminder
            var row = map?.localRowId?.let { db.reminderDao.getById(it).first() }
            if (!reminder.deleted && materialChange) {
                val rowId = map?.localRowId ?: db.reminderDao.insert(LocalReminder(reminder.name, localId, seconds(reminder.dueAt)))
                row = LocalReminder(reminder.name, localId, seconds(reminder.dueAt), rowId,alarmFingerprint=newNodusId())
            }
            if (map == null) map = incomingMapping(NodusResourceType.REMINDER, reminder.id, remote.id, row?.id)
            if (map.localRowId == null && row != null) map = dao.bindFirstLocalRow(map, row.id)
            if (!reminder.deleted && row != null) reminderRows += row
            // Missing unchanged rows mean device delivery consumed them, not shared deletion.
            dao.recordProjectionBase(NodusTracking(connectionId, map.mappingId, projected?.generation ?: 0, remote.revision, NodusJson.encode(V2Reminder.serializer(), reminder).toByteArray()))
        }
        for (attachment in remote.attachments) {
            val map = mapped(NodusResourceType.ATTACHMENT, attachment.id, remote.id) ?: incomingMapping(NodusResourceType.ATTACHMENT, attachment.id, remote.id, null)
            dao.recordProjectionBase(NodusTracking(connectionId, map.mappingId, dao.tracking(connectionId, map.mappingId)?.generation ?: 0, remote.revision, NodusJson.encode(V2Attachment.serializer(), attachment).toByteArray()))
            attachmentSources[attachment.id]?.let { source ->
                val blob = requireNotNull(dao.blob(connectionId, attachment.blobId))
                dao.storeReadiness(blob, listOf(NodusAttachmentTransfer(connectionId, remote.id, attachment.id, attachment.blobId, source, blob.state, null)))
            }
        }
        val projected = (local ?: Note(id = localId)).copy(
            title = remote.title, content = remote.text, isList = remote.kind == NoteKind.CHECKLIST,
            taskList = tasks, isArchived = remote.archived, isDeleted = remote.state == NoteState.TRASH,
            isPinned = remote.pinned, isHidden = remote.hidden, isMarkdownEnabled = remote.markdownEnabled,
            creationDate = seconds(remote.authoredAt), modifiedDate = seconds(remote.editedAt),
            deletionDate = (remote.sourceTrashedAt ?: remote.trashedAt)?.let(::seconds),
            attachments = attachments, color = LocalColor.entries.first { it.name.equals(remote.color.name, true) },
            notebookId = notebook?.id, tags = tags, reminders = reminderRows
        )
        val generation = dao.tracking(connectionId, root.mappingId)?.generation ?: 0
        dao.storeAggregate(connectionId, projected, emptyList(), emptyList(), NodusTracking(connectionId, root.mappingId, generation, remote.revision, snapshot.body), generation)
        return null
    }

    private suspend fun incomingMapping(type: NodusResourceType, wireId: String, parentId: String, localRowId: Long?): NodusMapping =
        NodusMapping(connectionId, newNodusId(), type, parentId, wireId, newNodusId(), localRowId).also { dao.addMappings(connectionId, listOf(it)) }
}

internal fun seconds(instant: String): Long {
    requireInstant(instant)
    // RFC3339 allows offsets wider than java.time.ZoneOffset; apply them explicitly.
    val local = java.time.LocalDateTime.parse(instant.substringBeforeLast('Z').let {
        if (instant.endsWith('Z')) it else instant.dropLast(6)
    })
    val offset = if (instant.endsWith('Z')) 0 else {
        val zone = instant.takeLast(6)
        (zone.substring(1, 3).toInt() * 3600 + zone.substring(4, 6).toInt() * 60) * if (zone[0] == '-') -1 else 1
    }
    return local.toEpochSecond(java.time.ZoneOffset.UTC) - offset
}

internal fun sourceInstant(seconds: Long, original: String?): String =
    if (original != null && seconds(original) == seconds) original else Instant.ofEpochSecond(seconds).toString()

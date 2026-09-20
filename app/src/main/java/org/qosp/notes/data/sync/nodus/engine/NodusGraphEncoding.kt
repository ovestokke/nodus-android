package org.qosp.notes.data.sync.nodus.engine

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.storage.*

/** Attachment identity is supplied explicitly, never inferred from a URI or array position. */
internal class NodusGraphEncoding(private val db: AppDatabase, private val connectionId: String) {
    private val dao get() = db.nodusDao
    private val planning = NodusPlanning(db, connectionId)

    suspend fun enroll(noteId: Long, readyBlobMappingIds: List<String>, primaryReminderRowId: Long? = null): String = db.withTransaction {
        val note = requireNotNull(db.noteDao.getById(noteId).first())
        require(!note.isLocalOnly)
        require(note.attachments.size == readyBlobMappingIds.size)
        require(dao.mappingByLocalRow(connectionId, NodusResourceType.NOTE, noteId) == null) { "Already enrolled; use explicit edits" }
        suspend fun mapping(type: NodusResourceType, row: Long, parent: String = ""): NodusMapping =
            dao.mappingByLocalRow(connectionId, type, row, parent)
                ?: planning.allocate(type, newNodusId(), row, parent)
        for (tag in note.tags) {
            val map = mapping(NodusResourceType.TAG, tag.id)
            if (dao.snapshot(connectionId, NodusResourceType.TAG, map.wireId) == null && dao.recentResourceIntents(connectionId, NodusResourceType.TAG, map.wireId).isEmpty()) {
                planning.enqueue(map.mappingId) { V2OrganizationCreate(it.deviceId, it.requestId, tag.name) }
            }
        }
        note.notebookId?.let { id ->
            val notebook = requireNotNull(db.notebookDao.getById(id).first())
            val map = mapping(NodusResourceType.NOTEBOOK, id)
            if (dao.snapshot(connectionId, NodusResourceType.NOTEBOOK, map.wireId) == null && dao.recentResourceIntents(connectionId, NodusResourceType.NOTEBOOK, map.wireId).isEmpty()) {
                planning.enqueue(map.mappingId) { V2OrganizationCreate(it.deviceId, it.requestId, notebook.name) }
            }
        }
        val root = mapping(NodusResourceType.NOTE, noteId)
        note.taskList.forEach { mapping(NodusResourceType.ITEM, it.id, root.wireId) }
        note.reminders.forEach { mapping(NodusResourceType.REMINDER, it.id, root.wireId) }
        val attachments = readyBlobMappingIds.mapIndexed { index, blobMappingId ->
            val blobMap = requireNotNull(dao.mapping(connectionId, blobMappingId))
            require(blobMap.resourceType == NodusResourceType.BLOB)
            val blob = requireNotNull(dao.blob(connectionId, blobMap.wireId))
            require(blob.state in setOf(NodusReadiness.READY, NodusReadiness.AVAILABLE))
            val ref = planning.allocate(NodusResourceType.ATTACHMENT, newNodusId(), null, root.wireId)
            dao.storeReadiness(blob, listOf(NodusAttachmentTransfer(connectionId, root.wireId, ref.wireId, blobMap.wireId, note.attachments[index].path, blob.state, null)))
            ref.mappingId
        }
        val primary = primaryReminderRowId?.let { row ->
            require(note.reminders.any { it.id == row })
            requireNotNull(dao.mappingByLocalRow(connectionId, NodusResourceType.REMINDER, row, root.wireId)).wireId
        }
        val create = createBody(root, note, attachments, primary, null)
        planning.enqueue(root.mappingId) { create.copy(deviceId = it.deviceId, requestId = it.requestId) }
        root.mappingId
    }

    suspend fun createBody(root: NodusMapping, note: Note, attachmentMappingIds: List<String>, primaryReminderId: String?, base: V2Note?, requireReady:Boolean=true): V2CreateNote {
        require(root.resourceType == NodusResourceType.NOTE && root.localRowId == note.id && !note.isLocalOnly)
        suspend fun wire(type: NodusResourceType, row: Long, parent: String = ""): String = requireNotNull(dao.mappingByLocalRow(connectionId, type, row, parent)).wireId
        require(attachmentMappingIds.size == note.attachments.size && attachmentMappingIds.distinct().size == attachmentMappingIds.size)
        val attachments = note.attachments.zip(attachmentMappingIds).map { (attachment, id) ->
            val map = requireNotNull(dao.mapping(connectionId, id)).also { require(it.resourceType == NodusResourceType.ATTACHMENT && it.parentId == root.wireId) }
            val transfer = requireNotNull(dao.attachment(connectionId, root.wireId, map.wireId))
            if(requireReady) require(requireNotNull(dao.blob(connectionId, transfer.blobId)).state in setOf(NodusReadiness.READY, NodusReadiness.AVAILABLE))
            V2AttachmentInput(map.wireId, transfer.blobId, AttachmentKind.valueOf(attachment.type.name), WireField.Present(attachment.description), WireField.Present(attachment.fileName))
        }
        // Absence of a delivered/expired local reminder is NOT removal of shared intent.
        val reminders = base?.reminders?.filterNot { it.deleted }?.associateBy { it.id }?.toMutableMap() ?: mutableMapOf()
        note.reminders.forEach { row ->
            val id = wire(NodusResourceType.REMINDER, row.id, root.wireId)
            reminders[id] = V2Reminder(id, row.name, sourceInstant(row.date, reminders[id]?.dueAt), false)
        }
        val reminderInputs = reminders.values.map { V2ReminderInput(it.id, WireField.Present(it.name), it.dueAt) }
        require(primaryReminderId == null || reminderInputs.any { it.id == primaryReminderId })
        return V2CreateNote("device", "request", if (note.isList) NoteKind.CHECKLIST else NoteKind.TEXT,
            title = WireField.Present(note.title), text = WireField.Present(note.content),
            archived = WireField.Present(note.isArchived), pinned = WireField.Present(note.isPinned), hidden = WireField.Present(note.isHidden),
            markdownEnabled = WireField.Present(note.isMarkdownEnabled), color = WireField.Present(NoteColor.entries.first { it.name.equals(note.color.name, true) }),
            notebookId = WireField.Present(note.notebookId?.let { wire(NodusResourceType.NOTEBOOK, it) }),
            tagIds = WireField.Present(note.tags.map { wire(NodusResourceType.TAG, it.id) }), primaryReminderId = WireField.Present(primaryReminderId),
            items = WireField.Present(note.taskList.map { ItemInput(wire(NodusResourceType.ITEM, it.id, root.wireId), WireField.Present(it.content), WireField.Present(it.isDone)) }),
            attachments = WireField.Present(attachments), reminders = WireField.Present(reminderInputs),
            authoredAt = WireField.Present(sourceInstant(note.creationDate, base?.authoredAt)), editedAt = WireField.Present(sourceInstant(note.modifiedDate, base?.editedAt)),
            state = WireField.Present(if (note.isDeleted) InitialNoteState.TRASH else InitialNoteState.LIVE),
            sourceTrashedAt = if (note.isDeleted) WireField.Present(
                if (base?.state == NoteState.TRASH && note.deletionDate == (base.sourceTrashedAt ?: base.trashedAt)?.let(::seconds)) base.sourceTrashedAt
                else note.deletionDate?.let { sourceInstant(it, null) }
            ) else WireField.Absent)
    }
}

package org.qosp.notes.data.sync.nodus.integration

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.engine.*
import org.qosp.notes.data.sync.nodus.storage.*

/** Compilation is recoverable local work, never a network call or a local-edit prerequisite. */
internal class NodusCaptureCompiler(private val db: AppDatabase, private val bridge: NodusAppBridge, private val attachments: NodusAttachments) {
    suspend fun compile(limit: Int = 20) {
        val active = bridge.active() ?: return
        val connectionId = requireNotNull(active.activeConnectionId)
        val dao = db.nodusDao
        for (pending in dao.pendingCaptures(connectionId, limit)) {
            val frozen = db.withTransaction {
                val current = dao.pendingCaptures(connectionId, limit).firstOrNull { it.id == pending.id } ?: return@withTransaction null
                current.copy(state="COMPILING").also { dao.updateCapture(it) }
            } ?: continue
            try {
                db.withTransaction {
                    require(bridge.active()?.activeConnectionId == connectionId)
                    require(frozen.deviceId == active.installationDeviceId && frozen.credentialEpoch == active.credentialEpoch)
                    val before = localJson.decodeFromString<LocalGraph>(frozen.beforeBody)
                    val after = localJson.decodeFromString<LocalGraph>(frozen.afterBody)
                    val removed = localJson.decodeFromString<List<Long>>(frozen.removedReminders)
                    compileGraph(connectionId, before, after, removed)
                    val changed=after.notes.filter { note -> before.notes.firstOrNull { it.id==note.id } != note } + before.notes.filter { note -> after.notes.none { it.id==note.id } }
                    val privateOnly=changed.isNotEmpty() && changed.all { it.isLocalOnly } && before.tags==after.tags && before.notebooks==after.notebooks
                    dao.updateCapture(frozen.copy(state=if (privateOnly) "PRIVATE" else "COMPILED"))
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if(bridge.active()?.activeConnectionId!=connectionId) return
                dao.updateCapture(frozen.copy(state="BLOCKED",errorCode="local_graph_not_compilable"))
            }
        }
    }

    private suspend fun compileGraph(connectionId: String, before: LocalGraph, after: LocalGraph, removedReminders: List<Long>) {
        val dao=db.nodusDao
        val plan=NodusPlanning(db,connectionId)
        val detachDependencies=mutableMapOf<Pair<NodusResourceType,Long>,MutableList<String>>()
        suspend fun map(type: NodusResourceType, row: Long, parent: String=""): NodusMapping =
            dao.mappingByLocalRow(connectionId,type,row,parent) ?: plan.allocate(type,newNodusId(),row,parent)
        suspend fun organization(type: NodusResourceType, id: Long, name: String, oldName: String?) {
            val existing=dao.mappingByLocalRow(connectionId,type,id)
            val mapping=existing ?: map(type,id)
            if(existing==null) plan.enqueue(mapping.mappingId) { V2OrganizationCreate(it.deviceId,it.requestId,name) }
            else if(name!=oldName) plan.enqueue(mapping.mappingId) { V2OrganizationEdit(it.deviceId,it.requestId,it.revision,name) }
        }
        val changedNotes=after.notes.filter { note -> !note.isLocalOnly && before.notes.firstOrNull { it.id==note.id } != note }
        for(tag in after.tags.filter { tag -> before.tags.firstOrNull {it.id==tag.id} != tag || changedNotes.any { note -> note.tags.any{it.id==tag.id} } }) organization(NodusResourceType.TAG,tag.id,tag.name,before.tags.firstOrNull{it.id==tag.id}?.name)
        for(book in after.notebooks.filter { book -> before.notebooks.firstOrNull {it.id==book.id} != book || changedNotes.any {it.notebookId==book.id} }) organization(NodusResourceType.NOTEBOOK,book.id,book.name,before.notebooks.firstOrNull{it.id==book.id}?.name)
        for(note in after.notes) {
            val old=before.notes.firstOrNull {it.id==note.id}
            var root=dao.mappingByLocalRow(connectionId,NodusResourceType.NOTE,note.id)
            if(note.isLocalOnly) {
                if(root!=null) dao.releaseLocalRow(connectionId,root.mappingId,note.id)
                continue
            }
            if(old==note) continue
            val isNew=root==null
            root=root ?: map(NodusResourceType.NOTE,note.id)
            val parent=root.wireId
            fun sameTask(a:org.qosp.notes.data.model.NoteTask,b:org.qosp.notes.data.model.NoteTask)=a.id==b.id && a.localKey==b.localKey
            val deletedTasks=old?.taskList.orEmpty().filter { previous -> note.taskList.none { sameTask(previous,it) } }
            val itemDeaths=mutableListOf<String>()
            for(task in deletedTasks) {
                val mapping=task.localKey?.let { dao.mappingByLocalKey(connectionId,NodusResourceType.ITEM,it,parent) }
                    ?: dao.mappingByLocalRow(connectionId,NodusResourceType.ITEM,task.id,parent)
                if(mapping!=null) {
                    if(old?.isList==true && note.isList) itemDeaths+=plan.enqueue(mapping.mappingId) { V2ChildDelete(it.deviceId,it.requestId,it.revision) }
                    mapping.localRowId?.let { dao.releaseLocalRow(connectionId,mapping.mappingId,it) }
                }
            }
            val itemMaps=note.taskList.associate { task -> task.id to (dao.mappingByLocalRow(connectionId,NodusResourceType.ITEM,task.id,parent)
                ?: plan.allocate(NodusResourceType.ITEM,task.localKey ?: newNodusId(),task.id,parent)) }
            val reminderMaps=note.reminders.associate { it.id to map(NodusResourceType.REMINDER,it.id,parent) }
            val base=dao.tracking(connectionId,root.mappingId)?.baseBody?.let { NodusJson.decode(V2Note.serializer(),wireString(it)) }
            val initialRefs=if(isNew) note.attachments.map { ref ->
                val transfer=attachments.journal(root.mappingId,requireNotNull(ref.localKey),ref,validateMembership=false,initial=true)
                requireNotNull(dao.mappingByWire(connectionId,NodusResourceType.ATTACHMENT,transfer.attachmentId,parent)).mappingId
            } else emptyList()
            val primaryReminderId=base?.primaryReminderId ?: note.reminders.singleOrNull()?.let { reminderMaps.getValue(it.id).wireId }
            val body=NodusGraphEncoding(db,connectionId).createBody(root,if(isNew)note else note.copy(attachments=emptyList()),initialRefs,primaryReminderId,base,requireReady=!isNew)
            if(isNew) {
                val create=plan.enqueue(root.mappingId) { body.copy(deviceId=it.deviceId,requestId=it.requestId) }
                for(mappingId in initialRefs) {
                    val ref=requireNotNull(dao.mapping(connectionId,mappingId))
                    val transfer=requireNotNull(dao.attachment(connectionId,parent,ref.wireId))
                    dao.storeReadiness(requireNotNull(dao.blob(connectionId,transfer.blobId)),listOf(transfer.copy(operationId=create)))
                }
            } else {
                val prior=requireNotNull(old)
                require(prior.creationDate==note.creationDate) { "immutable_authored_at_changed" }
                if(prior.isDeleted && !note.isDeleted) plan.enqueue(root.mappingId,lifecycle=LifecycleAction.RESTORE) { V2Lifecycle(it.deviceId,it.requestId,it.revision) }
                if(prior.title!=note.title || prior.content!=note.content || prior.isArchived!=note.isArchived || prior.isPinned!=note.isPinned || prior.isHidden!=note.isHidden || prior.isMarkdownEnabled!=note.isMarkdownEnabled || prior.color!=note.color || prior.notebookId!=note.notebookId || prior.tags!=note.tags || prior.modifiedDate!=note.modifiedDate) {
                    val detach=plan.enqueue(root.mappingId) { V2EditNote(it.deviceId,it.requestId,it.revision,body.title,body.text,body.archived,body.pinned,body.hidden,body.markdownEnabled,body.color,body.notebookId,body.tagIds,editedAt=body.editedAt) }
                    for(tag in prior.tags.filter { oldTag -> note.tags.none { it.id==oldTag.id } }) detachDependencies.getOrPut(NodusResourceType.TAG to tag.id) { mutableListOf() }.add(detach)
                    prior.notebookId?.takeIf { it!=note.notebookId }?.let { id -> detachDependencies.getOrPut(NodusResourceType.NOTEBOOK to id) { mutableListOf() }.add(detach) }
                }
                if(prior.isList!=note.isList || prior.taskList!=note.taskList) {
                    if(prior.isList!=note.isList || !note.isList) {
                        plan.enqueue(root.mappingId) { V2Content(it.deviceId,it.requestId,it.revision,body.kind,body.text,WireField.Present(note.taskList.map { task -> ItemInput(itemMaps.getValue(task.id).wireId,WireField.Present(task.content),WireField.Present(task.isDone)) }),body.editedAt) }
                    } else {
                        for(task in note.taskList) {
                            val previous=prior.taskList.firstOrNull{sameTask(it,task)}
                            val mapping=itemMaps.getValue(task.id)
                            if(previous==null) plan.enqueue(mapping.mappingId,dependencies=itemDeaths) { V2Append(it.deviceId,it.requestId,it.revision,mapping.wireId,task.content,WireField.Present(task.isDone),body.editedAt) }
                            else if(previous.content!=task.content) plan.enqueue(mapping.mappingId) { V2ItemEdit(it.deviceId,it.requestId,it.revision,WireField.Present(task.content),WireField.Present(task.isDone),body.editedAt) }
                            else if(previous.isDone!=task.isDone) plan.enqueue(mapping.mappingId) { V2Toggle(it.deviceId,it.requestId,it.revision,task.isDone,body.editedAt) }
                        }
                        if(prior.taskList.map{it.localKey to it.id}!=note.taskList.map{it.localKey to it.id}) plan.enqueue(root.mappingId) { V2Order(it.deviceId,it.requestId,it.revision,WireField.Present(note.taskList.map { task -> itemMaps.getValue(task.id).wireId }),body.editedAt) }
                    }

                }
                for(reminder in note.reminders) {
                    val previous=prior.reminders.firstOrNull{it.id==reminder.id}
                    val mapping=reminderMaps.getValue(reminder.id)
                    if(previous==null) plan.enqueue(mapping.mappingId) { V2ReminderCreate(it.deviceId,it.requestId,it.revision,mapping.wireId,WireField.Present(reminder.name),sourceInstant(reminder.date,null)) }
                    else if(previous!=reminder) plan.enqueue(mapping.mappingId) { V2ReminderEdit(it.deviceId,it.requestId,it.revision,WireField.Present(reminder.name),if(previous.date==reminder.date) WireField.Absent else WireField.Present(sourceInstant(reminder.date,base?.reminders?.firstOrNull { old -> old.id==mapping.wireId }?.dueAt))) }
                }
                for(id in removedReminders) dao.mappingByLocalRow(connectionId,NodusResourceType.REMINDER,id,parent)?.let { mapping ->
                    plan.enqueue(mapping.mappingId) { V2ChildDelete(it.deviceId,it.requestId,it.revision) }
                    dao.releaseLocalRow(connectionId,mapping.mappingId,id)
                }
                if(!prior.isDeleted && note.isDeleted) plan.enqueue(root.mappingId) { V2Trash(it.deviceId,it.requestId,it.revision,WireField.Present(note.deletionDate?.let { date -> sourceInstant(date,null) })) }
            }
            if(isNew) continue
            val refs=mutableListOf<String>()
            for(ref in note.attachments) {
                val key=requireNotNull(ref.localKey)
                val previous=old?.attachments?.firstOrNull { it.localKey==key }
                val existing=dao.mappingByLocalKey(connectionId,NodusResourceType.ATTACHMENT,key,parent)
                if(existing==null) {
                    val transfer=attachments.journal(root.mappingId,key,ref,validateMembership=false)
                    refs+=transfer.attachmentId
                } else {
                    refs+=existing.wireId
                    if(previous!=null && previous!=ref) plan.enqueue(existing.mappingId) { V2AttachmentEdit(it.deviceId,it.requestId,it.revision,WireField.Present(AttachmentKind.valueOf(ref.type.name)),WireField.Present(ref.description),WireField.Present(ref.fileName)) }
                }
            }
            for(ref in old?.attachments.orEmpty().filter { previous -> note.attachments.none { it.localKey==previous.localKey } }) {
                ref.localKey?.let { key -> dao.mappingByLocalKey(connectionId,NodusResourceType.ATTACHMENT,key,parent) }?.let { mapping -> plan.enqueue(mapping.mappingId) { V2ChildDelete(it.deviceId,it.requestId,it.revision) } }
            }
            if(refs.isNotEmpty() && note.attachments.map{it.localKey}!=old?.attachments?.map{it.localKey}) plan.enqueue(root.mappingId) { V2AttachmentOrder(it.deviceId,it.requestId,it.revision,refs) }
        }
        for(note in before.notes.filter { old -> after.notes.none { it.id==old.id } }) {
            if(note.isLocalOnly) continue
            val root=dao.mappingByLocalRow(connectionId,NodusResourceType.NOTE,note.id) ?: continue
            if(!note.isDeleted) plan.enqueue(root.mappingId) { V2Trash(it.deviceId,it.requestId,it.revision) }
            plan.enqueue(root.mappingId,lifecycle=LifecycleAction.PURGE) { V2Lifecycle(it.deviceId,it.requestId,it.revision) }
            // Retain the mapping so the intentional purge can drain after local deletion.
        }
        suspend fun remove(type: NodusResourceType, id: Long) { dao.mappingByLocalRow(connectionId,type,id)?.let { mapping -> plan.enqueue(mapping.mappingId,dependencies=detachDependencies[type to id].orEmpty()) { V2OrganizationDelete(it.deviceId,it.requestId,it.revision) } } }
        for(tag in before.tags.filter { old -> after.tags.none{it.id==old.id} }) remove(NodusResourceType.TAG,tag.id)
        for(book in before.notebooks.filter { old -> after.notebooks.none{it.id==old.id} }) remove(NodusResourceType.NOTEBOOK,book.id)
    }
}

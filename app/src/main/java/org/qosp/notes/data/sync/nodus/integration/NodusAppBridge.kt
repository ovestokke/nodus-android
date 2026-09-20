package org.qosp.notes.data.sync.nodus.integration

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.msoul.datastore.defaultOf
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.*
import org.qosp.notes.data.sync.nodus.NodusConfiguration
import org.qosp.notes.data.sync.nodus.engine.newNodusId
import org.qosp.notes.data.sync.nodus.storage.*

@Serializable
internal data class LocalGraph(val notes: List<Note>, val tags: List<Tag>, val notebooks: List<Notebook>, val version: Int = 1) { init { require(version == 1) } }
internal val localJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

/** Explicit configuration-bound activation; selecting a provider alone never activates. */
class NodusAppBridge internal constructor(private val db: AppDatabase, private val selected: suspend () -> Boolean = {true}, private val configuration: () -> NodusConfiguration?) {
    internal suspend fun active(): NodusIntegrationState? {
        if (!selected()) return null
        val state = db.nodusDao.integration() ?: return null
        val id = state.activeConnectionId ?: return null
        val config = runCatching { configuration() }.getOrNull() ?: return null
        val connection = db.nodusDao.connection(id) ?: return null
        return state.takeIf { it.installationDeviceId == config.deviceId && it.credentialEpoch == config.credentialEpoch &&
            connection.realmId!=null && it.origin == config.origin.toString() && connection.deviceId == config.deviceId && connection.credentialEpoch == config.credentialEpoch && connection.origin == it.origin }
    }
    internal suspend fun activate(connectionId: String) = db.withTransaction {
        require(selected())
        val config = requireNotNull(configuration())
        val connection = requireNotNull(db.nodusDao.connection(connectionId))
        require(connection.realmId!=null)
        require(connection.deviceId == config.deviceId && connection.credentialEpoch == config.credentialEpoch && connection.origin == config.origin.toString())
        val previous=db.nodusDao.integration()
        if (previous?.activeConnectionId==connectionId && previous.installationDeviceId==config.deviceId && previous.credentialEpoch==config.credentialEpoch && previous.origin==config.origin.toString()) return@withTransaction
        var generation=previous?.generation ?: 0
        connection.inactiveGraph?.let { checkpoint ->
            require(connection.checkpointDeviceId==config.deviceId && connection.checkpointEpoch!=null)
            val before=localJson.decodeFromString<LocalGraph>(checkpoint)
            normalizeKeys(before)
            val after=graph()
            detachPrivateMappings(after)
            val removed=connection.checkpointRemovedReminders ?: "[]"
            if(before!=after || removed!="[]") {
                generation++
                db.nodusDao.addCapture(NodusLocalCapture(connectionId=connectionId,deviceId=config.deviceId,credentialEpoch=config.credentialEpoch,generation=generation,
                    beforeBody=checkpoint,afterBody=localJson.encodeToString(after),removedReminders=removed))
            }
            db.nodusDao.checkpoint(connectionId,null,null,null,null)
        }
        db.nodusDao.putIntegration(NodusIntegrationState(activeConnectionId=connectionId, configuredConnectionId=connectionId, installationDeviceId=config.deviceId, credentialEpoch=config.credentialEpoch, origin=config.origin.toString(), generation=generation))
    }
    internal suspend fun seedFreshConnection() = db.withTransaction {
        val state=requireNotNull(active())
        val connectionId=requireNotNull(state.activeConnectionId)
        require(db.nodusDao.captures(connectionId).isEmpty() && db.nodusDao.mappings(connectionId).isEmpty())
        for(note in graph().notes) db.noteDao.update(note.copy(taskList=note.taskList.map { it.copy(localKey=newNodusId()) },attachments=note.attachments.map { it.copy(localKey=newNodusId()) }).toEntity())
        db.nodusDao.addCapture(NodusLocalCapture(connectionId=connectionId,deviceId=requireNotNull(state.installationDeviceId),credentialEpoch=requireNotNull(state.credentialEpoch),generation=state.generation+1,
            beforeBody=localJson.encodeToString(LocalGraph(emptyList(),emptyList(),emptyList())),afterBody=localJson.encodeToString(graph()),removedReminders="[]"))
        db.nodusDao.putIntegration(state.copy(generation=state.generation+1))
    }

    internal suspend fun deactivate() = db.withTransaction {
        val state=db.nodusDao.integration() ?: return@withTransaction
        val id=state.activeConnectionId ?: return@withTransaction
        val connection=requireNotNull(db.nodusDao.connection(id))
        db.nodusDao.checkpoint(id,localJson.encodeToString(graph()),connection.deviceId,connection.credentialEpoch,"[]")
        db.nodusDao.putIntegration(state.copy(activeConnectionId=null))
    }
    private suspend fun normalizeKeys(before:LocalGraph) {
        for(note in graph().notes) {
            val previous=before.notes.firstOrNull { it.id==note.id }
            val seen=mutableSetOf<String>()
            val normalized=note.attachments.map { ref ->
                val old=previous?.attachments?.firstOrNull { it.localKey==ref.localKey }
                if(ref.localKey==null || old==null || old.path!=ref.path || !seen.add(ref.localKey)) ref.copy(localKey=newNodusId()) else ref
            }
            val tasks=normalizeTasks(previous,note)
            if(normalized!=note.attachments || tasks!=note.taskList) db.noteDao.update(note.copy(attachments=normalized,taskList=tasks).toEntity())
        }
    }
    private fun normalizeTasks(previous:Note?,note:Note):List<NoteTask> {
        val seen=mutableSetOf<String>()
        return note.taskList.map { task ->
            val old=previous?.taskList?.firstOrNull { it.id==task.id }
            val key=if(old!=null && (task.localKey==null || task.localKey==old.localKey)) old.localKey else null
            task.copy(localKey=key?.takeIf { seen.add(it) } ?: newNodusId())
        }
    }
    private suspend fun detachPrivateMappings(graph:LocalGraph) {
        for(note in graph.notes.filter { it.isLocalOnly }) for(mapping in db.nodusDao.noteMappings(note.id))
            db.nodusDao.releaseLocalRow(mapping.connectionId,mapping.mappingId,note.id)
    }
    private suspend fun recordInactiveRemovals(removed:Set<Long>) {
        if(removed.isEmpty()) return
        for(connection in db.nodusDao.connections().filter { it.inactiveGraph!=null }) {
            val old=localJson.decodeFromString<List<Long>>(connection.checkpointRemovedReminders ?: "[]")
            db.nodusDao.checkpoint(connection.connectionId,connection.inactiveGraph,connection.checkpointDeviceId,connection.checkpointEpoch,localJson.encodeToString((old+removed).distinct()))
        }
    }
    private suspend fun graph() = LocalGraph(db.noteDao.getAll(defaultOf()).first().sortedBy { it.id }, db.tagDao.getAll().first().sortedBy { it.id }, db.notebookDao.getAll().first().sortedBy { it.id })

    /** Only local SQL and serialization. No file access, compilation or network in this block. */
    suspend fun <T> mutate(shared: Boolean = true, userReminderRemoval: Boolean = false, block: suspend () -> T): T {
        // Resolve encrypted configuration before acquiring Room's write transaction.
        val authorized = if (shared) active() else null
        return db.withTransaction {
            val current = db.nodusDao.integration()
            if (authorized == null || current == null || current.activeConnectionId != authorized.activeConnectionId ||
                current.installationDeviceId != authorized.installationDeviceId || current.credentialEpoch != authorized.credentialEpoch || current.origin != authorized.origin) {
                if(!shared) return@withTransaction block()
                val previous=graph()
                val before=if(userReminderRemoval) db.reminderDao.getAll().first().map { it.id }.toSet() else emptySet()
                val result=block()
                normalizeKeys(previous)
                detachPrivateMappings(graph())
                if(userReminderRemoval) recordInactiveRemovals(before-db.reminderDao.getAll().first().map { it.id }.toSet())
                return@withTransaction result
            }
            val before = graph()
            val result = block()
            val changed = graph()
            for (note in changed.notes) {
                val previous = before.notes.firstOrNull { it.id == note.id }
                if (previous?.copy(isCompactPreview=note.isCompactPreview,screenAlwaysOn=note.screenAlwaysOn) == note) continue
                val allowed = previous?.attachments?.mapNotNull { it.localKey }?.toSet() ?: emptySet()
                val seen = mutableSetOf<String>()
                val attachments = note.attachments.map { ref ->
                    if (ref.localKey == null || ref.localKey !in allowed || previous?.attachments?.firstOrNull { it.localKey==ref.localKey }?.path != ref.path || !seen.add(ref.localKey)) ref.copy(localKey=newNodusId()) else ref
                }
                val tasks=normalizeTasks(previous,note)
                if (attachments != note.attachments || tasks!=note.taskList) db.noteDao.update(note.copy(attachments=attachments,taskList=tasks).toEntity())
            }
            val after = graph()
            detachPrivateMappings(after)
            fun shared(graph: LocalGraph) = graph.copy(notes=graph.notes.map { it.copy(isCompactPreview=false,screenAlwaysOn=false) })
            if (shared(before) == shared(after)) return@withTransaction result
            val connectionId = requireNotNull(authorized.activeConnectionId)
            val generation = current.generation + 1
            val removed = if (userReminderRemoval) before.notes.flatMap { it.reminders }.map { it.id }.toSet() - after.notes.flatMap { it.reminders }.map { it.id }.toSet() else emptySet()
            recordInactiveRemovals(removed)
            val latest = db.nodusDao.latestCapture(connectionId)
            fun textOnly(a: LocalGraph, b: LocalGraph): Boolean = a.tags==b.tags && a.notebooks==b.notebooks && a.notes.size==b.notes.size && a.notes.zip(b.notes).all { (old,new) -> old.copy(title=new.title,content=new.content,modifiedDate=new.modifiedDate)==new }
            val coalesce = latest?.takeIf { it.state == "PENDING" && it.deviceId == authorized.installationDeviceId && it.credentialEpoch == authorized.credentialEpoch && !userReminderRemoval && textOnly(before,after) && localJson.decodeFromString<LocalGraph>(it.afterBody)==before }
            val capture = NodusLocalCapture(connectionId=connectionId, deviceId=requireNotNull(authorized.installationDeviceId), credentialEpoch=requireNotNull(authorized.credentialEpoch), generation=generation,
                beforeBody=coalesce?.beforeBody ?: localJson.encodeToString(before), afterBody=localJson.encodeToString(after),
                removedReminders=localJson.encodeToString((coalesce?.let { localJson.decodeFromString<List<Long>>(it.removedReminders) } ?: emptyList()) + removed))
            if (coalesce == null) db.nodusDao.addCapture(capture) else db.nodusDao.updateCapture(capture.copy(id=coalesce.id))
            db.nodusDao.putIntegration(current.copy(generation=generation))
            result
        }
    }

    suspend fun retainedAttachmentPaths(): Set<String> = buildSet {
        for(capture in db.nodusDao.allCaptures()) {
            for(body in listOf(capture.beforeBody,capture.afterBody)) addAll(localJson.decodeFromString<LocalGraph>(body).notes.flatMap { it.attachments }.map { it.path })
        }
        addAll(db.nodusDao.allAttachmentTransfers().mapNotNull { it.sourceUri })
        for(connection in db.nodusDao.connections()) connection.inactiveGraph?.let { addAll(localJson.decodeFromString<LocalGraph>(it).notes.flatMap { note -> note.attachments }.map { it.path }) }
    }

    /** Automatic empty/bin cleanup cannot erase retained shared or uncompiled graphs. */
    suspend fun cleanupEligible(noteId: Long): Boolean {
        if (db.nodusDao.hasRetainedMapping(noteId) || db.nodusDao.integration()?.activeConnectionId != null) return false
        if(db.nodusDao.connections().any { it.inactiveGraph?.let { body -> localJson.decodeFromString<LocalGraph>(body).notes.any { note -> note.id==noteId } }==true }) return false
        return db.nodusDao.allCaptures().none { capture ->
            listOf(capture.beforeBody,capture.afterBody).any { body -> localJson.decodeFromString<LocalGraph>(body).notes.any { it.id==noteId } }
        }
    }
}

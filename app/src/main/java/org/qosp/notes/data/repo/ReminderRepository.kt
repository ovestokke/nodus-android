package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.Flow
import org.qosp.notes.data.dao.ReminderDao
import org.qosp.notes.data.model.Reminder

class ReminderRepository(private val reminderDao: ReminderDao, private val nodus: org.qosp.notes.data.sync.nodus.integration.NodusAppBridge? = null) {

    private suspend fun <T> capture(removal: Boolean = false, block: suspend () -> T): T = nodus?.mutate(userReminderRemoval=removal,block=block) ?: block()

    fun getAll(): Flow<List<Reminder>> {
        return reminderDao.getAll()
    }

    fun getByNoteId(noteId: Long): Flow<List<Reminder>> {
        return reminderDao.getByNoteId(noteId)
    }

    fun getById(reminderId: Long): Flow<Reminder?> {
        return reminderDao.getById(reminderId)
    }

    suspend fun insert(reminder: Reminder): Long {
        return capture { reminderDao.insert(reminder) }
    }

    suspend fun update(vararg reminders: Reminder) {
        capture { reminderDao.update(*reminders) }
    }

    suspend fun deleteById(id: Long) {
        capture(true) { reminderDao.deleteById(id) }
    }

    suspend fun consumeById(id: Long) { reminderDao.deleteById(id) }

    suspend fun deleteByNoteId(noteId: Long) {
        capture(true) { reminderDao.deleteByNoteId(noteId) }
    }

    suspend fun copyReminders(fromNoteId: Long, toNoteId: Long) {
        capture { reminderDao.copyReminders(fromNoteId, toNoteId) }
    }
}

package org.qosp.notes.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.qosp.notes.data.model.Reminder

@Dao
interface ReminderDao {
    @Query("SELECT (SELECT COUNT(*) FROM reminders r JOIN notes n ON n.id=r.noteId LEFT JOIN reminder_alarm_state a ON a.id=r.id WHERE n.isDeleted=0 AND (a.id IS NULL OR a.noteId!=r.noteId OR a.date!=r.date OR a.name!=r.name OR a.fingerprint!=r.alarmFingerprint)) + (SELECT COUNT(*) FROM reminder_alarm_state a LEFT JOIN reminders r ON r.id=a.id LEFT JOIN notes n ON n.id=r.noteId WHERE r.id IS NULL OR n.id IS NULL OR n.isDeleted!=0)")
    suspend fun alarmBacklog():Int

    @Query("SELECT * FROM reminder_alarm_state")
    suspend fun alarmStates():List<org.qosp.notes.data.model.ReminderAlarmState>

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun appliedAlarm(value:org.qosp.notes.data.model.ReminderAlarmState)

    @Query("DELETE FROM reminder_alarm_state WHERE id=:id")
    suspend fun removeAlarmState(id:Long)

    @Query("DELETE FROM reminders WHERE id=:id AND noteId=:noteId AND date=:date AND name=:name AND alarmFingerprint=:fingerprint AND date<=:now AND EXISTS(SELECT 1 FROM notes WHERE notes.id=:noteId AND isDeleted=0)")
    suspend fun consumeAlarm(id:Long,noteId:Long,date:Long,name:String,fingerprint:String,now:Long):Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(vararg reminders: Reminder)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM reminders WHERE noteId = :noteId")
    suspend fun deleteByNoteId(noteId: Long)

    @Query("DELETE FROM reminders WHERE noteId IN (:ids)")
    suspend fun deleteIfNoteIdIn(ids: List<Long>)

    @Query("SELECT * FROM reminders")
    fun getAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE noteId = :noteId")
    fun getByNoteId(noteId: Long): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE id = :reminderId")
    fun getById(reminderId: Long): Flow<Reminder?>

    @Query(
        """
    INSERT INTO reminders (name, noteId, date)
    SELECT name, :toNoteId, date FROM reminders WHERE noteId = :fromNoteId
    """
    )
    suspend fun copyReminders(fromNoteId: Long, toNoteId: Long)
}

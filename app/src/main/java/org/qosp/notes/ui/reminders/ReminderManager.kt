package org.qosp.notes.ui.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.navigation.NavDeepLinkBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qosp.notes.App
import org.qosp.notes.R
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.repo.ReminderRepository
import org.qosp.notes.ui.MainActivity


class ReminderManager(
    private val context: Context,
    private val reminderRepository: ReminderRepository,
    private val noteRepository: NoteRepository,
    private val db:org.qosp.notes.data.AppDatabase,
) {
    private companion object { val alarmLock=Mutex() }
    private fun requestBroadcast(reminderId: Long, noteId: Long, flag: Int = PendingIntent.FLAG_UPDATE_CURRENT, dueAt:Long?=null, name:String?=null, fingerprint:String?=null): PendingIntent? {
        val defaultFlag = PendingIntent.FLAG_IMMUTABLE

        val notificationIntent = Intent(context, ReminderReceiver::class.java).apply {
            putExtras(
                bundleOf(
                    "noteId" to noteId,
                    "reminderId" to reminderId,
                )
            )
            if(dueAt!=null) putExtra("dueAt",dueAt)
            if(name!=null) putExtra("reminderName",name)
            if(fingerprint!=null) putExtra("alarmFingerprint",fingerprint)
            action = ReminderReceiver.REMINDER_HAS_FIRED
        }
        return PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            notificationIntent,
            flag or defaultFlag
        )
    }

    fun isReminderSet(reminderId: Long, noteId: Long): Boolean {
        val defaultFlag = PendingIntent.FLAG_IMMUTABLE
        return requestBroadcast(reminderId, noteId, PendingIntent.FLAG_NO_CREATE or defaultFlag) != null
    }

    fun schedule(reminderId: Long, dateTime: Long, noteId: Long, name:String?=null, fingerprint:String="") {
        val alarmManager = ContextCompat.getSystemService(context, AlarmManager::class.java) ?: return
        val broadcast = requestBroadcast(reminderId, noteId,dueAt=dateTime,name=name,fingerprint=fingerprint) ?: return

        cancel(reminderId, noteId, keepIntent = true)

        val triggerAtMillis = dateTime * 1000 // convert seconds to millis

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            AlarmManagerCompat.setAndAllowWhileIdle(
                alarmManager,
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                broadcast
            )
        } else {
            AlarmManagerCompat.setExactAndAllowWhileIdle(
                alarmManager,
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                broadcast
            )
        }
    }

    fun cancel(reminderId: Long, noteId: Long, keepIntent: Boolean = false) {
        val alarmManager = ContextCompat.getSystemService(context, AlarmManager::class.java) ?: return
        val defaultFlag = PendingIntent.FLAG_IMMUTABLE
        val broadcast = requestBroadcast(reminderId, noteId, PendingIntent.FLAG_NO_CREATE or defaultFlag) ?: return
        alarmManager.cancel(broadcast)
        if (!keepIntent) broadcast.cancel()
    }

    suspend fun cancelAllRemindersForNote(noteId: Long) {
        val reminders = reminderRepository.getByNoteId(noteId).first()
        reminders.forEach { cancel(it.id, noteId) }
    }

    suspend fun rescheduleAll() {
        reminderRepository
            .getAll()
            .first()
            .forEach { reminder ->
                if (reminder.hasExpired()) {
                    reminderRepository.consumeById(reminder.id)
                    return@forEach
                }
                schedule(reminder.id, reminder.date, reminder.noteId,reminder.name,reminder.alarmFingerprint)
            }
        reconcile(force=true)
    }

    /** Crash between OS action and ledger commit safely repeats the idempotent action. */
    suspend fun reconcile(force:Boolean=false)=alarmLock.withLock {
        check(!db.inTransaction())
        val desired=reminderRepository.getAll().first().filter { noteRepository.getById(it.noteId).first()?.isDeleted==false }
        val applied=db.reminderDao.alarmStates().associateBy { it.id }
        for(old in applied.values.filter { state -> desired.none { it.id==state.id } }) {
            cancel(old.id,old.noteId)
            db.reminderDao.removeAlarmState(old.id)
        }
        for(row in desired) {
            val fingerprint=org.qosp.notes.data.model.ReminderAlarmState(row.id,row.noteId,row.date,row.name,row.alarmFingerprint)
            if(force || applied[row.id]!=fingerprint) {
                schedule(row.id,row.date,row.noteId,row.name,row.alarmFingerprint)
                db.reminderDao.appliedAlarm(fingerprint)
            }
        }
    }

    suspend fun sendNotification(reminderId: Long, noteId: Long, dueAt:Long?=null, expectedName:String?=null, expectedFingerprint:String?=null) {
        val notificationManager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return

        val reminder=reminderRepository.getById(reminderId).first() ?: return
        if(reminder.alarmFingerprint!=(expectedFingerprint ?: "")) return
        if(reminder.noteId!=noteId || (dueAt!=null && reminder.date!=dueAt) || (expectedName!=null && reminder.name!=expectedName)) return
        if(db.reminderDao.consumeAlarm(reminderId,noteId,reminder.date,reminder.name,reminder.alarmFingerprint,java.time.Instant.now().epochSecond)!=1) return
        cancel(reminderId,noteId)
        db.reminderDao.removeAlarmState(reminderId)
        var notificationTitle = reminder.name

        if (notificationTitle.isEmpty()) {
            noteRepository.getById(noteId).first()?.let { notificationTitle = it.title }
        }

        val pendingIntent = NavDeepLinkBuilder(context)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.fragment_editor)
            .setArguments(
                bundleOf(
                    "noteId" to noteId,
                    "transitionName" to ""
                )
            )
            .setComponentName(MainActivity::class.java)
            .createPendingIntent()

        val notification = NotificationCompat.Builder(context, App.REMINDERS_CHANNEL_ID)
            .setContentText(notificationTitle)
            .setContentTitle(context.getString(R.string.notification_reminder_fired))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(reminderId.toInt(), notification)
    }
}

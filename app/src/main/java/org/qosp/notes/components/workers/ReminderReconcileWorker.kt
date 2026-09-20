package org.qosp.notes.components.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.qosp.notes.ui.reminders.ReminderManager

/** Network-independent restart recovery for committed desired alarm state. */
class ReminderReconcileWorker(context:Context,params:WorkerParameters,private val reminders:ReminderManager):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = try {
        reminders.reconcile(force=true)
        Result.success()
    } catch(cancel:CancellationException) { throw cancel }
    catch(_:Exception) { Result.retry() }
}

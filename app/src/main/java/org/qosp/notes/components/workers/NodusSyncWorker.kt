package org.qosp.notes.components.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qosp.notes.data.sync.nodus.integration.NodusController

/** Continuation of an explicit manual sync/activation, independent of periodic-sync preference. */
class NodusSyncWorker(context:Context,params:WorkerParameters,private val nodus:NodusController):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) { nodus.workerIfSelected() ?: Result.success() }
}

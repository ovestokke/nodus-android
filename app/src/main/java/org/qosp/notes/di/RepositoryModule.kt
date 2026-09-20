package org.qosp.notes.di

import org.koin.core.module.dsl.singleOf
import kotlinx.coroutines.flow.first
import org.qosp.notes.preferences.*
import org.qosp.notes.ui.utils.ConnectionManager
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.engine.*
import org.qosp.notes.data.sync.nodus.integration.*
import org.qosp.notes.data.repo.IdMappingRepository
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.repo.NoteRepositoryImpl
import org.qosp.notes.data.repo.NotebookRepository
import org.qosp.notes.data.repo.ReminderRepository
import org.qosp.notes.data.repo.TagRepository

object RepositoryModule {

    val repoModule = module {
        includes(DatabaseModule.dbModule)

        single { NodusConfigurationStore(androidContext()) }
        single { NodusAppBridge(get(),selected={get<PreferenceRepository>().get<CloudService>().first()==CloudService.NODUS}) { get<NodusConfigurationStore>().configuration() } }
        single { NodusByteStore(androidContext()) }
        single {
            val context=androidContext()
            NodusSyncService(get(),get(),{ get<NodusConfigurationStore>().configuration() },get(),NodusHttpEngineTransport(),NodusHttpBinaryTransport(),
                { uri -> requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(uri))) },reconcileReminders={get<org.qosp.notes.ui.reminders.ReminderManager>().reconcile()})
        }

        single {
            val context=androidContext()
            NodusController(get(),get(),get(),get(),get(),NodusHttpEngineTransport(),NodusHttpPairingTransport(),get(),
                networkAllowed={get<ConnectionManager>().isConnectionAvailable(get<PreferenceRepository>().get<SyncMode>().first(),CloudService.NODUS)},
                schedule={ androidx.work.WorkManager.getInstance(context).enqueueUniqueWork("NODUS_MANUAL",androidx.work.ExistingWorkPolicy.KEEP,
                    androidx.work.OneTimeWorkRequestBuilder<org.qosp.notes.components.workers.NodusSyncWorker>()
                        .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build()) })
        }

        singleOf(::NoteRepositoryImpl) bind NoteRepository::class
        singleOf(::ReminderRepository)
        singleOf(::NotebookRepository)
        singleOf(::TagRepository)
        singleOf(::IdMappingRepository)
    }
}

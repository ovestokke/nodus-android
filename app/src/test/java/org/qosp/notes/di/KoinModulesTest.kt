package org.qosp.notes.di

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.verify.definition
import org.koin.test.verify.injectedParameters
import org.koin.test.verify.verify

class KoinModulesTest : KoinTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun testAllModules() {
        val allModules = module {
            includes(
                MarkwonModule.markwonModule,
                NextcloudModule.nextcloudModule,
                PreferencesModule.prefModule,
                RepositoryModule.repoModule,
                UIModule.uiModule,
                UtilModule.utilModule,
                SyncModule.syncModule,
            )
        }
        allModules.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                WorkerParameters::class,
                SharedPreferences::class,
                CoroutineScope::class,
                kotlin.coroutines.CoroutineContext::class,
                SyncScope::class,
            ),
            injections = injectedParameters(
                definition<org.qosp.notes.data.sync.nodus.NodusConfigurationStore>(android.util.AtomicFile::class, Function1::class),
                definition<org.qosp.notes.data.sync.nodus.integration.NodusAppBridge>(Function0::class,Function1::class),
                definition<org.qosp.notes.data.sync.nodus.engine.NodusByteStore>(java.io.File::class,Long::class,Function1::class,Function2::class),
                definition<org.qosp.notes.data.sync.nodus.integration.NodusController>(Function0::class,Function1::class,org.qosp.notes.data.sync.nodus.engine.NodusEngineTransport::class),
                definition<org.qosp.notes.components.workers.NodusSyncWorker>(Context::class,WorkerParameters::class),
                definition<org.qosp.notes.data.sync.nodus.integration.NodusSyncService>(Function0::class, Function1::class,
                    org.qosp.notes.data.sync.nodus.integration.NodusReminderReconciliation::class,
                    org.qosp.notes.data.sync.nodus.engine.NodusByteStore::class,
                    org.qosp.notes.data.sync.nodus.engine.NodusEngineTransport::class,
                    org.qosp.notes.data.sync.nodus.engine.NodusBinaryTransport::class),
                definition<org.qosp.notes.ui.launcher.LauncherViewModel>(
                    Application::class
                ),
                definition<org.qosp.notes.components.workers.BinCleaningWorker>(
                    Context::class,
                    WorkerParameters::class
                ),
                definition<org.qosp.notes.components.workers.SyncWorker>(
                    Context::class,
                    WorkerParameters::class
                )
            )
        )
    }
}

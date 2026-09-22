package org.qosp.notes.ui.sync

import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.R
import org.qosp.notes.components.workers.NodusSyncWorker
import org.qosp.notes.components.workers.SyncWorker
import org.qosp.notes.data.repo.NoteRepository
import org.qosp.notes.data.sync.nodus.integration.*
import org.qosp.notes.databinding.LayoutNodusSettingsBinding
import org.qosp.notes.preferences.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class NodusSettingsTest {
    @Test fun xmlBindingExposesOnlyShortLivedPairingCodeAndExcludesItFromSavedState() {
        val context=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme)
        val binding=LayoutNodusSettingsBinding.inflate(LayoutInflater.from(context))
        assertTrue(binding.pairingCode.inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS!=0)
        assertFalse(binding.pairingCode.isSaveEnabled)
        assertEquals(android.view.View.IMPORTANT_FOR_AUTOFILL_NO,binding.pairingCode.importantForAutofill)
        assertFalse(binding.pair.isAllCaps)
        assertFalse(binding.cancelPairing.isAllCaps)
        assertNotNull(binding.pairingHelp);assertNotNull(binding.retained)
        assertNotNull(binding.activate);assertNotNull(binding.conflicts);assertNotNull(binding.status)
    }

    @Test fun viewModelNeverIncludesPairingCodeOrExceptionTextInObservableState():Unit=runBlocking {
        val controller=mockk<NodusController>()
        coEvery {controller.status()} returns NodusStatus()
        coEvery {controller.pair(any(),any(),true)} throws IllegalArgumentException("23456-789AB")
        val model=NodusSettingsViewModel(controller)
        model.pair("https://notes.example","23456-789AB")
        val failed=withTimeout(5000){model.state.first{!it.busy && it.message!=null}}
        assertEquals(R.string.nodus_pairing_input_invalid,failed.message)
        assertFalse(failed.toString().contains("23456-789AB"))
        coEvery {controller.pair(any(),any(),true)} just Runs
        model.pair("https://notes.example","23456-789AB")
        val saved=withTimeout(5000){model.state.first{!it.busy && it.credentialVersion==1}}
        assertEquals(R.string.nodus_paired,saved.message)
        assertFalse(saved.toString().contains("23456-789AB"))
    }

    @Test fun initialStatusLoadDoesNotDisablePairingAndPairingReportsProgress():Unit=runBlocking {
        val controller=mockk<NodusController>()
        val statusGate=CompletableDeferred<Unit>()
        val pairGate=CompletableDeferred<Unit>()
        val pairStarted=CompletableDeferred<Unit>()
        coEvery {controller.status()} coAnswers {statusGate.await();NodusStatus()}
        coEvery {controller.pair(any(),any(),true)} coAnswers {pairStarted.complete(Unit);pairGate.await()}
        val model=NodusSettingsViewModel(controller)
        model.load()
        model.pair("https://notes.example","23456-789AB")
        withTimeout(5000){pairStarted.await()}
        assertTrue(model.state.value.busy)
        assertTrue(model.state.value.pairingBusy)
        statusGate.complete(Unit)
        pairGate.complete(Unit)
        val completed=withTimeout(5000){model.state.first{!it.busy && it.message==R.string.nodus_paired}}
        assertFalse(completed.pairingBusy)
    }

    @Test fun viewModelPairsWithoutExposingCredentialAndRoutesExplicitConflictActions():Unit=runBlocking {
        val controller=mockk<NodusController>()
        coEvery {controller.status()} returns NodusStatus(active=true,pending=2,blocked=1,unknown=1)
        coEvery {controller.pair(any(),any(),true)} just Runs
        coEvery {controller.refreshConflicts()} returns listOf(NodusConflictView("conflict","pending","Checklist item","Choose which version to keep.","new","current"))
        coEvery {controller.resolve("conflict",false,true)} just Runs
        val model=NodusSettingsViewModel(controller)
        model.pair("https://notes.example","23456-789AB")
        withTimeout(5000){model.state.first{!it.busy && it.message==R.string.nodus_paired}}
        coVerify(exactly=1){controller.pair("https://notes.example","23456-789AB",true)}
        model.conflicts()
        val state=withTimeout(5000){model.state.first{!it.busy && it.showConflicts}}
        assertEquals("Checklist item",state.conflicts.single().title)
        assertEquals("new",state.conflicts.single().proposed)
        assertFalse(state.status.synchronized)
        model.consumeConflictList();model.resolve("conflict",false)
        withTimeout(5000){model.state.first{!it.busy}}
        coVerify(exactly=1){controller.resolve("conflict",false,true)}
    }

    @Test fun manualAndPeriodicWorkersPreserveRetryAndLegacyRouting():Unit=runBlocking {
        val controller=mockk<NodusController>()
        coEvery{controller.workerIfSelected()} returns ListenableWorker.Result.retry()
        val context=RuntimeEnvironment.getApplication() as android.content.Context
        val parameters=mockk<WorkerParameters>(relaxed=true)
        val preferences=PreferenceRepository(MemoryPreferences(),mockk(relaxed=true))
        val repository=mockk<NoteRepository>()
        val periodic=SyncWorker(preferences,repository,context,parameters,controller)
        val manual=NodusSyncWorker(context,parameters,controller)
        assertEquals(ListenableWorker.Result.retry(),periodic.doWork())
        assertEquals(ListenableWorker.Result.retry(),manual.doWork())
        coVerify(exactly=0){repository.syncNotes()}
        preferences.set(BackgroundSync.DISABLED)
        assertEquals(ListenableWorker.Result.failure(),periodic.doWork())
        assertEquals(ListenableWorker.Result.retry(),manual.doWork())
        preferences.set(BackgroundSync.ENABLED)
        coEvery{controller.workerIfSelected()} returns null
        coEvery{repository.syncNotes()} returns org.qosp.notes.data.sync.core.Success
        assertEquals(ListenableWorker.Result.success(),periodic.doWork())
        coVerify(exactly=1){repository.syncNotes()}
    }
}

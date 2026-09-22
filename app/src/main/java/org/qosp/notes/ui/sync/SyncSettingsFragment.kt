package org.qosp.notes.ui.sync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.qosp.notes.R
import org.qosp.notes.data.sync.fs.toFriendlyString
import org.qosp.notes.databinding.FragmentSyncSettingsBinding
import org.qosp.notes.preferences.AppPreferences
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.common.BaseFragment
import org.qosp.notes.ui.settings.SettingsViewModel
import org.qosp.notes.ui.settings.showPreferenceDialog
import org.qosp.notes.ui.sync.nextcloud.NextcloudAccountDialog
import org.qosp.notes.ui.sync.nextcloud.NextcloudServerDialog
import org.qosp.notes.ui.utils.StorageLocationContract
import org.qosp.notes.ui.utils.collect
import org.qosp.notes.ui.utils.liftAppBarOnScroll
import org.qosp.notes.ui.utils.viewBinding

class SyncSettingsFragment : BaseFragment(R.layout.fragment_sync_settings) {
    private val binding by viewBinding(FragmentSyncSettingsBinding::bind)
    private val model: SettingsViewModel by activityViewModel()
    private val nodus: NodusSettingsViewModel by viewModel()
    private var credentialVersion=0

    override val hasMenu = false
    override val toolbar: Toolbar
        get() = binding.layoutAppBar.toolbar
    override val toolbarTitle: String
        get() = getString(R.string.preferences_header_syncing)

    private var appPreferences = AppPreferences()
    private var nextcloudUrl = ""
    private var storageLocation: Uri? = null

    private val locationListener = registerForActivityResult(StorageLocationContract) { uri ->
        uri?.let {
            // Get the previous location before setting the new one
            val previousLocation = storageLocation?.toString() ?: ""
            val newLocation = it.toString()

            // Set the new location
            model.setEncryptedString(PreferenceRepository.STORAGE_LOCATION, newLocation)
            Log.i(TAG, "Storing location: $it")

            // Remove IdMappings if needed
            model.removeFileStorageIdMappingsIfNeeded(newLocation, previousLocation)

            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context?.contentResolver?.takePersistableUriPermission(it, takeFlags)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.scrollView.liftAppBarOnScroll(
            binding.layoutAppBar.appBar,
            requireContext().resources.getDimension(R.dimen.app_bar_elevation)
        )

        setupPreferenceObservers()
        setupSyncServiceListener()
        setupSyncModeListener()
        setupBackgroundSyncListener()
        setupNewNotesSyncableListener()

        setupNextcloudServerListener()
        setupNextcloudAccountListener()
        setupClearNextcloudCredentialsListener()
        setupTrustCertificatesListener()

        setupLocalLocationListener()
        setupNodus()
    }

    private fun View.show(visible: Boolean) = if (visible) visibility = View.VISIBLE else visibility = View.GONE

    private fun setupPreferenceObservers() {
        model.appPreferences.collect(viewLifecycleOwner) { prefs ->
            appPreferences = prefs

            // Update visibility of layouts based on cloud service
            binding.layoutNodusSettings.root.show(prefs.cloudService == CloudService.NODUS)
            binding.layoutGenericSettings.show(prefs.cloudService in setOf(CloudService.NEXTCLOUD,CloudService.NODUS))
            binding.layoutNextcloudSettings.show(prefs.cloudService == CloudService.NEXTCLOUD)
            binding.layoutStorageSettings.show(prefs.cloudService == CloudService.FILE_STORAGE)
            binding.settingSyncMode.show(prefs.cloudService in setOf(CloudService.NEXTCLOUD,CloudService.NODUS))
            binding.settingBackgroundSync.show(prefs.cloudService in setOf(CloudService.NEXTCLOUD,CloudService.NODUS))
            binding.settingNotesSyncableByDefault.show(prefs.cloudService in setOf(CloudService.NEXTCLOUD,CloudService.NODUS))
            binding.settingTrustSelfSignedCertificate.show(prefs.cloudService == CloudService.NEXTCLOUD)

            binding.settingSyncProvider.subText = getString(prefs.cloudService.nameResource)
            binding.settingSyncMode.subText = getString(prefs.syncMode.nameResource)
            binding.settingBackgroundSync.subText = getString(prefs.backgroundSync.nameResource)
            binding.settingNotesSyncableByDefault.subText = getString(prefs.newNotesSyncable.nameResource)
            binding.settingTrustSelfSignedCertificate.subText = getString(prefs.trustSelfSignedCertificate.nameResource)
        }

        // ENCRYPTED
        model.getEncryptedString(PreferenceRepository.NEXTCLOUD_INSTANCE_URL).collect(viewLifecycleOwner) {
            nextcloudUrl = it
            binding.settingNextcloudServer.subText =
                nextcloudUrl.ifEmpty { getString(R.string.preferences_nextcloud_set_server_url) }
        }

        model.loggedInUsername.collect(viewLifecycleOwner) {
            binding.settingNextcloudAccount.subText = if (it != null) {
                getString(R.string.indicator_nextcloud_currently_logged_in_as, it)
            } else {
                getString(R.string.preferences_nextcloud_set_your_credentials)
            }
        }

        model.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).collect(viewLifecycleOwner) { u ->
            val uri = u.toUri()
            storageLocation = uri
            val appName = if (u.isNotBlank()) context?.let { uri.toFriendlyString(it) } else null
            binding.settingStorageLocation.subText = appName ?: getString(R.string.preferences_file_storage_select)
        }
    }

    private fun confirm(message:Int, action:()->Unit) {
        MaterialAlertDialogBuilder(requireContext()).setMessage(message)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok){_,_->action()}.show()
    }
    private fun setupNodus() = with(binding.layoutNodusSettings) {
        arguments?.getString("pairingOrigin")?.let { pairedOrigin ->
            origin.setText(pairedOrigin)
            pairingCode.setText(arguments?.getString("pairingCode").orEmpty())
            model.setPreference(CloudService.NODUS)
        }
        pair.setOnClickListener {
            val code=if(nodus.state.value.status.pairingPending) "" else pairingCode.text.toString()
            nodus.pair(origin.text.toString(),code)
        }
        cancelPairing.setOnClickListener { confirm(R.string.nodus_cancel_pairing_warning){nodus.cancelPairing()} }
        activate.setOnClickListener { confirm(R.string.nodus_activate_warning){nodus.activate()} }
        sync.setOnClickListener { nodus.sync() }
        conflicts.setOnClickListener { nodus.conflicts() }
        nodus.state.collect(viewLifecycleOwner) { state ->
            val current=state.status
            if(origin.text.isEmpty()) origin.setText(current.origin.ifEmpty { "https://nodus.vstokke.com" })
            if(state.credentialVersion!=credentialVersion){pairingCode.setText("");origin.setText(current.origin.ifEmpty { origin.text });credentialVersion=state.credentialVersion}

            val needsPairing=!current.tokenSaved
            origin.show(needsPairing)
            origin.isEnabled=!state.busy && !current.pairingPending
            pairingCode.show(needsPairing && !current.pairingPending)
            pair.show(needsPairing)
            cancelPairing.show(current.pairingCancelable)
            pairingStatus.setText(when {
                current.active -> R.string.nodus_connected
                current.tokenSaved -> R.string.nodus_credential_saved
                current.pairingPending -> R.string.nodus_pairing_pending
                else -> R.string.nodus_pairing_ready
            })
            pairingHelp.text=when {
                current.active -> getString(R.string.nodus_connected_help,current.origin)
                current.tokenSaved -> getString(R.string.nodus_credential_saved_help,current.origin)
                current.pairingPending -> getString(R.string.nodus_pairing_pending_help)
                else -> getString(R.string.nodus_pairing_ready_help)
            }
            pair.setText(when {
                state.pairingBusy -> R.string.nodus_pairing_connecting
                current.pairingPending -> R.string.nodus_pairing_continue
                else -> R.string.nodus_pair
            })

            val showSync=current.connectionId!=null || current.tokenSaved
            syncHeading.show(showSync)
            status.show(showSync)
            status.text=when {
                current.synchronized -> getString(R.string.nodus_synchronized)
                current.conflicted>0 -> getString(R.string.nodus_sync_conflicted,current.conflicted)
                current.blocked>0 -> getString(R.string.nodus_sync_blocked,current.blocked)
                current.unknown>0 -> getString(R.string.nodus_sync_unknown,current.unknown)
                current.pending>0 -> getString(R.string.nodus_sync_waiting,current.pending)
                else -> getString(R.string.nodus_sync_ready)
            }
            conflicts.show(current.conflicted>0)
            conflicts.text=getString(R.string.nodus_conflicts_count,current.conflicted)
            sync.show(current.active)
            activate.show(current.connectionId!=null && current.tokenSaved && !current.active)

            val showConnection=current.connectionId!=null || current.tokenSaved
            connectionHeading.show(showConnection)
            retained.show(showConnection)
            retained.setOnClickListener {
                val labels=buildList {
                    if(current.connectionId!=null)add(getString(R.string.nodus_retained))
                    if(current.active)add(getString(R.string.nodus_disconnect))
                    if(current.tokenSaved)add(getString(R.string.nodus_clear))
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.nodus_manage_connection)
                    .setItems(labels.toTypedArray()){_,index ->
                        var item=index
                        if(current.connectionId!=null) {
                            if(item==0){nodus.connections();return@setItems}
                            item--
                        }
                        if(current.active) {
                            if(item==0){confirm(R.string.nodus_disconnect_warning){nodus.disconnect(false)};return@setItems}
                            item--
                        }
                        if(current.tokenSaved && item==0)confirm(R.string.nodus_epoch_warning){nodus.disconnect(true)}
                    }.setNegativeButton(android.R.string.cancel,null).show()
            }

            message.text=if(state.busy)getString(R.string.nodus_busy) else state.message?.let(::getString).orEmpty()
            message.show(message.text.isNotEmpty())
            listOf(pair,cancelPairing,activate,sync,conflicts,retained).forEach{it.isEnabled = !state.busy}
            activate.isEnabled = !state.busy && current.connectionId!=null && current.tokenSaved && !current.active
            sync.isEnabled = !state.busy && current.active
            conflicts.isEnabled = !state.busy && current.conflicted>0
            if(state.showConnections) {
                nodus.consumeConnections()
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.nodus_retained)
                    .setItems(state.connections.map { it.origin }.toTypedArray()){_,index ->
                        confirm(R.string.nodus_review_warning){nodus.reviewConnection(state.connections[index].id)}
                    }.setNegativeButton(android.R.string.cancel,null).show()
            }
            if(state.showConflicts) {
                nodus.consumeConflictList()
                if(state.conflicts.isEmpty()) MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.nodus_no_conflicts).setPositiveButton(android.R.string.ok,null).show()
                else MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.nodus_conflicts)
                    .setItems(state.conflicts.map{ it.title }.toTypedArray()){_,index->
                        val conflict=state.conflicts[index]
                        val details=buildString {
                            append(conflict.description)
                            conflict.proposed?.let { append("\n\n");append(getString(R.string.nodus_conflict_proposed));append("\n");append(it) }
                            conflict.current?.let { append("\n\n");append(getString(R.string.nodus_conflict_current));append("\n");append(it) }
                        }
                        val dialog=MaterialAlertDialogBuilder(requireContext()).setTitle(conflict.title).setMessage(details).setNeutralButton(android.R.string.cancel,null)
                        if(conflict.state=="pending" && current.active) {
                            if(!conflict.historical) dialog.setPositiveButton(R.string.nodus_apply){_,_->confirm(R.string.nodus_apply_warning){nodus.resolve(conflict.id,true)}}
                            dialog.setNegativeButton(R.string.nodus_discard){_,_->confirm(R.string.nodus_discard_warning){nodus.resolve(conflict.id,false)}}
                        }
                        dialog.show()
                    }.show()
            }
        }
        nodus.load()
    }

    private fun setupLocalLocationListener() = binding.settingStorageLocation.setOnClickListener {
        locationListener.launch(storageLocation)
    }

    private fun setupNextcloudServerListener() = binding.settingNextcloudServer.setOnClickListener {
        NextcloudServerDialog.build(nextcloudUrl).show(childFragmentManager, null)
    }

    private fun setupNextcloudAccountListener() = binding.settingNextcloudAccount.setOnClickListener {
        NextcloudAccountDialog().show(childFragmentManager, null)
    }

    private fun setupSyncServiceListener() = binding.settingSyncProvider.setOnClickListener {
        showPreferenceDialog(R.string.preferences_cloud_service, appPreferences.cloudService) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupSyncModeListener() = binding.settingSyncMode.setOnClickListener {
        showPreferenceDialog(R.string.preferences_sync_when_on, appPreferences.syncMode) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupBackgroundSyncListener() = binding.settingBackgroundSync.setOnClickListener {
        showPreferenceDialog(R.string.preferences_background_sync, appPreferences.backgroundSync) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupNewNotesSyncableListener() = binding.settingNotesSyncableByDefault.setOnClickListener {
        showPreferenceDialog(
            R.string.preferences_new_notes_synchronizable,
            appPreferences.newNotesSyncable
        ) { selected ->
            model.setPreference(selected)
        }
    }
    private fun setupTrustCertificatesListener() = binding.settingTrustSelfSignedCertificate.setOnClickListener {
        showPreferenceDialog(
            R.string.preferences_trust_self_signed_certificate,
            appPreferences.trustSelfSignedCertificate
        ) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupClearNextcloudCredentialsListener() = binding.settingNextcloudClearCredentials.setOnClickListener {
        model.clearNextcloudCredentials()
    }
}

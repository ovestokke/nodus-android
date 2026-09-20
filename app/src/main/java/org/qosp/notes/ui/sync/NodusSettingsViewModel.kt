package org.qosp.notes.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.qosp.notes.R
import org.qosp.notes.data.sync.nodus.integration.NodusConnectionView
import org.qosp.notes.data.sync.nodus.integration.NodusConflictView
import org.qosp.notes.data.sync.nodus.integration.NodusController
import org.qosp.notes.data.sync.nodus.integration.NodusStatus

data class NodusSettingsState(val status:NodusStatus=NodusStatus(),val busy:Boolean=false,val pairingBusy:Boolean=false,val message:Int?=null,
    val credentialVersion:Int=0,val conflicts:List<NodusConflictView> = emptyList(),val showConflicts:Boolean=false,val connections:List<NodusConnectionView> = emptyList(),val showConnections:Boolean=false)

class NodusSettingsViewModel(private val controller:NodusController):ViewModel() {
    private val mutable=MutableStateFlow(NodusSettingsState())
    val state=mutable.asStateFlow()
    private fun action(success:Int?=null,clearToken:Boolean=false,pairing:Boolean=false,block:suspend ()->Unit) {
        if(mutable.value.busy)return
        mutable.value=mutable.value.copy(busy=true,pairingBusy=pairing,message=null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                mutable.value=mutable.value.copy(status=controller.status(),busy=false,pairingBusy=false,message=success,
                    credentialVersion=mutable.value.credentialVersion+if(clearToken)1 else 0)
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) {
                val status=try {controller.status()} catch(_:Exception) {NodusStatus(issues=listOf("status_unavailable"))}
                val message=when(error.message) {
                    "invalid_pairing" -> R.string.nodus_pairing_invalid
                    "pairing_key_reuse" -> R.string.nodus_pairing_reused
                    "pairing_throttled" -> R.string.nodus_pairing_throttled
                    "pairing_recovery_required" -> R.string.nodus_pairing_recovery
                    "pairing_retry_required","pairing_target_changed" -> R.string.nodus_pairing_retry_saved
                    else -> when(error) {
                        is UnknownHostException -> R.string.nodus_pairing_dns_failed
                        is SocketTimeoutException -> R.string.nodus_pairing_timeout
                        is SSLException -> R.string.nodus_pairing_tls_failed
                        is ConnectException,is IOException -> R.string.nodus_pairing_network_failed
                        is IllegalArgumentException -> R.string.nodus_pairing_input_invalid
                        else -> R.string.nodus_action_failed
                    }
                }
                mutable.value=mutable.value.copy(status=status,busy=false,pairingBusy=false,message=message)
            }
        }
    }
    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            try { mutable.value=mutable.value.copy(status=controller.status()) }
            catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { }
        }
    }
    fun connections()=action { mutable.value=mutable.value.copy(connections=controller.retainedConnections(),showConnections=true) }
    fun consumeConnections(){mutable.value=mutable.value.copy(showConnections=false)}
    fun reviewConnection(id:String)=action(clearToken=true){controller.reviewConnection(id)}
    fun pair(origin:String,code:String)=action(R.string.nodus_paired,true,true) {controller.pair(origin,code,true)}
    fun cancelPairing()=action(R.string.nodus_pairing_cancelled,true) {controller.cancelPairing()}
    fun activate()=action {controller.activate(true)}
    fun disconnect(clear:Boolean)=action {controller.disconnect(clear)}
    fun sync()=action {controller.syncIfSelected()}
    fun conflicts()=action {
        val entries=controller.refreshConflicts()
        mutable.value=mutable.value.copy(conflicts=entries,showConflicts=true)
    }
    fun consumeConflictList(){mutable.value=mutable.value.copy(showConflicts=false)}
    fun resolve(id:String,apply:Boolean)=action {controller.resolve(id,apply,true)}
}

package org.qosp.notes.data.sync.nodus.integration

import androidx.room.withTransaction
import androidx.work.ListenableWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.sync.core.BaseResult
import org.qosp.notes.data.sync.core.GenericError
import org.qosp.notes.data.sync.core.Success
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.engine.*
import org.qosp.notes.data.sync.nodus.storage.*
import org.qosp.notes.preferences.CloudService
import org.qosp.notes.preferences.PreferenceRepository

data class NodusStatus(val connectionId:String?=null,val origin:String="",val tokenSaved:Boolean=false,val pairingPending:Boolean=false,val pairingCancelable:Boolean=false,val active:Boolean=false,
    val pending:Int=0,val conflicted:Int=0,val blocked:Int=0,val unknown:Int=0,val issues:List<String> = emptyList()) {
    val synchronized:Boolean get()=active && pending==0 && conflicted==0 && blocked==0 && unknown==0
}
data class NodusConnectionView(val id:String,val origin:String,val realmId:String?)
data class NodusConflictView(
    val id:String,
    val state:String,
    val title:String,
    val description:String,
    val proposed:String?=null,
    val current:String?=null,
    val historical:Boolean=false
)

/** Account identity is intentionally NOT inferred from token/origin. Each fresh setup has
 * a random local owner scope. A future authenticated server identity can be added explicitly. */
class NodusController internal constructor(private val db:AppDatabase,private val bridge:NodusAppBridge,
    private val credentials:NodusConfigurationStore,private val bytes:NodusByteStore,private val service:NodusSyncService,
    private val http:NodusEngineTransport,private val pairing:NodusPairingTransport,private val preferences:PreferenceRepository,
    private val networkAllowed:suspend () -> Boolean = {true},private val schedule:() -> Unit = {}
) {
    private val dao get()=db.nodusDao
    private val lifecycle=Mutex()
    suspend fun selected()=preferences.get<CloudService>().first()==CloudService.NODUS
    suspend fun select(provider:CloudService) = lifecycle.withLock {
        if(provider!=CloudService.NODUS) bridge.deactivate()
        preferences.set(provider)
    }
    private suspend fun storageGate() {
        check(db.openHelper.writableDatabase.version==6)
        dao.integration();dao.allCaptures();dao.allAttachmentTransfers()
        bytes.checkAvailable()
    }
    private suspend fun testConfiguration(candidate:NodusConfiguration):String {
        val caps=http.get(candidate,"/api/v2/capabilities")
        require(caps.status==200)
        val capabilities=NodusJson.decode(V2Capabilities.serializer(),wireString(caps.body))
        // Also validate bounded feed readability before saving any credentials.
        val authenticated=http.get(candidate,"/api/v2/changes",mapOf("after" to "0","limit" to "1"))
        require(authenticated.status==200)
        NodusJson.decode(V2Changes.serializer(),wireString(authenticated.body))
        return capabilities.realmId
    }
    suspend fun testConnection(origin:String,token:String):String {
        storageGate()
        return testConfiguration(NodusConfiguration(NodusOrigin.parse(origin),token,newNodusId(),credentials.deviceId()))
    }
    suspend fun pair(origin:String,code:String,confirmed:Boolean) {
        require(confirmed)
        storageGate()
        val target=dao.integration()?.configuredConnectionId
        val pending=credentials.beginPairing(NodusOrigin.parse(origin),code,target)
        val result=pairing.redeem(NodusOrigin.parse(pending.origin),pending.input())
        if(result.status in setOf(401,409)) {
            credentials.clearPendingPairing(pending.requestId)
            error(if(result.status==401)"invalid_pairing" else "pairing_key_reuse")
        }
        if(result.status==429) error("pairing_throttled")
        if(result.status==503) error("pairing_recovery_required")
        require(result.status==200)
        val redeemed=NodusJson.decode(PairingRedeem.serializer(),wireString(result.body))
        val config=credentials.acceptPairing(pending,redeemed)
        val accepted=requireNotNull(credentials.pendingPairing())
        val realmId=testConfiguration(config)
        lifecycle.withLock {
            bridge.deactivate()
            val state=dao.integration()
            val alreadyConfigured=state?.configuredConnectionId?.let { configuredId ->
                val connection=dao.connection(configuredId)
                state.credentialEpoch==config.credentialEpoch && state.installationDeviceId==config.deviceId &&
                    state.origin==config.origin.toString() && connection?.realmId==realmId
            }==true
            if(!alreadyConfigured) {
                val retained=accepted.targetConnectionId?.let { dao.connection(it) }
                if(retained?.realmId==realmId) {
                    db.withTransaction {
                        dao.rotateCredentialEpoch(retained.connectionId,retained.credentialEpoch,config.credentialEpoch)
                        dao.updateConnectionOrigin(retained.connectionId,config.origin.toString())
                        val latest=requireNotNull(dao.integration())
                        require(latest.configuredConnectionId==retained.connectionId)
                        dao.putIntegration(latest.copy(activeConnectionId=null,installationDeviceId=config.deviceId,
                            credentialEpoch=config.credentialEpoch,origin=config.origin.toString(),retryAtMillis=null,
                            intentRow=0,operationRow=0,intentEnded=false,operationEnded=false,lastError=null))
                    }
                } else {
                    val connection=NodusConnection(newNodusId(),config.origin.toString(),newNodusId(),config.deviceId,config.credentialEpoch,realmId=realmId)
                    db.withTransaction {
                        dao.createConnection(connection)
                        dao.putIntegration(NodusIntegrationState(configuredConnectionId=connection.connectionId,
                            installationDeviceId=config.deviceId,credentialEpoch=config.credentialEpoch,origin=config.origin.toString(),
                            generation=state?.generation ?: 0))
                    }
                }
            }
        }
        credentials.completePairing(accepted.requestId)
    }
    suspend fun cancelPairing()=credentials.cancelPendingPairing()
    suspend fun configureFresh(origin:String,token:String,confirmed:Boolean) {
        require(confirmed)
        val realmId=testConnection(origin,token)
        lifecycle.withLock {
        bridge.deactivate()
        val config=credentials.configure(NodusOrigin.parse(origin),token)
        val connection=NodusConnection(newNodusId(),config.origin.toString(),newNodusId(),config.deviceId,config.credentialEpoch,realmId=realmId)
        db.withTransaction {
            dao.createConnection(connection)
            dao.putIntegration(NodusIntegrationState(configuredConnectionId=connection.connectionId,installationDeviceId=config.deviceId,credentialEpoch=config.credentialEpoch,origin=config.origin.toString(),generation=dao.integration()?.generation ?: 0))
        }
        }
    }
    private suspend fun requireRealm(id:String,realm:String) {
        if(dao.connection(id)?.realmId==realm) return
        lifecycle.withLock {
            db.withTransaction {
                if(dao.integration()?.activeConnectionId==id) bridge.deactivate()
                dao.integration()?.takeIf { it.configuredConnectionId==id }?.let {
                    dao.putIntegration(it.copy(credentialEpoch=null,lastError="realm_changed_fresh_connection_required"))
                }
            }
        }
        error("realm_changed_fresh_connection_required")
    }
    suspend fun replaceCredential(token:String,confirmed:Boolean,origin:String?=null) {
        require(confirmed)
        val state=requireNotNull(dao.integration())
        val id=requireNotNull(state.configuredConnectionId)
        val connection=requireNotNull(dao.connection(id))
        val targetOrigin=origin ?: connection.origin
        requireRealm(id,testConnection(targetOrigin,token))
        lifecycle.withLock {
        require(dao.integration()?.configuredConnectionId==id && dao.connection(id)?.credentialEpoch==connection.credentialEpoch)
        bridge.deactivate()
        val config=credentials.configure(NodusOrigin.parse(targetOrigin),token)
        db.withTransaction {
            dao.rotateCredentialEpoch(id,connection.credentialEpoch,config.credentialEpoch)
            dao.updateConnectionOrigin(id,config.origin.toString())
            val latest=requireNotNull(dao.integration())
            require(latest.configuredConnectionId==id)
            dao.putIntegration(latest.copy(activeConnectionId=null,installationDeviceId=config.deviceId,credentialEpoch=config.credentialEpoch,origin=config.origin.toString(),retryAtMillis=null,intentRow=0,operationRow=0,intentEnded=false,operationEnded=false))
        }
        }
    }
    suspend fun activate(confirmed:Boolean) {
        require(confirmed && selected())
        storageGate()
        val state=requireNotNull(dao.integration())
        val id=requireNotNull(state.configuredConnectionId)
        val config=requireNotNull(credentials.configuration())
        require(state.credentialEpoch==config.credentialEpoch && state.installationDeviceId==config.deviceId)
        requireRealm(id,testConnection(config.origin.toString(),config.bearerToken))
        lifecycle.withLock {
            val current=requireNotNull(credentials.configuration())
            require(current.credentialEpoch==config.credentialEpoch && selected())
        db.withTransaction {
            bridge.activate(id)
            if(dao.captures(id).isEmpty() && dao.mappings(id).isEmpty()) bridge.seedFreshConnection()
        }
        }
        schedule()
    }
    suspend fun disconnect(clear:Boolean=false) = lifecycle.withLock {
        bridge.deactivate()
        if(clear) credentials.clearCredential()
    }
    suspend fun retainedConnections():List<NodusConnectionView> = dao.connections().map { NodusConnectionView(it.connectionId,it.origin,it.realmId) }
    suspend fun reviewConnection(id:String) = lifecycle.withLock {
        bridge.deactivate()
        db.withTransaction {
            val connection=requireNotNull(dao.connection(id))
            dao.putIntegration(NodusIntegrationState(configuredConnectionId=id,origin=connection.origin,generation=dao.integration()?.generation ?: 0))
        }
    }

    suspend fun status():NodusStatus {
        val state=dao.integration()
        val config=runCatching { credentials.configuration() }.getOrNull()
        val retainedPairing=runCatching { credentials.pendingPairing() }.getOrNull()
        val pairingPending=retainedPairing!=null
        val pairingCancelable=retainedPairing!=null
        val id=state?.configuredConnectionId ?: return NodusStatus(pairingPending=pairingPending,pairingCancelable=pairingCancelable)
        val operations=dao.operations(id)
        val captures=dao.captures(id)
        val refs=dao.attachmentTransfers(id)
        val blobs=dao.blobs(id)
        val conflicts=dao.conflicts(id)
        val unprepared=dao.intents(id).count { intent -> operations.none{it.intentId==intent.intentId} }
        val alarmBacklog=db.reminderDao.alarmBacklog()
        val issues=((if(alarmBacklog>0)listOf("alarm_reconciliation_pending")else emptyList())+operations.mapNotNull { dao.latestEvidence(id,it.operationId)?.errorCode }+listOfNotNull(state.lastError)+captures.filter{it.state=="BLOCKED"}.mapNotNull{it.errorCode}+refs.mapNotNull{it.errorCode}+blobs.mapNotNull{it.errorCode}).distinct()
        val active=bridge.active()!=null
        val conflictIds=mutableSetOf<String>()
        var retainedDrafts=0
        for(op in operations) for(evidence in dao.operationConflicts(id,op.operationId)) {
            val conflictId=evidence.conflictId
            if(conflictId!=null && conflicts.none{it.conflictId==conflictId && it.state!="pending"}) conflictIds+=conflictId
            if(conflictId==null || dao.resolutionReceipt(id,"/api/v2/conflicts/$conflictId/apply")==null) retainedDrafts++
        }
        return NodusStatus(id,state.origin.orEmpty(),config!=null && config.credentialEpoch==state.credentialEpoch && config.deviceId==state.installationDeviceId && config.origin.toString()==state.origin,pairingPending,pairingCancelable,active,
            alarmBacklog+(if(dao.cursor(id,"v2",NodusStream.CHANGES)==null)1 else 0)+dao.projectionBacklog(id)+operations.count{it.state!=NodusOutboxState.RETIRED}+unprepared+captures.count{it.state in setOf("PENDING","COMPILING")}+refs.count{it.state!=NodusReadiness.AVAILABLE && it.state!=NodusReadiness.ERROR},
            (conflictIds+conflicts.filter{it.state=="pending"}.map{it.conflictId}).size,
            (if(state.lastError!=null)1 else 0)+captures.count{it.state=="BLOCKED"}+refs.count{it.state==NodusReadiness.ERROR}+blobs.count{it.state==NodusReadiness.ERROR}+retainedDrafts,
            operations.count{it.state==NodusOutboxState.UNKNOWN},issues+if(!active)listOf("inactive_or_reconnect_required") else emptyList())
    }
    private fun engine(id:String)=NodusCoordinator(db,id,{credentials.configuration()},http,bytes)
    suspend fun refreshConflicts():List<NodusConflictView> {
        val id=bridge.active()?.activeConnectionId ?: return conflictList()
        val result=engine(id).discoverConflicts(refresh=dao.cursor(id,"v2",NodusStream.CONFLICTS)?.until==null,maxPages=20)
        require(result.blocked.isEmpty())
        if(result.hasMore) schedule()
        return conflictList()
    }
    suspend fun conflictList():List<NodusConflictView> {
        val id=dao.integration()?.configuredConnectionId ?: return emptyList()
        return dao.conflicts(id).map { record ->
            if(isStoredHistoricalConflict(record.body)) NodusConflictView(record.conflictId,record.state,
                "Older sync conflict","This change was saved by an older Nodus version. Keep the current server version to close it.",historical=true)
            else conflictView(record.conflictId,record.state,NodusJson.decode(V2Conflict.serializer(),wireString(record.body)))
        }
    }
    private fun conflictView(id:String,state:String,conflict:V2Conflict):NodusConflictView {
        if(conflict.operation is V2Proposal.Historical) return NodusConflictView(id,state,
            "Older sync conflict","This change was saved by an older Nodus version. Keep the current server version to close it.",historical=true)
        val proposal=conflict.operation as V2Proposal.Native
        val note=conflict.snapshot as? V2Note
        val noteName=note?.title?.takeIf(String::isNotBlank)?.let { "“$it”" } ?: "an untitled note"
        val itemId=proposal.path.substringAfter("/items/","").substringBefore('/')
        val item=note?.items?.firstOrNull { it.id==itemId }
        fun <T> value(field:WireField<T>):T?=(field as? WireField.Present<T>)?.value
        return when(val input=proposal.input) {
            is V2ItemEdit -> {
                val text=value(input.text)
                val checked=value(input.checked)
                when {
                    text!=null -> NodusConflictView(id,state,"Checklist item in $noteName",
                        "The item was changed in two places. Choose which text to keep.",text,item?.text)
                    checked!=null -> NodusConflictView(id,state,"Checklist item in $noteName",
                        "The item status was changed in two places. Choose which status to keep.",
                        if(checked)"Completed" else "Not completed",item?.let { if(it.checked)"Completed" else "Not completed" })
                    else -> NodusConflictView(id,state,"Checklist change in $noteName","Choose which version to keep.")
                }
            }
            is V2Toggle -> NodusConflictView(id,state,"Checklist item in $noteName",
                "The item status was changed in two places. Choose which status to keep.",
                if(input.checked)"Completed" else "Not completed",item?.let { if(it.checked)"Completed" else "Not completed" })
            is V2EditNote -> {
                val title=value(input.title)
                val text=value(input.text)
                when {
                    title!=null -> NodusConflictView(id,state,"Note title in $noteName",
                        "The title was changed in two places. Choose which title to keep.",title,note?.title)
                    text!=null -> NodusConflictView(id,state,"Note text in $noteName",
                        "The note was changed in two places. Choose which text to keep.",text,note?.text)
                    else -> NodusConflictView(id,state,"Note settings in $noteName","Choose which version to keep.")
                }
            }
            is V2OrganizationEdit -> {
                val currentName=when(val snapshot=conflict.snapshot) { is V2Tag -> snapshot.name; is V2Notebook -> snapshot.name; else -> null }
                NodusConflictView(id,state,"Name changed in two places","Choose which name to keep.",input.name,currentName)
            }
            else -> NodusConflictView(id,state,"Sync conflict in $noteName",
                "A saved change conflicts with a newer server version. Choose which version to keep.")
        }
    }
    suspend fun resolve(conflictId:String,apply:Boolean,confirmed:Boolean) {
        require(confirmed)
        val id=requireNotNull(bridge.active()?.activeConnectionId)
        NodusConnectionLocks.get(id).withLock {
            val record=requireNotNull(dao.conflict(id,conflictId))
            val historical=isStoredHistoricalConflict(record.body)
            require(record.state=="pending")
            if(historical && apply) error("historical_operation")
            val pending=dao.operations(id).filter { it.path.startsWith("/api/v2/conflicts/$conflictId/") && it.state!=NodusOutboxState.RETIRED }
            val resolutionSuffix=if(apply) "/apply" else "/discard"
            if(pending.any { it.path.endsWith(resolutionSuffix) }) return@withLock
            if(pending.isNotEmpty()) {
                require(historical && !apply && pending.all { it.path.endsWith("/apply") }) { "resolution_already_pending" }
                pending.forEach { dao.quarantineHistoricalApply(id,it.operationId,newNodusId()) }
            }
            val config=requireNotNull(credentials.configuration())
            requireRealm(id,testConnection(config.origin.toString(),config.bearerToken))
            if(historical) {
                val localKey="historical-conflict:$conflictId"
                val mapped=dao.mappingByLocalKey(id,NodusResourceType.BLOB,localKey) ?: db.withTransaction {
                    dao.mappingByLocalKey(id,NodusResourceType.BLOB,localKey) ?: NodusMapping(
                        id,newNodusId(),NodusResourceType.BLOB,"",newNodusId(),localKey,null
                    ).also { dao.addMappings(id,listOf(it)) }
                }
                require(bridge.active()?.credentialEpoch==config.credentialEpoch)
                engine(id).discardConflict(mapped.mappingId,conflictId,historical=true)
                return@withLock
            }
            val conflict=NodusJson.decode(V2Conflict.serializer(),wireString(record.body))
            require(conflict.state==ConflictState.PENDING)
            val operation=conflict.operation as? V2Proposal.Native ?: error("historical_operation")
            val parts=operation.path.split('/')
            val type=when(parts[3]) { "notes" -> NodusResourceType.NOTE;"tags" -> NodusResourceType.TAG;"notebooks" -> NodusResourceType.NOTEBOOK;"blobs" -> NodusResourceType.BLOB;else -> error("unsupported_target") }
            val root=dao.mappingByWire(id,type,parts[4])
            val v2NoteCreate=operation.method=="PUT" && type==NodusResourceType.NOTE && parts.size==5
            suspend fun retainTarget()=root ?: db.withTransaction {
                dao.mappingByWire(id,type,parts[4]) ?: NodusMapping(id,newNodusId(),type,"",parts[4],newNodusId(),null).also {
                    dao.addMappings(id,listOf(it))
                }
            }
            if(apply) {
                val response=http.get(config,"/api/v2/${parts[3]}/${parts[4]}")
                val revision=if(v2NoteCreate && response.status==404) "0" else {
                    require(response.status==200)
                    when(type) {
                        NodusResourceType.NOTE -> NodusJson.decode(V2Note.serializer(),wireString(response.body)).let { note ->
                            require(note.id==parts[4])
                            if(parts.getOrNull(5)=="items" && parts.getOrNull(6)!=null && operation.method in setOf("PATCH","DELETE"))
                                note.items.single { it.id==parts[6] }.revision
                            else note.revision
                        }
                        NodusResourceType.TAG -> NodusJson.decode(V2Tag.serializer(),wireString(response.body)).also{require(it.id==parts[4])}.revision
                        NodusResourceType.NOTEBOOK -> NodusJson.decode(V2Notebook.serializer(),wireString(response.body)).also{require(it.id==parts[4])}.revision
                        else -> NodusJson.decode(V2Blob.serializer(),wireString(response.body)).also{require(it.id==parts[4])}.revision
                    }
                }
                val mapped=if(v2NoteCreate) retainTarget() else requireNotNull(root) { "unmapped_conflict_requires_review" }
                require(bridge.active()?.credentialEpoch==config.credentialEpoch)
                engine(id).applyConflict(mapped.mappingId,conflictId,revision)
            } else {
                engine(id).discardConflict(retainTarget().mappingId,conflictId)
            }
        }
        schedule()
    }
    internal suspend fun execute():NodusExecution {
        if(!selected() || bridge.active()==null) return NodusExecution(false,listOf("inactive"))
        if(!networkAllowed()) {
            db.withTransaction { dao.integration()?.let { dao.putIntegration(it.copy(lastError="network_policy")) } }
            return NodusExecution(true,listOf("network_policy"))
        }
        val result=service.run(includeConflicts=true)
        return result
    }
    suspend fun syncIfSelected():BaseResult? {
        if(!selected()) return null
        val result=execute()
        if(result.pending || result.retryAtMillis!=null) schedule()
        val state=status()
        return if(!result.pending && result.blocked.isEmpty() && state.synchronized) Success else GenericError("Nodus: pending, blocked or inactive; see synchronization settings")
    }
    suspend fun workerIfSelected():ListenableWorker.Result? = if(selected()) execute().workerResult() else null
}

package org.qosp.notes.data.sync.nodus.integration

import androidx.room.withTransaction
import androidx.work.ListenableWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.engine.*
import org.qosp.notes.data.sync.nodus.storage.*
import java.io.InputStream

internal fun interface NodusReminderReconciliation { suspend fun reconcile() }

internal data class NodusExecution(val pending: Boolean, val blocked: List<String> = emptyList(), val retryAtMillis: Long? = null) {
    fun workerResult(): ListenableWorker.Result = if(pending || retryAtMillis!=null) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
}

/** Serialized manual/background entry point behind explicit Nodus selection and activation. */
internal class NodusSyncService(private val db: AppDatabase, private val bridge: NodusAppBridge,
    private val configuration: () -> NodusConfiguration?, private val bytes: NodusByteStore,
    private val json: NodusEngineTransport, private val binary: NodusBinaryTransport,
    private val openSource: (String) -> InputStream, private val reconcileReminders:NodusReminderReconciliation = NodusReminderReconciliation {}, private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun run(maxOperations: Int = 20, maxExamined: Int = 100, includeConflicts: Boolean = false): NodusExecution = locks.getOrPut(db.openHelper.databaseName ?: "memory") { Mutex() }.withLock {
        try { reconcileReminders.reconcile() }
        catch(cancel:CancellationException) { throw cancel }
        catch(_:Exception) {
            db.withTransaction { db.nodusDao.integration()?.let { db.nodusDao.putIntegration(it.copy(lastError="alarm_reconciliation_failed")) } }
            return@withLock NodusExecution(true,listOf("alarm_reconciliation_failed"))
        }
        var state=bridge.active() ?: return@withLock NodusExecution(false,listOf("inactive"))
        val connectionId=requireNotNull(state.activeConnectionId)
        state.retryAtMillis?.takeIf { now()<it }?.let { return@withLock NodusExecution(true,retryAtMillis=it) }
        suspend fun authorize(config: NodusConfiguration) {
            check(!db.inTransaction()) { "Network inside local transaction" }
            val active=bridge.active()
            check(active?.activeConnectionId==connectionId && active.credentialEpoch==config.credentialEpoch && active.installationDeviceId==config.deviceId) { "Inactive connection" }
        }
        suspend fun authorizeWrite(operation:NodusOutbox) {
            if(operation.path.startsWith("/api/v2/conflicts/") && operation.path.endsWith("/discard")) return
            val mapping=requireNotNull(db.nodusDao.mapping(connectionId,operation.mappingId))
            if(mapping.resourceType==NodusResourceType.BLOB) check(db.nodusDao.hasPublicBlobReference(connectionId,mapping.wireId)) { "private_blob_source" }
            val target=resourceTarget(mapping)
            if(target.first==NodusResourceType.NOTE) {
                val root=requireNotNull(db.nodusDao.mappingByWire(connectionId,NodusResourceType.NOTE,target.second))
                check(!root.localRowDetached && root.localRowId?.let { db.noteDao.getById(it).first()?.isLocalOnly }!=true) { "private_or_detached_note" }
            }
        }
        val guardedJson=object:NodusEngineTransport {
            override suspend fun get(configuration:NodusConfiguration,path:String,query:Map<String,String>):NodusHttpResult { authorize(configuration);return json.get(configuration,path,query).also { authorize(configuration) } }
            override suspend fun send(configuration:NodusConfiguration,operation:NodusOutbox):NodusHttpResult { authorize(configuration);authorizeWrite(operation);return json.send(configuration,operation) }
        }
        val guardedBinary=object:NodusBinaryTransport {
            override suspend fun upload(configuration:NodusConfiguration,operation:NodusOutbox,source:java.io.File):NodusHttpResult { authorize(configuration);authorizeWrite(operation);return binary.upload(configuration,operation,source) }
            override suspend fun download(configuration:NodusConfiguration,blobId:String):NodusDownload { authorize(configuration);return binary.download(configuration,blobId) }
        }
        val transfers=NodusAttachments(db,connectionId,configuration,bytes,guardedJson,guardedBinary,now)
        val engine=NodusCoordinator(db,connectionId,configuration,guardedJson,bytes,projectionAllowed={bridge.active()?.activeConnectionId==connectionId},now=now)
        val blocks=mutableListOf<String>()
        suspend fun persist(next: NodusIntegrationState) = db.withTransaction {
            val current=db.nodusDao.integration()
            if(current != null && current.activeConnectionId==state.activeConnectionId && current.credentialEpoch==state.credentialEpoch && current.installationDeviceId==state.installationDeviceId) {
                // Local capture may have advanced generation during network I/O.
                db.nodusDao.putIntegration(next.copy(generation=current.generation))
                state=next.copy(generation=current.generation)
            }
        }
        try {
            NodusCaptureCompiler(db,bridge,transfers).compile()
            val candidates=db.nodusDao.attachmentTransfers(connectionId).filter { "${it.noteId}:${it.attachmentId}" > state.attachmentAfter }.take(20)
            for(ref in candidates) {
                val blob=db.nodusDao.blob(connectionId,ref.blobId)
                if(blob?.size==null && ref.state!=NodusReadiness.ERROR && ref.draftBody!=null) {
                    val map=requireNotNull(db.nodusDao.mappingByWire(connectionId,NodusResourceType.ATTACHMENT,ref.attachmentId,ref.noteId))
                    val root=requireNotNull(db.nodusDao.mappingByWire(connectionId,NodusResourceType.NOTE,ref.noteId))
                    val draft=NodusJson.decode(V2AttachmentInput.serializer(),wireString(ref.draftBody))
                    val original=Attachment(Attachment.Type.valueOf(draft.kind.name),requireNotNull(ref.sourceUri),
                        (draft.description as? WireField.Present)?.value ?: "",(draft.fileName as? WireField.Present)?.value ?: "",map.localKey)
                    transfers.capture(root.mappingId,map.localKey,original,"application/octet-stream") { openSource(original.path) }
                }
                if(ref.draftBody!=null && ref.state!=NodusReadiness.AVAILABLE) {
                    val result=transfers.advance(ref.noteId,ref.attachmentId)
                    result.blocked?.let { blocks+="${ref.attachmentId}:$it" }
                    result.retryAtMillis?.let { deadline -> persist(state.copy(retryAtMillis=deadline)); return@withLock NodusExecution(true,blocks,deadline) }
                } else if(ref.draftBody==null && ref.state!=NodusReadiness.AVAILABLE) {
                    val result=transfers.download(ref.blobId)
                    result.blocked?.let { blocks+="${ref.attachmentId}:$it" }
                    result.retryAtMillis?.let { deadline -> persist(state.copy(retryAtMillis=deadline)); return@withLock NodusExecution(true,blocks,deadline) }
                }
                persist(state.copy(attachmentAfter="${ref.noteId}:${ref.attachmentId}",retryAtMillis=null))
            }
            if(candidates.size<20) persist(state.copy(attachmentAfter=""))
            val drained=engine.drain(maxOperations,maxExamined,NodusQueueContinuation(state.intentRow,state.operationRow,state.retryAtMillis,state.intentEnded,state.operationEnded))
            drained.queueContinuation?.let { continuation -> persist(state.copy(intentRow=continuation.intentRowId,operationRow=continuation.operationRowId,intentEnded=continuation.intentStreamEnded,operationEnded=continuation.operationStreamEnded,retryAtMillis=continuation.retryAtMillis)) }
            blocks+=drained.blocked
            if(drained.retryAtMillis!=null) return@withLock NodusExecution(true,blocks,drained.retryAtMillis)
            val beforeCursor=db.nodusDao.cursor(connectionId,"v2",NodusStream.CHANGES)?.cursor ?: "0"
            val beforeTransfers=db.nodusDao.attachmentTransfers(connectionId).map { it.noteId to it.attachmentId }.toSet()
            val pull=engine.pullChanges(projectionAfter=state.projectionAfter)
            reconcileReminders.reconcile()
            val feedAdvanced=(db.nodusDao.cursor(connectionId,"v2",NodusStream.CHANGES)?.cursor ?: "0") != beforeCursor
            val newTransfers=db.nodusDao.attachmentTransfers(connectionId).any { (it.noteId to it.attachmentId) !in beforeTransfers }
            val resumedByPull=feedAdvanced || newTransfers || (pull.projections?.applied ?: 0)>0
            blocks+=pull.blocked
            pull.projections?.let { persist(state.copy(projectionAfter=if(it.examined<100) "0" else it.nextRevision)) }
            pull.retryAtMillis?.let { persist(state.copy(retryAtMillis=it)) }
            val conflicts=if(includeConflicts) engine.discoverConflicts(maxPages=1) else null
            conflicts?.let { blocks+=it.blocked;it.retryAtMillis?.let { deadline -> persist(state.copy(retryAtMillis=deadline)) } }
            blocks+=db.nodusDao.captures(connectionId).filter { it.state=="BLOCKED" }.map { "capture:${it.id}:${it.errorCode}" }
            persist(state.copy(lastError=blocks.distinct().take(100).joinToString("\n").takeIf { it.isNotEmpty() }))
            if(blocks.any { it=="realm_mismatch" }) db.withTransaction {
                if(db.nodusDao.integration()?.activeConnectionId==connectionId) {
                    bridge.deactivate()
                    db.nodusDao.integration()?.let { db.nodusDao.putIntegration(it.copy(credentialEpoch=null,lastError="realm_changed_fresh_connection_required")) }
                }
            }
            NodusExecution(drained.completedRequests>0 || conflicts?.hasMore==true || resumedByPull || (drained.hasMore && !drained.queueScanComplete) || pull.hasMore || pull.projections?.examined==100 || candidates.size==20 || db.nodusDao.pendingCaptures(connectionId,1).isNotEmpty(),blocks,conflicts?.retryAtMillis ?: pull.retryAtMillis)
        } catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) {
            val deadline=now()+60_000
            persist(state.copy(retryAtMillis=deadline,lastError="execution_interrupted"))
            NodusExecution(true,blocks+"execution_interrupted",deadline)
        }
    }
    private companion object { val locks=java.util.concurrent.ConcurrentHashMap<String,Mutex>() }
}

package org.qosp.notes.data.sync.nodus.integration

import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.*
import org.qosp.notes.data.model.Reminder
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.engine.*
import org.qosp.notes.data.sync.nodus.storage.*
import org.qosp.notes.preferences.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.nio.file.Files
import javax.crypto.KeyGenerator

internal class MemoryPreferences:DataStore<Preferences> {
    private val mutex=Mutex()
    private val value=MutableStateFlow(emptyPreferences())
    override val data:Flow<Preferences> = value
    override suspend fun updateData(transform:suspend(Preferences)->Preferences):Preferences=mutex.withLock { transform(value.value).also{value.value=it} }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class NodusControllerTest {
    private lateinit var db:AppDatabase
    private lateinit var directory:File
    private lateinit var credentials:NodusConfigurationStore
    private lateinit var preferences:PreferenceRepository
    private lateinit var bridge:NodusAppBridge
    private lateinit var bytes:NodusByteStore
    private lateinit var controller:NodusController
    private var clock=System.currentTimeMillis()
    private val changeQueries=mutableListOf<Map<String,String>>()
    private var scheduled=0
    private var allowed=true
    private var caps=NodusFixtures.capabilities
    private var authenticated=200
    private var responseStatus=200
    private var noteResponseStatus=200
    private val sent=mutableListOf<NodusOutbox>()
    private val gets=mutableListOf<String>()
    private val pairingInputs=mutableListOf<PairingRedeemInput>()
    private var pairingStatus=200
    private val server=RoundTripServer()
    private var roundTrip=false
    private var conflict:V2Conflict?=null
    private var freshNote:V2Note?=null
    private val http=object:NodusEngineTransport {
        override suspend fun get(configuration:NodusConfiguration,path:String,query:Map<String,String>):NodusHttpResult {
            assertFalse(db.inTransaction());gets+=path
            if(path=="/api/v2/changes")changeQueries+=query.toMap()
            if(roundTrip)return server.get(configuration,path,query)
            return when(path) {
                "/api/v2/capabilities" -> NodusHttpResult(200,caps.toByteArray())
                "/api/v2/changes" -> NodusHttpResult(authenticated,NodusJson.encode(V2Changes.serializer(),V2Changes(emptyList(),"0","0",false)).toByteArray())
                "/api/v2/conflicts" -> NodusHttpResult(200,NodusJson.encode(V2Conflicts.serializer(),V2Conflicts(listOfNotNull(conflict),if(conflict==null)"0" else "50",if(conflict==null)"0" else "50",false)).toByteArray())
                else -> if(noteResponseStatus==200) NodusHttpResult(200,NodusJson.encode(V2Note.serializer(),requireNotNull(freshNote)).toByteArray())
                    else NodusHttpResult(noteResponseStatus,"""{"error":"not found"}""".toByteArray())
            }
        }
        override suspend fun send(configuration:NodusConfiguration,operation:NodusOutbox):NodusHttpResult {
            assertFalse(db.inTransaction());sent+=operation
            return if(roundTrip)server.send(configuration,operation) else if(responseStatus==200)FakeNodusTransport.receipt(operation.path.split('/')[3].dropLast(1),operation.path.split('/')[4],"1") else NodusHttpResult(responseStatus,"""{"error":"unavailable"}""".toByteArray(),"1")
        }
    }
    private val pairing=object:NodusPairingTransport {
        override suspend fun redeem(origin:NodusOrigin,input:PairingRedeemInput):NodusHttpResult {
            pairingInputs+=input
            return if(pairingStatus==200) NodusHttpResult(200,NodusJson.encode(PairingRedeem.serializer(),PairingRedeem("paired-token.${input.credentialSecret}","paired-token")).toByteArray())
            else NodusHttpResult(pairingStatus,"""{"error":"unavailable"}""".toByteArray())
        }
    }
    private val binary=object:NodusBinaryTransport {
        override suspend fun upload(configuration:NodusConfiguration,operation:NodusOutbox,source:File):NodusHttpResult=server.upload(configuration,operation,source)
        override suspend fun download(configuration:NodusConfiguration,blobId:String):NodusDownload=error("not needed")
    }
    private val dao get()=db.nodusDao
    @Before fun setup():Unit=runBlocking {
        directory=Files.createTempDirectory("nodus-controller").toFile()
        db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),AppDatabase::class.java).allowMainThreadQueries().build()
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        credentials=NodusConfigurationStore(AtomicFile(File(directory,"configuration"))){key}
        preferences=PreferenceRepository(MemoryPreferences(),mockk(relaxed=true))
        preferences.set(CloudService.NODUS)
        bridge=NodusAppBridge(db,selected={preferences.get<CloudService>().first()==CloudService.NODUS}){credentials.configuration()}
        bytes=NodusByteStore(File(directory,"bytes"),publish={from,to->Files.createLink(to.toPath(),from.toPath());Unit},syncDirectory={})
        val service=NodusSyncService(db,bridge,{credentials.configuration()},bytes,http,binary,{assertFalse(db.inTransaction());ByteArrayInputStream(byteArrayOf(1,2,3))},now={clock})
        controller=NodusController(db,bridge,credentials,bytes,service,http,pairing,preferences,{allowed},{scheduled++})
    }
    @After fun close(){db.close();directory.deleteRecursively()}
    private suspend fun configure()=controller.configureFresh("https://notes.example","synthetic-token",true)

    @Test fun pairingPersistsExactUnknownRedemptionAndRetriesWithoutReplacingSecret():Unit=runBlocking {
        pairingStatus=503
        assertThrows(Exception::class.java){runBlocking{controller.pair("https://notes.example","23456-789AB",true)}}
        val pending=requireNotNull(credentials.pendingPairing())
        assertTrue(controller.status().pairingPending)
        pairingStatus=200
        controller.pair("https://notes.example","",true)
        assertEquals(2,pairingInputs.size)
        assertEquals(pairingInputs[0],pairingInputs[1])
        assertEquals(pending.requestId,pairingInputs[1].requestId)
        assertEquals(pending.credentialSecret,pairingInputs[1].credentialSecret)
        assertNull(credentials.pendingPairing())
        assertEquals("paired-token.${pending.credentialSecret}",credentials.configuration()!!.bearerToken)
        val status=controller.status()
        assertTrue(status.tokenSaved);assertFalse(status.pairingPending);assertFalse(status.active)
        assertNotNull(status.connectionId)
    }

    @Test fun selectionAndConfigurationNeverActivateWithoutSeparateConfirmation():Unit=runBlocking {
        assertNull(bridge.active())
        configure()
        val status=controller.status()
        assertNotNull(status.connectionId);assertTrue(status.tokenSaved);assertFalse(status.active);assertFalse(status.synchronized)
        assertTrue(dao.captures(status.connectionId!!).isEmpty())
        assertThrows(Exception::class.java){runBlocking{controller.activate(false)}}
        controller.activate(true)
        assertTrue(controller.status().active);assertTrue(controller.status().pending>0)
        assertEquals(1,scheduled)
        controller.execute()
        assertTrue(controller.status().synchronized)
        controller.select(CloudService.NEXTCLOUD)
        assertNull(bridge.active());assertNull(controller.workerIfSelected());assertNull(controller.syncIfSelected())
        controller.select(CloudService.NODUS);assertNull(bridge.active())
    }

    @Test fun invalidOriginAuthCapabilitiesAndByteStoreFailClosed():Unit=runBlocking {
        for(origin in listOf("http://notes.example","https://notes.example/api/v2","https://user@notes.example")) assertThrows(Exception::class.java){runBlocking{controller.configureFresh(origin,"secret",true)}}
        authenticated=401
        assertThrows(Exception::class.java){runBlocking{configure()}}
        authenticated=200;caps="{}"
        assertThrows(Exception::class.java){runBlocking{configure()}}
        assertNull(dao.integration());assertTrue(sent.isEmpty())
        val unusable=NodusByteStore(File(directory,"bad-bytes"),publish={_,_->throw IOException("denied")},syncDirectory={})
        assertThrows(Exception::class.java){unusable.checkAvailable()}
        assertFalse(controller.status().active)
    }

    @Test fun freshConnectionsNeverReuseOwnerScopeAndDisconnectPreservesHistory():Unit=runBlocking {
        configure();controller.activate(true)
        val first=controller.status().connectionId!!
        val owner=dao.connection(first)!!.ownerKey
        controller.disconnect()
        assertNull(bridge.active());assertTrue(controller.status().tokenSaved)
        configure()
        val second=controller.status().connectionId!!
        assertNotEquals(first,second);assertNotEquals(owner,dao.connection(second)!!.ownerKey)
        assertTrue(dao.captures(first).isNotEmpty())
        controller.disconnect(true)
        assertNull(credentials.configuration());assertFalse(controller.status().synchronized)
        assertNotNull(dao.connection(first));assertNotNull(dao.connection(second))
    }

    @Test fun credentialReplacementRequiresConfirmationAndNeverReplaysOldUnknownWork():Unit=runBlocking {
        val localId=db.noteDao.insert(Note(title="unknown").toEntity())
        configure();controller.activate(true)
        responseStatus=503
        controller.execute()
        val original=sent.single()
        assertEquals(NodusOutboxState.UNKNOWN,dao.outbox(original.connectionId,original.operationId)!!.state)
        assertThrows(Exception::class.java){runBlocking{controller.replaceCredential("new-secret",false)}}
        controller.disconnect()
        bridge.mutate { db.noteDao.update(db.noteDao.getById(localId).first()!!.copy(title="inactive edit").toEntity()) }
        controller.replaceCredential("new-secret",true)
        assertFalse(controller.status().active)
        controller.activate(true);responseStatus=200
        val capture=dao.latestCapture(original.connectionId)!!
        assertEquals(credentials.configuration()!!.credentialEpoch,capture.credentialEpoch)
        assertTrue(capture.afterBody.contains("inactive edit"))
        controller.execute()
        assertEquals(1,sent.size)
        val retained=dao.outbox(original.connectionId,original.operationId)!!
        assertEquals(original.requestId,retained.requestId);assertEquals(original.credentialEpoch,retained.credentialEpoch)
        assertTrue(controller.status().unknown>0);assertFalse(controller.status().synchronized)
    }

    @Test fun restoredNoBackupIdentityCannotActivateRetainedConnection():Unit=runBlocking {
        configure();controller.activate(true)
        val id=controller.status().connectionId!!
        File(directory,"configuration").delete()
        assertFalse(controller.status().active)
        assertThrows(Exception::class.java){runBlocking{controller.activate(true)}}
        assertNotNull(dao.connection(id));assertTrue(dao.captures(id).isNotEmpty())
    }

    @Test fun unsupportedAttachmentsAndNetworkPolicyNeverReportSynchronized():Unit=runBlocking {
        db.noteDao.insert(Note(title="oversized metadata",attachments=listOf(Attachment(fileName="x".repeat(1025),path="content://original"))).toEntity())
        configure();controller.activate(true);controller.execute()
        assertTrue(controller.status().blocked>0);assertFalse(controller.status().synchronized)
        assertEquals("content://original",db.noteDao.getAll(SortMethod.MODIFIED_DESC).first().single().attachments.single().path)
        allowed=false
        assertEquals(androidx.work.ListenableWorker.Result.retry(),controller.workerIfSelected())
        assertTrue(controller.status().issues.contains("network_policy"))
    }

    @Test fun conflictsShowExactEvidenceAndApplyUsesFreshRevisionWithoutDeletingLocalDraft():Unit=runBlocking {
        val localId=db.noteDao.insert(Note(title="retained local draft").toEntity())
        configure();controller.activate(true)
        val id=controller.status().connectionId!!
        val config=credentials.configuration()!!
        val transfers=NodusAttachments(db,id,{config},bytes,http,binary)
        NodusCaptureCompiler(db,bridge,transfers).compile()
        val root=dao.mappingByLocalRow(id,NodusResourceType.NOTE,localId)!!
        val input=V2EditNote(config.deviceId,"original-request","1",title=WireField.Present("proposal"))
        val exact=NodusJson.encode(V2EditNote.serializer(),input)
        val proposal=V2Proposal("v2","PATCH","/api/v2/notes/${root.wireId}",input,exact)
        val old=NodusJson.decode(V2Note.serializer(),NodusFixtures.note).copy(id=root.wireId,revision="20")
        freshNote=old.copy(revision="41")
        conflict=V2Conflict("clash",ConflictState.PENDING,"","revision_mismatch",proposal,old)
        val evidence=controller.refreshConflicts().single()
        assertTrue(evidence.evidence.contains("original-request"))
        assertTrue(controller.status().conflicted>0)
        assertThrows(Exception::class.java){runBlocking{controller.resolve("clash",true,false)}}
        controller.resolve("clash",true,true)
        val apply=dao.operations(id).single{it.path.endsWith("/apply")}
        assertEquals("41",NodusJson.decode(V2Apply.serializer(),wireString(apply.body!!)).expectedRevision)
        controller.resolve("clash",true,true)
        assertEquals(1,dao.operations(id).count{it.path.endsWith("/apply")})
        assertThrows(Exception::class.java){runBlocking{controller.resolve("clash",false,true)}}
        conflict=V2Conflict("discard-clash",ConflictState.PENDING,"","revision_mismatch",proposal,old)
        controller.refreshConflicts();controller.resolve("discard-clash",false,true)
        assertTrue(dao.operations(id).any{it.path.endsWith("/discard")})
        assertEquals("retained local draft",db.noteDao.getById(localId).first()!!.title)
        assertEquals(evidence.evidence,controller.conflictList().first{it.id=="clash"}.evidence)
        assertTrue(dao.captures(id).isNotEmpty())
    }

    @Test fun itemConflictApplyUsesFreshItemRevisionInsteadOfAggregateRevision():Unit=runBlocking {
        val localId=db.noteDao.insert(Note(title="item conflict",isList=true,taskList=listOf(NoteTask(0,"task",false))).toEntity())
        configure();controller.activate(true)
        val id=controller.status().connectionId!!
        val config=credentials.configuration()!!
        NodusCaptureCompiler(db,bridge,NodusAttachments(db,id,{config},bytes,http,binary)).compile()
        val root=dao.mappingByLocalRow(id,NodusResourceType.NOTE,localId)!!
        val item=dao.childMappings(id,root.wireId).single { it.resourceType==NodusResourceType.ITEM }
        val input=V2Toggle(config.deviceId,"original-item-request","5",true)
        val proposal=V2Proposal("v2","PATCH","/api/v2/notes/${root.wireId}/items/${item.wireId}/checked",input,NodusJson.encode(V2Toggle.serializer(),input))
        val current=NodusJson.decode(V2Note.serializer(),NodusFixtures.note).copy(id=root.wireId,kind=NoteKind.CHECKLIST,revision="41",state=NoteState.LIVE,
            trashedAt=null,sourceTrashedAt=null,items=listOf(Item(item.wireId,"task",false,0,"17",false)),attachments=emptyList(),reminders=emptyList(),primaryReminderId=null)
        freshNote=current
        conflict=V2Conflict("item-clash",ConflictState.PENDING,"","revision_mismatch",proposal,current.copy(revision="40",items=current.items.map { it.copy(revision="5") }))
        controller.refreshConflicts();controller.resolve("item-clash",true,true)
        val apply=dao.operations(id).single { it.path=="/api/v2/conflicts/item-clash/apply" }
        assertEquals("17",NodusJson.decode(V2Apply.serializer(),wireString(apply.body!!)).expectedRevision)
    }

    @Test fun unmappedConflictCanBeDiscardedWithoutReadingTarget():Unit=runBlocking {
        configure();controller.activate(true)
        val id=controller.status().connectionId!!
        val config=credentials.configuration()!!
        val input=V2CreateNote(config.deviceId,"foreign-request",NoteKind.TEXT)
        val proposal=V2Proposal("v2","PUT","/api/v2/notes/foreign-note",input,NodusJson.encode(V2CreateNote.serializer(),input))
        conflict=V2Conflict("foreign-clash",ConflictState.PENDING,"","deleted_dependency",proposal,null)
        controller.refreshConflicts()
        assertNull(dao.mappingByWire(id,NodusResourceType.NOTE,"foreign-note"))
        controller.resolve("foreign-clash",false,true)
        val discard=dao.operations(id).single { it.path=="/api/v2/conflicts/foreign-clash/discard" }
        assertEquals(config.deviceId,NodusJson.decode(V2Discard.serializer(),wireString(discard.body!!)).deviceId)
        assertNotNull(dao.mappingByWire(id,NodusResourceType.NOTE,"foreign-note"))
        assertFalse(gets.contains("/api/v2/notes/foreign-note"))
    }

    @Test fun nullSnapshotV2NoteCreateCanApplyWithAbsentTargetSentinelAfterDependencyRepair():Unit=runBlocking {
        configure();controller.activate(true)
        val id=controller.status().connectionId!!
        val config=credentials.configuration()!!
        val input=V2CreateNote(config.deviceId,"dependency-repair",NoteKind.TEXT,tagIds=WireField.Present(listOf("repaired-tag")))
        val proposal=V2Proposal("v2","PUT","/api/v2/notes/repaired-note",input,NodusJson.encode(V2CreateNote.serializer(),input))
        conflict=V2Conflict("repairable-create",ConflictState.PENDING,"","dependency_deleted",proposal,null)
        noteResponseStatus=404
        controller.refreshConflicts();controller.resolve("repairable-create",true,true)
        val apply=dao.operations(id).single { it.path=="/api/v2/conflicts/repairable-create/apply" }
        assertEquals("0",NodusJson.decode(V2Apply.serializer(),wireString(apply.body!!)).expectedRevision)
        assertNotNull(dao.mappingByWire(id,NodusResourceType.NOTE,"repaired-note"))
        assertTrue(gets.contains("/api/v2/notes/repaired-note"))
    }

    @Test fun failedPassPersistsVisibleStatusInsteadOfClaimingLastSuccessfulState():Unit=runBlocking {
        configure();controller.activate(true);controller.execute()
        assertTrue(controller.status().synchronized)
        caps="{}";controller.execute()
        assertFalse(controller.status().synchronized)
        assertTrue(controller.status().blocked>0)
        assertNotNull(dao.integration()!!.lastError)
    }

    @Test fun selectedNodusManualSyncNeverFallsThroughLegacyBackend():Unit=runBlocking {
        val legacy=mockk<org.qosp.notes.data.sync.core.BackendProvider>(relaxed=true)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val repository=org.qosp.notes.data.repo.NoteRepositoryImpl(db.noteDao,db.idMappingDao,db.reminderDao,legacy,mockk(relaxed=true),mockk(relaxed=true),org.qosp.notes.di.SyncScope(scope),mockk(relaxed=true),bridge,controller)
            assertTrue(repository.syncNotes() is org.qosp.notes.data.sync.core.GenericError)
            verify(exactly=0){legacy.isSyncing}
            verify(exactly=0){legacy.syncProvider}
        } finally {scope.cancel()}
    }

    @Test fun authenticatedRealmGuardsRetainedScopesAndExplicitOriginMoves():Unit=runBlocking {
        db.noteDao.insert(Note(title="retained").toEntity())
        configure();val original=dao.connection(controller.status().connectionId!!)!!
        assertEquals("realm-fixture",original.realmId)
        controller.activate(true)
        caps=caps.replace("realm-fixture","other-realm")
        controller.execute()
        assertTrue(sent.isEmpty());assertFalse(controller.status().synchronized)
        val credential=credentials.configuration()!!
        assertThrows(Exception::class.java){runBlocking{controller.replaceCredential("other",true)}}
        assertEquals(credential.credentialEpoch,credentials.configuration()!!.credentialEpoch)
        configure();val separate=dao.connection(controller.status().connectionId!!)!!
        assertNotEquals(original.connectionId,separate.connectionId)
        assertNotEquals(original.ownerKey,separate.ownerKey);assertEquals("other-realm",separate.realmId)
        val reads=gets.size
        controller.reviewConnection(original.connectionId)
        assertEquals(reads,gets.size);assertFalse(controller.status().active);assertFalse(controller.status().tokenSaved)
        controller.refreshConflicts();assertEquals(reads,gets.size)
        assertThrows(Exception::class.java){runBlocking{controller.activate(true)}}
        assertEquals(reads,gets.size)
        assertThrows(Exception::class.java){runBlocking{controller.replaceCredential("same-realm-token",true,"https://moved.example")}}
        caps=NodusFixtures.capabilities
        controller.replaceCredential("same-realm-token",true,"https://moved.example")
        val rebound=dao.connection(original.connectionId)!!
        assertEquals(original.realmId,rebound.realmId);assertEquals(original.ownerKey,rebound.ownerKey)
        assertEquals("https://moved.example/",rebound.origin)
        assertNotEquals(original.credentialEpoch,rebound.credentialEpoch)
        assertFalse(controller.status().active)
        controller.activate(true);assertTrue(controller.status().active)
        assertEquals(2,controller.retainedConnections().size)
    }

    @Test fun restoredServerRealmRotationQuarantinesUnknownWorkAndBootstrapsSeparateScopeFromZero():Unit=runBlocking {
        db.noteDao.insert(Note(title="recovery draft").toEntity())
        configure();controller.activate(true);responseStatus=503;controller.execute()
        val old=dao.connection(controller.status().connectionId!!)!!
        val unknown=dao.operations(old.connectionId).single()
        assertEquals(NodusOutboxState.UNKNOWN,unknown.state)
        dao.storePage(old.connectionId,NodusStream.CHANGES,"0",null,"999999","999999",false,emptyList(),emptyList())
        caps=caps.replace("realm-fixture","restored-realm")
        clock+=120000;controller.execute()
        assertFalse(controller.status().active);assertFalse(controller.status().tokenSaved)
        assertTrue(controller.status().issues.contains("realm_changed_fresh_connection_required"))
        val reads=gets.size
        controller.workerIfSelected()
        assertThrows(Exception::class.java){runBlocking{controller.activate(true)}}
        assertEquals(reads,gets.size);assertEquals(1,sent.size)
        assertNotNull(dao.connection(old.connectionId)!!.inactiveGraph)
        assertThrows(Exception::class.java){runBlocking{controller.replaceCredential("replacement",true)}}
        controller.configureFresh("https://notes.example","replacement",true)
        val fresh=dao.connection(controller.status().connectionId!!)!!
        assertNotEquals(old.connectionId,fresh.connectionId);assertNotEquals(old.ownerKey,fresh.ownerKey)
        assertNotEquals(old.credentialEpoch,fresh.credentialEpoch)
        assertEquals("restored-realm",fresh.realmId)
        assertNull(dao.cursor(fresh.connectionId,"v2",NodusStream.CHANGES))
        changeQueries.clear();responseStatus=200;controller.activate(true);controller.execute()
        assertTrue(changeQueries.isNotEmpty());assertTrue(changeQueries.all{it["after"]=="0"})
        assertEquals("999999",dao.cursor(old.connectionId,"v2",NodusStream.CHANGES)!!.cursor)
        assertEquals("0",dao.cursor(fresh.connectionId,"v2",NodusStream.CHANGES)!!.cursor)
        assertTrue(sent.drop(1).all{it.connectionId==fresh.connectionId && it.credentialEpoch==fresh.credentialEpoch})
        val retained=dao.outbox(old.connectionId,unknown.operationId)!!
        assertEquals(unknown.requestId,retained.requestId);assertEquals(unknown.credentialEpoch,retained.credentialEpoch)
        assertArrayEquals(unknown.body,retained.body);assertEquals(NodusOutboxState.UNKNOWN,retained.state)
    }

    @Test fun completeInventoryRoundTripsThroughConfigurationWorkerAndProjection():Unit=runBlocking {
        roundTrip=true
        val tag=db.tagDao.insert(Tag(" used tag "));db.tagDao.insert(Tag("unused"))
        val book=db.notebookDao.insert(Notebook("assigned"));db.notebookDao.insert(Notebook("empty"))
        val id=db.noteDao.insert(Note(title="raw title",content="# raw **text**\n",isList=false,
            taskList=listOf(NoteTask(31,"inactive checked",true),NoteTask(32,"inactive unchecked",false)),
            isArchived=true,isPinned=true,isHidden=true,isMarkdownEnabled=false,isCompactPreview=true,screenAlwaysOn=true,
            creationDate=946684800,modifiedDate=946684900,isDeleted=true,deletionDate=946685000,
            color=org.qosp.notes.data.model.NoteColor.Purple,notebookId=book,
            attachments=listOf(Attachment(Attachment.Type.AUDIO,"content://source"," raw description ","../../source.mp3"))).toEntity())
        db.noteTagDao.insert(NoteTagJoin(tag,id))
        db.reminderDao.insert(Reminder("one",id,946685100));db.reminderDao.insert(Reminder("two",id,946685200))
        db.noteDao.insert(Note(title="private",isLocalOnly=true).toEntity())
        val original=db.noteDao.getById(id).first()!!
        configure();controller.activate(true)
        var result:androidx.work.ListenableWorker.Result
        var attempts=0
        do { result=controller.workerIfSelected()!!;assertTrue(++attempts<20) } while(result==androidx.work.ListenableWorker.Result.retry())
        assertTrue(controller.status().synchronized)
        assertEquals(1,server.notes.size);assertEquals(2,server.tags.size);assertEquals(2,server.books.size)
        val projected=db.noteDao.getById(id).first()!!
        assertEquals(original.copy(attachments=projected.attachments,reminders=original.reminders.mapIndexed { index,row -> row.copy(alarmFingerprint=projected.reminders[index].alarmFingerprint) },taskList=original.taskList.mapIndexed { index,task -> task.copy(localKey=projected.taskList[index].localKey) }),projected)
        assertEquals(original.attachments.single().copy(path=projected.attachments.single().path,localKey=projected.attachments.single().localKey),projected.attachments.single())
        assertArrayEquals(byteArrayOf(1,2,3),File(java.net.URI(projected.attachments.single().path)).readBytes())
        assertFalse(sent.any{wireString(it.body ?: byteArrayOf()).contains("private")})
    }
}

private class RoundTripServer:NodusEngineTransport,NodusBinaryTransport {
    val notes=mutableMapOf<String,V2Note>()
    val tags=mutableMapOf<String,V2Tag>()
    val books=mutableMapOf<String,V2Notebook>()
    private val blobs=mutableMapOf<String,V2Blob>()
    private val events=mutableListOf<V2Event>()
    private var revision=0
    private val time="2000-01-01T00:00:00Z"
    private fun <T> WireField<T>.value()=(this as WireField.Present<T>).value
    override suspend fun get(configuration:NodusConfiguration,path:String,query:Map<String,String>):NodusHttpResult=when(path) {
        "/api/v2/capabilities" -> NodusHttpResult(200,NodusFixtures.capabilities.toByteArray())
        "/api/v2/changes" -> {
            val after=query["after"]!!.toInt();val until=query["until"]?.toInt() ?: revision
            val eligible=events.filter{it.revision.toInt() in (after+1)..until}
            val page=eligible.take(query["limit"]?.toInt() ?: 100);val more=eligible.size>page.size
            NodusHttpResult(200,NodusJson.encode(V2Changes.serializer(),V2Changes(page,if(more)page.last().revision else until.toString(),until.toString(),more)).toByteArray())
        }
        "/api/v2/conflicts" -> NodusHttpResult(200,NodusJson.encode(V2Conflicts.serializer(),V2Conflicts(emptyList(),revision.toString(),revision.toString(),false)).toByteArray())
        else -> NodusHttpResult(200,NodusJson.encode(V2Blob.serializer(),blobs.getValue(path.substringAfterLast('/'))).toByteArray())
    }
    override suspend fun send(configuration:NodusConfiguration,operation:NodusOutbox):NodusHttpResult {
        val pieces=operation.path.split('/');val id=pieces[4];val rev=(++revision).toString();val body=wireString(operation.body!!)
        when(pieces[3]) {
            "tags" -> {val input=NodusJson.decode(V2OrganizationCreate.serializer(),body);val value=V2Tag(id,input.name,rev,time,time,false);tags[id]=value;events+=V2Event.Tag(rev,"tag",value)}
            "notebooks" -> {val input=NodusJson.decode(V2OrganizationCreate.serializer(),body);val value=V2Notebook(id,input.name,rev,time,time,false);books[id]=value;events+=V2Event.Notebook(rev,"notebook",value)}
            "blobs" -> {
                val input=NodusJson.decode(V2BlobReserve.serializer(),body)
                blobs[id]=V2Blob(id,input.size,input.sha256,input.mediaType,BlobState.PENDING,rev,time)
                return NodusHttpResult(200,"""{"resourceType":"blob","resourceId":"$id","revision":"$rev","state":"pending"}""".toByteArray())
            }
            "notes" -> {
                val value=if(pieces.size==5) {
                    val input=NodusJson.decode(V2CreateNote.serializer(),body)
                    V2Note(id,input.kind,input.title.value(),input.text.value(),input.archived.value(),input.pinned.value(),input.hidden.value(),input.markdownEnabled.value(),input.color.value(),input.notebookId.value(),input.tagIds.value(),input.primaryReminderId.value(),
                        input.items.value().mapIndexed{position,item->Item(item.id,item.text.value(),item.checked.value(),position,rev,false)},rev,time,time,input.authoredAt.value(),input.editedAt.value(),if(input.state.value()==InitialNoteState.TRASH)NoteState.TRASH else NoteState.LIVE,
                        if(input.state.value()==InitialNoteState.TRASH)time else null,(input.sourceTrashedAt as? WireField.Present)?.value,input.attachments.value().mapIndexed { index,ref -> require(blobs.getValue(ref.blobId).state==BlobState.READY);V2Attachment(ref.id,ref.blobId,ref.kind,ref.description.value(),ref.fileName.value(),index,false) },input.reminders.value().map{V2Reminder(it.id,it.name.value(),it.dueAt,false)})
                } else if(pieces.last()=="order") {
                    val input=NodusJson.decode(V2AttachmentOrder.serializer(),body)
                    notes.getValue(id).copy(revision=rev,attachments=input.order.mapIndexed{position,ref->notes.getValue(id).attachments.first{it.id==ref}.copy(position=position)})
                } else {
                    val input=NodusJson.decode(V2AttachmentCreate.serializer(),body)
                    notes.getValue(id).copy(revision=rev,attachments=notes.getValue(id).attachments+V2Attachment(input.id,input.blobId,input.kind,input.description.value(),input.fileName.value(),0,false))
                }
                notes[id]=value;events+=V2Event.Note(rev,"note",value)
            }
        }
        return FakeNodusTransport.receipt(pieces[3].dropLast(1),id,rev)
    }
    override suspend fun upload(configuration:NodusConfiguration,operation:NodusOutbox,source:File):NodusHttpResult {
        val id=operation.path.split('/')[4];val rev=(++revision).toString()
        assertArrayEquals(byteArrayOf(1,2,3),source.readBytes())
        blobs[id]=blobs.getValue(id).copy(state=BlobState.READY,revision=rev)
        return NodusHttpResult(200,"""{"resourceType":"blob","resourceId":"$id","revision":"$rev","state":"ready"}""".toByteArray())
    }
    override suspend fun download(configuration:NodusConfiguration,blobId:String):NodusDownload=error("not needed")
}

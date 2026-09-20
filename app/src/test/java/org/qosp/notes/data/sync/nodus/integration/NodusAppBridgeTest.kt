package org.qosp.notes.data.sync.nodus.integration

import androidx.room.Room
import androidx.room.withTransaction
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.*
import org.qosp.notes.data.model.Reminder
import org.qosp.notes.data.repo.*
import org.qosp.notes.data.sync.core.*
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.engine.*
import org.qosp.notes.data.sync.nodus.storage.*
import org.qosp.notes.di.SyncScope
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class NodusAppBridgeTest {
    private lateinit var db: AppDatabase
    private lateinit var directory: File
    private lateinit var bridge: NodusAppBridge
    private lateinit var notes: NoteRepositoryImpl
    private lateinit var tags: TagRepository
    private lateinit var books: NotebookRepository
    private lateinit var reminders: ReminderRepository
    private lateinit var config: NodusConfiguration
    private lateinit var bytes: NodusByteStore
    private lateinit var backend: BackendProvider
    private lateinit var actions: ProcessRemoteActions
    private lateinit var scope: CoroutineScope
    private val transport=FakeNodusTransport()
    private val binary=object:NodusBinaryTransport {
        override suspend fun upload(configuration:NodusConfiguration,operation:NodusOutbox,source:File):NodusHttpResult=error("unexpected network")
        override suspend fun download(configuration:NodusConfiguration,blobId:String):NodusDownload=error("unexpected network")
    }
    private val dao get()=db.nodusDao
    private fun transfers()=NodusAttachments(db,"c",{config},bytes,transport,binary)
    private suspend fun compile()=NodusCaptureCompiler(db,bridge,transfers()).compile(100)
    private suspend fun note(id:Long)=db.noteDao.getById(id).first()!!
    private suspend fun graph()=localJson.decodeFromString<LocalGraph>(dao.latestCapture("c")!!.afterBody)
    private suspend fun drafts()=dao.mappings("c").flatMap { dao.recentResourceIntents("c",it.resourceType,it.wireId) }.distinctBy{it.intentId}.map(::decodeDraft)
    private fun wireBodies(values:List<NodusDraft>)=values.joinToString("\n") { wireString(it.body) }

    @Before fun setup():Unit=runBlocking {
        directory=Files.createTempDirectory("nodus-integration").toFile()
        config=NodusConfiguration(NodusOrigin.parse("https://notes.example"),"synthetic","epoch","device")
        scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        backend=mockk(relaxed=true); every { backend.isSyncing } returns false
        actions=mockk(relaxed=true)
        bytes=NodusByteStore(File(directory,"bytes"),publish={from,to -> Files.createLink(to.toPath(),from.toPath()); Unit},syncDirectory={})
        open()
        dao.createConnection(NodusConnection("c",config.origin.toString(),"owner","device","epoch",realmId="realm-fixture"))
        bridge.activate("c")
    }
    private fun open() {
        db=Room.databaseBuilder(RuntimeEnvironment.getApplication(),AppDatabase::class.java,File(directory,"journal.db").path).allowMainThreadQueries().build()
        bridge=NodusAppBridge(db){config}
        notes=NoteRepositoryImpl(db.noteDao,db.idMappingDao,db.reminderDao,backend,mockk(relaxed=true),actions,SyncScope(scope),mockk(relaxed=true),bridge)
        tags=TagRepository(db.tagDao,db.noteTagDao,notes,SyncScope(scope),bridge)
        books=NotebookRepository(db.notebookDao,notes,bridge)
        reminders=ReminderRepository(db.reminderDao,bridge)
    }
    private fun reopen() {db.close();open()}
    @After fun close(){scope.cancel();db.close();directory.deleteRecursively()}

    @Test fun inactiveAndRestoredIdentityNeverCaptureOrActivateImplicitly():Unit=runBlocking {
        bridge.deactivate()
        notes.insertNote(Note(title="inactive"))
        assertTrue(dao.captures("c").isEmpty())
        bridge.activate("c")
        config=NodusConfiguration(config.origin,"other","other-epoch","restored-device")
        reopen()
        notes.insertNote(Note(title="restored"))
        assertNull(bridge.active());assertEquals(1,dao.captures("c").size)
        assertEquals(listOf("DISABLED","NEXTCLOUD","FILE_STORAGE","NODUS"),org.qosp.notes.preferences.CloudService.entries.map{it.name})
    }

    @Test fun noteMutationsCaptureRawGraphOfflineAndCompileWithoutNetwork():Unit=runBlocking {
        val id=notes.insertNote(Note(title="first",content="raw ** text\n",taskList=listOf(NoteTask(9,"inactive",true)),creationDate=946684800,modifiedDate=946684801))
        compile()
        notes.updateNotes(note(id).copy(title="next",content="new raw",isMarkdownEnabled=false,color=org.qosp.notes.data.model.NoteColor.Purple,isPinned=true,isArchived=true,isHidden=true,isList=true,taskList=listOf(NoteTask(10,"new",false),NoteTask(9,"edit",false)),modifiedDate=946684805))
        compile()
        notes.updateNotes(note(id).copy(taskList=listOf(NoteTask(9,"checked",true),NoteTask(10,"new",false))))
        compile()
        notes.updateNotes(note(id).copy(isList=false,taskList=listOf(NoteTask(9,"checked",true))))
        compile()
        notes.moveNotesToBin(note(id));compile()
        notes.restoreNotes(note(id));compile()
        notes.deleteNotes(note(id));compile()
        assertTrue(dao.captures("c").all{it.state=="COMPILED"})
        val values=drafts()
        assertTrue(values.any{it.path.endsWith("/trash")});assertTrue(values.any{it.path.endsWith("/restore")});assertTrue(values.any{it.path.endsWith("/purge")})
        val wire=wireBodies(values)
        for(value in listOf("raw ** text", "inactive", "checked", "markdownEnabled", "hidden", "purple", "1999")) {
            if(value!="1999") assertTrue(value,wire.contains(value))
        }
        assertTrue(transport.sent.isEmpty() && transport.gets.isEmpty())
    }

    @Test fun enrollmentWithSingleReminderSelectsV1PrimaryProjection():Unit=runBlocking {
        val noteId=bridge.mutate {
            val id=db.noteDao.insert(Note(title="existing reminder").toEntity())
            db.reminderDao.insert(Reminder("primary",id,946684800))
            id
        }
        compile()
        val root=dao.mappingByLocalRow("c",NodusResourceType.NOTE,noteId)!!
        val create=drafts().single { it.method=="PUT" && it.path=="/api/v2/notes/${root.wireId}" }
        val input=NodusJson.decode(V2CreateNote.serializer(),wireString(create.body))
        val reminder=(input.reminders as WireField.Present).value.single()
        assertEquals(reminder.id,(input.primaryReminderId as WireField.Present).value)
    }

    @Test fun everyOrganizationMembershipAndReminderEntryPointIsCaptured():Unit=runBlocking {
        val id=notes.insertNote(Note(title="relations"));compile()
        val tag=tags.insert(Tag("unused"));val book=books.insert(Notebook("empty"));compile()
        tags.update(Tag("renamed",tag));books.update(Notebook("renamed book",book));compile()
        tags.addTagToNote(tag,id,shouldSync=false)
        notes.updateNotes(note(id).copy(notebookId=book));compile()
        val first=reminders.insert(Reminder("one",id,946684800));val second=reminders.insert(Reminder("two",id,946684900));compile()
        reminders.update(Reminder("edited",id,946685000,first));compile()
        reminders.deleteById(first);compile()
        reminders.deleteByNoteId(id);compile()
        tags.deleteTagFromNote(tag,id,shouldSync=false);compile()
        tags.delete(Tag("renamed",tag),shouldSync=false);books.delete(Notebook("renamed book",book));compile()
        assertTrue(dao.captures("c").all{it.state=="COMPILED"})
        val body=wireBodies(drafts())
        for(value in listOf("unused","empty","renamed","renamed book","one","two","edited")) assertTrue(value,body.contains(value))
        assertTrue(drafts().count{it.method=="DELETE"}>=4)
        assertNull(db.reminderDao.getById(second).first())
    }

    @Test fun deliveryExpiryDoesNotQueueDeletionAndPrivateForkNeverDeletesSharedNote():Unit=runBlocking {
        val id=notes.insertNote(Note(title="shared"));val reminder=reminders.insert(Reminder("delivered",id,946684800));compile()
        val count=dao.captures("c").size
        reminders.consumeById(reminder)
        assertEquals(count,dao.captures("c").size)
        notes.updateNotes(note(id).copy(title="edited after delivery"));compile()
        assertFalse(drafts().any{it.method=="DELETE"})
        notes.updateNotes(note(id).copy(isLocalOnly=true));compile()
        val root=dao.mappings("c").first{it.resourceType==NodusResourceType.NOTE}
        assertTrue(root.localRowDetached)
        assertFalse(drafts().any{it.path.endsWith("/purge")})
        assertEquals("PRIVATE",dao.latestCapture("c")!!.state)
        notes.updateNotes(note(id).copy(isLocalOnly=false));compile()
        assertEquals(2,dao.mappings("c").count{it.resourceType==NodusResourceType.NOTE})
    }

    @Test fun localWriteAndCaptureRollbackTogetherAndInvalidWireGraphStaysVisible():Unit=runBlocking {
        assertThrows(IllegalStateException::class.java) { runBlocking { bridge.mutate { db.noteDao.insert(Note(title="rollback").toEntity());error("rollback") } } }
        assertTrue(notes.getAll().first().isEmpty());assertTrue(dao.captures("c").isEmpty())
        val id=notes.insertNote(Note(title="wire invalid",taskList=(0..1000).map{NoteTask(it.toLong(),"task",false)}))
        compile()
        assertEquals(1001,note(id).taskList.size)
        assertEquals("BLOCKED",dao.latestCapture("c")!!.state)
        assertTrue(dao.mappings("c").isEmpty())
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun autosaveCoalescesOnlyUnpreparedCapturesAndSurvivesReopen():Unit=runBlocking {
        val id=notes.insertNote(Note(title="initial"))
        repeat(100){notes.updateNotes(note(id).copy(title="edit $it"))}
        assertEquals(1,dao.captures("c").size)
        assertEquals("edit 99",graph().notes.single().title)
        reopen();compile()
        assertEquals("COMPILED",dao.latestCapture("c")!!.state)
        val frozen=dao.latestCapture("c")!!
        notes.updateNotes(note(id).copy(title="successor"))
        assertEquals(2,dao.captures("c").size)
        assertEquals(frozen,dao.captures("c").first())
        reopen();compile();assertTrue(dao.captures("c").all{it.state=="COMPILED"})
    }

    @Test fun legacyDuplicateAttachmentKeysCopyRestoreMetadataOrderAndRemovalAreStable():Unit=runBlocking {
        val old=localJson.decodeFromString<Attachment>("""{"type":"GENERIC","path":"content://same"}""")
        assertNull(old.localKey)
        val id=notes.insertNote(Note(title="attachments",attachments=listOf(old,old)))
        val first=note(id).attachments
        assertEquals(2,first.map{it.localKey}.distinct().size);assertTrue(first.all{it.localKey!=null})
        compile();assertEquals("COMPILED",dao.latestCapture("c")!!.state)
        notes.updateNotes(note(id).copy(attachments=first.reversed().map{it.copy(description="changed")}));compile()
        assertEquals(first.reversed().map{it.localKey},note(id).attachments.map{it.localKey})
        val copy=notes.insertNote(note(id).copy(id=0));compile()
        assertTrue(note(copy).attachments.none{it.localKey in first.map{it.localKey}})
        notes.updateNotes(note(id).copy(attachments=note(id).attachments.take(1)));compile()
        assertTrue(drafts().any{it.method=="DELETE" && it.path.contains("/attachments/")})
        assertTrue(bridge.retainedAttachmentPaths().contains("content://same"))
        assertTrue(dao.attachmentTransfers("c").all{it.draftBody!=null})
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun cleanupCannotEraseMappedOrUncompiledNotesAndDeviceFlagsDoNotSend():Unit=runBlocking {
        val id=notes.insertNote(Note())
        assertFalse(notes.discardEmptyNotes())
        assertFalse(bridge.cleanupEligible(id))
        compile()
        val old=drafts().size
        notes.updateNotes(note(id).copy(isCompactPreview=true,screenAlwaysOn=true));compile()
        assertEquals(old,drafts().size)
        bridge.deactivate()
        assertFalse(bridge.cleanupEligible(id))
    }

    @Test fun concurrentLocalMutationsAreAtomicAndFrozenCompileGetsSuccessor():Unit=runBlocking {
        val id=notes.insertNote(Note(title="initial"));compile()
        coroutineScope { (1..10).map { n -> async(Dispatchers.Default) { bridge.mutate { val current=note(id);db.noteDao.update(current.copy(content=current.content+"$n,").toEntity()) } } }.awaitAll() }
        assertEquals(note(id).content,graph().notes.single().content)
        assertEquals(11L,dao.integration()!!.generation)
        assertEquals(10,note(id).content.split(',').filter{it.isNotEmpty()}.size)
        val capture=dao.latestCapture("c")!!
        dao.updateCapture(capture.copy(state="COMPILING"))
        notes.updateNotes(note(id).copy(title="during compile"))
        assertEquals(3,dao.captures("c").size)
        reopen();compile()
        assertTrue(dao.captures("c").all{it.state=="COMPILED"})
    }

    @Test fun servicePersistsContinuationEndFlagsRetryAfterAndResumesAfterRestart():Unit=runBlocking {
        repeat(8) { notes.insertNote(Note(title="queued $it")) }
        compile()
        var clock=1000L
        fun service()=NodusSyncService(db,bridge,{config},bytes,transport,binary,{error("no sources")}) {clock}
        transport.sendHandler={ NodusHttpResult(429,"""{"error":"rate_limited"}""".toByteArray(),"2") }
        val first=service().run(1,4)
        assertEquals(3000L,first.retryAtMillis)
        val stored=dao.integration()!!
        assertTrue(stored.operationRow>0)
        assertEquals(3000L,stored.retryAtMillis)
        bridge.activate("c");assertEquals(stored,dao.integration())
        assertEquals(androidx.work.ListenableWorker.Result.retry(),first.workerResult())
        reopen()
        val count=transport.gets.size
        assertEquals(3000L,service().run(1,4).retryAtMillis)
        assertEquals(count,transport.gets.size)
        assertEquals(stored,dao.integration())
        clock=3001
        transport.sendHandler={ operation -> FakeNodusTransport.receipt("note",operation.path.substringAfterLast('/'),"2") }
        repeat(15) {
            transport.pages.add(V2Changes(emptyList(),"0","0",false))
            service().run(1,4)
            reopen()
        }
        assertEquals(8,transport.sent.map{it.operationId}.distinct().size)
        assertTrue(dao.pendingOperations("c",100).isEmpty())
        assertEquals(androidx.work.ListenableWorker.Result.success(),NodusExecution(false).workerResult())
    }

    @Test fun permissionLossAfterOfflineAttachmentCaptureRetainsOriginalAndBlocksVisibly():Unit=runBlocking {
        val id=notes.insertNote(Note(title="attachment",attachments=listOf(Attachment(path="content://revoked"))))
        reopen()
        val service=NodusSyncService(db,bridge,{config},bytes,transport,binary,{ assertFalse(db.inTransaction());throw SecurityException("revoked") })
        transport.pages.add(V2Changes(emptyList(),"0","0",false))
        val progress=service.run()
        assertTrue(progress.blocked.any{it.contains("source_inaccessible")})
        assertTrue(transport.sent.none { it.path.startsWith("/api/v2/notes/") })
        assertEquals("content://revoked",note(id).attachments.single().path)
        assertEquals(NodusReadiness.ERROR,dao.attachmentTransfers("c").single().state)
        assertTrue(File(directory,"bytes").listFiles()!!.isEmpty())
        assertTrue(bridge.retainedAttachmentPaths().contains("content://revoked"))
    }

    @Test fun successorCaptureBlocksProjectionBeforeCompilation():Unit=runBlocking {
        val id=notes.insertNote(Note(title="first"));compile()
        val root=dao.mappingByLocalRow("c",NodusResourceType.NOTE,id)!!
        notes.updateNotes(note(id).copy(title="not compiled"))
        assertEquals("uncompiled_local_capture",NodusProjection(db,"c",bytes).dirtyReason(NodusResourceType.NOTE,root.wireId,"99"))
        assertEquals("not compiled",note(id).title)
    }

    @Test fun copyRelationsAndExplicitEmptyBinHaveDurableCaptures():Unit=runBlocking {
        val first=notes.insertNote(Note(title="source"))
        val second=notes.insertNote(Note(title="copy"))
        val tag=tags.insert(Tag("tag"))
        tags.addTagToNote(tag,first,shouldSync=false)
        reminders.insert(Reminder("shared reminder",first,946684800))
        compile()
        tags.copyTags(first,second);reminders.copyReminders(first,second);compile()
        assertEquals(1,note(second).tags.size);assertEquals(1,note(second).reminders.size)
        assertNotEquals(note(first).reminders.single().id,note(second).reminders.single().id)
        notes.moveNotesToBin(note(first),note(second));compile()
        notes.permanentlyDeleteNotesInBin();compile()
        assertEquals(2,drafts().count{it.path.endsWith("/purge")})
        assertTrue(dao.captures("c").all{it.state=="COMPILED"})
    }

    @Test fun deviceOnlyEditNeverEnrollsAndDisconnectedPendingDraftSurvivesCleanup():Unit=runBlocking {
        val id=db.noteDao.insert(Note(title="not enrolled").toEntity())
        notes.updateNotes(note(id).copy(isCompactPreview=true,screenAlwaysOn=true))
        assertTrue(dao.captures("c").isEmpty())
        notes.updateNotes(note(id).copy(title="draft"))
        bridge.deactivate()
        assertFalse(bridge.cleanupEligible(id))
        assertEquals("PENDING",dao.latestCapture("c")!!.state)
    }

    @Test fun deactivationDuringExecutionPreventsEverySubsequentNetworkRequest():Unit=runBlocking {
        notes.insertNote(Note(title="first"));notes.insertNote(Note(title="second"));compile()
        var count=0
        transport.sendHandler={ operation ->
            assertFalse(db.inTransaction())
            count++
            bridge.deactivate()
            FakeNodusTransport.receipt("note",operation.path.substringAfterLast('/'),"2")
        }
        val service=NodusSyncService(db,bridge,{config},bytes,transport,binary,{error("no file")})
        service.run()
        assertEquals(1,count)
        assertNull(bridge.active())
        assertEquals(listOf("inactive"),service.run().blocked)
    }

    @Test fun captureInsertFailureRollsBackTheLocalWrite():Unit=runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_capture BEFORE INSERT ON nodus_captures BEGIN SELECT RAISE(ABORT, 'test'); END")
        try {
            assertThrows(Exception::class.java) { runBlocking {notes.insertNote(Note(title="must rollback"))} }
            assertTrue(notes.getAll().first().isEmpty())
            assertEquals(0L,dao.integration()!!.generation)
        } finally {db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_capture")}
    }

    @Test fun serviceCapturesOwnedBytesAfterCommitAndCompletesDistinctOperationsAcrossRestart():Unit=runBlocking {
        val payload=byteArrayOf(1,2,3)
        val remote=mutableMapOf<String,V2Blob>()
        val sent=mutableListOf<NodusOutbox>()
        var revision=0
        var opens=0
        var uploads=0
        val json=object:NodusEngineTransport {
            override suspend fun get(configuration:NodusConfiguration,path:String,query:Map<String,String>):NodusHttpResult {
                assertFalse(db.inTransaction())
                return when(path) {
                    "/api/v2/capabilities" -> NodusHttpResult(200,NodusFixtures.capabilities.toByteArray())
                    "/api/v2/changes" -> NodusHttpResult(200,NodusJson.encode(V2Changes.serializer(),V2Changes(emptyList(),"0","0",false)).toByteArray())
                    else -> NodusHttpResult(200,NodusJson.encode(V2Blob.serializer(),remote.getValue(path.substringAfterLast('/'))).toByteArray())
                }
            }
            override suspend fun send(configuration:NodusConfiguration,operation:NodusOutbox):NodusHttpResult {
                assertFalse(db.inTransaction());sent+=operation;revision++
                if(operation.path.startsWith("/api/v2/blobs/")) {
                    val request=NodusJson.decode(V2BlobReserve.serializer(),wireString(operation.body!!))
                    val id=operation.path.substringAfterLast('/')
                    remote[id]=V2Blob(id,request.size,request.sha256,request.mediaType,BlobState.PENDING,revision.toString(),"2000-01-01T00:00:00Z")
                    return NodusHttpResult(200,"""{"resourceType":"blob","resourceId":"$id","revision":"$revision","state":"pending"}""".toByteArray())
                }
                return FakeNodusTransport.receipt("note",operation.path.split('/')[4],revision.toString())
            }
        }
        val binary=object:NodusBinaryTransport {
            override suspend fun upload(configuration:NodusConfiguration,operation:NodusOutbox,source:File):NodusHttpResult {
                assertFalse(db.inTransaction());assertArrayEquals(payload,source.readBytes());uploads++;revision++
                val id=operation.path.split('/')[4]
                remote[id]=remote.getValue(id).copy(state=BlobState.READY,revision=revision.toString())
                return NodusHttpResult(200,"""{"resourceType":"blob","resourceId":"$id","revision":"$revision","state":"ready"}""".toByteArray())
            }
            override suspend fun download(configuration:NodusConfiguration,blobId:String):NodusDownload=error("not needed")
        }
        notes.insertNote(Note(title="file",attachments=listOf(Attachment(path="content://owned",description="frozen"),Attachment(path="content://owned",description="second"))))
        fun service()=NodusSyncService(db,bridge,{config},bytes,json,binary,{uri -> assertFalse(db.inTransaction());opens++;if(uri.endsWith("missing"))throw IOException("inaccessible");ByteArrayInputStream(payload)})
        repeat(3) { service().run();reopen() }
        val refs=dao.attachmentTransfers("c")
        assertEquals(2,refs.size);assertTrue(refs.all { it.state==NodusReadiness.AVAILABLE })
        val ref=refs.first()
        val blob=dao.blob("c",ref.blobId)!!
        assertEquals(2,opens);assertEquals(2,uploads)
        assertEquals(3,listOf(blob.reservationOperationId,blob.uploadOperationId,ref.operationId).filterNotNull().distinct().size)
        assertEquals(0,sent.count{it.path.endsWith("/attachments")})
        val create=sent.single{it.path.startsWith("/api/v2/notes/")}
        val aggregate=NodusJson.decode(V2CreateNote.serializer(),wireString(create.body!!))
        assertEquals(refs.map{it.attachmentId}.toSet(),(aggregate.attachments as WireField.Present).value.map{it.id}.toSet())
        assertArrayEquals(payload,File(java.net.URI(blob.sourceUri!!)).readBytes())
        val blocked=notes.insertNote(Note(title="all-or-nothing",attachments=listOf(Attachment(path="content://owned"),Attachment(path="content://missing"))))
        repeat(3){service().run();reopen()}
        val root=dao.mappingByLocalRow("c",NodusResourceType.NOTE,blocked)!!
        assertTrue(sent.none{it.path=="/api/v2/notes/${root.wireId}"})
        assertTrue(dao.attachmentTransfers("c").any{it.noteId==root.wireId && it.state==NodusReadiness.ERROR})
        assertEquals(2,note(blocked).attachments.size)
        assertTrue(bridge.retainedAttachmentPaths().contains("content://missing"))
    }

    @Test fun privateMembershipDoesNotEnrollPreexistingOrganizationDependencies():Unit=runBlocking {
        val tag=db.tagDao.insert(Tag("private dependency"))
        val id=notes.insertNote(Note(title="private",isLocalOnly=true));compile()
        tags.addTagToNote(tag,id,shouldSync=false);compile()
        assertEquals("PRIVATE",dao.latestCapture("c")!!.state)
        assertTrue(dao.mappings("c").isEmpty())
        assertTrue(drafts().isEmpty())
    }

    @Test fun returnedPendingAloneCompletesNotebookDependentEnrollment():Unit=runBlocking {
        val book=books.insert(Notebook("dependency"))
        val id=notes.insertNote(Note(title="dependent",notebookId=book));compile()
        val bookMap=dao.mappingByLocalRow("c",NodusResourceType.NOTEBOOK,book)!!
        val root=dao.mappingByLocalRow("c",NodusResourceType.NOTE,id)!!
        val time="2000-01-01T00:00:00Z"
        transport.pages.add(V2Changes(listOf(V2Event.Notebook("2","notebook",V2Notebook(bookMap.wireId,"dependency","2",time,time,false))),"2","2",false))
        fun service()=NodusSyncService(db,bridge,{config},bytes,transport,binary,{error("no source")})
        var result=service().run()
        assertTrue(result.pending)
        var passes=1
        while(result.pending && passes++<10) {
            reopen();transport.pages.add(V2Changes(emptyList(),"2","2",false));result=service().run()
        }
        assertFalse(result.pending)
        assertTrue(transport.sent.any{it.path=="/api/v2/notes/${root.wireId}"})
        assertTrue(dao.pendingOperations("c",100).isEmpty())
    }

    @Test fun returnedPendingAloneDownloadsFinalPageAttachmentAndProjectsWholeNote():Unit=runBlocking {
        val payload=byteArrayOf(3,4,5)
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString(""){"%02x".format(it.toInt() and 255)}
        val blob=V2Blob("incoming-blob","3",hash,"application/octet-stream",BlobState.READY,"1","2000-01-01T00:00:00Z")
        val incoming=NodusJson.decode(V2Note.serializer(),NodusFixtures.note).copy(id="incoming",revision="2",items=emptyList(),reminders=emptyList(),primaryReminderId=null,notebookId=null,tagIds=emptyList(),attachments=listOf(V2Attachment("ref",blob.id,AttachmentKind.GENERIC,"incoming file","file",0,false)))
        var first=true
        var downloads=0
        val http=object:NodusEngineTransport {
            override suspend fun get(configuration:NodusConfiguration,path:String,query:Map<String,String>):NodusHttpResult = when(path) {
                "/api/v2/capabilities" -> NodusHttpResult(200,NodusFixtures.capabilities.toByteArray())
                "/api/v2/changes" -> NodusHttpResult(200,NodusJson.encode(V2Changes.serializer(),V2Changes(if(first)listOf(V2Event.Note("2","note",incoming)).also{first=false}else emptyList(),"2","2",false)).toByteArray())
                else -> NodusHttpResult(200,NodusJson.encode(V2Blob.serializer(),blob).toByteArray())
            }
            override suspend fun send(configuration:NodusConfiguration,operation:NodusOutbox):NodusHttpResult=error("no writes")
        }
        val binary=object:NodusBinaryTransport {
            override suspend fun upload(configuration:NodusConfiguration,operation:NodusOutbox,source:File):NodusHttpResult=error("no upload")
            override suspend fun download(configuration:NodusConfiguration,blobId:String):NodusDownload {downloads++;return NodusDownload(200,"3","application/octet-stream","attachment",null,ByteArrayInputStream(payload))}
        }
        fun service()=NodusSyncService(db,bridge,{config},bytes,http,binary,{error("no source")})
        var result=service().run();assertTrue(result.pending)
        var passes=1
        while(result.pending && passes++<10) { reopen();result=service().run() }
        assertFalse(result.pending);assertEquals(1,downloads)
        val local=notes.getAll().first().single()
        assertArrayEquals(payload,File(java.net.URI(local.attachments.single().path)).readBytes())
    }

    @Test fun organizationDeletesWaitForDeferredDetachesIncludingTrashedNotes():Unit=runBlocking {
        val tag=tags.insert(Tag("tag"));val book=books.insert(Notebook("book"))
        val id=notes.insertNote(Note(title="trash",isDeleted=true,notebookId=book));tags.addTagToNote(tag,id,shouldSync=false);compile()
        val time="2000-01-01T00:00:00Z"
        val tm=dao.mappingByLocalRow("c",NodusResourceType.TAG,tag)!!
        val bm=dao.mappingByLocalRow("c",NodusResourceType.NOTEBOOK,book)!!
        val root=dao.mappingByLocalRow("c",NodusResourceType.NOTE,id)!!
        val initial=listOf(V2Event.Tag("1","tag",V2Tag(tm.wireId,"tag","1",time,time,false)),V2Event.Notebook("2","notebook",V2Notebook(bm.wireId,"book","2",time,time,false)))
        transport.pages.add(V2Changes(initial,"2","2",false))
        val engine=NodusCoordinator(db,"c",{config},transport,bytes)
        engine.pullChanges();repeat(4){engine.drain()}
        val canonical=NodusJson.decode(V2Note.serializer(),NodusFixtures.note).copy(id=root.wireId,revision="30",title="trash",state=NoteState.TRASH,trashedAt=time,items=emptyList(),attachments=emptyList(),reminders=emptyList(),primaryReminderId=null,tagIds=listOf(tm.wireId),notebookId=bm.wireId)
        transport.pages.add(V2Changes(listOf(V2Event.Note("30","note",canonical)),"30","30",false));engine.pullChanges()
        notes.updateNotes(note(id).copy(title="queued title"));compile()
        tags.delete(Tag("tag",tag),shouldSync=false);books.delete(Notebook("book",book));compile()
        val deletions=dao.captures("c").size
        val deleteDrafts=drafts().filter{it.method=="DELETE" && !it.path.contains("/notes/")}
        assertEquals(2,deleteDrafts.size);assertTrue(deleteDrafts.all{it.dependencies.isNotEmpty()})
        val frozen=deleteDrafts.map{it.requestId}.toSet()
        for(draft in deleteDrafts) for(dependency in draft.dependencies) assertNotEquals(NodusOutboxState.RETIRED,dao.outbox("c",dependency)?.state)
        transport.sent.clear();reopen()
        var revision=30
        val successful=mutableSetOf<String>()
        transport.sendHandler={ op ->
            val intent=dao.intent("c",op.intentId)!!
            val draft=decodeDraft(intent)
            if(op.method=="DELETE") assertTrue(draft.dependencies.all{it in successful})
            successful+=op.operationId
            FakeNodusTransport.receipt(op.path.split('/')[3].dropLast(1),op.path.split('/')[4],(++revision).toString())
        }
        repeat(10){NodusCoordinator(db,"c",{config},transport,bytes).drain()}
        assertEquals(frozen,transport.sent.filter{it.method=="DELETE"}.map{it.requestId}.toSet())
        assertEquals(deletions,dao.captures("c").size)
    }

    @Test fun inactiveLegacyProviderStillReceivesCreateUpdateDelete():Unit=runBlocking {
        bridge.deactivate();every{backend.isSyncing} returns true
        val id=notes.insertNote(Note(title="legacy"));notes.updateNotes(note(id).copy(title="updated"));notes.deleteNotes(note(id))
        verify(exactly=3){actions.invoke(any(),any())}
        assertTrue(dao.captures("c").isEmpty())
    }
    @Test fun inactiveCheckpointJournalsCompleteGraphAcrossRestartWithoutConsumingSharedReminderIntent():Unit=runBlocking {
        val tag=tags.insert(Tag("before-tag"));val book=books.insert(Notebook("before-book"))
        val id=notes.insertNote(Note(title="before",content="inactive text",notebookId=book,taskList=listOf(NoteTask(1,"inactive original",false)),
            attachments=listOf(Attachment(path="content://original",fileName="original"))))
        tags.addTagToNote(tag,id,shouldSync=false)
        val explicit=reminders.insert(Reminder("remove",id,946684800))
        val consumed=reminders.insert(Reminder("delivered",id,946684900))
        compile();bridge.deactivate()
        val checkpoint=dao.connection("c")!!.inactiveGraph!!
        val captures=dao.captures("c").size
        notes.updateNotes(note(id).copy(title="offline",content="raw **offline**",isPinned=true,isArchived=true,taskList=listOf(NoteTask(1,"inactive edited",true)),
            attachments=note(id).attachments+Attachment(path="content://offline-new",fileName="new")))
        tags.update(Tag("after-tag",tag));books.delete(Notebook("before-book",book))
        reminders.deleteById(explicit);reminders.consumeById(consumed)
        notes.moveNotesToBin(note(id))
        reminders.insert(Reminder("new offline intent",id,946685000))
        assertEquals(captures,dao.captures("c").size)
        assertTrue(bridge.retainedAttachmentPaths().contains("content://original"))
        reopen();assertNull(bridge.active());assertEquals(checkpoint,dao.connection("c")!!.inactiveGraph)
        bridge.activate("c")
        val capture=dao.latestCapture("c")!!
        assertEquals(checkpoint,capture.beforeBody);assertEquals("epoch",capture.credentialEpoch)
        assertEquals(listOf(explicit),localJson.decodeFromString<List<Long>>(capture.removedReminders))
        val after=localJson.decodeFromString<LocalGraph>(capture.afterBody)
        val saved=after.notes.single()
        assertEquals("offline",saved.title);assertEquals("raw **offline**",saved.content)
        assertEquals("inactive edited",saved.taskList.single().content);assertTrue(saved.taskList.single().isDone)
        assertEquals("new offline intent",saved.reminders.single().name)
        assertTrue(saved.isPinned && saved.isArchived && saved.isDeleted);assertNull(saved.notebookId)
        assertEquals("after-tag",after.tags.single().name);assertTrue(after.notebooks.isEmpty())
        assertEquals(2,saved.attachments.mapNotNull{it.localKey}.distinct().size)
        assertNull(dao.connection("c")!!.inactiveGraph);assertEquals(captures+1,dao.captures("c").size)
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun checkpointActivationFailureRollsBackAndRetainsPrivateForkAcrossDeletion():Unit=runBlocking {
        val id=notes.insertNote(Note(title="shared"));compile()
        val root=dao.mappingByLocalRow("c",NodusResourceType.NOTE,id)!!
        bridge.deactivate();notes.updateNotes(note(id).copy(isLocalOnly=true))
        assertTrue(dao.mapping("c",root.mappingId)!!.localRowDetached)
        notes.deleteNotes(note(id));reopen()
        val connection=dao.connection("c")!!;val captures=dao.captures("c").size
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_activation BEFORE INSERT ON nodus_captures BEGIN SELECT RAISE(ABORT,'journal unavailable'); END")
        assertThrows(Exception::class.java){runBlocking{bridge.activate("c")}}
        assertNull(bridge.active());assertEquals(connection.inactiveGraph,dao.connection("c")!!.inactiveGraph)
        assertEquals(captures,dao.captures("c").size)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_activation")
        bridge.activate("c");compile()
        assertTrue(drafts().none{it.path.endsWith("/purge")})
        assertTrue(dao.mapping("c",root.mappingId)!!.localRowDetached)
    }

    @Test fun inFlightPullCannotProjectAfterDisconnectOrOverwriteInactiveEdits():Unit=runBlocking {
        val remote=NodusJson.decode(V2Note.serializer(),NodusFixtures.note).copy(id="remote",revision="2",items=emptyList(),attachments=emptyList(),tagIds=emptyList(),notebookId=null,reminders=emptyList(),primaryReminderId=null)
        val http=object:NodusEngineTransport {
            override suspend fun get(configuration:NodusConfiguration,path:String,query:Map<String,String>):NodusHttpResult {
                assertFalse(db.inTransaction())
                if(path.endsWith("capabilities"))return NodusHttpResult(200,NodusFixtures.capabilities.toByteArray())
                bridge.deactivate();notes.insertNote(Note(title="inactive-only"))
                return NodusHttpResult(200,NodusJson.encode(V2Changes.serializer(),V2Changes(listOf(V2Event.Note("2","note",remote)),"2","2",false)).toByteArray())
            }
            override suspend fun send(configuration:NodusConfiguration,operation:NodusOutbox):NodusHttpResult=error("no writes")
        }
        NodusSyncService(db,bridge,{config},bytes,http,binary,{error("no source")}).run()
        assertEquals(listOf("inactive-only"),notes.getAll().first().map{it.title})
        assertNull(bridge.active())
        assertNull(dao.cursor("c","v2",NodusStream.CHANGES))
        dao.storePage("c",NodusStream.CHANGES,"0",null,"2","2",false,listOf(NodusSnapshot("c",NodusResourceType.NOTE,"remote","2",NodusJson.encode(V2Note.serializer(),remote).toByteArray(),false)),emptyList())
        assertEquals("inactive_connection",NodusProjection(db,"c",bytes){bridge.active()!=null}.project().blocked.single().reason)
        assertEquals(listOf("inactive-only"),notes.getAll().first().map{it.title})
    }

    @Test fun acknowledgedDrainReceivesQuiescencePassEvenWhenPullAddsNothing():Unit=runBlocking {
        val id=notes.insertNote(Note(title="first"));compile()
        notes.updateNotes(note(id).copy(title="second"));compile()
        dao.storePage("c",NodusStream.CHANGES,"0",null,"0","0",false,emptyList(),emptyList())
        repeat(5){transport.pages.add(V2Changes(emptyList(),"0","0",false))}
        fun service()=NodusSyncService(db,bridge,{config},bytes,transport,binary,{error("no source")})
        var result=service().run()
        assertEquals(2,transport.sent.size)
        assertTrue(result.pending)
        var passes=1
        while(result.pending && passes++<5) {reopen();result=service().run()}
        assertFalse(result.pending)
        assertEquals(2,transport.sent.size)
        assertTrue(wireString(transport.sent.last().body!!).contains("second"))
    }

    @Test fun inactiveTaskLifetimeSurvivesEditorReloadIdenticalReplacementAndRepeatedCycles():Unit=runBlocking {
        val id=notes.insertNote(Note(isList=true,taskList=listOf(NoteTask(0,"same",false))))
        compile()
        val root=dao.mappingByLocalRow("c",NodusResourceType.NOTE,id)!!
        val original=dao.mappingByLocalRow("c",NodusResourceType.ITEM,0,root.wireId)!!
        bridge.deactivate()
        val keys=mutableSetOf(note(id).taskList.single().localKey)
        repeat(3) {
            notes.updateNotes(note(id).copy(taskList=emptyList()));reopen()
            notes.updateNotes(note(id).copy(taskList=listOf(NoteTask(0,"same",false))));reopen()
            assertTrue(keys.add(note(id).taskList.single().localKey))
        }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_task_activation BEFORE INSERT ON nodus_captures BEGIN SELECT RAISE(ABORT,'crash'); END")
        assertThrows(Exception::class.java){runBlocking{bridge.activate("c")}}
        assertNull(bridge.active());assertNotNull(dao.connection("c")!!.inactiveGraph)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_task_activation")
        reopen();bridge.activate("c");compile()
        val replacement=dao.mappingByLocalRow("c",NodusResourceType.ITEM,0,root.wireId)!!
        assertNotEquals(original.wireId,replacement.wireId)
        assertTrue(dao.mapping("c",original.mappingId)!!.localRowDetached)
        val deletion=dao.intents("c").first { decodeDraft(it).method=="DELETE" && decodeDraft(it).path.endsWith(original.wireId) }
        val append=dao.intents("c").first { it.mappingId==replacement.mappingId }
        assertTrue(decodeDraft(append).dependencies.contains(deletion.intentId))
        assertNull(dao.outbox("c",append.intentId))
        assertFalse(NodusPlanning(db,"c").materialize(append))
        val copy=notes.insertNote(note(id).copy(id=0))
        assertNotEquals(note(id).taskList.single().localKey,note(copy).taskList.single().localKey)
    }

    @Test fun reminderRenameOmitsUneditedCanonicalFractionalDueInstant():Unit=runBlocking {
        val remote=NodusJson.decode(V2Note.serializer(),NodusFixtures.note).copy(id="fractional",revision="2",items=emptyList(),attachments=emptyList(),tagIds=emptyList(),notebookId=null,
            reminders=listOf(V2Reminder("r","before","2026-01-01T12:00:00.123Z",false)),primaryReminderId="r")
        dao.storePage("c",NodusStream.CHANGES,"0",null,"2","2",false,listOf(NodusSnapshot("c",NodusResourceType.NOTE,remote.id,"2",NodusJson.encode(V2Note.serializer(),remote).toByteArray(),false)),emptyList())
        NodusProjection(db,"c",bytes).project()
        val row=db.reminderDao.getAll().first().single()
        reminders.update(row.copy(name="after"));compile()
        val patch=drafts().single { it.method=="PATCH" && it.path.endsWith("/reminders/r") }
        assertFalse(wireString(patch.body).contains("dueAt"))
        assertTrue(wireString(patch.body).contains("after"))
        reminders.update(row.copy(name="after",date=row.date+60));compile()
        assertTrue(wireBodies(drafts()).contains("2026-01-01T12:01:00Z"))
    }

    @Test fun workerProjectionReconcilesAlarmsAndRestartRecoversBeforeStaleDelivery():Unit=runBlocking {
        val context=RuntimeEnvironment.getApplication() as android.content.Context
        val alarms=org.robolectric.Shadows.shadowOf(context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager)
        fun manager()=org.qosp.notes.ui.reminders.ReminderManager(context,reminders,notes,db)
        val future=java.time.Instant.now().epochSecond+3600
        var remote=NodusJson.decode(V2Note.serializer(),NodusFixtures.note).copy(id="alarm-note",revision="2",state=NoteState.LIVE,trashedAt=null,sourceTrashedAt=null,items=emptyList(),attachments=emptyList(),tagIds=emptyList(),notebookId=null,
            reminders=listOf(V2Reminder("alarm","remote",java.time.Instant.ofEpochSecond(future).toString(),false)),primaryReminderId="alarm")
        fun page() {transport.pages.add(V2Changes(listOf(V2Event.Note(remote.revision,"note",remote)),remote.revision,remote.revision,false))}
        var reconciliation=0
        val service=NodusSyncService(db,bridge,{config},bytes,transport,binary,{error("no source")},reconcileReminders={
            assertFalse(db.inTransaction())
            if(++reconciliation==2)error("process died after graph commit")
            manager().reconcile()
        })
        val controller=mockk<NodusController>()
        coEvery { controller.workerIfSelected() } coAnswers {service.run().workerResult()}
        val worker=org.qosp.notes.components.workers.NodusSyncWorker(context,mockk(relaxed=true),controller)
        page();assertEquals(androidx.work.ListenableWorker.Result.retry(),worker.doWork())
        assertTrue(alarms.scheduledAlarms.isEmpty());assertEquals(1,db.reminderDao.getAll().first().size)
        assertEquals(1,db.reminderDao.alarmBacklog())
        reopen()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_alarm_commit BEFORE INSERT ON reminder_alarm_state BEGIN SELECT RAISE(ABORT,'crash after OS scheduling'); END")
        assertEquals(androidx.work.ListenableWorker.Result.retry(),org.qosp.notes.components.workers.ReminderReconcileWorker(context,mockk(relaxed=true),manager()).doWork())
        assertEquals(1,alarms.scheduledAlarms.size);assertTrue(db.reminderDao.alarmStates().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_alarm_commit")
        reopen()
        val recovery=org.qosp.notes.components.workers.ReminderReconcileWorker(context,mockk(relaxed=true),manager())
        assertEquals(androidx.work.ListenableWorker.Result.success(),recovery.doWork())
        assertEquals(0,db.reminderDao.alarmBacklog())
        val row=db.reminderDao.getAll().first().single()
        assertEquals(future*1000,alarms.scheduledAlarms.single().triggerAtTime)
        fun currentService()=NodusSyncService(db,bridge,{config},bytes,transport,binary,{error("no source")},reconcileReminders={manager().reconcile()},now={System.currentTimeMillis()+120000})
        coEvery { controller.workerIfSelected() } coAnswers {currentService().run().workerResult()}
        remote=remote.copy(revision="3",reminders=listOf(remote.reminders.single().copy(dueAt=java.time.Instant.ofEpochSecond(future+600).toString())))
        page();worker.doWork()
        assertEquals((future+600)*1000,alarms.scheduledAlarms.single().triggerAtTime)
        manager().sendNotification(row.id,row.noteId,future,"remote",row.alarmFingerprint)
        assertEquals(future+600,db.reminderDao.getById(row.id).first()!!.date)
        remote=remote.copy(revision="4",reminders=listOf(remote.reminders.single().copy(deleted=true)),primaryReminderId=null)
        page();worker.doWork()
        assertTrue(alarms.scheduledAlarms.isEmpty());assertTrue(db.reminderDao.alarmStates().isEmpty())
        manager().sendNotification(row.id,row.noteId,future+600,"remote")
        assertNull(db.reminderDao.getById(row.id).first())
        val trashed=notes.insertNote(Note(title="trashed",isDeleted=true))
        val expired=reminders.insert(Reminder("must not fire",trashed,0))
        manager().reconcile();manager().sendNotification(expired,trashed,0,"must not fire")
        assertNotNull(db.reminderDao.getById(expired).first())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        val live=notes.insertNote(Note(title="fingerprint"))
        val versioned=reminders.insert(Reminder("same-second update",live,0,alarmFingerprint="new-version"))
        manager().sendNotification(versioned,live,0,"same-second update","old-version")
        assertNotNull(db.reminderDao.getById(versioned).first())
        assertEquals(0,db.reminderDao.consumeAlarm(versioned,live,0,"same-second update","old-version",Long.MAX_VALUE))
    }

}

package org.qosp.notes.data.sync.nodus.engine

import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.Tag
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.model.NoteTagJoin
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.storage.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

internal class FakeNodusTransport : NodusEngineTransport {
    data class Get(val path: String, val query: Map<String, String>)
    val gets = mutableListOf<Get>()
    val sent = mutableListOf<NodusOutbox>()
    var capabilitiesBody=NodusFixtures.capabilities
    val pages = ArrayDeque<V2Changes>()
    val conflictPages = ArrayDeque<V2Conflicts>()
    var sendHandler: suspend (NodusOutbox) -> NodusHttpResult = { op ->
        val path = op.path.split('/')
        receipt(path[3].dropLast(1), path[4], "2")
    }
    override suspend fun get(configuration: NodusConfiguration, path: String, query: Map<String, String>): NodusHttpResult {
        gets += Get(path, query.toMap())
        return when (path) {
            "/api/v2/capabilities" -> NodusHttpResult(200, capabilitiesBody.toByteArray())
            "/api/v2/changes" -> NodusHttpResult(200, NodusJson.encode(V2Changes.serializer(), pages.removeFirst()).toByteArray())
            "/api/v2/conflicts" -> NodusHttpResult(200, NodusJson.encode(V2Conflicts.serializer(), conflictPages.removeFirst()).toByteArray())
            else -> error("Unexpected read")
        }
    }
    override suspend fun send(configuration: NodusConfiguration, operation: NodusOutbox): NodusHttpResult {
        assertEquals(operation.credentialEpoch, configuration.credentialEpoch)
        sent += operation.copy(body = operation.body?.clone())
        return sendHandler(operation)
    }
    companion object {
        fun receipt(type: String, id: String, revision: String) = NodusHttpResult(200, """{"resourceType":"$type","resourceId":"$id","revision":"$revision"}""".toByteArray())
        fun clash(id: String = "clash", revision: String = "2") = NodusHttpResult(409, """{"error":"conflict","code":"durable_conflict","conflictId":"$id","revision":"$revision"}""".toByteArray())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NodusEngineTest {
    private lateinit var db: AppDatabase
    private lateinit var fake: FakeNodusTransport
    private lateinit var config: NodusConfiguration
    private lateinit var byteDirectory: java.io.File
    private lateinit var byteStore: NodusByteStore
    private lateinit var blobSource: String
    private var clock = 1000L
    private val dao get() = db.nodusDao
    private fun engine() = NodusCoordinator(db, "c", { config }, fake, byteStore) { clock }
    private fun remote(revision: String = "1") = NodusJson.decode(V2Note.serializer(), NodusFixtures.note).copy(
        id="n", revision=revision, kind=NoteKind.CHECKLIST, title="original", state=NoteState.LIVE, trashedAt=null, sourceTrashedAt=null,
        items=listOf(Item("a","first",false,0,revision,false),Item("b","second",true,1,revision,false)),
        attachments=emptyList(), reminders=emptyList(), primaryReminderId=null, archived=false, hidden=false,
        pinned=false, markdownEnabled=true, color=NoteColor.DEFAULT)
    private fun page(note: V2Note, until: String = note.revision, more: Boolean = false) = V2Changes(listOf(V2Event.Note(note.revision,"note",note)),note.revision,until,more)
    private suspend fun root(): NodusMapping = dao.mappings("c").first { it.resourceType==NodusResourceType.NOTE && it.wireId=="n" }
    private suspend fun local(): Note = requireNotNull(db.noteDao.getById(requireNotNull(root().localRowId)).first())
    private fun body(op: NodusOutbox) = NodusJson.format.parseToJsonElement(wireString(requireNotNull(op.body))).jsonObject
    @Before fun setup(): Unit = runBlocking {
        byteDirectory=java.nio.file.Files.createTempDirectory("nodus-graph-bytes").toFile()
        byteStore=NodusByteStore(byteDirectory, publish={ from, to -> java.nio.file.Files.createLink(to.toPath(), from.toPath()); Unit }) { java.nio.channels.FileChannel.open(it.toPath(),java.nio.file.StandardOpenOption.READ).use { channel -> channel.force(true) } }
        db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),AppDatabase::class.java).allowMainThreadQueries().build()
        config=NodusConfiguration(NodusOrigin.parse("https://notes.example/"),"synthetic","epoch","d")
        fake=FakeNodusTransport()
        dao.createConnection(NodusConnection("c",config.origin.toString(),"owner","d","epoch",realmId="realm-fixture"))
    }
    @After fun close() { db.close(); byteDirectory.deleteRecursively() }
    private suspend fun bootstrap(note: V2Note = remote()) {
        fake.pages.add(page(note)); engine().pullChanges()
    }
    private suspend fun readyBlob() {
        val file=byteStore.install({java.io.ByteArrayInputStream(byteArrayOf(0))})
        blobSource=byteStore.file(file.id).toURI().toString()
        dao.addMappings("c",listOf(NodusMapping("c","blob-map",NodusResourceType.BLOB,"","blob","blob-lifetime",null)))
        dao.storeReadiness(NodusBlobTransfer("c","blob","1",file.sha256,blobSource,NodusReadiness.AVAILABLE,null),emptyList())
        val blob=V2Blob("blob","1",file.sha256,"image/png",BlobState.READY,"1","2000-01-01T00:00:00Z")
        dao.storeBlobMetadata(NodusSnapshot("c",NodusResourceType.BLOB,"blob","1",NodusJson.encode(V2Blob.serializer(),blob).toByteArray(),false))
    }

    @Test fun paginationKeepsWatermarksGapsAndNeverDeletesMissingNotes(): Unit = runBlocking {
        fake.pages.add(page(remote("2"),"9",true))
        fake.pages.add(V2Changes(emptyList(),"9","9",false))
        val result=engine().pullChanges(2)
        assertFalse(result.hasMore)
        val reads=fake.gets.filter { it.path.endsWith("changes") }
        assertEquals(mapOf("after" to "0","limit" to "100"),reads[0].query)
        assertEquals(mapOf("after" to "2","until" to "9","limit" to "100"),reads[1].query)
        assertEquals("9",dao.cursor("c","v2",NodusStream.CHANGES)!!.cursor)
        assertEquals("original",local().title)
        fake.pages.add(V2Changes(emptyList(),"12","12",false))
        engine().pullChanges()
        assertEquals("original",local().title)
        assertFalse(local().isDeleted)
        assertNull(dao.cursor("c","v1",NodusStream.CHANGES))
    }

    @Test fun malformedLaterWatermarkCannotCommitPageOrLocalGraph(): Unit = runBlocking {
        fake.pages.add(page(remote("2"),"9",true))
        engine().pullChanges()
        fake.pages.add(page(remote("10").copy(title="wrong watermark"),"11",false))
        assertThrows(Exception::class.java) { runBlocking { engine().pullChanges() } }
        assertEquals("2",dao.cursor("c","v2",NodusStream.CHANGES)!!.cursor)
        assertEquals("9",dao.cursor("c","v2",NodusStream.CHANGES)!!.until)
        assertEquals("original",local().title)
    }

    @Test fun fullGraphRoundTripPreservesRawInactiveContentSourcePrecisionAndDeviceFlags(): Unit = runBlocking {
        readyBlob()
        db.noteDao.insert(Note(id=11,isCompactPreview=true,screenAlwaysOn=true).toEntity())
        dao.addMappings("c",listOf(NodusMapping("c","root",NodusResourceType.NOTE,"","n","local-lifetime",11)))
        val tag=NodusJson.decode(V2Tag.serializer(),NodusFixtures.tag).copy(id="t",revision="2",name=" duplicated ")
        val notebook=V2Notebook("book"," duplicated ","3",tag.created,tag.updated,false)
        val note=remote("4").copy(kind=NoteKind.TEXT,title="  title\n",text="# raw\n![inline](content://verbatim)",archived=true,pinned=true,hidden=true,markdownEnabled=false,color=NoteColor.CYAN,
            state=NoteState.TRASH,trashedAt="2001-01-01T00:00:00.123456789Z",sourceTrashedAt="1999-01-01T00:00:00.987654321Z",
            authoredAt="1998-01-01T00:00:00.123456789Z",editedAt="2000-01-01T00:00:00.987654321Z",tagIds=listOf("t"),notebookId="book",
            reminders=listOf(V2Reminder("r1","old named intent","1999-01-01T00:00:00.123456789Z",false),V2Reminder("r2","second","1998-01-01T00:00:00Z",false)),primaryReminderId="r2",
            attachments=listOf(V2Attachment("ref","blob",AttachmentKind.AUDIO," label ","../../raw.mp3",0,false)))
        fake.pages.add(V2Changes(listOf(V2Event.Tag("2","tag",tag),V2Event.Notebook("3","notebook",notebook),V2Event.Note("4","note",note)),"4","4",false))
        val pulled=engine().pullChanges()
        assertTrue(pulled.blocked.isEmpty())
        val local=local()
        assertTrue(local.isCompactPreview && local.screenAlwaysOn)
        assertFalse(local.isLocalOnly)
        assertEquals(note.text,local.content)
        assertEquals(2,local.taskList.size) // Inactive checklist representation is not cleared.
        assertEquals(2,local.reminders.size)
        assertEquals(blobSource,local.attachments.single().path)
        val ref=dao.mappings("c").single { it.resourceType==NodusResourceType.ATTACHMENT }
        val encoded=engine().graphs.createBody(root(),local,listOf(ref.mappingId),note.primaryReminderId,note)
        val json=NodusJson.format.parseToJsonElement(NodusJson.encode(V2CreateNote.serializer(),encoded)).jsonObject
        assertEquals(JsonPrimitive(note.text),json["text"])
        assertEquals(JsonPrimitive(note.authoredAt),json["authoredAt"])
        assertEquals(JsonPrimitive(note.editedAt),json["editedAt"])
        assertEquals(JsonPrimitive(note.sourceTrashedAt),json["sourceTrashedAt"])
        assertEquals(JsonPrimitive("r2"),json["primaryReminderId"])
        assertEquals(JsonPrimitive("cyan"),json["color"])
        assertEquals(JsonPrimitive("trash"),json["state"])
        assertFalse(json.toString().contains(blobSource))
        for (field in listOf("isLocalOnly","localOnly","screenAlwaysOn","compactPreview","alarmState")) assertFalse(json.containsKey(field))
        assertArrayEquals(NodusJson.encode(V2Note.serializer(),note).toByteArray(),dao.tracking("c",root().mappingId)!!.baseBody)
    }

    @Test fun missingDependencyRetainsWholeCanonicalGraphWithoutPartialLocalSuccess(): Unit = runBlocking {
        val note=remote().copy(attachments=listOf(V2Attachment("a","blob",AttachmentKind.IMAGE,"label","file",0,false)))
        fake.pages.add(page(note))
        val result=engine().pullChanges()
        assertTrue(result.blocked.any { it.contains("missing_blob") })
        assertTrue(dao.mappings("c").none { it.resourceType==NodusResourceType.NOTE })
        assertNotNull(dao.snapshot("c",NodusResourceType.NOTE,"n"))
        assertEquals("1",dao.cursor("c","v2",NodusStream.CHANGES)!!.cursor)
        readyBlob()
        assertEquals(1,engine().projectPending().applied)
        assertEquals(1,local().attachments.size)
    }

    @Test fun reminderConsumptionIsLocalAndChangedSharedIntentProjectsAgain(): Unit = runBlocking {
        val reminder=V2Reminder("r","past named","1999-01-01T00:00:00.123456789Z",false)
        bootstrap(remote().copy(reminders=listOf(reminder),primaryReminderId="r"))
        val row=local().reminders.single()
        db.reminderDao.deleteById(row.id) // Firing, expiry or boot cleanup: no shared mutation.
        fake.pages.add(page(remote("2").copy(title="unrelated",reminders=listOf(reminder),primaryReminderId="r")))
        engine().pullChanges()
        assertTrue(local().reminders.isEmpty())
        assertTrue(dao.pendingOperations("c",100).isEmpty())
        val base=NodusJson.decode(V2Note.serializer(),wireString(dao.tracking("c",root().mappingId)!!.baseBody!!))
        val create=engine().graphs.createBody(root(),local(),emptyList(),"r",base)
        assertEquals(1,(create.reminders as WireField.Present).value.size)
        assertEquals(reminder.dueAt,(create.reminders as WireField.Present).value.single().dueAt)
        fake.pages.add(page(remote("3").copy(reminders=listOf(reminder.copy(name="changed")),primaryReminderId="r")))
        engine().pullChanges()
        assertEquals(row.id,local().reminders.single().id)
        assertEquals("changed",local().reminders.single().name)
    }

    @Test fun offlineEnrollmentAllocatesRandomIdentitiesAndSendsOrganizationFirst(): Unit = runBlocking {
        db.notebookDao.insert(Notebook("same",7)); db.tagDao.insert(Tag("same",8)); db.tagDao.insert(Tag("same",9))
        db.noteDao.insert(Note(id=11,title="offline",content="# text",notebookId=7).toEntity())
        db.noteTagDao.insert(NoteTagJoin(8,11),NoteTagJoin(9,11))
        val engine=engine()
        val root=engine.graphs.enroll(11,emptyList())
        assertTrue(fake.gets.isEmpty() && fake.sent.isEmpty())
        val mappings=dao.mappings("c")
        assertEquals(4,mappings.size)
        assertEquals(4,mappings.map { it.wireId }.distinct().size)
        assertTrue(mappings.all { it.wireId.length==36 && it.wireId!=it.localRowId.toString() })
        fake.sendHandler={ op -> FakeNodusTransport.receipt(op.path.split('/')[3].dropLast(1),op.path.split('/')[4],"1") }
        val first=engine.drain()
        assertEquals(3,first.completedRequests)
        assertTrue(fake.sent.all { "/tags/" in it.path || "/notebooks/" in it.path })
        val tags=mappings.filter { it.resourceType==NodusResourceType.TAG }
        val notebook=mappings.single { it.resourceType==NodusResourceType.NOTEBOOK }
        val time="2000-01-01T00:00:00Z"
        fake.pages.add(V2Changes(listOf(
            V2Event.Tag("1","tag",V2Tag(tags[0].wireId,"same","1",time,time,false)),
            V2Event.Tag("2","tag",V2Tag(tags[1].wireId,"same","2",time,time,false)),
            V2Event.Notebook("3","notebook",V2Notebook(notebook.wireId,"same","3",time,time,false))),"3","3",false))
        engine.pullChanges(); engine.drain()
        val note=fake.sent.last()
        assertEquals("/api/v2/notes/${dao.mapping("c",root)!!.wireId}",note.path)
        assertEquals(2,body(note).getValue("tagIds").jsonArray.size)
        assertEquals(JsonPrimitive(notebook.wireId),body(note)["notebookId"])
    }

    @Test fun independentItemsKeepOwnRevisionsAndConversionUsesBarrierPredecessor(): Unit = runBlocking {
        bootstrap()
        val engine=engine()
        val maps=dao.mappings("c")
        val a=maps.single { it.resourceType==NodusResourceType.ITEM && it.wireId=="a" }
        val b=maps.single { it.resourceType==NodusResourceType.ITEM && it.wireId=="b" }
        engine.planning.enqueue(a.mappingId) { V2Toggle(it.deviceId,it.requestId,it.revision,true) }
        engine.planning.enqueue(b.mappingId) { V2ItemEdit(it.deviceId,it.requestId,it.revision,text=WireField.Present("independent")) }
        assertTrue(dao.pendingOperations("c",100).all { body(it)["expectedRevision"]==JsonPrimitive("1") })
        var revision=1
        fake.sendHandler={ FakeNodusTransport.receipt("note","n",(++revision).toString()) }
        engine.drain()
        fake.pages.add(page(remote("3").copy(items=listOf(Item("a","first",true,0,"2",false),Item("b","independent",true,1,"3",false)))))
        engine.pullChanges()
        assertTrue(local().taskList.first().isDone)
        assertEquals("independent",local().taskList[1].content)
        engine.planning.enqueue(root().mappingId) { V2Content(it.deviceId,it.requestId,it.revision,NoteKind.TEXT) }
        engine.planning.enqueue(root().mappingId) { V2Content(it.deviceId,it.requestId,it.revision,NoteKind.CHECKLIST) }
        engine.planning.enqueue(a.mappingId) { V2Toggle(it.deviceId,it.requestId,it.revision,false) }
        engine.drain()
        val converted=fake.sent.takeLast(3)
        assertEquals(listOf("3","4","5"),converted.map { body(it).getValue("expectedRevision").jsonPrimitive.content })
        assertFalse(body(converted[0]).containsKey("items"))
        assertFalse(body(converted[0]).containsKey("text"))
    }

    @Test fun exactReplayWaitsForRetryAfterAndCredentialRotationNeverResubmits(): Unit = runBlocking {
        bootstrap()
        val engine=engine()
        val operation=engine.planning.enqueue(root().mappingId,local().copy(title="  offline\n")) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("  offline\n")) }
        val prepared=dao.outbox("c",operation)!!.body!!.clone()
        fake.sendHandler={ op ->
            assertArrayEquals(prepared,dao.outbox("c",op.operationId)!!.body)
            assertEquals(NodusOutboxState.SENT,dao.outbox("c",op.operationId)!!.state)
            throw IOException("simulated lost response")
        }
        engine.drain(); assertEquals(1,fake.sent.size)
        engine().drain(); assertEquals(1,fake.sent.size)
        clock=2001
        fake.sendHandler={ NodusHttpResult(429,"""{"error":"limited"}""".toByteArray(),"60") }
        val limited=engine().drain()
        assertEquals(62001L,limited.retryAtMillis)
        clock=62000; engine().drain(); assertEquals(2,fake.sent.size)
        clock=62002
        fake.sendHandler={ FakeNodusTransport.receipt("note","n","2") }
        engine().drain()
        assertEquals(3,fake.sent.size)
        assertTrue(fake.sent.all { it.operationId==operation && it.requestId==fake.sent[0].requestId && it.body!!.contentEquals(prepared) })
        val second=engine.planning.enqueue(root().mappingId) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("later")) }
        fake.sendHandler={ throw IOException("lost") }; engine.drain()
        config=NodusConfiguration(config.origin,"rotated","other-epoch","d")
        dao.rotateCredentialEpoch("c","epoch","other-epoch")
        clock+=10000
        val count=fake.sent.size
        engine().drain()
        assertEquals(count,fake.sent.size)
        assertEquals(NodusOutboxState.UNKNOWN,dao.outbox("c",second)!!.state)
        assertEquals("manual:credential_epoch_changed",dao.latestEvidence("c",second)!!.errorCode)
    }

    @Test fun localEditingDoesNotWaitForNetworkMutexAndQueuesOwnRevisionChain(): Unit = runBlocking {
        bootstrap()
        val engine=engine()
        engine.planning.enqueue(root().mappingId) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("first")) }
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        fake.sendHandler={ started.complete(Unit); release.await(); FakeNodusTransport.receipt("note","n","2") }
        val sending=async { engine.drain(1) }
        started.await()
        val next=withTimeout(2000) {
            engine.planning.enqueue(root().mappingId,local().copy(title="offline second")) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("offline second")) }
        }
        assertEquals("offline second",local().title)
        assertNotNull(dao.intent("c",next))
        assertNull(dao.outbox("c",next)) // Depends on first operation; no guessed revision.
        release.complete(Unit); sending.await()
        fake.sendHandler={ FakeNodusTransport.receipt("note","n","3") }
        engine.drain(1)
        assertEquals(JsonPrimitive("2"),body(fake.sent.last())["expectedRevision"])
    }

    @Test fun privateLocalForkNeverUploadsAndRemotePurgeCannotEraseDirtyDraft(): Unit = runBlocking {
        bootstrap()
        val engine=engine()
        val draft=local().copy(title="private draft",isLocalOnly=true,isCompactPreview=true,screenAlwaysOn=true)
        engine.planning.enqueue(root().mappingId,draft) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present(draft.title)) }
        assertTrue(dao.pendingOperations("c",100).isEmpty())
        fake.pages.add(page(remote("2").copy(state=NoteState.PURGED)))
        val result=engine.pullChanges()
        assertTrue(result.blocked.isNotEmpty())
        assertEquals(draft,local())
        assertEquals(NoteState.PURGED,NodusJson.decode(V2Note.serializer(),wireString(dao.snapshot("c",NodusResourceType.NOTE,"n")!!.body)).state)
        engine.drain()
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun trashRestorePurgeUseAggregateBarriersAndRetainSafetyState(): Unit = runBlocking {
        bootstrap()
        val engine=engine()
        engine.planning.enqueue(root().mappingId) { V2Trash(it.deviceId,it.requestId,it.revision,WireField.Present("1999-01-01T00:00:00.123456789Z")) }
        engine.planning.enqueue(root().mappingId,lifecycle=LifecycleAction.RESTORE) { V2Lifecycle(it.deviceId,it.requestId,it.revision) }
        engine.planning.enqueue(root().mappingId) { V2Trash(it.deviceId,it.requestId,it.revision) }
        engine.planning.enqueue(root().mappingId,lifecycle=LifecycleAction.PURGE) { V2Lifecycle(it.deviceId,it.requestId,it.revision) }
        var rev=1
        fake.sendHandler={ FakeNodusTransport.receipt("note","n",(++rev).toString()) }
        engine.drain()
        assertEquals(listOf("trash","restore","trash","purge"),fake.sent.map { it.path.substringAfterLast('/') })
        assertEquals(listOf("1","2","3","4"),fake.sent.map { body(it).getValue("expectedRevision").jsonPrimitive.content })
        fake.pages.add(page(remote("5").copy(state=NoteState.PURGED)))
        engine.pullChanges()
        assertTrue(local().isDeleted && local().isHidden)
        assertNotNull(dao.mapping("c",root().mappingId))
        fake.sent.forEach { assertNotNull(dao.outbox("c",it.operationId)); assertEquals(1,dao.evidence("c",it.operationId).size) }
    }
    private suspend fun conflictedDraft(): Pair<NodusOutbox,V2Conflict> {
        bootstrap()
        val engine=engine()
        engine.planning.enqueue(root().mappingId,local().copy(title="rejected draft")) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("rejected draft")) }
        fake.sendHandler={ FakeNodusTransport.clash(revision="9000") }
        engine.drain()
        val operation=fake.sent.single()
        val proposal=V2Proposal("v2",operation.method,operation.path,NodusJson.decode(V2EditNote.serializer(),wireString(operation.body!!)),wireString(operation.body))
        val conflict=V2Conflict("clash",ConflictState.PENDING,"","revision_mismatch",proposal,remote("3").copy(title="remote"))
        fake.conflictPages.add(V2Conflicts(listOf(conflict),"9000","9000",false))
        engine.discoverConflicts()
        assertNull(dao.maximumAcknowledgedRevision("c",NodusResourceType.NOTE,"n"))
        fake.pages.add(page(remote("3").copy(title="remote")))
        assertTrue(engine.pullChanges().blocked.isNotEmpty())
        assertEquals("rejected draft",local().title)
        return operation to conflict
    }

    @Test fun discardResolvesProposalOnlyAndRetainsBlockedDraftAndExactEvidence(): Unit = runBlocking {
        val (original,conflict)=conflictedDraft()
        val engine=engine()
        val discard=engine.discardConflict(root().mappingId,"clash")
        fake.sendHandler={ NodusHttpResult(200,"""{"conflictId":"clash","revision":"9001","state":"discarded"}""".toByteArray()) }
        engine.drain()
        assertNull(dao.maximumAcknowledgedRevision("c",NodusResourceType.NOTE,"n"))
        assertNull(dao.latestEvidence("c",discard)!!.resourceRevision)
        fake.conflictPages.add(V2Conflicts(listOf(V2Conflict("clash",ConflictState.DISCARDED,"",conflict.reason,conflict.operation,conflict.snapshot)),"9001","9001",false))
        engine.discoverConflicts(refresh=true)
        assertEquals("discarded",dao.conflict("c","clash")!!.state)
        assertEquals("0",fake.gets.last { it.path.endsWith("conflicts") }.query["after"])
        assertTrue(engine.projectPending().blocked.any { it.reason=="conflicted_local_draft" })
        assertEquals("rejected draft",local().title)
        assertArrayEquals(original.body,dao.outbox("c",original.operationId)!!.body)
        assertEquals(NodusEvidenceKind.CONFLICT,dao.latestEvidence("c",original.operationId)!!.kind)
    }

    @Test fun applyUnblocksOnlyAfterAuthoritativeSnapshotAtReceiptRevisionWithoutRebase(): Unit = runBlocking {
        val (original,_)=conflictedDraft()
        val engine=engine()
        val apply=engine.applyConflict(root().mappingId,"clash","3")
        assertEquals(JsonPrimitive("3"),body(dao.outbox("c",apply)!!)["expectedRevision"])
        fake.sendHandler={ FakeNodusTransport.receipt("note","n","9002") }
        engine.drain()
        assertEquals("9002",dao.maximumAcknowledgedRevision("c",NodusResourceType.NOTE,"n"))
        assertTrue(engine.projectPending().blocked.any { it.reason=="awaiting_applied_snapshot" })
        assertEquals("1",dao.tracking("c",root().mappingId)!!.baseRevision)
        fake.pages.add(page(remote("9002").copy(title="authoritative applied")))
        assertTrue(engine.pullChanges().blocked.isEmpty())
        assertEquals("authoritative applied",local().title)
        assertEquals("9002",dao.tracking("c",root().mappingId)!!.baseRevision)
        assertArrayEquals(original.body,dao.outbox("c",original.operationId)!!.body)
    }

    @Test fun organizationResourceInUseConflictKeepsMembershipAndSeparateConflictStream(): Unit = runBlocking {
        val time="2000-01-01T00:00:00Z"
        val tag=V2Tag("t","label","1",time,time,false)
        fake.pages.add(V2Changes(listOf(V2Event.Tag("1","tag",tag),V2Event.Note("2","note",remote("2").copy(tagIds=listOf("t")))),"2","2",false))
        val engine=engine(); engine.pullChanges()
        val map=dao.mappingByWire("c",NodusResourceType.TAG,"t")!!
        engine.planning.enqueue(map.mappingId) { V2OrganizationDelete(it.deviceId,it.requestId,it.revision) }
        fake.sendHandler={ NodusHttpResult(409,"""{"error":"resource_in_use","code":"durable_conflict","conflictId":"in-use","revision":"3"}""".toByteArray()) }
        engine.drain()
        val op=fake.sent.single()
        val proposal=V2Proposal("v2","DELETE",op.path,NodusJson.decode(V2OrganizationDelete.serializer(),wireString(op.body!!)),wireString(op.body))
        fake.conflictPages.add(V2Conflicts(listOf(V2Conflict("in-use",ConflictState.PENDING,"","resource_in_use",proposal,tag)),"3","3",false))
        engine.discoverConflicts()
        assertEquals("resource_in_use",NodusJson.decode(V2Conflict.serializer(),wireString(dao.conflict("c","in-use")!!.body)).reason)
        assertEquals("2",dao.cursor("c","v2",NodusStream.CHANGES)!!.cursor)
        assertEquals("3",dao.cursor("c","v2",NodusStream.CONFLICTS)!!.cursor)
        assertEquals("label",local().tags.single().name)
        assertNull(dao.maximumAcknowledgedRevision("c",NodusResourceType.TAG,"t"))
        assertTrue(engine.projectPending().blocked.isEmpty()) // No canonical change to project.
    }

    @Test fun outOfOrderAcknowledgementsCannotHideCanonicalFloorBehindOldReceiptHistory(): Unit = runBlocking {
        val initial=remote().copy(items=(0..105).map { Item("i$it","task $it",false,it,"1",false) })
        bootstrap(initial)
        val engine=engine()
        val draft=local().copy(taskList=local().taskList.map { it.copy(isDone=true) })
        val operations=(0..105).map { index ->
            val mapping=dao.mappingByWire("c",NodusResourceType.ITEM,"i$index","n")!!
            engine.planning.enqueue(mapping.mappingId,if (index==0) draft else null) { V2Toggle(it.deviceId,it.requestId,it.revision,true) }
        }
        // Independent item requests were accepted in order; receipt replay returns the
        // newest acknowledgement first, followed by >100 older acknowledgements.
        for (index in listOf(105)+(0..104)) {
            val operation=operations[index]
            if (dao.outbox("c",operation)==null) {
                // Seed the already-prepared replay record; this regression targets receipt
                // ordering independently of the coordinator's bounded planning budget.
                val intent=dao.intent("c",operation)!!
                val draftBody=decodeDraft(intent)
                dao.prepare(NodusOutbox("c",operation,operation,intent.mappingId,intent.generation,"v2",draftBody.method,draftBody.path,"application/json",draftBody.body,null,null,null,"d",draftBody.requestId,"epoch",NodusOutboxState.PREPARED))
            }
            val response=FakeNodusTransport.receipt("note","n",(index+2).toString())
            dao.recordOutcome(NodusEvidence("c",newNodusId(),operation,"epoch",NodusEvidenceKind.RECEIPT,200,response.body,null,null))
            dao.retire("c",operation)
        }
        assertEquals("107",dao.maximumAcknowledgedRevision("c",NodusResourceType.NOTE,"n"))
        assertNull(dao.maximumAcknowledgedRevision("c",NodusResourceType.ITEM,"i105"))
        fake.pages.add(page(initial.copy(revision="106")))
        assertTrue(engine.pullChanges().blocked.any { it.endsWith("awaiting_acknowledged_snapshot") })
        assertTrue(local().taskList.all { it.isDone })
        fake.pages.add(page(initial.copy(revision="107",items=initial.items.mapIndexed { i,item -> item.copy(checked=true,revision=(i+2).toString()) })))
        assertTrue(engine.pullChanges().blocked.isEmpty())
        assertEquals("107",dao.tracking("c",root().mappingId)!!.baseRevision)
    }

    @Test fun metadataAndSameKindContentWithoutItemChangesLeaveIndependentItemRevisionsAlone(): Unit = runBlocking {
        bootstrap(); val engine=engine()
        val a=dao.mappingByWire("c",NodusResourceType.ITEM,"a","n")!!
        val b=dao.mappingByWire("c",NodusResourceType.ITEM,"b","n")!!
        engine.planning.enqueue(root().mappingId) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("metadata")) }
        engine.planning.enqueue(a.mappingId) { V2Toggle(it.deviceId,it.requestId,it.revision,true) }
        var rev=1; fake.sendHandler={ FakeNodusTransport.receipt("note","n",(++rev).toString()) }
        engine.drain()
        engine.planning.enqueue(root().mappingId) { V2Content(it.deviceId,it.requestId,it.revision,NoteKind.CHECKLIST) }
        engine.planning.enqueue(b.mappingId) { V2Toggle(it.deviceId,it.requestId,it.revision,false) }
        engine.drain()
        assertEquals(listOf("1","1","3","1"),fake.sent.map { body(it).getValue("expectedRevision").jsonPrimitive.content })
    }

    @Test fun sameKindContentOnlyAdvancesProvenChangedItems(): Unit = runBlocking {
        bootstrap(); val engine=engine()
        engine.planning.enqueue(root().mappingId) { V2Content(it.deviceId,it.requestId,it.revision,NoteKind.CHECKLIST,
            items=WireField.Present(listOf(ItemInput("a",WireField.Present("changed"),WireField.Present(false)),ItemInput("b",WireField.Present("second"),WireField.Present(true))))) }
        for (id in listOf("a","b")) engine.planning.enqueue(dao.mappingByWire("c",NodusResourceType.ITEM,id,"n")!!.mappingId) { V2Toggle(it.deviceId,it.requestId,it.revision,true) }
        var rev=1; fake.sendHandler={ FakeNodusTransport.receipt("note","n",(++rev).toString()) }
        engine.drain()
        assertEquals(listOf("1","2","1"),fake.sent.map { body(it).getValue("expectedRevision").jsonPrimitive.content })
    }

    @Test fun reorderOnlyAdvancesMovedItems(): Unit = runBlocking {
        bootstrap(remote().copy(items=remote().items+Item("third","fixed",false,2,"1",false))); val engine=engine()
        engine.planning.enqueue(root().mappingId) { V2Order(it.deviceId,it.requestId,it.revision,WireField.Present(listOf("b","a","third"))) }
        for (id in listOf("a","b","third")) engine.planning.enqueue(dao.mappingByWire("c",NodusResourceType.ITEM,id,"n")!!.mappingId) { V2Toggle(it.deviceId,it.requestId,it.revision,true) }
        var rev=1; fake.sendHandler={ FakeNodusTransport.receipt("note","n",(++rev).toString()) }
        engine.drain()
        assertEquals(listOf("1","2","2","1"),fake.sent.map { body(it).getValue("expectedRevision").jsonPrimitive.content })
    }

    @Test fun appendUsesAggregateExpectationThenOwnEditsAndDeletionUseItemReceipts(): Unit = runBlocking {
        bootstrap(); val engine=engine()
        val child=engine.planning.allocate(NodusResourceType.ITEM,"new-local-task",2,"n")
        engine.planning.enqueue(root().mappingId) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("metadata")) }
        engine.planning.enqueue(child.mappingId) { V2Append(it.deviceId,it.requestId,it.revision,child.wireId,"new") }
        engine.planning.enqueue(child.mappingId) { V2Toggle(it.deviceId,it.requestId,it.revision,true) }
        engine.planning.enqueue(child.mappingId) { V2ChildDelete(it.deviceId,it.requestId,it.revision) }
        var rev=1; fake.sendHandler={ FakeNodusTransport.receipt("note","n",(++rev).toString()) }
        engine.drain()
        assertEquals(listOf("1","2","3","4"),fake.sent.map { body(it).getValue("expectedRevision").jsonPrimitive.content })
        assertEquals("DELETE",fake.sent.last().method)
        assertEquals(JsonPrimitive(child.wireId),body(fake.sent[1])["itemId"])
    }

    @Test fun trashAndRestoreInvalidateEveryLiveItemBeforeDependentToggle(): Unit = runBlocking {
        bootstrap(); val engine=engine()
        engine.planning.enqueue(root().mappingId) { V2Trash(it.deviceId,it.requestId,it.revision) }
        engine.planning.enqueue(root().mappingId,lifecycle=LifecycleAction.RESTORE) { V2Lifecycle(it.deviceId,it.requestId,it.revision) }
        engine.planning.enqueue(dao.mappingByWire("c",NodusResourceType.ITEM,"a","n")!!.mappingId) { V2Toggle(it.deviceId,it.requestId,it.revision,true) }
        var rev=1; fake.sendHandler={ FakeNodusTransport.receipt("note","n",(++rev).toString()) }
        engine.drain()
        assertEquals(listOf("1","2","3"),fake.sent.map { body(it).getValue("expectedRevision").jsonPrimitive.content })
    }

    @Test fun unknownItemPredecessorBlocksSendButNotDurableOfflineEditing(): Unit = runBlocking {
        bootstrap(); val engine=engine()
        val child=dao.mappingByWire("c",NodusResourceType.ITEM,"a","n")!!
        val first=engine.planning.enqueue(child.mappingId) { V2Toggle(it.deviceId,it.requestId,it.revision,true) }
        fake.sendHandler={ throw IOException("lost") }; engine.drain()
        val next=engine.planning.enqueue(child.mappingId,local().copy(title="still editing")) { V2ItemEdit(it.deviceId,it.requestId,it.revision,text=WireField.Present("later")) }
        assertNotNull(dao.intent("c",next)); assertNull(dao.outbox("c",next)); assertEquals("still editing",local().title)
        clock=2001; fake.sendHandler={ FakeNodusTransport.clash() }; engine.drain()
        assertTrue(fake.sent.all { it.operationId==first })
        assertNull(dao.outbox("c",next))
    }

    @Test fun truncatedUnprovableHistoryWaitsForAuthoritativeUnchangedItemWithoutRemoteRebase(): Unit = runBlocking {
        bootstrap(); val engine=engine()
        repeat(102) { index -> engine.planning.enqueue(root().mappingId) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("metadata $index")) } }
        val next=engine.planning.enqueue(dao.mappingByWire("c",NodusResourceType.ITEM,"a","n")!!.mappingId) { V2Toggle(it.deviceId,it.requestId,it.revision,true) }
        assertFalse(decodeDraft(dao.intent("c",next)!!).itemExpectation!!.completeHistory)
        assertNull(dao.outbox("c",next))
        var rev=1; fake.sendHandler={ FakeNodusTransport.receipt("note","n",(++rev).toString()) }
        engine.drain(100); engine.drain(100)
        assertNull(dao.outbox("c",next))
        // Canonical read changes metadata only: the frozen item expectation is still 1.
        fake.pages.add(page(remote().copy(revision="103",title="metadata 101")))
        engine.pullChanges(); engine.drain()
        assertEquals(JsonPrimitive("1"),body(fake.sent.last())["expectedRevision"])
        assertEquals(next,fake.sent.last().operationId)
    }

    @Test fun reminderAndReadyAttachmentMetadataCrudRemainJsonOnlyWithExplicitNull(): Unit = runBlocking {
        readyBlob(); bootstrap(); val engine=engine()
        val reminder=engine.planning.allocate(NodusResourceType.REMINDER,"new-reminder",null,"n")
        val ref=engine.planning.allocate(NodusResourceType.ATTACHMENT,"new-ref",null,"n")
        engine.planning.enqueue(reminder.mappingId) { V2ReminderCreate(it.deviceId,it.requestId,it.revision,reminder.wireId,WireField.Present("named"),"1999-01-01T00:00:00.123456789Z",WireField.Present(true)) }
        engine.planning.enqueue(reminder.mappingId) { V2ReminderEdit(it.deviceId,it.requestId,it.revision,name=WireField.Present("edited")) }
        engine.planning.enqueue(root().mappingId) { V2EditNote(it.deviceId,it.requestId,it.revision,primaryReminderId=WireField.Present(null)) }
        engine.planning.enqueue(reminder.mappingId) { V2ChildDelete(it.deviceId,it.requestId,it.revision) }
        engine.planning.enqueue(ref.mappingId) { V2AttachmentCreate(it.deviceId,it.requestId,it.revision,ref.wireId,"blob",AttachmentKind.AUDIO,WireField.Present("description"),WireField.Present("../../name")) }
        engine.planning.enqueue(ref.mappingId) { V2AttachmentEdit(it.deviceId,it.requestId,it.revision,kind=WireField.Present(AttachmentKind.VIDEO),description=WireField.Present("")) }
        engine.planning.enqueue(root().mappingId) { V2AttachmentOrder(it.deviceId,it.requestId,it.revision,listOf(ref.wireId)) }
        engine.planning.enqueue(ref.mappingId) { V2ChildDelete(it.deviceId,it.requestId,it.revision) }
        var rev=1; fake.sendHandler={ FakeNodusTransport.receipt("note","n",(++rev).toString()) }
        engine.drain()
        assertEquals((1..8).map(Int::toString),fake.sent.map { body(it).getValue("expectedRevision").jsonPrimitive.content })
        assertTrue(fake.sent.all { it.contentType=="application/json" && it.sourceFileId==null })
        assertEquals(JsonNull,body(fake.sent[2])["primaryReminderId"])
        assertFalse(body(fake.sent[2]).containsKey("authoredAt"))
        assertEquals("DELETE",fake.sent[3].method)
        assertEquals("/api/v2/notes/n/attachments/order",fake.sent[6].path)
        assertFalse(fake.sent.any { wireString(it.body!!).contains(blobSource) })
    }

    @Test fun genericReferenceSenderRevalidatesOwnedBytesBeforeEveryReplay(): Unit = runBlocking {
        readyBlob(); bootstrap(); val engine=engine()
        val ref=engine.planning.allocate(NodusResourceType.ATTACHMENT,"new-ref",null,"n")
        engine.planning.enqueue(ref.mappingId) { V2AttachmentCreate(it.deviceId,it.requestId,it.revision,ref.wireId,"blob",AttachmentKind.GENERIC) }
        fake.sendHandler={ throw java.io.IOException("lost response") }
        engine.drain()
        assertEquals(1,fake.sent.size)
        val file=java.io.File(java.net.URI(blobSource))
        assertTrue(file.delete()); file.writeBytes(byteArrayOf(1))
        val progress=NodusCoordinator(db,"c",{config},fake,byteStore) { Long.MAX_VALUE / 2 }.drain()
        assertTrue(progress.blocked.any { it.endsWith("blob_source_missing_or_corrupt") })
        assertEquals(1,fake.sent.size)
    }

    @Test fun incompatibleCapabilitiesFailClosedWithoutV1FallbackOrBlockingLocalCapture(): Unit = runBlocking {
        bootstrap(); val engine=engine()
        fake.capabilitiesBody=NodusFixtures.capabilities.replace("2.0","9.0")
        val intent=engine.planning.enqueue(root().mappingId,local().copy(title="offline saved")) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("offline saved")) }
        val readCount=fake.gets.size
        assertThrows(Exception::class.java) { runBlocking { engine.drain() } }
        assertEquals("offline saved",local().title)
        assertEquals(NodusOutboxState.PREPARED,dao.outbox("c",intent)!!.state)
        assertTrue(fake.sent.isEmpty())
        assertEquals(listOf("/api/v2/capabilities"),fake.gets.drop(readCount).map { it.path })
    }

    @Test fun deletedOrganizationWaitsForLocalReferencesInsteadOfDestroyingLegacyRelations(): Unit = runBlocking {
        val time="2000-01-01T00:00:00Z"; val tag=V2Tag("t","shared label","1",time,time,false)
        fake.pages.add(V2Changes(listOf(V2Event.Tag("1","tag",tag)),"1","1",false))
        val engine=engine(); engine.pullChanges()
        val map=dao.mappingByWire("c",NodusResourceType.TAG,"t")!!
        db.noteDao.insert(Note(id=100,isLocalOnly=true,title="unrelated private note").toEntity())
        db.noteTagDao.insert(NoteTagJoin(map.localRowId!!,100))
        fake.pages.add(V2Changes(listOf(V2Event.Tag("2","tag",tag.copy(revision="2",deleted=true))),"2","2",false))
        assertTrue(engine.pullChanges().blocked.any { it.endsWith("local_resource_in_use") })
        assertEquals("shared label",db.noteDao.getById(100).first()!!.tags.single().name)
        assertEquals("1",dao.tracking("c",map.mappingId)!!.baseRevision)
        assertTrue(dao.snapshot("c",NodusResourceType.TAG,"t")!!.tombstone)
    }

    @Test fun persistedLocalScanContinuationPassesMultiplePagesOfPrivateManualAndTemporaryBlocks(): Unit = runBlocking {
        bootstrap(); val engine=engine()
        repeat(130) { index ->
            engine.planning.enqueue(root().mappingId,local().copy(isLocalOnly=true)) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("private $index")) }
            val mapping=engine.planning.allocate(NodusResourceType.TAG,"blocked-$index",null)
            val operation=engine.planning.enqueue(mapping.mappingId) { V2OrganizationCreate(it.deviceId,it.requestId,"blocked") }
            dao.recordOutcome(NodusEvidence("c",newNodusId(),operation,"epoch",NodusEvidenceKind.UNKNOWN,null,null,null,"manual:rejected"))
        }
        db.tagDao.insert(Tag("temporary dependency",300))
        db.noteDao.insert(Note(id=200,title="temporarily blocked").toEntity())
        db.noteTagDao.insert(NoteTagJoin(300,200))
        val temporary=engine.graphs.enroll(200,emptyList())
        val tag=dao.mappingByLocalRow("c",NodusResourceType.TAG,300)!!
        val tagOperation=dao.recentResourceIntents("c",NodusResourceType.TAG,tag.wireId).single().intentId
        dao.recordOutcome(NodusEvidence("c",newNodusId(),tagOperation,"epoch",NodusEvidenceKind.UNKNOWN,null,null,null,"manual:rejected"))
        db.noteDao.insert(Note(id=201,title="independent ready").toEntity())
        val ready=engine.graphs.enroll(201,emptyList())
        var continuation=NodusQueueContinuation()
        for (tick in 0..40) {
            // Recreate the coordinator, as a worker/process restart would. The caller
            // persists and returns these LOCAL row continuations, never wire cursors.
            val result=engine().drain(1,25,continuation)
            assertTrue(result.examinedRows<=25 && result.completedRequests<=1)
            continuation=result.queueContinuation!!
            if (fake.sent.isNotEmpty()) break
        }
        assertEquals(listOf("/api/v2/notes/${dao.mapping("c",ready)!!.wireId}"),fake.sent.map { it.path })
        assertTrue(dao.unpreparedIntents("c",1000).size>=130)
        assertEquals(NodusOutboxState.UNKNOWN,dao.outbox("c",tagOperation)!!.state)
        var wrapped=false
        for (tick in 0..40) {
            val result=engine().drain(1,25,continuation)
            continuation=result.queueContinuation!!
            if (result.queueScanComplete) {
                assertEquals(0L,continuation.intentRowId); assertEquals(0L,continuation.operationRowId)
                assertTrue(result.hasMore) // Retained blocks remain; no false complete acknowledgement.
                wrapped=true; break
            }
        }
        assertTrue(wrapped)
        val time="2000-01-01T00:00:00Z"
        fake.pages.add(V2Changes(listOf(V2Event.Tag("2","tag",V2Tag(tag.wireId,"temporary dependency","2",time,time,false))),"2","2",false))
        engine.pullChanges()
        for (tick in 0..40) {
            val result=engine().drain(1,25,continuation)
            continuation=result.queueContinuation!!
            if (fake.sent.size==2) break
        }
        assertEquals("/api/v2/notes/${dao.mapping("c",temporary)!!.wireId}",fake.sent.last().path)
        assertEquals(2,fake.sent.size)
        assertEquals("2",dao.cursor("c","v2",NodusStream.CHANGES)!!.cursor)
    }

    @Test fun continuationCannotBypassRetryAfterAndEndWrapReplaysOriginalExactBytes(): Unit = runBlocking {
        bootstrap(); val engine=engine()
        val original=engine.planning.enqueue(root().mappingId) { V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("first")) }
        val bytes=dao.outbox("c",original)!!.body!!.clone()
        fake.sendHandler={ NodusHttpResult(429,"""{"error":"limited"}""".toByteArray(),"60") }
        val limited=engine.drain(1,10)
        db.noteDao.insert(Note(id=201,title="later independent").toEntity())
        val later=engine.graphs.enroll(201,emptyList())
        val reads=fake.gets.size
        val waiting=engine().drain(1,10,limited.queueContinuation!!)
        assertEquals(0,waiting.examinedRows); assertEquals(reads,fake.gets.size); assertEquals(1,fake.sent.size)
        clock=61001
        fake.sendHandler={ op -> FakeNodusTransport.receipt("note",op.path.split('/')[4],"2") }
        var result=engine().drain(1,10,waiting.queueContinuation!!)
        assertEquals("/api/v2/notes/${dao.mapping("c",later)!!.wireId}",fake.sent.last().path)
        result=engine().drain(1,10,result.queueContinuation!!)
        assertTrue(result.queueScanComplete && result.hasMore)
        engine().drain(1,10,result.queueContinuation!!)
        assertEquals(original,fake.sent.last().operationId)
        assertArrayEquals(bytes,fake.sent.last().body)
        assertEquals(NodusOutboxState.RETIRED,dao.outbox("c",original)!!.state)
    }

    @Test fun preallocatedReminderCreateAckPullBindsOnceAndExplicitDetachStaysBlocked(): Unit = runBlocking {
        bootstrap(); val engine=engine()
        val map=engine.planning.allocate(NodusResourceType.REMINDER,"preallocated",null,"n")
        engine.planning.enqueue(map.mappingId) { V2ReminderCreate(it.deviceId,it.requestId,it.revision,map.wireId,WireField.Present("named"),"2000-01-01T00:00:00Z") }
        engine.drain()
        val reminder=V2Reminder(map.wireId,"named","2000-01-01T00:00:00Z",false)
        fake.pages.add(page(remote("2").copy(reminders=listOf(reminder))))
        assertTrue(engine.pullChanges().blocked.isEmpty())
        val bound=dao.mapping("c",map.mappingId)!!
        assertNotNull(bound.localRowId); assertEquals(bound.localRowId,local().reminders.single().id)
        fake.pages.add(page(remote("3").copy(reminders=listOf(reminder))))
        engine.pullChanges()
        assertEquals(bound,dao.mapping("c",map.mappingId))
        dao.releaseLocalRow("c",map.mappingId,bound.localRowId!!)
        fake.pages.add(page(remote("4").copy(reminders=listOf(reminder.copy(name="changed")))))
        assertTrue(engine.pullChanges().blocked.any { it.endsWith("detached_child_mapping") })
        assertNull(dao.mapping("c",map.mappingId)!!.localRowId)
        assertTrue(dao.mapping("c",map.mappingId)!!.localRowDetached)
    }

    @Test fun preallocatedOrganizationMappingsBindBeforeReferencingNoteProjection(): Unit = runBlocking {
        val engine=engine(); val tag=engine.planning.allocate(NodusResourceType.TAG,"pre-tag",null)
        val book=engine.planning.allocate(NodusResourceType.NOTEBOOK,"pre-book",null)
        val time="2000-01-01T00:00:00Z"
        fake.pages.add(V2Changes(listOf(V2Event.Tag("1","tag",V2Tag(tag.wireId,"tag","1",time,time,false)),
            V2Event.Notebook("2","notebook",V2Notebook(book.wireId,"book","2",time,time,false)),
            V2Event.Note("3","note",remote("3").copy(tagIds=listOf(tag.wireId),notebookId=book.wireId))),"3","3",false))
        assertTrue(engine.pullChanges().blocked.isEmpty())
        assertEquals(dao.mapping("c",tag.mappingId)!!.localRowId,local().tags.single().id)
        assertEquals(dao.mapping("c",book.mappingId)!!.localRowId,local().notebookId)
        val id=dao.mapping("c",tag.mappingId)!!.localRowId!!
        dao.releaseLocalRow("c",tag.mappingId,id)
        fake.pages.add(V2Changes(listOf(V2Event.Tag("4","tag",V2Tag(tag.wireId,"changed","4",time,time,false))),"4","4",false))
        assertTrue(engine.pullChanges().blocked.any { it.endsWith("detached_local_mapping") })
        assertNull(dao.mapping("c",tag.mappingId)!!.localRowId)
    }

    @Test fun unavailableVerifierOrDeletedAndCorruptBytesNeverRollBackCanonicalFeed(): Unit = runBlocking {
        readyBlob()
        val ref=V2Attachment("ref","blob",AttachmentKind.GENERIC,"visible metadata","file",0,false)
        fake.pages.add(page(remote().copy(attachments=listOf(ref))))
        val unverified=NodusCoordinator(db,"c",{config},fake).pullChanges()
        assertTrue(unverified.blocked.any {it.contains("byte_verification_required")})
        assertEquals("1",dao.cursor("c","v2",NodusStream.CHANGES)!!.cursor)
        assertEquals(1,engine().projectPending().applied)
        val file=java.io.File(java.net.URI(blobSource))
        file.setWritable(true); file.writeBytes(byteArrayOf(1)); file.setReadOnly()
        fake.pages.add(page(remote("2").copy(title="remote edit",attachments=listOf(ref))))
        assertTrue(engine().pullChanges().blocked.any {it.contains("byte_missing_or_corrupt")})
        assertEquals("2",dao.cursor("c","v2",NodusStream.CHANGES)!!.cursor)
        assertEquals("1",dao.tracking("c",root().mappingId)!!.baseRevision)
        assertEquals("visible metadata",local().attachments.single().description)
        file.delete()
        fake.pages.add(page(remote("3").copy(attachments=listOf(ref))))
        assertTrue(engine().pullChanges().blocked.any {it.contains("byte_missing_or_corrupt")})
        assertEquals("3",dao.cursor("c","v2",NodusStream.CHANGES)!!.cursor)
        assertTrue(dao.attachment("c","n","ref")!!.errorCode!!.startsWith("projection:"))
        assertTrue(engine().projectPending().blocked.isNotEmpty()) // Coordinator restart does not trust persisted readiness.
    }

    @Test fun replacementBetweenHashVerificationAndProjectionIsDetectedByFileIdentity(): Unit = runBlocking {
        readyBlob(); var calls=0
        byteStore=NodusByteStore(byteDirectory, publish={ from, to -> java.nio.file.Files.createLink(to.toPath(), from.toPath()); Unit }, fingerprintFile={ file ->
            val attributes=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java)
            val identity="${attributes.fileKey()}:${attributes.size()}:${attributes.lastModifiedTime()}"
            if(++calls==2) {
                val replacement=java.io.File(byteDirectory,"replacement.bin")
                replacement.writeBytes(byteArrayOf(1)); replacement.setLastModified(file.lastModified()); replacement.setReadOnly()
                assertTrue(replacement.renameTo(file))
            }
            identity
        }) { java.nio.channels.FileChannel.open(it.toPath(),java.nio.file.StandardOpenOption.READ).use { channel -> channel.force(true) } }
        val ref=V2Attachment("ref","blob",AttachmentKind.GENERIC,"retained","name",0,false)
        fake.pages.add(page(remote().copy(attachments=listOf(ref))))
        assertTrue(engine().pullChanges().blocked.any {it.contains("byte_replaced")})
        assertEquals("1",dao.cursor("c","v2",NodusStream.CHANGES)!!.cursor)
        assertNotNull(dao.snapshot("c",NodusResourceType.NOTE,"n"))
        assertNull(dao.mappingByWire("c",NodusResourceType.NOTE,"n"))
        assertEquals("projection:byte_replaced",dao.attachment("c","n","ref")!!.errorCode)
    }

    @Test fun hashingDoesNotHoldRoomWriteTransactionOrPreventOfflineEdits(): Unit = runBlocking {
        bootstrap(); readyBlob()
        val started=CompletableDeferred<Unit>(); val release=java.util.concurrent.CountDownLatch(1)
        byteStore=NodusByteStore(byteDirectory, publish={ from, to -> java.nio.file.Files.createLink(to.toPath(), from.toPath()); Unit }, fingerprintFile={ file ->
            started.complete(Unit); require(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
            "${file.length()}:${file.lastModified()}"
        }) { java.nio.channels.FileChannel.open(it.toPath(),java.nio.file.StandardOpenOption.READ).use { channel -> channel.force(true) } }
        val ref=V2Attachment("ref","blob",AttachmentKind.GENERIC,"remote ref","file",0,false)
        fake.pages.add(page(remote("2").copy(attachments=listOf(ref))))
        val coordinator=engine(); val pulling=async {coordinator.pullChanges()}
        started.await()
        try {
            assertEquals("2",dao.cursor("c","v2",NodusStream.CHANGES)!!.cursor)
            withTimeout(2000) { coordinator.planning.enqueue(root().mappingId,local().copy(title="offline edit")) {V2EditNote(it.deviceId,it.requestId,it.revision,title=WireField.Present("offline edit"))} }
        } finally {release.countDown()}
        assertTrue(pulling.await().blocked.isNotEmpty())
        assertEquals("offline edit",local().title)
    }

}

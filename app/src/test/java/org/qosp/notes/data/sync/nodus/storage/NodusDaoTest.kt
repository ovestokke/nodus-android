package org.qosp.notes.data.sync.nodus.storage

import androidx.room.withTransaction
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.*
import org.qosp.notes.data.sync.nodus.NodusFixtures
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NodusDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: NodusDao
    private val root = NodusMapping("c", "note", NodusResourceType.NOTE, "", "n", "local-note-lifetime", 11)
    private val connection = NodusConnection("c", "https://notes.example/", "owner", "d", "epoch")
    @Before fun setup() : Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.nodusDao
        dao.createConnection(connection)
        dao.addMappings("c", listOf(root))
    }
    @After fun close() { db.close() }
    private suspend fun operation(id: String = "op", request: String = "r", intentId: String = "intent", generation: Long = 0): NodusOutbox {
        val next = dao.recordLocalChange("c", "note", generation, intentId, byteArrayOf(0, 1, -1))
        val body = "{\n \"requestId\":\"$request\", \"deviceId\":\"d\", \"expectedRevision\":\"1\", \"title\":\"  exact \\n\"\n}"
        return NodusOutbox("c", id, intentId, "note", next, "v2", "PATCH", "/api/v2/notes/n", "application/json", body.toByteArray(), null, null, null, "d", request, "epoch", NodusOutboxState.PREPARED)
    }
    private fun success(op: String = "op", evidenceId: String = "receipt") = NodusEvidence("c", evidenceId, op, "epoch", NodusEvidenceKind.RECEIPT, 200,
        """{"resourceType":"note","resourceId":"n","revision":"2"}""".toByteArray(), null, null)

    @Test fun uniqueLifetimeMappingsAreConnectionScopedAndNeverReplaced() : Unit = runBlocking {
        for (type in NodusResourceType.entries.filter { it != NodusResourceType.NOTE }) {
            dao.addMappings("c", listOf(NodusMapping("c", type.name, type, if (type in setOf(NodusResourceType.ITEM, NodusResourceType.REMINDER, NodusResourceType.ATTACHMENT)) "n" else "", "wire", "local", 1)))
        }
        assertEquals(7, dao.mappings("c").size)
        assertThrows(Exception::class.java) { runBlocking { dao.addMappings("c", listOf(root.copy(mappingId = "duplicate"))) } }
        assertThrows(Exception::class.java) { runBlocking { dao.addMappings("c", listOf(root.copy(mappingId = "duplicate", wireId = "different"))) } }
        dao.createConnection(connection.copy(connectionId = "other", ownerKey = "other-owner"))
        dao.addMappings("other", listOf(root.copy(connectionId = "other")))
        assertEquals(root, dao.mapping("c", "note"))
        assertEquals(1, dao.mappings("other").size)
        assertThrows(Exception::class.java) { runBlocking { dao.createConnection(connection.copy(connectionId = "alias")) } }
        assertThrows(Exception::class.java) { runBlocking { dao.addMappings("c", listOf(root.copy(mappingId = "valid", wireId = "new", localKey = "new"), root.copy(mappingId = "collision"))) } }
        assertNull(dao.mapping("c", "valid"))
        assertThrows(Exception::class.java) { runBlocking { dao.addMappings("c",listOf(root.copy(mappingId="new-life",wireId="new",localKey="new-lifetime"))) } }
        dao.releaseLocalRow("c","note",11)
        dao.addMappings("c",listOf(root.copy(mappingId="new-life",wireId="new",localKey="new-lifetime")))
        assertNull(dao.mapping("c","note")!!.localRowId)
        assertEquals("n",dao.mapping("c","note")!!.wireId)
        assertEquals(11L,dao.mapping("c","new-life")!!.localRowId)
    }

    @Test fun aggregateGraphRelationsMappingsAndBaseCommitOrRollBackAsOne() : Unit = runBlocking {
        val note = Note(id = 11, title = " exact ", content = "raw", isList = true,
            taskList = listOf(NoteTask(1,"task",true)), attachments = listOf(Attachment(path = "content://local")),
            isArchived = true, isDeleted = true, isPinned = true, isHidden = true, isMarkdownEnabled = false,
            isLocalOnly = true, isCompactPreview = true, screenAlwaysOn = true,
            creationDate = 1, modifiedDate = 2, deletionDate = 3, color = NoteColor.Purple, notebookId = 7,
            tags = listOf(Tag("tag",9)), reminders = listOf(Reminder("reminder",11,4,13)))
        val related = listOf(
            NodusMapping("c","tag",NodusResourceType.TAG,"","t","tag-key",9),
            NodusMapping("c","notebook",NodusResourceType.NOTEBOOK,"","book","book-key",7),
            NodusMapping("c","reminder",NodusResourceType.REMINDER,"n","r","reminder-key",13))
        val base = NodusTracking("c","note",0,"9223372036854775807",NodusFixtures.note.toByteArray())
        dao.storeAggregate("c",note,listOf(Notebook("book",7)),related,base,0)
        assertEquals(note,db.noteDao.getById(11).first())
        assertArrayEquals(base.baseBody,dao.tracking("c","note")!!.baseBody)
        val extra = NodusMapping("c","extra",NodusResourceType.TAG,"","extra","extra",99)
        assertThrows(Exception::class.java) { runBlocking {
            dao.storeAggregate("c",note.copy(title="must rollback", reminders=note.reminders + note.reminders),emptyList(),listOf(extra),base,0)
        } }
        assertEquals(note,db.noteDao.getById(11).first())
        assertNull(dao.mapping("c","extra"))
        dao.recordLocalChange("c","note",0,"edit",byteArrayOf(10),note.copy(tags=emptyList(),reminders=emptyList()))
        assertTrue(db.noteDao.getById(11).first()!!.tags.isEmpty())
        assertEquals(1L,dao.tracking("c","note")!!.generation)
        assertThrows(Exception::class.java) { runBlocking { dao.recordLocalChange("c","note",1,"edit",byteArrayOf(11),note.copy(title="rollback")) } }
        assertEquals(1L,dao.tracking("c","note")!!.generation)
        assertEquals(" exact ",db.noteDao.getById(11).first()!!.title)
        assertThrows(Exception::class.java) { runBlocking { dao.storeAggregate("c",note,emptyList(),emptyList(),base,0) } }
    }

    @Test fun descendingBaseRevisionCannotRegressGraphOrInsertMappings() : Unit = runBlocking {
        val newer = NodusFixtures.note.replace("9223372036854775807", "20").toByteArray()
        val older = NodusFixtures.note.replace("9223372036854775807", "19").toByteArray()
        dao.storeAggregate("c",Note(id=11,title="new"),emptyList(),emptyList(),NodusTracking("c","note",0,"20",newer),0)
        val extra = NodusMapping("c","extra",NodusResourceType.TAG,"","t","extra",44)
        assertThrows(Exception::class.java) { runBlocking {
            dao.storeAggregate("c",Note(id=11,title="old"),emptyList(),listOf(extra),NodusTracking("c","note",0,"19",older),0)
        } }
        assertEquals("new",db.noteDao.getById(11).first()!!.title)
        assertEquals("20",dao.tracking("c","note")!!.baseRevision)
        assertNull(dao.mapping("c","extra"))
    }

    @Test fun childPreparationRequiresMatchingTypePathAndCreateBodyIdentity() : Unit = runBlocking {
        for ((type, collection) in listOf(NodusResourceType.ITEM to "items", NodusResourceType.REMINDER to "reminders", NodusResourceType.ATTACHMENT to "attachments")) {
            val map = NodusMapping("c",collection,type,"n","a",collection,1)
            dao.addMappings("c",listOf(map))
            dao.recordLocalChange("c",collection,0,collection,byteArrayOf(1))
            val op = NodusOutbox("c",collection,collection,collection,1,"v2","PATCH","/api/v2/notes/n/$collection/a","application/json",
                """{"deviceId":"d","requestId":"$collection","expectedRevision":"1"}""".toByteArray(),null,null,null,"d",collection,"epoch",NodusOutboxState.PREPARED)
            assertThrows(Exception::class.java) { runBlocking { dao.prepare(op.copy(path="/api/v2/notes/n/$collection/b")) } }
            val wrongType = if (collection == "items") "reminders" else "items"
            assertThrows(Exception::class.java) { runBlocking { dao.prepare(op.copy(path="/api/v2/notes/n/$wrongType/a")) } }
            assertNull(dao.outbox("c",collection))
            dao.prepare(op)
        }
        val map = NodusMapping("c","append",NodusResourceType.ITEM,"n","new","append",2)
        dao.addMappings("c",listOf(map)); dao.recordLocalChange("c","append",0,"append",byteArrayOf(1))
        val body = """{"deviceId":"d","requestId":"append","expectedRevision":"1","itemId":"wrong","text":""}""".toByteArray()
        val op = NodusOutbox("c","append","append","append",1,"v2","POST","/api/v2/notes/n/items","application/json",body,null,null,null,"d","append","epoch",NodusOutboxState.PREPARED)
        assertThrows(Exception::class.java) { runBlocking { dao.prepare(op) } }
        assertNull(dao.outbox("c","append"))
    }

    @Test fun fixedWatermarkPageAndCursorRollbackAndVersionSeparation() : Unit = runBlocking {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO nodus_cursors VALUES ('c','v1','CHANGES','99','100')")
        fun tag(revision: String, id: String = "t") = NodusSnapshot("c",NodusResourceType.TAG,id,revision,NodusFixtures.tag.replace("\"12\"","\"$revision\"").replace("\"t\"","\"$id\"").toByteArray(),false)
        dao.storePage("c",NodusStream.CHANGES,"0",null,"12","15",true,listOf(tag("12")),emptyList())
        assertEquals("15",dao.cursor("c","v2",NodusStream.CHANGES)!!.until)
        assertThrows(Exception::class.java) { runBlocking { dao.storePage("c",NodusStream.CHANGES,"12","15","14","15",true,listOf(tag("13","new"),tag("14").copy(body=byteArrayOf(0))),emptyList()) } }
        assertNull(dao.snapshot("c",NodusResourceType.TAG,"new"))
        assertEquals("12",dao.cursor("c","v2",NodusStream.CHANGES)!!.cursor)
        assertThrows(Exception::class.java) { runBlocking { dao.storePage("c",NodusStream.CHANGES,"12","15","16","16",false,emptyList(),emptyList()) } }
        dao.storePage("c",NodusStream.CHANGES,"12","15","15","15",false,emptyList(),emptyList())
        assertNull(dao.cursor("c","v2",NodusStream.CHANGES)!!.until)
        assertEquals("99",dao.cursor("c","v1",NodusStream.CHANGES)!!.cursor)
        assertNull(dao.cursor("c","v2",NodusStream.CONFLICTS))
        assertThrows(Exception::class.java) { runBlocking { dao.storePage("c",NodusStream.CHANGES,"12","15","15","15",false,emptyList(),emptyList()) } }
    }

    @Test fun outboxBytesKeysEpochAndUnknownOutcomesAreDurableAndImmutable() : Unit = runBlocking {
        val op = operation()
        dao.prepare(op)
        assertArrayEquals(op.body,dao.outbox("c","op")!!.body)
        assertThrows(Exception::class.java) { runBlocking { dao.prepare(op.copy(operationId="reuse")) } }
        assertThrows(Exception::class.java) { runBlocking { dao.prepare(op.copy(body="{}".toByteArray())) } }
        dao.markSent("c","op")
        dao.recordOutcome(NodusEvidence("c","unknown","op","epoch",NodusEvidenceKind.UNKNOWN,null,null,null,"connection_lost"))
        assertEquals(NodusOutboxState.UNKNOWN,dao.outbox("c","op")!!.state)
        assertThrows(Exception::class.java) { runBlocking { dao.retire("c","op") } }
        dao.rotateCredentialEpoch("c","epoch","new-epoch")
        assertThrows(Exception::class.java) { runBlocking { dao.markSent("c","op") } }
        assertThrows(Exception::class.java) { runBlocking { dao.prepare(op.copy(operationId="old-epoch", requestId="new")) } }
        dao.recordOutcome(success()) // A late result stays associated with the original epoch.
        dao.retire("c","op")
        assertArrayEquals(op.body,dao.outbox("c","op")!!.body)
        assertEquals("epoch",dao.outbox("c","op")!!.credentialEpoch)
        assertEquals(2,dao.evidence("c","op").size)
        assertArrayEquals(byteArrayOf(0,1,-1),dao.intent("c","intent")!!.body)
    }

    @Test fun duplicateOrWrongReceiptCannotRetireOrPartiallyChangeSafetyState() : Unit = runBlocking {
        dao.prepare(operation())
        val wrong = success().copy(body="""{"resourceType":"note","resourceId":"wrong","revision":"2"}""".toByteArray())
        assertThrows(Exception::class.java) { runBlocking { dao.recordOutcome(wrong) } }
        assertEquals(NodusOutboxState.PREPARED,dao.outbox("c","op")!!.state)
        assertTrue(dao.evidence("c","op").isEmpty())
        dao.recordOutcome(NodusEvidence("c","same","op","epoch",NodusEvidenceKind.UNKNOWN,null,null,null,"lost"))
        assertThrows(Exception::class.java) { runBlocking { dao.recordOutcome(success(evidenceId="same")) } }
        assertEquals(NodusOutboxState.UNKNOWN,dao.outbox("c","op")!!.state)
        assertEquals(1,dao.evidence("c","op").size)
    }

    @Test fun localDeletionNeverCascadesMappingsSnapshotsIntentsOutboxEvidenceOrReadiness() : Unit = runBlocking {
        db.noteDao.insert(Note(id=11,title="local").toEntity())
        dao.prepare(operation())
        val body = NodusFixtures.note.replace("\"state\":\"trash\"","\"state\":\"purged\"").toByteArray()
        dao.storePage("c",NodusStream.CHANGES,"0",null,"9223372036854775807","9223372036854775807",false,
            listOf(NodusSnapshot("c",NodusResourceType.NOTE,"n","9223372036854775807",body,true)),emptyList())
        dao.recordOutcome(success())
        val blob = NodusBlobTransfer("c","blob","12","a".repeat(64),"content://original",NodusReadiness.ERROR,"permission_lost")
        val attachment = NodusAttachmentTransfer("c","n","a","blob","content://original",NodusReadiness.ERROR,"permission_lost")
        dao.storeReadiness(blob,listOf(attachment))
        db.noteDao.delete(Note(id=11,title="local").toEntity())
        assertNotNull(dao.mapping("c","note")); assertNotNull(dao.tracking("c","note"))
        assertNotNull(dao.outbox("c","op")); assertNotNull(dao.intent("c","intent"))
        assertEquals(1,dao.evidence("c","op").size)
        assertArrayEquals(body,dao.snapshot("c",NodusResourceType.NOTE,"n")!!.body)
        assertEquals(blob,dao.blob("c","blob")); assertEquals(attachment,dao.attachment("c","n","a"))
        assertThrows(Exception::class.java) { runBlocking { dao.storeReadiness(blob.copy(state=NodusReadiness.READY),listOf(attachment.copy(connectionId="other"))) } }
        assertEquals(NodusReadiness.ERROR,dao.blob("c","blob")!!.state)
        val inaccessible = blob.copy(blobId="inaccessible",size=null,sha256=null)
        dao.storeReadiness(inaccessible,listOf(attachment.copy(attachmentId="unreadable",blobId="inaccessible")))
        assertNull(dao.blob("c","inaccessible")!!.sha256)
        assertEquals("content://original",dao.attachment("c","n","unreadable")!!.sourceUri)
    }

    @Test fun conflictsAndBlobMetadataPersistOutsideDomainFeed() : Unit = runBlocking {
        dao.prepare(operation())
        val receipt = """{"error":"conflict","code":"durable_conflict","conflictId":"clash","revision":"4"}""".toByteArray()
        dao.recordOutcome(NodusEvidence("c","clash-receipt","op","epoch",NodusEvidenceKind.CONFLICT,409,receipt,"clash",null))
        dao.retire("c","op")
        assertArrayEquals(receipt,dao.evidence("c","op").single().body)
        val full = """{"id":"clash","state":"pending","parent":"","reason":"unknown_reason","operation":{"apiVersion":"v2","method":"PATCH","path":"/api/v2/notes/n","input":{"deviceId":"d","requestId":"r","expectedRevision":"1","title":"proposal"},"submittedBody":"exact proposal"},"snapshot":${NodusFixtures.note}}""".toByteArray()
        dao.storePage("c",NodusStream.CONFLICTS,"0",null,"1","1",false,emptyList(),listOf(NodusConflictRecord("c","clash","pending","",full)))
        assertArrayEquals(full,dao.conflict("c","clash")!!.body)
        assertNull(dao.cursor("c","v2",NodusStream.CHANGES))
        val metadata = """{"id":"b","size":"12","sha256":"${"a".repeat(64)}","mediaType":"image/svg+xml","state":"ready","revision":"5","created":"2000-01-01T00:00:00Z"}""".toByteArray()
        dao.storeBlobMetadata(NodusSnapshot("c",NodusResourceType.BLOB,"b","5",metadata,false))
        assertArrayEquals(metadata,dao.snapshot("c",NodusResourceType.BLOB,"b")!!.body)
        assertEquals("1",dao.cursor("c","v2",NodusStream.CONFLICTS)!!.cursor)
        dao.restartConflictDiscovery("c","1",null)
        val applied = full.toString(Charsets.UTF_8).replace("\"state\":\"pending\"","\"state\":\"applied\"").toByteArray()
        dao.storePage("c",NodusStream.CONFLICTS,"0",null,"1","1",false,emptyList(),listOf(NodusConflictRecord("c","clash","applied","",applied)))
        assertEquals("applied",dao.conflict("c","clash")!!.state)
        assertArrayEquals(receipt,dao.evidence("c","op").single().body)
        assertNull(dao.cursor("c","v2",NodusStream.CHANGES))
    }

    @Test fun preparedJsonRejectsDuplicateKeysAndSupportsConflictActionsWithoutNormalization() : Unit = runBlocking {
        val op = operation()
        val duplicate = """{"deviceId":"d","requestId":"r","expectedRevision":"1","title":"first","title":"second"}""".toByteArray()
        assertThrows(Exception::class.java) { runBlocking { dao.prepare(op.copy(body=duplicate)) } }
        assertNull(dao.outbox("c","op"))
        val body = "{ \"deviceId\":\"d\",\"requestId\":\"r\" }".toByteArray()
        dao.prepare(op.copy(method="POST",path="/api/v2/conflicts/clash/discard",body=body))
        assertArrayEquals(body,dao.outbox("c","op")!!.body)
        dao.recordOutcome(success().copy(body="""{"conflictId":"clash","revision":"3","state":"discarded"}""".toByteArray()))
        dao.retire("c","op")
    }

    @Test fun evidenceInsertRollsBackWhenSafetyStateUpdateFails() : Unit = runBlocking {
        dao.prepare(operation())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_state BEFORE UPDATE OF state ON nodus_outbox BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertThrows(Exception::class.java) { runBlocking { dao.recordOutcome(success()) } }
        assertTrue(dao.evidence("c","op").isEmpty())
        assertEquals(NodusOutboxState.PREPARED,dao.outbox("c","op")!!.state)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_state")
        dao.recordOutcome(success())
        assertEquals(NodusOutboxState.RECEIPTED,dao.outbox("c","op")!!.state)
    }

    @Test fun preparedBytesAndUnknownEvidenceSurviveDatabaseReopen() : Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "nodus-reopen-${java.util.UUID.randomUUID()}"
        db.close()
        fun open() = Room.databaseBuilder(context,AppDatabase::class.java,name).allowMainThreadQueries().build()
        try {
            db = open(); dao = db.nodusDao
            dao.createConnection(connection); dao.addMappings("c",listOf(root))
            val op = operation()
            dao.prepare(op)
            dao.recordOutcome(NodusEvidence("c","unknown","op","epoch",NodusEvidenceKind.UNKNOWN,null,null,null,"connection_lost"))
            db.close()
            db = open(); dao = db.nodusDao
            assertArrayEquals(op.body,dao.outbox("c","op")!!.body)
            assertEquals(NodusOutboxState.UNKNOWN,dao.outbox("c","op")!!.state)
            assertEquals("connection_lost",dao.evidence("c","op").single().errorCode)
            assertEquals(1L,dao.tracking("c","note")!!.generation)
            assertEquals(root,dao.mapping("c","note"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun binaryOutboxRetainsImmutableSourceMetadataWithoutLargeSqliteBody() : Unit = runBlocking {
        dao.addMappings("c",listOf(NodusMapping("c","blob-map",NodusResourceType.BLOB,"","b","blob-key",null)))
        dao.recordLocalChange("c","blob-map",0,"binary-intent",byteArrayOf(1))
        val op = NodusOutbox("c","upload","binary-intent","blob-map",1,"v2","PUT","/api/v2/blobs/b/content","application/octet-stream",null,"immutable-file","a".repeat(64),"536870912","d","upload-request","epoch",NodusOutboxState.PREPARED)
        dao.prepare(op)
        assertEquals(op,dao.outbox("c","upload"))
        assertNull(dao.outbox("c","upload")!!.body)
        val receipt = NodusEvidence("c","binary-receipt","upload","epoch",NodusEvidenceKind.RECEIPT,200,
            """{"resourceType":"blob","resourceId":"b","revision":"2","state":"pending"}""".toByteArray(),null,null)
        assertThrows(Exception::class.java) { runBlocking { dao.recordOutcome(receipt) } }
        dao.recordOutcome(receipt.copy(body=receipt.body!!.toString(Charsets.UTF_8).replace("pending","ready").toByteArray()))
        dao.retire("c","upload")
        assertEquals("immutable-file",dao.outbox("c","upload")!!.sourceFileId)
        assertEquals("536870912",dao.outbox("c","upload")!!.sourceSize)
        assertThrows(Exception::class.java) { runBlocking { dao.prepare(op.copy(operationId="bad",sourceFileId="content://mutable")) } }
    }
    @Test fun canonicalAcknowledgementFloorIsValidatedDecimalScopedAndDaoOwned(): Unit = runBlocking {
        val op=operation()
        dao.prepare(op)
        val valid=success().copy(body="""{"resourceType":"note","resourceId":"n","revision":"9223372036854775807"}""".toByteArray())
        assertThrows(Exception::class.java) { runBlocking { dao.recordOutcome(valid.copy(resourceType=NodusResourceType.NOTE,resourceId="n",resourceRevision="9",resourceRevisionDigits=1)) } }
        for (bad in listOf("01","-1","9223372036854775808")) {
            assertThrows(Exception::class.java) { runBlocking { dao.recordOutcome(valid.copy(body="""{"resourceType":"note","resourceId":"n","revision":"$bad"}""".toByteArray())) } }
        }
        dao.recordOutcome(valid)
        val stored=dao.latestEvidence("c","op")!!
        assertEquals(19,stored.resourceRevisionDigits)
        assertEquals("9223372036854775807",dao.maximumAcknowledgedRevision("c",NodusResourceType.NOTE,"n"))
        assertNull(dao.maximumAcknowledgedRevision("c",NodusResourceType.TAG,"n"))
        assertNull(dao.maximumAcknowledgedRevision("other",NodusResourceType.NOTE,"n"))
        assertArrayEquals(valid.body,stored.body)
    }

    @Test fun firstBindingIsIdentityCheckedAndDoubleBindRollsBackLocalWrites(): Unit = runBlocking {
        val map=NodusMapping("c","unbound",NodusResourceType.TAG,"","wire","lifetime",null)
        dao.addMappings("c",listOf(map))
        assertThrows(Exception::class.java) { runBlocking { dao.bindFirstLocalRow(map.copy(wireId="wrong"),10) } }
        dao.bindFirstLocalRow(map,10)
        assertThrows(Exception::class.java) { runBlocking { db.withTransaction {
            db.tagDao.insert(Tag("must roll back",20))
            dao.bindFirstLocalRow(map,20)
        } } }
        assertNull(db.tagDao.getById(20).first())
        assertEquals(10L,dao.mapping("c","unbound")!!.localRowId)
        dao.releaseLocalRow("c","unbound",10)
        assertThrows(Exception::class.java) { runBlocking { dao.bindFirstLocalRow(dao.mapping("c","unbound")!!,30) } }
    }

    @Test fun concurrentFirstBindHasOneWinnerAndRollsBackLosingLocalRow(): Unit = runBlocking {
        val map=NodusMapping("c","race",NodusResourceType.TAG,"","race","race",null)
        dao.addMappings("c",listOf(map))
        val outcomes=listOf(101L,102L).map { row -> async(Dispatchers.Default) {
            runCatching { db.withTransaction {
                db.tagDao.insert(Tag("candidate",row))
                dao.bindFirstLocalRow(map,row)
            } }.isSuccess
        } }.awaitAll()
        assertEquals(1,outcomes.count { it })
        val winner=dao.mapping("c","race")!!.localRowId!!
        assertNotNull(db.tagDao.getById(winner).first())
        assertNull(db.tagDao.getById(if(winner==101L)102L else 101L).first())
    }

}

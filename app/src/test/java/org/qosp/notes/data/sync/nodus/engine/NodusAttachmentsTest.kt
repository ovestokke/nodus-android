package org.qosp.notes.data.sync.nodus.engine

import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.qosp.notes.data.AppDatabase
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.storage.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class NodusAttachmentsTest {
    private lateinit var db: AppDatabase
    private lateinit var directory: File
    private lateinit var store: NodusByteStore
    private lateinit var config: NodusConfiguration
    private val dao get()=db.nodusDao
    private val payload=byteArrayOf(0,1,2,-1,60,62)
    private val original=Attachment(Attachment.Type.GENERIC,"content://original/source"," untouched ","../../active.html")
    private var clock=1000L
    private var revision=1
    private var loseUpload=false
    private var loseReadyMetadata=false
    private var loseReference=false
    private var interruptDownload=false
    private var reserveError: NodusHttpResult?=null
    private var uploadError: NodusHttpResult?=null
    private var badMetadata=false
    private var onRead: suspend () -> Unit = {}
    private var readNumber=0
    private var failReadNumber:Int?=null
    private var readFailureStatus=503
    private var beforeReadyMetadata: (() -> Unit)? = null
    private var downloadPayload: ByteArray?=null
    private var downloadLength: String?=null
    private var downloadType="application/octet-stream"
    private var downloadStatus=200
    private var downloadDisposition="attachment"
    private val remote=mutableMapOf<String,V2Blob>()
    private val sent=mutableListOf<NodusOutbox>()
    private val uploads=mutableListOf<Pair<NodusOutbox,ByteArray>>()
    private val receipts=mutableMapOf<String,NodusHttpResult>()
    private val json=object : NodusEngineTransport {
        override suspend fun get(configuration: NodusConfiguration,path: String,query: Map<String,String>): NodusHttpResult {
            assertEquals(config.credentialEpoch,configuration.credentialEpoch)
            readNumber++
            onRead()
            if(readNumber==failReadNumber) return NodusHttpResult(readFailureStatus,"".toByteArray(),"2")
            if (path.endsWith("capabilities")) return NodusHttpResult(200,NodusFixtures.capabilities.toByteArray())
            if (loseReadyMetadata && remote[path.substringAfterLast('/')]?.state == BlobState.READY) throw IOException("read interrupted")
            if (badMetadata) return NodusHttpResult(200,"{}".toByteArray())
            if (remote[path.substringAfterLast('/')]?.state == BlobState.READY) beforeReadyMetadata?.invoke()
            return NodusHttpResult(200,NodusJson.encode(V2Blob.serializer(),remote.getValue(path.substringAfterLast('/'))).toByteArray())
        }
        override suspend fun send(configuration: NodusConfiguration,operation: NodusOutbox): NodusHttpResult {
            sent += operation
            assertEquals(NodusOutboxState.SENT,dao.outbox("c",operation.operationId)!!.state)
            receipts[operation.operationId]?.let { return it }
            val response=if (operation.path.startsWith("/api/v2/blobs/")) {
                reserveError?.let { return it }
                val input=NodusJson.decode(V2BlobReserve.serializer(),wireString(operation.body!!))
                val id=operation.path.substringAfterLast('/')
                val value=V2Blob(id,input.size,input.sha256,input.mediaType,BlobState.PENDING,(++revision).toString(),"2000-01-01T00:00:00Z")
                remote[id]=value
                receipt(id,value.revision,"pending")
            } else {
                val blob=dao.blob("c",remote.keys.first())!!
                assertTrue(blob.state in setOf(NodusReadiness.READY,NodusReadiness.AVAILABLE))
                assertEquals(NodusEvidenceKind.RECEIPT,dao.latestEvidence("c",blob.uploadOperationId!!)!!.kind)
                FakeNodusTransport.receipt("note","n",(++revision).toString())
            }
            receipts[operation.operationId]=response
            if (loseReference && operation.path.endsWith("attachments")) throw IOException("reference receipt lost")
            return response
        }
    }
    private val binary=object : NodusBinaryTransport {
        override suspend fun upload(configuration: NodusConfiguration,operation: NodusOutbox,source: File): NodusHttpResult {
            assertEquals(config.credentialEpoch,configuration.credentialEpoch)
            assertEquals(NodusOutboxState.SENT,dao.outbox("c",operation.operationId)!!.state)
            uploads += operation to source.readBytes()
            uploadError?.let { return it }
            receipts[operation.operationId]?.let { return it }
            val id=operation.path.split('/')[4]
            val value=remote.getValue(id).copy(state=BlobState.READY,revision=(++revision).toString())
            remote[id]=value
            val response=receipt(id,value.revision,"ready")
            receipts[operation.operationId]=response
            if (loseUpload) throw IOException("response lost after commit")
            return response
        }
        override suspend fun download(configuration: NodusConfiguration,blobId: String): NodusDownload {
            assertEquals(config.credentialEpoch,configuration.credentialEpoch)
            return NodusDownload(downloadStatus,downloadLength ?: remote.getValue(blobId).size,downloadType,downloadDisposition,null,if (interruptDownload) object:InputStream(){override fun read():Int=throw IOException("interrupted")} else ByteArrayInputStream(downloadPayload ?: payload))
        }
    }
    private fun receipt(id:String,rev:String,state:String)=NodusHttpResult(200,"""{"resourceType":"blob","resourceId":"$id","revision":"$rev","state":"$state"}""".toByteArray())
    private fun engine()=NodusAttachments(db,"c",{config},store,json,binary){clock}
    private suspend fun capture(key:String="one",attachment:Attachment=original,reuse:String?=null): NodusAttachmentTransfer {
        val local=db.noteDao.getById(11).first()!!
        if(attachment !in local.attachments) db.noteDao.update(local.copy(attachments=local.attachments+attachment).toEntity())
        return engine().capture("root",key,attachment,"application/octet-stream",reuse){ByteArrayInputStream(payload)}
    }
    @Before fun setup(): Unit=runBlocking {
        directory=File(Files.createTempDirectory("nodus-private-bytes").toFile(),"bytes").also { it.mkdirs() }
        // ShadowLinux cannot open directories; use a real host directory fsync, not a no-op.
        store=NodusByteStore(directory, publish={ from, to -> Files.createLink(to.toPath(), from.toPath()); Unit }) { java.nio.channels.FileChannel.open(it.toPath(),java.nio.file.StandardOpenOption.READ).use { channel -> channel.force(true) } }
        openDatabase()
        config=NodusConfiguration(NodusOrigin.parse("https://notes.example/"),"synthetic","epoch","d")
        dao.createConnection(NodusConnection("c",config.origin.toString(),"owner","d","epoch",realmId="realm-fixture"))
        dao.addMappings("c",listOf(NodusMapping("c","root",NodusResourceType.NOTE,"","n","local-note",11)))
        db.noteDao.insert(Note(id=11,title="original",attachments=listOf(original)).toEntity())
        val note=NodusJson.decode(V2Note.serializer(),NodusFixtures.note).copy(id="n",revision="1",attachments=emptyList(),reminders=emptyList(),items=emptyList(),primaryReminderId=null)
        dao.recordProjectionBase(NodusTracking("c","root",0,"1",NodusJson.encode(V2Note.serializer(),note).toByteArray()))
    }
    private fun openDatabase() { db=Room.databaseBuilder(RuntimeEnvironment.getApplication(),AppDatabase::class.java,File(directory.parentFile,"journal.db").path).allowMainThreadQueries().build() }
    private fun reopen() { db.close(); openDatabase() }
    @After fun close(){db.close(); directory.parentFile!!.deleteRecursively()}
    private suspend fun assertOriginal(){assertEquals(original,db.noteDao.getById(11).first()!!.attachments.first())}

    @Test fun captureRestartAndDuplicateInvocationFreezeMetadataAndOwnedBytes(): Unit=runBlocking {
        val ref=capture()
        assertNotNull(dao.blob("c",ref.blobId)!!.sourceUri)
        val duplicate=capture(attachment=original.copy(description="later UI edit",fileName="changed"))
        assertEquals(ref.attachmentId,duplicate.attachmentId)
        assertArrayEquals(ref.draftBody,duplicate.draftBody)
        val edited=original.copy(description="UI edit after capture")
        db.noteDao.update(db.noteDao.getById(11).first()!!.copy(attachments=listOf(edited)).toEntity())
        assertTrue(engine().advance("n",ref.attachmentId).ready)
        assertTrue(engine().advance("n",ref.attachmentId).ready)
        assertEquals(1,uploads.size)
        assertEquals(2,sent.size)
        val request=NodusJson.decode(V2AttachmentCreate.serializer(),wireString(sent.last().body!!))
        assertEquals(WireField.Present(original.fileName),request.fileName)
        assertEquals(WireField.Present(original.description),request.description)
        assertFalse(wireString(sent.last().body!!).contains("content://"))
        assertTrue(directory.listFiles()!!.all { Regex("[0-9a-f-]{36}\\.bin").matches(it.name) })
        assertEquals(edited,db.noteDao.getById(11).first()!!.attachments.single())
    }

    @Test fun interruptedUploadReplaysWholeExactFileUnderSameEpochBeforeAnyReference(): Unit=runBlocking {
        val ref=capture(); loseUpload=true
        assertEquals("unknown_outcome",engine().advance("n",ref.attachmentId).blocked)
        assertEquals(1,sent.size); assertEquals(1,uploads.size)
        assertOriginal()
        assertFalse(engine().advance("n",ref.attachmentId).ready)
        assertEquals(1,uploads.size)
        clock=2001; loseUpload=false
        assertTrue(engine().advance("n",ref.attachmentId).ready)
        assertEquals(2,uploads.size)
        assertEquals(uploads[0].first.operationId,uploads[1].first.operationId)
        assertEquals(uploads[0].first.requestId,uploads[1].first.requestId)
        assertEquals(uploads[0].first.sourceFileId,uploads[1].first.sourceFileId)
        assertArrayEquals(payload,uploads[1].second)
        assertEquals(1,sent.count { it.path.endsWith("attachments") })
    }

    @Test fun rotationAfterUnknownUploadNeverCreatesNewKeyOrReference(): Unit=runBlocking {
        val ref=capture(); loseUpload=true; engine().advance("n",ref.attachmentId)
        config=NodusConfiguration(config.origin,"rotated","next","d")
        dao.rotateCredentialEpoch("c","epoch","next"); clock=3000
        assertEquals("credential_epoch_changed",engine().advance("n",ref.attachmentId).blocked)
        assertEquals(1,uploads.size); assertEquals(1,sent.size)
        assertEquals("manual:credential_epoch_changed",dao.latestEvidence("c",uploads.single().first.operationId)!!.errorCode)
        assertOriginal()
    }

    @Test fun missingOrChangedOwnedSourcesBlockWithoutSendingOrDroppingOriginal(): Unit=runBlocking {
        val first=capture()
        val source=File(java.net.URI(dao.blob("c",first.blobId)!!.sourceUri!!))
        source.setWritable(true) // Simulate external corruption, never a supported source rewrite.
        source.writeBytes(byteArrayOf(9,9,9,9,9,9))
        assertFalse(engine().advance("n",first.attachmentId).ready)
        assertTrue(sent.isEmpty() && uploads.isEmpty())
        assertEquals(NodusReadiness.ERROR,dao.blob("c",first.blobId)!!.state)
        assertOriginal()
        val second=capture("second"); File(java.net.URI(dao.blob("c",second.blobId)!!.sourceUri!!)).delete()
        assertFalse(engine().advance("n",second.attachmentId).ready)
        assertOriginal()
    }

    @Test fun inaccessibleMalformedAndInterruptedSourcesRetainVisibleOriginalAndDurableBlock(): Unit=runBlocking {
        val ref=engine().capture("root","inaccessible",original,"application/octet-stream"){throw FileNotFoundException()}
        assertEquals(NodusReadiness.ERROR,ref.state); assertOriginal()
        val malformed=capture("malformed",original.copy(fileName="x".repeat(1025)))
        assertEquals("invalid_attachment_metadata",malformed.errorCode)
        assertTrue(dao.hasBlockedAttachment("c","n"))
        val interrupted=engine().capture("root","interrupted",original,"application/octet-stream") { object:InputStream(){override fun read():Int=throw IOException()} }
        assertEquals(NodusReadiness.ERROR,interrupted.state)
        assertFalse(directory.listFiles()!!.any { it.extension=="part" })
        assertTrue(sent.isEmpty()); assertOriginal()
    }

    @Test fun immutableQuotaAndMalformedServerResponsesNeverCreateReferences(): Unit=runBlocking {
        val ref=capture(); uploadError=NodusHttpResult(409,"""{"error":"immutable","code":"blob_immutable"}""".toByteArray())
        assertEquals("blob_immutable",engine().advance("n",ref.attachmentId).blocked)
        assertEquals(1,sent.size); assertOriginal()
        assertFalse(engine().advance("n",ref.attachmentId).ready); assertEquals(1,uploads.size)
        val second=capture("quota"); reserveError=NodusHttpResult(507,"""{"error":"quota","code":"storage_quota_exceeded"}""".toByteArray())
        assertEquals("quota_exceeded",engine().advance("n",second.attachmentId).blocked)
        assertOriginal()
        reserveError=null; val third=capture("malformed-server"); badMetadata=true
        assertFalse(engine().advance("n",third.attachmentId).ready)
        assertTrue(sent.none { it.path.endsWith("attachments") }); assertOriginal()
    }

    @Test fun sharedBlobHasDistinctStableRefsWithoutRecopyOrReupload(): Unit=runBlocking {
        val first=capture(); assertTrue(engine().advance("n",first.attachmentId).ready)
        db.noteDao.update(db.noteDao.getById(11).first()!!.copy(attachments=listOf(original,original.copy(description="second"))).toEntity())
        val second=engine().capture("root","second",original.copy(description="second"),"application/octet-stream",first.blobId){error("must not reopen")}
        assertNotEquals(first.attachmentId,second.attachmentId)
        assertEquals(first.blobId,second.blobId)
        assertTrue(engine().advance("n",second.attachmentId).ready)
        assertEquals(1,uploads.size)
        assertEquals(2,sent.count { it.path.endsWith("attachments") })
        assertEquals(1,directory.listFiles()!!.size)
    }

    private fun remoteDownload(id:String="remote") {
        remote[id]=V2Blob(id,payload.size.toString(),MessageDigest.getInstance("SHA-256").digest(payload).joinToString(""){"%02x".format(it.toInt() and 255)},"text/html",BlobState.READY,"9","2000-01-01T00:00:00Z")
    }
    @Test fun authenticatedDownloadChecksLengthHashHeadersAndInstallsOnlyOpaquePrivateFile(): Unit=runBlocking {
        remoteDownload()
        assertTrue(engine().download("remote").ready)
        val blob=dao.blob("c","remote")!!
        assertEquals(NodusReadiness.AVAILABLE,blob.state)
        val file=File(java.net.URI(blob.sourceUri!!))
        assertEquals(directory,file.parentFile); assertEquals("bin",file.extension)
        assertArrayEquals(payload,file.readBytes())
        assertTrue(engine().download("remote").ready)
        assertEquals(1,directory.listFiles()!!.size)
        assertFalse(engine().download("../escape").ready)
    }
    @Test fun downloadedHashLengthTypeAndPartialStatusMismatchesNeverInstall(): Unit=runBlocking {
        for (case in 0..5) {
            val id="remote$case"; remoteDownload(id)
            downloadPayload=if(case==0) byteArrayOf(9,9,9,9,9,9) else if(case==1) byteArrayOf(0) else payload
            downloadLength=if(case==2) "999" else payload.size.toString()
            downloadType=if(case==3) "text/html" else "application/octet-stream"
            downloadStatus=if(case==4) 206 else 200
            downloadDisposition=if(case==5) "inline; filename=../../active.html" else "attachment"
            assertFalse(engine().download(id).ready)
            assertEquals(NodusReadiness.ERROR,dao.blob("c",id)!!.state)
            assertNull(dao.blob("c",id)!!.sourceUri)
        }
        assertTrue(directory.listFiles()!!.isEmpty()); assertOriginal()
    }
    @Test fun localQuotaAndLifetimeLimitsRetainMetadataWithoutNetwork(): Unit=runBlocking {
        store=NodusByteStore(directory, maxTotalBytes=0, publish={ from, to -> Files.createLink(to.toPath(),from.toPath()); Unit }, syncDirectory={})
        val quota=capture(); assertEquals("quota_exceeded",quota.errorCode)
        assertTrue(sent.isEmpty()); assertOriginal()
        dao.addMappings("c",(0..998).map { NodusMapping("c","ref$it",NodusResourceType.ATTACHMENT,"n","ref$it","lifetime$it",null) })
        val excess=capture("excess"); assertEquals("lifetime_reference_limit",excess.errorCode)
        assertTrue(sent.isEmpty()); assertOriginal()
    }
    @Test fun ownedInstallFsyncAndVerify() {
        val stored=store.install({ByteArrayInputStream(payload)})
        assertArrayEquals(payload,store.verify(stored.id,stored.size,stored.sha256).readBytes())
    }
    @Test fun storeRejectsTraversalHashLengthMismatchAndOversizedStream(): Unit=runBlocking {
        for(id in listOf("../outside","/tmp/file","name.html","%2e%2e")) assertThrows(Exception::class.java){store.file(id)}
        assertThrows(Exception::class.java){store.install({ByteArrayInputStream(payload)},"9",null)}
        assertThrows(Exception::class.java){store.install({ByteArrayInputStream(payload)},payload.size.toString(),"0".repeat(64))}
        val oversized=object:InputStream(){ var remaining=NodusByteStore.MAX_BLOB_BYTES+1; override fun read():Int=error("buffered only"); override fun read(b:ByteArray,off:Int,len:Int):Int { if(remaining==0L)return -1; val count=minOf(remaining,len.toLong()).toInt(); remaining-=count; return count } }
        val blocked=engine().capture("root","oversized",original,"application/octet-stream"){oversized}
        assertEquals(NodusReadiness.ERROR,blocked.state)
        assertTrue(sent.isEmpty()); assertOriginal()
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun actualDatabaseReopenAtEachTransferPhaseRetainsExactOperationsAndSource(): Unit=runBlocking {
        val ref=capture(); reopen()
        val blob=dao.blob("c",ref.blobId)!!
        val reservation=dao.outbox("c",blob.reservationOperationId!!)!!
        dao.markAttempt("c",reservation.operationId)
        val response=json.send(config,reservation)
        dao.recordOutcome(NodusEvidence("c",newNodusId(),reservation.operationId,"epoch",NodusEvidenceKind.RECEIPT,200,response.body,null,null))
        dao.retire("c",reservation.operationId); reopen()
        loseReadyMetadata=true
        assertEquals("transport_read_interrupted",engine().advance("n",ref.attachmentId).blocked)
        assertEquals(1,uploads.size)
        val upload=uploads.single().first
        assertEquals(NodusEvidenceKind.RECEIPT,dao.latestEvidence("c",upload.operationId)!!.kind)
        reopen(); loseReadyMetadata=false; loseReference=true
        assertEquals("unknown_outcome",engine().advance("n",ref.attachmentId).blocked)
        val originalRef=sent.last()
        reopen(); clock=3000; loseReference=false
        assertTrue(engine().advance("n",ref.attachmentId).ready)
        assertEquals(1,uploads.size)
        assertEquals(originalRef.operationId,sent.last().operationId)
        assertArrayEquals(originalRef.body,sent.last().body)
        assertEquals(blob.sourceUri,dao.blob("c",ref.blobId)!!.sourceUri)
        assertOriginal()
    }
    @Test fun interruptedDownloadCanRestartWithoutPublishingPartialBytes(): Unit=runBlocking {
        remoteDownload(); interruptDownload=true
        assertFalse(engine().download("remote").ready)
        assertNull(dao.blob("c","remote")!!.sourceUri)
        assertTrue(directory.listFiles()!!.isEmpty())
        reopen(); interruptDownload=false
        assertTrue(engine().download("remote").ready)
        assertEquals(NodusReadiness.AVAILABLE,dao.blob("c","remote")!!.state)
    }
    @Test fun sourceAndOperationLinksCannotBeChangedAndFailedLinkRollsBack(): Unit=runBlocking {
        val ref=capture(); val blob=dao.blob("c",ref.blobId)!!
        assertThrows(Exception::class.java){runBlocking {dao.storeReadiness(blob.copy(sourceUri=store.file(newNodusId()).toURI().toString()),emptyList())}}
        assertThrows(Exception::class.java){runBlocking {dao.storeReadiness(blob.copy(sha256="0".repeat(64)),emptyList())}}
        assertThrows(Exception::class.java){runBlocking {dao.storeReadiness(blob,listOf(ref.copy(operationId=blob.reservationOperationId)))}}
        assertThrows(Exception::class.java){runBlocking {dao.storeReadiness(blob,listOf(ref.copy(draftBody="{}".toByteArray())))}}
        assertArrayEquals(ref.draftBody,dao.attachment("c","n",ref.attachmentId)!!.draftBody)
        assertEquals(blob,dao.blob("c",ref.blobId))
    }

    @Test fun crashAfterFileInstallBeforeMetadataCommitRecoversPlannedSourceWithoutReopeningUri(): Unit=runBlocking {
        var crash=true
        store=NodusByteStore(directory, publish={ from, to -> Files.createLink(to.toPath(), from.toPath()); Unit }) { dir ->
            java.nio.channels.FileChannel.open(dir.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            if(crash) { crash=false; throw AssertionError("simulated process death after durable rename") }
        }
        assertThrows(AssertionError::class.java){runBlocking {capture()}}
        val map=dao.mappingByLocalKey("c",NodusResourceType.ATTACHMENT,"one","n")!!
        val before=dao.attachment("c","n",map.wireId)!!
        val source=dao.blob("c",before.blobId)!!.sourceUri!!
        assertTrue(File(java.net.URI(source)).isFile)
        assertNull(dao.blob("c",before.blobId)!!.size)
        reopen()
        val recovered=engine().capture("root","one",original,"application/octet-stream"){throw FileNotFoundException("grant expired")}
        assertEquals(before.attachmentId,recovered.attachmentId)
        assertEquals(source,dao.blob("c",recovered.blobId)!!.sourceUri)
        assertTrue(engine().advance("n",recovered.attachmentId).ready)
        assertArrayEquals(payload,uploads.single().second)
    }
    @Test fun malformedReadyReceiptIsRetainedAsEvidenceWithoutCreatingReference(): Unit=runBlocking {
        val ref=capture()
        uploadError=receipt(ref.blobId,"3","pending")
        assertEquals("invalid_receipt",engine().advance("n",ref.attachmentId).blocked)
        val op=uploads.single().first
        assertEquals(NodusOutboxState.UNKNOWN,dao.outbox("c",op.operationId)!!.state)
        assertArrayEquals(uploadError!!.body,dao.latestEvidence("c",op.operationId)!!.body)
        assertEquals(1,sent.size); assertOriginal()
    }

    @Test fun completeGraphProjectsSharedReferenceOrderAndTombstonesWithoutDeletingBytes(): Unit=runBlocking {
        val first=capture(); assertTrue(engine().advance("n",first.attachmentId).ready)
        val second=capture("two",original.copy(description="second"),first.blobId)
        assertTrue(engine().advance("n",second.attachmentId).ready)
        val attachments=listOf(V2Attachment(second.attachmentId,first.blobId,AttachmentKind.GENERIC,"second","second.bin",0,false),
            V2Attachment(first.attachmentId,first.blobId,AttachmentKind.GENERIC,"first","../../active.html",1,false))
        val note=NodusJson.decode(V2Note.serializer(),NodusFixtures.note).copy(id="n",revision=revision.toString(),items=emptyList(),reminders=emptyList(),attachments=attachments)
        dao.storePage("c",NodusStream.CHANGES,"0",null,note.revision,note.revision,false,listOf(NodusSnapshot("c",NodusResourceType.NOTE,"n",note.revision,NodusJson.encode(V2Note.serializer(),note).toByteArray(),false)),emptyList())
        assertTrue(NodusProjection(db,"c",store).project().blocked.isEmpty())
        val local=db.noteDao.getById(11).first()!!
        assertEquals(listOf("second","first"),local.attachments.map {it.description})
        assertEquals(1,local.attachments.map {it.path}.distinct().size)
        val deleted=note.copy(revision=(++revision).toString(),attachments=attachments.map { if(it.id==first.attachmentId)it.copy(deleted=true)else it })
        dao.storePage("c",NodusStream.CHANGES,note.revision,null,deleted.revision,deleted.revision,false,listOf(NodusSnapshot("c",NodusResourceType.NOTE,"n",deleted.revision,NodusJson.encode(V2Note.serializer(),deleted).toByteArray(),false)),emptyList())
        assertTrue(NodusProjection(db,"c",store).project().blocked.isEmpty())
        assertEquals(1,db.noteDao.getById(11).first()!!.attachments.size)
        val mapping=dao.mappingByWire("c",NodusResourceType.ATTACHMENT,first.attachmentId,"n")!!
        assertTrue(NodusJson.decode(V2Attachment.serializer(),wireString(dao.tracking("c",mapping.mappingId)!!.baseBody!!)).deleted)
        assertTrue(File(java.net.URI(dao.blob("c",first.blobId)!!.sourceUri!!)).isFile)
        assertEquals("reference_or_note_deleted",engine().advance("n",first.attachmentId).blocked)
    }

    @Test fun temporaryFailureAtEveryReadPhaseResumesSameOperationsAfterRestart(): Unit=runBlocking {
        for(status in listOf(429,503)) for(phase in 1..3) {
            readFailureStatus=status
            val ref=capture("status$status-phase$phase")
            readNumber=0; failReadNumber=phase
            val result=engine().advance("n",ref.attachmentId)
            assertEquals("retry_after",result.blocked)
            val blob=dao.blob("c",ref.blobId)!!
            assertNotEquals(NodusReadiness.ERROR,blob.state)
            reopen()
            val count=readNumber
            assertEquals(result,engine().advance("n",ref.attachmentId))
            assertEquals(count,readNumber)
            clock+=2001; failReadNumber=null
            assertTrue(engine().advance("n",ref.attachmentId).ready)
            assertEquals(blob.reservationOperationId,dao.blob("c",ref.blobId)!!.reservationOperationId)
            blob.uploadOperationId?.let { assertEquals(it,dao.blob("c",ref.blobId)!!.uploadOperationId) }
        }
    }

    @Test fun quotaAdmissionCountsErrorFilesAndConcurrentFailuresPublishNothing(): Unit=runBlocking {
        store=NodusByteStore(directory, maxTotalBytes=payload.size.toLong(), publish={ from,to -> Files.createLink(to.toPath(),from.toPath()); Unit }, syncDirectory={})
        val ref=capture()
        val blob=dao.blob("c",ref.blobId)!!
        dao.storeReadiness(blob.copy(state=NodusReadiness.ERROR),emptyList())
        assertEquals(payload.size.toLong(),dao.retainedBlobBytes("c"))
        repeat(3) { assertEquals("quota_exceeded",capture("rejected$it").errorCode) }
        val failures=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results=(1..2).map { failures.submit<Boolean> { runCatching { store.install({ByteArrayInputStream(payload)}) }.isFailure } }
            assertTrue(results.all { it.get() })
        } finally { failures.shutdownNow() }
        assertEquals(1,directory.listFiles()!!.size)
        assertEquals(payload.size.toLong(),directory.listFiles()!!.sumOf {it.length()})
    }

    @Test fun temporaryDownloadReadFailuresPreservePhaseAndResume(): Unit=runBlocking {
        for(status in listOf(429,503)) for(phase in 1..2) {
            val id="download-$status-$phase"
            remote[id]=V2Blob(id,payload.size.toString(),MessageDigest.getInstance("SHA-256").digest(payload).joinToString(""){"%02x".format(it.toInt() and 255)},"application/octet-stream",BlobState.READY,"1","2000-01-01T00:00:00Z")
            readNumber=0; failReadNumber=phase;readFailureStatus=status
            val progress=engine().download(id)
            assertEquals("retry_after",progress.blocked)
            reopen()
            assertEquals(progress,engine().download(id))
            clock+=2001;failReadNumber=null
            assertTrue(engine().download(id).ready)
            assertEquals(NodusReadiness.AVAILABLE,dao.blob("c",id)!!.state)
        }
    }

    @Test fun distinctConcurrentInstallersSharePrepublicationBudget() {
        val limited=NodusByteStore(directory,maxTotalBytes=payload.size.toLong(),publish={from,to -> Files.createLink(to.toPath(),from.toPath()); Unit},syncDirectory={})
        val other=NodusByteStore(directory,maxTotalBytes=payload.size.toLong(),publish={from,to -> Files.createLink(to.toPath(),from.toPath()); Unit},syncDirectory={})
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val a=pool.submit<Boolean>{runCatching{limited.install({ByteArrayInputStream(payload)})}.isSuccess}
            val b=pool.submit<Boolean>{runCatching{other.install({ByteArrayInputStream(payload)},payload.size.toString())}.isSuccess}
            assertEquals(1,listOf(a.get(),b.get()).count{it})
            assertEquals(payload.size.toLong(),directory.listFiles()!!.sumOf{it.length()})
        } finally {pool.shutdownNow()}
    }

    @Test fun concurrentSameIdPublicationHasExactlyOneWinner() {
        val id=newNodusId()
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        val start=java.util.concurrent.CountDownLatch(1)
        try {
            val jobs=(1..2).map { value -> pool.submit<Boolean> { start.await(); runCatching { store.install({ByteArrayInputStream(byteArrayOf(value.toByte()))},id=id) }.isSuccess } }
            start.countDown()
            assertEquals(1,jobs.count { it.get() })
            assertEquals(1,directory.listFiles()!!.size)
        } finally { pool.shutdownNow() }
    }

    private suspend fun privateForkAtRead(phase:Int) {
        val ref=capture()
        onRead={ if(readNumber==phase) db.withTransaction {
            val root=dao.mapping("c","root")!!
            db.noteDao.update(db.noteDao.getById(11).first()!!.copy(isLocalOnly=true).toEntity())
            dao.releaseLocalRow("c",root.mappingId,11)
        } }
        assertEquals("private_or_detached_note",engine().advance("n",ref.attachmentId).blocked)
        assertEquals(if(phase==1) 0 else 1,sent.size)
        assertEquals(if(phase==3) 1 else 0,uploads.size)
        assertTrue(sent.none{it.path.endsWith("/attachments")})
        assertNotEquals(NodusReadiness.AVAILABLE,dao.attachment("c","n",ref.attachmentId)!!.state)
    }
    @Test fun privateForkDuringCapabilitiesStopsReservation():Unit=runBlocking { privateForkAtRead(1) }
    @Test fun privateForkDuringMetadataStopsUpload():Unit=runBlocking { privateForkAtRead(2) }
    @Test fun privateForkDuringReadyReadStopsReference():Unit=runBlocking { privateForkAtRead(3) }

    @Test fun sourceChangedAfterUploadBlocksReferenceSend(): Unit=runBlocking {
        val ref=capture()
        val file=File(java.net.URI(dao.blob("c",ref.blobId)!!.sourceUri!!))
        beforeReadyMetadata={ assertTrue(file.delete()); file.writeBytes(ByteArray(payload.size)) }
        assertEquals("source_or_transfer_validation_failed",engine().advance("n",ref.attachmentId).blocked)
        assertEquals(1,uploads.size)
        assertEquals(1,sent.size) // Reservation only; no reference after the intervening read.
        assertOriginal()
    }

    @Test fun genericReservationSenderRevalidatesOwnedBytes(): Unit=runBlocking {
        val ref=capture()
        val file=File(java.net.URI(dao.blob("c",ref.blobId)!!.sourceUri!!))
        assertTrue(file.delete()); file.writeBytes(ByteArray(payload.size))
        val progress=NodusCoordinator(db,"c",{config},json,store).drain()
        assertTrue(progress.blocked.any { it.endsWith("blob_source_missing_or_corrupt") })
        assertTrue(sent.isEmpty() && uploads.isEmpty())
        assertOriginal()
    }

    @Test fun stagingNeverFollowsPreexistingSymlink() {
        val id=newNodusId()
        val victim=File(directory.parentFile,"unrelated").also { it.writeBytes(payload) }
        val abandoned=File(directory,"$id.part")
        Files.createSymbolicLink(abandoned.toPath(),victim.toPath())
        val installed=store.install({ByteArrayInputStream(byteArrayOf(9))},id=id)
        assertArrayEquals(payload,victim.readBytes())
        assertTrue(Files.isSymbolicLink(abandoned.toPath()))
        assertArrayEquals(byteArrayOf(9),store.verify(installed.id,installed.size,installed.sha256).readBytes())
    }

    @Test fun privateFlagStopsBothByteTransferAndGenericGraphReservationSender(): Unit=runBlocking {
        val ref=capture()
        db.noteDao.update(db.noteDao.getById(11).first()!!.copy(isLocalOnly=true).toEntity())
        assertEquals("private_or_detached_note",engine().advance("n",ref.attachmentId).blocked)
        NodusCoordinator(db,"c",{config},json).drain()
        assertTrue(sent.isEmpty() && uploads.isEmpty())
        assertOriginal()
    }

}

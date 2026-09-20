package org.qosp.notes.data.sync.nodus.storage

import androidx.room.*
import org.qosp.notes.data.model.*
import org.qosp.notes.data.model.Reminder
import org.qosp.notes.data.sync.nodus.*

@Dao
abstract class NodusDao {
    @Query("SELECT * FROM nodus_mappings WHERE resourceType='NOTE' AND localRowId=:id AND localRowDetached=0")
    abstract suspend fun noteMappings(id:Long):List<NodusMapping>

    @Query("UPDATE nodus_connections SET origin=:origin WHERE connectionId=:id")
    abstract suspend fun updateConnectionOrigin(id:String,origin:String)

    @Query("SELECT * FROM nodus_connections ORDER BY connectionId")
    abstract suspend fun connections(): List<NodusConnection>
    @Query("UPDATE nodus_connections SET inactiveGraph=:graph, checkpointDeviceId=:deviceId, checkpointEpoch=:epoch, checkpointRemovedReminders=:removed WHERE connectionId=:id")
    abstract suspend fun checkpoint(id:String,graph:String?,deviceId:String?,epoch:String?,removed:String?)

    @Query("SELECT COUNT(*) FROM nodus_snapshots s LEFT JOIN nodus_mappings m ON m.connectionId=s.connectionId AND m.resourceType=s.resourceType AND m.wireId=s.wireId AND m.parentId='' LEFT JOIN nodus_tracking t ON t.connectionId=m.connectionId AND t.mappingId=m.mappingId WHERE s.connectionId=:connectionId AND s.resourceType IN ('NOTE','TAG','NOTEBOOK') AND (t.baseRevision IS NULL OR length(t.baseRevision)<length(s.revision) OR (length(t.baseRevision)=length(s.revision) AND t.baseRevision<s.revision))")
    abstract suspend fun projectionBacklog(connectionId: String): Int

    @Query("SELECT * FROM nodus_conflicts WHERE connectionId=:connectionId ORDER BY conflictId")
    abstract suspend fun conflicts(connectionId: String): List<NodusConflictRecord>
    @Query("SELECT * FROM nodus_outbox WHERE connectionId=:connectionId ORDER BY rowid")
    abstract suspend fun operations(connectionId: String): List<NodusOutbox>
    @Query("SELECT * FROM nodus_intents WHERE connectionId=:connectionId ORDER BY rowid")
    abstract suspend fun intents(connectionId: String): List<NodusIntent>
    @Query("SELECT * FROM nodus_blobs WHERE connectionId=:connectionId")
    abstract suspend fun blobs(connectionId: String): List<NodusBlobTransfer>

    @Query("SELECT * FROM nodus_captures")
    abstract suspend fun allCaptures(): List<NodusLocalCapture>
    @Query("SELECT * FROM nodus_attachments")
    abstract suspend fun allAttachmentTransfers(): List<NodusAttachmentTransfer>
    @Query("SELECT * FROM nodus_integration WHERE id=1")
    abstract suspend fun integration(): NodusIntegrationState?
    @Upsert abstract suspend fun putIntegration(value: NodusIntegrationState)
    @Insert abstract suspend fun addCapture(value: NodusLocalCapture): Long
    @Update abstract suspend fun updateCapture(value: NodusLocalCapture)
    @Query("SELECT * FROM nodus_captures WHERE connectionId=:connectionId ORDER BY id DESC LIMIT 1")
    abstract suspend fun latestCapture(connectionId: String): NodusLocalCapture?
    @Query("SELECT * FROM nodus_captures WHERE connectionId=:connectionId AND state IN ('PENDING','COMPILING') ORDER BY id LIMIT :limit")
    abstract suspend fun pendingCaptures(connectionId: String, limit: Int): List<NodusLocalCapture>
    @Query("SELECT * FROM nodus_captures WHERE connectionId=:connectionId ORDER BY id")
    abstract suspend fun captures(connectionId: String): List<NodusLocalCapture>
    @Query("SELECT * FROM nodus_attachments WHERE connectionId=:connectionId ORDER BY noteId,attachmentId")
    abstract suspend fun attachmentTransfers(connectionId: String): List<NodusAttachmentTransfer>
    @Query("SELECT EXISTS(SELECT 1 FROM nodus_mappings WHERE resourceType='NOTE' AND localRowId=:noteId)")
    abstract suspend fun hasRetainedMapping(noteId: Long): Boolean

    @Insert protected abstract suspend fun insertConnection(value: NodusConnection)
    @Query("SELECT * FROM nodus_connections WHERE connectionId = :id")
    abstract suspend fun connection(id: String): NodusConnection?
    @Query("UPDATE nodus_connections SET credentialEpoch = :epoch WHERE connectionId = :id")
    protected abstract suspend fun updateEpoch(id: String, epoch: String)

    @Insert protected abstract suspend fun insertMappings(values: List<NodusMapping>)
    @Query("SELECT * FROM nodus_mappings WHERE connectionId = :connectionId AND mappingId = :mappingId")
    abstract suspend fun mapping(connectionId: String, mappingId: String): NodusMapping?
    @Query("SELECT * FROM nodus_mappings WHERE connectionId = :connectionId")
    abstract suspend fun mappings(connectionId: String): List<NodusMapping>
    @Query("SELECT * FROM nodus_mappings WHERE connectionId=:connectionId AND resourceType=:type AND parentId=:parentId AND wireId=:wireId")
    abstract suspend fun mappingByWire(connectionId: String, type: NodusResourceType, wireId: String, parentId: String = ""): NodusMapping?
    @Query("SELECT * FROM nodus_mappings WHERE connectionId=:connectionId AND resourceType=:type AND parentId=:parentId AND localKey=:key")
    abstract suspend fun mappingByLocalKey(connectionId: String, type: NodusResourceType, key: String, parentId: String = ""): NodusMapping?
    @Query("SELECT * FROM nodus_mappings WHERE connectionId=:connectionId AND resourceType=:type AND parentId=:parentId AND localRowId=:rowId")
    abstract suspend fun mappingByLocalRow(connectionId: String, type: NodusResourceType, rowId: Long, parentId: String = ""): NodusMapping?
    @Query("SELECT * FROM nodus_mappings WHERE connectionId=:connectionId AND parentId=:parentId")
    abstract suspend fun childMappings(connectionId: String, parentId: String): List<NodusMapping>

    @Query("UPDATE nodus_mappings SET localRowId = NULL, localRowDetached = 1 WHERE connectionId = :connectionId AND mappingId = :mappingId")
    protected abstract suspend fun clearLocalRow(connectionId: String, mappingId: String)
    @Upsert protected abstract suspend fun putSnapshots(values: List<NodusSnapshot>)
    @Query("SELECT * FROM nodus_snapshots WHERE connectionId = :connectionId AND resourceType = :type AND wireId = :wireId")
    abstract suspend fun snapshot(connectionId: String, type: NodusResourceType, wireId: String): NodusSnapshot?
    @Upsert protected abstract suspend fun putTracking(value: NodusTracking)
    @Query("SELECT * FROM nodus_tracking WHERE connectionId = :connectionId AND mappingId = :mappingId")
    abstract suspend fun tracking(connectionId: String, mappingId: String): NodusTracking?
    @Insert protected abstract suspend fun insertIntent(value: NodusIntent)
    @Query("SELECT * FROM nodus_intents WHERE connectionId = :connectionId AND intentId = :intentId")
    abstract suspend fun intent(connectionId: String, intentId: String): NodusIntent?
    @Query("SELECT * FROM nodus_intents WHERE connectionId=:connectionId AND mappingId=:mappingId ORDER BY generation LIMIT 1")
    abstract suspend fun firstMappingIntent(connectionId: String, mappingId: String): NodusIntent?
    @Insert protected abstract suspend fun insertOutbox(value: NodusOutbox)
    @Query("SELECT * FROM nodus_outbox WHERE connectionId = :connectionId AND operationId = :operationId")
    abstract suspend fun outbox(connectionId: String, operationId: String): NodusOutbox?
    @Query("UPDATE nodus_outbox SET state = :state WHERE connectionId = :connectionId AND operationId = :operationId")
    protected abstract suspend fun updateOutboxState(connectionId: String, operationId: String, state: NodusOutboxState)
    @Upsert protected abstract suspend fun putCursor(value: NodusCursor)
    @Query("SELECT * FROM nodus_cursors WHERE connectionId = :connectionId AND apiVersion = :version AND stream = :stream")
    abstract suspend fun cursor(connectionId: String, version: String, stream: NodusStream): NodusCursor?
    @Insert protected abstract suspend fun insertEvidence(value: NodusEvidence)
    @Query("SELECT * FROM nodus_evidence WHERE connectionId = :connectionId AND operationId = :operationId")
    abstract suspend fun evidence(connectionId: String, operationId: String): List<NodusEvidence>
    @Upsert protected abstract suspend fun putConflicts(values: List<NodusConflictRecord>)
    @Query("SELECT * FROM nodus_conflicts WHERE connectionId = :connectionId AND conflictId = :conflictId")
    abstract suspend fun conflict(connectionId: String, conflictId: String): NodusConflictRecord?
    @Upsert protected abstract suspend fun putBlob(value: NodusBlobTransfer)
    @Upsert protected abstract suspend fun putAttachment(value: NodusAttachmentTransfer)
    @Query("SELECT * FROM nodus_blobs WHERE connectionId = :connectionId AND blobId = :blobId")
    abstract suspend fun blob(connectionId: String, blobId: String): NodusBlobTransfer?
    @Query("SELECT * FROM nodus_attachments WHERE connectionId = :connectionId AND noteId = :noteId AND attachmentId = :attachmentId")
    abstract suspend fun attachment(connectionId: String, noteId: String, attachmentId: String): NodusAttachmentTransfer?

    @Query("SELECT s.* FROM nodus_snapshots s LEFT JOIN nodus_mappings m ON m.connectionId=s.connectionId AND m.resourceType=s.resourceType AND m.wireId=s.wireId AND m.parentId='' LEFT JOIN nodus_tracking t ON t.connectionId=m.connectionId AND t.mappingId=m.mappingId WHERE s.connectionId=:connectionId AND s.resourceType!='BLOB' AND (t.baseRevision IS NULL OR length(t.baseRevision)<length(s.revision) OR (length(t.baseRevision)=length(s.revision) AND t.baseRevision<s.revision)) AND (length(s.revision)>length(:after) OR (length(s.revision)=length(:after) AND s.revision>:after)) ORDER BY length(s.revision), s.revision LIMIT :limit")
    abstract suspend fun projectionPage(connectionId: String, after: String, limit: Int): List<NodusSnapshot>

    @Query("SELECT o.* FROM nodus_outbox o JOIN nodus_mappings m ON m.connectionId=o.connectionId AND m.mappingId=o.mappingId WHERE o.connectionId=:connectionId AND o.state IN ('PREPARED','SENT','UNKNOWN') ORDER BY CASE WHEN m.resourceType IN ('TAG','NOTEBOOK') THEN 0 ELSE 1 END, o.rowid LIMIT :limit")
    abstract suspend fun pendingOperations(connectionId: String, limit: Int): List<NodusOutbox>

    @Query("SELECT i.* FROM nodus_intents i JOIN nodus_mappings m ON m.connectionId=i.connectionId AND m.mappingId=i.mappingId WHERE i.connectionId=:connectionId AND NOT EXISTS(SELECT 1 FROM nodus_outbox o WHERE o.connectionId=i.connectionId AND o.intentId=i.intentId) ORDER BY CASE WHEN m.resourceType IN ('TAG','NOTEBOOK') THEN 0 ELSE 1 END, i.rowid LIMIT :limit")
    abstract suspend fun unpreparedIntents(connectionId: String, limit: Int): List<NodusIntent>

    @Query("SELECT EXISTS(SELECT 1 FROM nodus_outbox WHERE connectionId=:connectionId AND state IN ('PREPARED','SENT','UNKNOWN')) OR EXISTS(SELECT 1 FROM nodus_intents i WHERE i.connectionId=:connectionId AND NOT EXISTS(SELECT 1 FROM nodus_outbox o WHERE o.connectionId=i.connectionId AND o.intentId=i.intentId))")
    abstract suspend fun hasQueueWork(connectionId: String): Boolean

    @Query("SELECT i.*, i.rowid AS scanRowId FROM nodus_intents i WHERE i.connectionId=:connectionId AND i.rowid>:afterRowId AND NOT EXISTS(SELECT 1 FROM nodus_outbox o WHERE o.connectionId=i.connectionId AND o.intentId=i.intentId) ORDER BY i.rowid LIMIT 1")
    abstract suspend fun nextUnpreparedIntent(connectionId: String, afterRowId: Long): NodusIntentQueueRow?

    @Query("SELECT o.*, o.rowid AS scanRowId FROM nodus_outbox o WHERE o.connectionId=:connectionId AND o.rowid>:afterRowId AND o.state IN ('PREPARED','SENT','UNKNOWN') ORDER BY o.rowid LIMIT 1")
    abstract suspend fun nextPendingOperation(connectionId: String, afterRowId: Long): NodusOutboxQueueRow?

    @Query("SELECT i.* FROM nodus_intents i JOIN nodus_mappings m ON m.connectionId=i.connectionId AND m.mappingId=i.mappingId WHERE i.connectionId=:connectionId AND ((m.resourceType=:type AND m.wireId=:wireId AND m.parentId='') OR (:type='NOTE' AND m.parentId=:wireId)) ORDER BY i.rowid DESC LIMIT 101")
    abstract suspend fun recentResourceIntents(connectionId: String, type: NodusResourceType, wireId: String): List<NodusIntent>

    @Query("SELECT * FROM nodus_outbox WHERE connectionId=:connectionId AND intentId=:intentId ORDER BY rowid LIMIT 101")
    abstract suspend fun intentOperations(connectionId: String, intentId: String): List<NodusOutbox>

    @Query("SELECT i.* FROM nodus_intents i JOIN nodus_mappings m ON m.connectionId=i.connectionId AND m.mappingId=i.mappingId WHERE i.connectionId=:connectionId AND ((m.resourceType=:type AND m.wireId=:wireId AND m.parentId='') OR (:type='NOTE' AND m.parentId=:wireId)) AND (NOT EXISTS(SELECT 1 FROM nodus_outbox o WHERE o.connectionId=i.connectionId AND o.intentId=i.intentId) OR EXISTS(SELECT 1 FROM nodus_outbox o WHERE o.connectionId=i.connectionId AND o.intentId=i.intentId AND (o.state!='RETIRED' OR EXISTS(SELECT 1 FROM nodus_evidence e WHERE e.connectionId=o.connectionId AND e.operationId=o.operationId AND e.kind='CONFLICT')))) LIMIT 101")
    abstract suspend fun unsettledIntents(connectionId: String, type: NodusResourceType, wireId: String): List<NodusIntent>

    @Query("SELECT resourceRevision FROM nodus_evidence WHERE connectionId=:connectionId AND resourceType=:type AND resourceId=:wireId AND resourceRevision IS NOT NULL ORDER BY resourceRevisionDigits DESC, resourceRevision DESC LIMIT 1")
    abstract suspend fun maximumAcknowledgedRevision(connectionId: String, type: NodusResourceType, wireId: String): String?

    @Query("SELECT * FROM nodus_evidence WHERE connectionId=:connectionId AND operationId=:operationId AND kind='CONFLICT' LIMIT 101")
    abstract suspend fun operationConflicts(connectionId: String, operationId: String): List<NodusEvidence>

    @Query("SELECT e.* FROM nodus_evidence e JOIN nodus_outbox o ON o.connectionId=e.connectionId AND o.operationId=e.operationId WHERE e.connectionId=:connectionId AND e.kind='RECEIPT' AND o.path=:path ORDER BY e.rowid DESC LIMIT 1")
    abstract suspend fun resolutionReceipt(connectionId: String, path: String): NodusEvidence?

    @Query("SELECT * FROM nodus_evidence WHERE connectionId=:connectionId AND operationId=:operationId ORDER BY rowid DESC LIMIT 1")
    abstract suspend fun latestEvidence(connectionId: String, operationId: String): NodusEvidence?

    @Transaction
    open suspend fun recordProjectionBase(value: NodusTracking) {
        requireConnection(value.connectionId)
        requireNotNull(mapping(value.connectionId, value.mappingId))
        val old = tracking(value.connectionId, value.mappingId)
        require(old == null || value.generation == old.generation)
        requireNotNull(value.baseRevision).also(::requireDecimal)
        require(old?.baseRevision == null || compareDecimal(value.baseRevision, old.baseRevision) >= 0)
        putTracking(value)
    }

    @Transaction
    open suspend fun markAttempt(connectionId: String, operationId: String) {
        val op = requireNotNull(outbox(connectionId, operationId))
        require(op.state in setOf(NodusOutboxState.PREPARED, NodusOutboxState.SENT, NodusOutboxState.UNKNOWN))
        require(requireConnection(connectionId).credentialEpoch == op.credentialEpoch)
        updateOutboxState(connectionId, operationId, NodusOutboxState.SENT)
    }

    @Query("SELECT count(*) FROM note_tags WHERE tagId=:id")
    abstract suspend fun localTagReferences(id: Long): Int
    @Query("SELECT count(*) FROM notes WHERE notebookId=:id")
    abstract suspend fun localNotebookReferences(id: Long): Int
    @Upsert abstract suspend fun projectTag(tag: Tag)
    @Upsert abstract suspend fun projectNotebook(notebook: Notebook)

    // Upsert (not REPLACE) avoids SQLite's delete/reinsert cascade on existing local rows.
    @Upsert protected abstract suspend fun putNote(value: NoteEntity)
    @Upsert protected abstract suspend fun putTags(values: List<Tag>)
    @Upsert protected abstract suspend fun putNotebooks(values: List<Notebook>)
    @Insert protected abstract suspend fun insertJoins(values: List<NoteTagJoin>)
    @Insert protected abstract suspend fun insertReminders(values: List<Reminder>)
    @Query("DELETE FROM note_tags WHERE noteId = :id") protected abstract suspend fun clearJoins(id: Long)
    @Query("DELETE FROM reminders WHERE noteId = :id") protected abstract suspend fun clearReminders(id: Long)

    @Transaction
    open suspend fun createConnection(value: NodusConnection) {
        id(value.connectionId); id(value.deviceId); id(value.credentialEpoch)
        require(value.ownerKey.isNotBlank())
        require(NodusOrigin.parse(value.origin).toString() == value.origin)
        insertConnection(value)
    }

    @Transaction
    open suspend fun rotateCredentialEpoch(connectionId: String, expectedEpoch: String, newEpoch: String) {
        require(requireConnection(connectionId).credentialEpoch == expectedEpoch)
        id(newEpoch); require(newEpoch != expectedEpoch)
        updateEpoch(connectionId, newEpoch)
        // Old operations are retained with their original epochs; this is not retry authorization.
    }

    @Transaction
    open suspend fun addMappings(connectionId: String, values: List<NodusMapping>) {
        requireConnection(connectionId)
        values.forEach {
            require(it.connectionId == connectionId)
            id(it.mappingId); id(it.wireId); require(it.localKey.isNotBlank())
            if (it.resourceType in children) id(it.parentId) else require(it.parentId.isEmpty())
            require(!it.localRowDetached || it.localRowId == null)
        }
        insertMappings(values)
    }

    @Query("UPDATE nodus_mappings SET localRowId=:rowId WHERE connectionId=:connectionId AND mappingId=:mappingId AND resourceType=:type AND parentId=:parentId AND wireId=:wireId AND localRowId IS NULL AND localRowDetached=0")
    protected abstract suspend fun bindRow(connectionId: String, mappingId: String, type: NodusResourceType, parentId: String, wireId: String, rowId: Long): Int

    @Transaction
    open suspend fun bindFirstLocalRow(expected: NodusMapping, rowId: Long): NodusMapping {
        require(expected.localRowId == null && !expected.localRowDetached)
        require(expected.resourceType in setOf(NodusResourceType.NOTE, NodusResourceType.TAG, NodusResourceType.NOTEBOOK, NodusResourceType.ITEM, NodusResourceType.REMINDER))
        require(if (expected.resourceType == NodusResourceType.ITEM) rowId >= 0 else rowId > 0)
        require(mapping(expected.connectionId, expected.mappingId) == expected)
        require(bindRow(expected.connectionId, expected.mappingId, expected.resourceType, expected.parentId, expected.wireId, rowId) == 1)
        return requireNotNull(mapping(expected.connectionId, expected.mappingId))
    }

    /** Explicitly detach a deleted local row without forgetting its lifetime wire identity. */
    @Transaction
    open suspend fun releaseLocalRow(connectionId: String, mappingId: String, expectedLocalRowId: Long) {
        requireConnection(connectionId)
        require(requireNotNull(mapping(connectionId, mappingId)).localRowId == expectedLocalRowId)
        clearLocalRow(connectionId, mappingId)
    }

    /** Compare-and-set cursor and fixed watermark together with a complete v2 page. */
    @Transaction
    open suspend fun storePage(
        connectionId: String, stream: NodusStream, expectedCursor: String, expectedUntil: String?,
        pageCursor: String, pageUntil: String, hasMore: Boolean,
        snapshots: List<NodusSnapshot>, conflicts: List<NodusConflictRecord>
    ) {
        requireConnection(connectionId)
        val current = cursor(connectionId, "v2", stream)
        require((current?.cursor ?: "0") == expectedCursor && current?.until == expectedUntil)
        listOf(expectedCursor, pageCursor, pageUntil).forEach(::requireDecimal)
        require(compareDecimal(expectedCursor, pageCursor) <= 0 && compareDecimal(pageCursor, pageUntil) <= 0)
        require(expectedUntil == null || expectedUntil == pageUntil)
        require(if (hasMore) compareDecimal(pageCursor, expectedCursor) > 0 && pageCursor != pageUntil else pageCursor == pageUntil)
        require(snapshots.size + conflicts.size <= 100)
        require(!hasMore || snapshots.isNotEmpty() || conflicts.isNotEmpty())
        if (stream == NodusStream.CHANGES) {
            require(conflicts.isEmpty())
            var previous = expectedCursor
            snapshots.forEach {
                require(it.connectionId == connectionId && it.resourceType != NodusResourceType.BLOB)
                validateSnapshot(it)
                require(compareDecimal(previous, it.revision) < 0 && compareDecimal(it.revision, pageCursor) <= 0)
                previous = it.revision
                val old = snapshot(connectionId, it.resourceType, it.wireId)
                if (old == null || compareDecimal(old.revision, it.revision) <= 0) putSnapshots(listOf(it))
            }
        } else {
            require(snapshots.isEmpty())
            conflicts.forEach { require(it.connectionId == connectionId); validateConflict(it) }
            putConflicts(conflicts)
        }
        putCursor(NodusCursor(connectionId, "v2", stream, pageCursor, if (hasMore) pageUntil else null))
    }

    /** Conflict discovery must restart at zero to observe changed resolution states.
     * This never resets the domain feed or removes any retained conflict/evidence.
     */
    @Transaction
    open suspend fun restartConflictDiscovery(connectionId: String, expectedCursor: String, expectedUntil: String?) {
        requireConnection(connectionId)
        val current = cursor(connectionId, "v2", NodusStream.CONFLICTS)
        require((current?.cursor ?: "0") == expectedCursor && current?.until == expectedUntil)
        putCursor(NodusCursor(connectionId, "v2", NodusStream.CONFLICTS, "0", null))
    }

    /** Blob revisions do not participate in domain cursors. Preserve full metadata separately. */
    @Transaction
    open suspend fun storeBlobMetadata(value: NodusSnapshot) {
        requireConnection(value.connectionId)
        require(value.resourceType == NodusResourceType.BLOB && !value.tombstone)
        val blob = NodusJson.decode(V2Blob.serializer(), wireText(value.body))
        require(blob.id == value.wireId && blob.revision == value.revision)
        val old = snapshot(value.connectionId, value.resourceType, value.wireId)
        if (old == null || compareDecimal(old.revision, value.revision) <= 0) putSnapshots(listOf(value))
    }

    /** Persist an explicitly supplied local graph, relationships, identities and canonical base.
     * IDs must already be allocated; no title matching, alarm scheduling or repository hooks.
     */
    @Transaction
    open suspend fun storeAggregate(
        connectionId: String, note: Note, notebooks: List<Notebook>, newMappings: List<NodusMapping>,
        base: NodusTracking, expectedGeneration: Long
    ) {
        require(base.connectionId == connectionId && base.generation == expectedGeneration)
        val previous = tracking(connectionId, base.mappingId)
        require((previous?.generation ?: 0L) == expectedGeneration)
        requireNotNull(base.baseRevision).also(::requireDecimal)
        require(previous?.baseRevision == null || compareDecimal(base.baseRevision, previous.baseRevision) >= 0)
        addMappings(connectionId, newMappings)
        val root = requireNotNull(mapping(connectionId, base.mappingId))
        require(root.resourceType == NodusResourceType.NOTE && root.localRowId == note.id)
        require(base.baseBody != null)
        val canonical = NodusJson.decode(V2Note.serializer(), wireText(base.baseBody))
        require(canonical.id == root.wireId && canonical.revision == base.baseRevision)
        require(note.tags.all { mappingByLocalRow(connectionId, NodusResourceType.TAG, it.id) != null })
        require(note.reminders.all { mappingByLocalRow(connectionId, NodusResourceType.REMINDER, it.id, root.wireId) != null })
        require(note.notebookId == null || mappingByLocalRow(connectionId, NodusResourceType.NOTEBOOK, note.notebookId) != null)
        writeGraph(note, notebooks)
        putTracking(base)
    }

    /** A caller may include the changed aggregate so relation-only edits and intent commit together. */
    @Transaction
    open suspend fun recordLocalChange(
        connectionId: String, mappingId: String, expectedGeneration: Long, intentId: String,
        intentBody: ByteArray, note: Note? = null, notebooks: List<Notebook> = emptyList()
    ): Long {
        requireConnection(connectionId); id(intentId)
        val mapping = requireNotNull(mapping(connectionId, mappingId))
        val previous = tracking(connectionId, mappingId) ?: NodusTracking(connectionId, mappingId, 0, null, null)
        require(previous.generation == expectedGeneration && expectedGeneration < Long.MAX_VALUE)
        if (note != null) {
            val root = if (mapping.parentId.isEmpty()) mapping else mappingByWire(connectionId, NodusResourceType.NOTE, mapping.parentId)
            require(root?.resourceType == NodusResourceType.NOTE && root.localRowId == note.id)
            writeGraph(note, notebooks)
        } else require(notebooks.isEmpty())
        val next = expectedGeneration + 1
        insertIntent(NodusIntent(connectionId, intentId, mappingId, next, intentBody))
        putTracking(previous.copy(generation = next))
        return next
    }

    @Transaction
    open suspend fun prepare(value: NodusOutbox) {
        val connection = requireConnection(value.connectionId)
        val intent = requireNotNull(intent(value.connectionId, value.intentId))
        require(intent.mappingId == value.mappingId && intent.generation == value.generation)
        require(value.credentialEpoch == connection.credentialEpoch && value.deviceId == connection.deviceId)
        require(value.apiVersion == "v2" && value.state == NodusOutboxState.PREPARED)
        val target = requireNotNull(mapping(value.connectionId, value.mappingId))
        id(value.operationId); id(value.requestId)
        require(value.method in setOf("PUT", "POST", "PATCH", "DELETE"))
        require(Regex("/api/v2/[A-Za-z0-9_/-]+").matches(value.path))
        val path = value.path.split('/')
        require(path.size >= 5)
        if (path[3] != "conflicts") {
            val (type, wireId) = receiptTarget(target)
            require(path[3] == type + "s" && path[4] == wireId)
        }
        if (value.contentType == "application/octet-stream") {
            require(value.method == "PUT" && Regex("/api/v2/blobs/[A-Za-z0-9_-]{1,128}/content").matches(value.path))
            require(value.body == null)
            id(requireNotNull(value.sourceFileId))
            require(Regex("[0-9a-f]{64}").matches(requireNotNull(value.sourceSha256)))
            requireDecimal(requireNotNull(value.sourceSize), "536870912")
        } else {
            require(value.contentType == "application/json" && requireNotNull(value.body).size <= 1_048_576)
            require(value.sourceFileId == null && value.sourceSha256 == null && value.sourceSize == null)
            val body = wireText(value.body)
            require(body.toByteArray(Charsets.UTF_8).contentEquals(value.body))
            val format = NodusJson.format
            when {
                value.method == "POST" && Regex("/api/v2/conflicts/[A-Za-z0-9_-]{1,128}/apply").matches(value.path) ->
                    NodusJson.decode(V2Apply.serializer(), body)
                value.method == "POST" && Regex("/api/v2/conflicts/[A-Za-z0-9_-]{1,128}/discard").matches(value.path) ->
                    NodusJson.decode(V2Discard.serializer(), body)
                else -> {
                    // Embed raw input, not a parsed/re-encoded object, so duplicate keys fail.
                    val envelope = "{\"apiVersion\":\"v2\",\"method\":\"${value.method}\",\"path\":\"${value.path}\",\"input\":$body,\"submittedBody\":\"\"}"
                    val proposal = NodusJson.decode(V2Proposal.serializer(), envelope)
                    require(proposal.input !is V2CreateNoteApplyEvidenceInput && proposal.input !is V2OrganizationCreateApplyEvidenceInput && proposal.input !is V2BlobReserveApplyEvidenceInput)
                }
            }
            val fields = format.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
            require(fields["deviceId"] == kotlinx.serialization.json.JsonPrimitive(value.deviceId))
            require(fields["requestId"] == kotlinx.serialization.json.JsonPrimitive(value.requestId))
            if (target.resourceType in children && path[3] != "conflicts") {
                val collection = when (target.resourceType) {
                    NodusResourceType.ITEM -> "items"
                    NodusResourceType.REMINDER -> "reminders"
                    NodusResourceType.ATTACHMENT -> "attachments"
                    else -> error("Not a child")
                }
                require(path.size >= 6 && path[5] == collection)
                if (value.method == "POST" && path.size == 6) {
                    val field = if (target.resourceType == NodusResourceType.ITEM) "itemId" else "id"
                    require(fields[field] == kotlinx.serialization.json.JsonPrimitive(target.wireId))
                } else require(path.size >= 7 && path[6] == target.wireId)
            }
        }
        insertOutbox(value)
    }

    /** Durable execution marker only, not network authorization. Future transport must check
     * live configuration and reverify any immutable source file before every send/retry.
     */
    @Transaction
    open suspend fun markSent(connectionId: String, operationId: String) {
        val op = requireNotNull(outbox(connectionId, operationId))
        require(op.state == NodusOutboxState.PREPARED)
        require(requireConnection(connectionId).credentialEpoch == op.credentialEpoch)
        updateOutboxState(connectionId, operationId, NodusOutboxState.SENT)
    }

    /** Receipt/evidence insert precedes the state change in one transaction. No bytes are deleted. */
    @Transaction
    open suspend fun recordOutcome(value: NodusEvidence) {
        val op = requireNotNull(outbox(value.connectionId, requireNotNull(value.operationId)))
        require(value.credentialEpoch == op.credentialEpoch)
        require(op.state in setOf(NodusOutboxState.PREPARED, NodusOutboxState.SENT, NodusOutboxState.UNKNOWN))
        id(value.evidenceId)
        require(value.resourceRevision == null) { "Canonical acknowledgement metadata is DAO-owned" }
        var stored = value
        val state = when (value.kind) {
            NodusEvidenceKind.UNKNOWN -> NodusOutboxState.UNKNOWN
            NodusEvidenceKind.RECEIPT -> {
                val result = NodusOutcome.classify(requireNotNull(value.httpStatus), wireText(requireNotNull(value.body)), op.path.endsWith("/discard"))
                require(result.kind in setOf(OutcomeKind.SUCCESS, OutcomeKind.DISCARDED))
                if (result.kind == OutcomeKind.DISCARDED) {
                    require(result.discardReceipt?.conflictId == op.path.split('/')[4])
                } else {
                    val (type, wireId) = receiptTarget(requireNotNull(mapping(op.connectionId, op.mappingId)))
                    require(result.receipt?.resourceType?.name?.lowercase() == type && result.receipt.resourceId == wireId)
                    if (op.contentType == "application/octet-stream") require(result.receipt.state == WireField.Present(BlobState.READY))
                    val revision = result.receipt.revision
                    stored = value.copy(resourceType = NodusResourceType.valueOf(type.uppercase()), resourceId = wireId,
                        resourceRevision = revision, resourceRevisionDigits = revision.length)
                }
                NodusOutboxState.RECEIPTED
            }
            NodusEvidenceKind.CONFLICT -> {
                val result = NodusOutcome.classify(requireNotNull(value.httpStatus), wireText(requireNotNull(value.body)))
                require(result.kind in setOf(OutcomeKind.DURABLE_CONFLICT, OutcomeKind.TERMINAL_CONFLICT))
                require(result.error?.conflictId == WireField.Present(value.conflictId))
                NodusOutboxState.CONFLICT
            }
        }
        insertEvidence(stored)
        updateOutboxState(op.connectionId, op.operationId, state)
    }

    @Transaction
    open suspend fun retire(connectionId: String, operationId: String) {
        val op = requireNotNull(outbox(connectionId, operationId))
        require(op.state in setOf(NodusOutboxState.RECEIPTED, NodusOutboxState.CONFLICT))
        require(latestEvidence(connectionId, operationId)?.kind in setOf(NodusEvidenceKind.RECEIPT, NodusEvidenceKind.CONFLICT))
        updateOutboxState(connectionId, operationId, NodusOutboxState.RETIRED)
    }

    @Query("SELECT EXISTS(SELECT 1 FROM nodus_attachments a JOIN nodus_mappings m ON m.connectionId=a.connectionId AND m.resourceType='NOTE' AND m.wireId=a.noteId JOIN notes n ON n.id=m.localRowId WHERE a.connectionId=:connectionId AND a.blobId=:blobId AND m.localRowDetached=0 AND n.isLocalOnly=0)")
    abstract suspend fun hasPublicBlobReference(connectionId: String, blobId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM nodus_attachments WHERE connectionId=:connectionId AND noteId=:noteId AND state='ERROR' AND (errorCode IS NULL OR errorCode NOT LIKE 'projection:%'))")
    abstract suspend fun hasBlockedAttachment(connectionId: String, noteId: String): Boolean

    @Query("SELECT COALESCE(SUM(CAST(size AS INTEGER)),0) FROM nodus_blobs WHERE connectionId=:connectionId AND sourceUri IS NOT NULL")
    abstract suspend fun retainedBlobBytes(connectionId: String): Long

    @Transaction
    open suspend fun storeReadiness(blob: NodusBlobTransfer, attachments: List<NodusAttachmentTransfer>) {
        requireConnection(blob.connectionId); id(blob.blobId)
        require((blob.size == null) == (blob.sha256 == null))
        if (blob.state !in setOf(NodusReadiness.LOCAL, NodusReadiness.ERROR)) require(blob.size != null)
        blob.size?.let { requireDecimal(it, "536870912") }
        blob.sha256?.let { require(Regex("[0-9a-f]{64}").matches(it)) }
        val old = blob(blob.connectionId, blob.blobId)
        if (old != null) {
            require(old.size == null || old.size == blob.size)
            require(old.sha256 == null || old.sha256 == blob.sha256)
            require(old.sourceUri == null || old.sourceUri == blob.sourceUri)
            require(old.reservationOperationId == null || old.reservationOperationId == blob.reservationOperationId)
            require(old.uploadOperationId == null || old.uploadOperationId == blob.uploadOperationId)
        }
        suspend fun operation(operationId: String, path: String, contentType: String): NodusOutbox {
            val op = requireNotNull(outbox(blob.connectionId, operationId))
            require(op.path == path && op.contentType == contentType)
            return op
        }
        blob.reservationOperationId?.let {
            val op = operation(it, "/api/v2/blobs/${blob.blobId}", "application/json")
            require(op.method == "PUT")
            val input = NodusJson.decode(V2BlobReserve.serializer(), wireText(requireNotNull(op.body)))
            require(input.size == blob.size && input.sha256 == blob.sha256)
        }
        blob.uploadOperationId?.let {
            val op = operation(it, "/api/v2/blobs/${blob.blobId}/content", "application/octet-stream")
            require(op.method == "PUT" && op.sourceSha256 == blob.sha256 && op.sourceSize == blob.size)
            val source = java.net.URI(requireNotNull(blob.sourceUri))
            require(source.scheme == "file" && source.path.substringAfterLast('/') == "${op.sourceFileId}.bin")
        }
        attachments.forEach {
            require(it.connectionId == blob.connectionId && it.blobId == blob.blobId); id(it.noteId); id(it.attachmentId)
            val previous = attachment(it.connectionId, it.noteId, it.attachmentId)
            require(previous == null || previous.blobId == it.blobId)
            if (previous?.draftBody != null && it.draftBody != null) require(previous.draftBody.contentEquals(it.draftBody))
            require(previous?.operationId == null || it.operationId == null || previous.operationId == it.operationId)
            val draft = it.draftBody ?: previous?.draftBody
            draft?.let { bytes ->
                val input = NodusJson.decode(V2AttachmentInput.serializer(), wireText(bytes))
                require(input.id == it.attachmentId && input.blobId == it.blobId)
            }
            val linked = it.operationId ?: previous?.operationId
            linked?.let { linkedId ->
                val op = requireNotNull(outbox(it.connectionId,linkedId))
                val target = requireNotNull(mapping(it.connectionId, op.mappingId))
                val frozen=if(target.resourceType==NodusResourceType.NOTE) {
                    require(target.wireId==it.noteId && op.path=="/api/v2/notes/${it.noteId}" && op.method=="PUT" && op.contentType=="application/json")
                    val input=NodusJson.decode(V2CreateNote.serializer(),wireText(requireNotNull(op.body)))
                    (input.attachments as WireField.Present).value.single { ref -> ref.id==it.attachmentId }
                } else {
                    require(op.path=="/api/v2/notes/${it.noteId}/attachments" && op.contentType=="application/json" && op.method=="POST")
                    require(target.resourceType == NodusResourceType.ATTACHMENT && target.parentId == it.noteId && target.wireId == it.attachmentId)
                    val input = NodusJson.decode(V2AttachmentCreate.serializer(), wireText(requireNotNull(op.body)))
                    V2AttachmentInput(input.id, input.blobId, input.kind, input.description, input.fileName)
                }
                require(requireNotNull(draft).contentEquals(NodusJson.encode(V2AttachmentInput.serializer(), frozen).toByteArray()))
            }
            putAttachment(it.copy(draftBody = draft, operationId = linked))
        }
        putBlob(blob)
    }

    private suspend fun writeGraph(note: Note, notebooks: List<Notebook>) {
        require(note.id > 0 && note.tags.all { it.id > 0 } && notebooks.all { it.id > 0 })
        require(note.reminders.all { it.id > 0 && it.noteId == note.id })
        putNotebooks(notebooks); putTags(note.tags); putNote(note.toEntity())
        clearJoins(note.id); clearReminders(note.id)
        insertJoins(note.tags.map { NoteTagJoin(it.id, note.id) }); insertReminders(note.reminders)
    }

    private fun receiptTarget(mapping: NodusMapping): Pair<String, String> =
        if (mapping.resourceType in children) "note" to mapping.parentId
        else mapping.resourceType.name.lowercase() to mapping.wireId

    private fun wireText(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).also {
        require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Invalid UTF-8 document" }
    }

    private suspend fun requireConnection(id: String) = requireNotNull(connection(id))
    private fun id(value: String) { require(Regex("[A-Za-z0-9_-]{1,128}").matches(value)) }
    private fun compareDecimal(a: String, b: String) = if (a.length == b.length) a.compareTo(b) else a.length.compareTo(b.length)
    private fun validateConflict(value: NodusConflictRecord) {
        val decoded = NodusJson.decode(V2Conflict.serializer(), wireText(value.body))
        require(decoded.id == value.conflictId && decoded.parent == value.parentId && decoded.state.name.lowercase() == value.state)
    }
    private fun validateSnapshot(value: NodusSnapshot) {
        val body = wireText(value.body)
        val fields = when (value.resourceType) {
            NodusResourceType.NOTE -> NodusJson.decode(V2Note.serializer(), body).let { Triple(it.id, it.revision, it.state == NoteState.PURGED) }
            NodusResourceType.TAG -> NodusJson.decode(V2Tag.serializer(), body).let { Triple(it.id, it.revision, it.deleted) }
            NodusResourceType.NOTEBOOK -> NodusJson.decode(V2Notebook.serializer(), body).let { Triple(it.id, it.revision, it.deleted) }
            else -> error("Only domain aggregates enter the feed")
        }
        require(fields == Triple(value.wireId, value.revision, value.tombstone))
    }
    private companion object {
        val children = setOf(NodusResourceType.ITEM, NodusResourceType.REMINDER, NodusResourceType.ATTACHMENT)
    }
}

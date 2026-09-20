package org.qosp.notes.data.sync.nodus.storage

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index

// Deliberately no foreign keys to local content (or cascade deletes). These records are
// retained safety state, not owned by the lifetime of a local note row or credential.
@Entity(tableName = "nodus_connections", primaryKeys = ["connectionId"],
    indices = [Index(value = ["origin", "ownerKey"], unique = true)])
data class NodusConnection(
    val connectionId: String,
    val origin: String,
    val ownerKey: String,
    val deviceId: String,
    val credentialEpoch: String,
    val realmId: String? = null,
    val inactiveGraph: String? = null,
    val checkpointDeviceId: String? = null,
    val checkpointEpoch: String? = null,
    val checkpointRemovedReminders: String? = null
)

@Entity(tableName = "nodus_mappings", primaryKeys = ["connectionId", "mappingId"], indices = [
    Index(value = ["connectionId", "resourceType", "parentId", "wireId"], unique = true),
    Index(value = ["connectionId", "resourceType", "parentId", "localKey"], unique = true),
    Index(value = ["connectionId", "resourceType", "parentId", "localRowId"], unique = true)
])
data class NodusMapping(
    val connectionId: String,
    val mappingId: String,
    val resourceType: NodusResourceType,
    // Empty for independent resources; wire note ID for note-local children.
    val parentId: String,
    val wireId: String,
    // Lifetime local identity, not title/path. A recreated/reused task row needs a new key.
    val localKey: String,
    // Nullable active local link: explicit release permits local row-ID reuse without
    // reusing a wire/lifetime identity. Historical mappings themselves remain immutable.
    val localRowId: Long?,
    @ColumnInfo(defaultValue = "0") val localRowDetached: Boolean = false
)

enum class NodusResourceType { NOTE, ITEM, TAG, NOTEBOOK, REMINDER, ATTACHMENT, BLOB }

@Entity(tableName = "nodus_snapshots", primaryKeys = ["connectionId", "resourceType", "wireId"])
data class NodusSnapshot(
    val connectionId: String,
    val resourceType: NodusResourceType,
    val wireId: String,
    val revision: String,
    // Full canonical UTF-8 aggregate including inactive content and child tombstones.
    val body: ByteArray,
    val tombstone: Boolean
)

@Entity(tableName = "nodus_tracking", primaryKeys = ["connectionId", "mappingId"])
data class NodusTracking(
    val connectionId: String,
    val mappingId: String,
    val generation: Long,
    val baseRevision: String?,
    val baseBody: ByteArray?
)

@Entity(tableName = "nodus_intents", primaryKeys = ["connectionId", "intentId"], indices = [
    Index(value = ["connectionId", "mappingId", "generation"], unique = true)
])
data class NodusIntent(
    val connectionId: String,
    val intentId: String,
    val mappingId: String,
    val generation: Long,
    val body: ByteArray
)

enum class NodusOutboxState { PREPARED, SENT, UNKNOWN, RECEIPTED, CONFLICT, RETIRED }

@Entity(tableName = "nodus_outbox", primaryKeys = ["connectionId", "operationId"], indices = [
    Index(value = ["connectionId", "credentialEpoch", "deviceId", "requestId"], unique = true),
    Index(value = ["connectionId", "intentId"])
])
data class NodusOutbox(
    val connectionId: String,
    val operationId: String,
    val intentId: String,
    val mappingId: String,
    val generation: Long,
    val apiVersion: String,
    val method: String,
    val path: String,
    val contentType: String,
    // JSON: exact bytes, never regenerated for retries. Binary: immutable app-owned file
    // identity plus verified digest/size. Later transport must reverify the retained file.
    val body: ByteArray?,
    val sourceFileId: String?,
    val sourceSha256: String?,
    val sourceSize: String?,
    val deviceId: String,
    val requestId: String,
    val credentialEpoch: String,
    val state: NodusOutboxState
)

@Entity(tableName = "nodus_cursors", primaryKeys = ["connectionId", "apiVersion", "stream"])
data class NodusCursor(
    val connectionId: String,
    val apiVersion: String,
    val stream: NodusStream,
    val cursor: String,
    val until: String?
)

data class NodusIntentQueueRow(@Embedded val intent: NodusIntent, val scanRowId: Long)
data class NodusOutboxQueueRow(@Embedded val operation: NodusOutbox, val scanRowId: Long)

enum class NodusStream { CHANGES, CONFLICTS }
enum class NodusEvidenceKind { RECEIPT, CONFLICT, UNKNOWN }

@Entity(tableName = "nodus_evidence", primaryKeys = ["connectionId", "evidenceId"], indices = [
    Index(value = ["connectionId", "operationId"]),
    Index(value = ["connectionId", "resourceType", "resourceId", "resourceRevisionDigits", "resourceRevision"])
])
data class NodusEvidence(
    val connectionId: String,
    val evidenceId: String,
    val operationId: String?,
    val credentialEpoch: String?,
    val kind: NodusEvidenceKind,
    val httpStatus: Int?,
    val body: ByteArray?,
    val conflictId: String?,
    val errorCode: String?,
    // DAO-derived canonical acknowledgement floor; never a conflict/discard revision.
    val resourceType: NodusResourceType? = null,
    val resourceId: String? = null,
    val resourceRevision: String? = null,
    val resourceRevisionDigits: Int? = null
) {
    init {
        if (resourceRevision == null) require(resourceType == null && resourceId == null && resourceRevisionDigits == null)
        else {
            org.qosp.notes.data.sync.nodus.requireDecimal(resourceRevision)
            require(resourceType in setOf(NodusResourceType.NOTE, NodusResourceType.TAG, NodusResourceType.NOTEBOOK, NodusResourceType.BLOB))
            require(resourceId != null && Regex("[A-Za-z0-9_-]{1,128}").matches(resourceId))
            require(resourceRevisionDigits == resourceRevision.length && kind == NodusEvidenceKind.RECEIPT)
        }
    }
}

@Entity(tableName = "nodus_conflicts", primaryKeys = ["connectionId", "conflictId"])
data class NodusConflictRecord(
    val connectionId: String,
    val conflictId: String,
    val state: String,
    val parentId: String,
    val body: ByteArray
)

enum class NodusReadiness { LOCAL, RESERVED, UPLOADING, READY, DOWNLOADING, AVAILABLE, ERROR }

@Entity(tableName = "nodus_blobs", primaryKeys = ["connectionId", "blobId"])
data class NodusBlobTransfer(
    val connectionId: String,
    val blobId: String,
    // Unknown until source bytes are accessible. Prepared uploads require both separately.
    val size: String?,
    val sha256: String?,
    val sourceUri: String?,
    val state: NodusReadiness,
    val errorCode: String?,
    val reservationOperationId: String? = null,
    val uploadOperationId: String? = null
)

@Entity(tableName = "nodus_attachments", primaryKeys = ["connectionId", "noteId", "attachmentId"])
data class NodusAttachmentTransfer(
    val connectionId: String,
    val noteId: String,
    val attachmentId: String,
    val blobId: String,
    val sourceUri: String?,
    val state: NodusReadiness,
    val errorCode: String?,
    val draftBody: ByteArray? = null,
    val operationId: String? = null
)

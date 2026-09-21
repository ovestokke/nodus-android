@file:UseSerializers(StrictStringSerializer::class, StrictBooleanSerializer::class, StrictIntSerializer::class)

package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

internal sealed interface WireSnapshot

@Serializable
internal data class ItemInput(
    val id: String,
    val text: WireField<String> = WireField.Absent,
    val checked: WireField<Boolean> = WireField.Absent
) {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
    }
}

@Serializable
internal data class Item(
    val id: String,
    val text: String,
    val checked: Boolean,
    val position: Int,
    val revision: String,
    val deleted: Boolean
) {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
        require(position >= 0)
        requireDecimal(revision)
    }
}

@Serializable
internal data class Reminder(
    val dueAt: String
) {
    init {
        requireInstant(dueAt)
    }
}

@Serializable
internal data class V2ReminderInput(
    val id: String,
    val name: WireField<String> = WireField.Absent,
    val dueAt: String
) {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
        requireInstant(dueAt)
    }
}

@Serializable
internal data class V2Reminder(
    val id: String,
    val name: String,
    val dueAt: String,
    val deleted: Boolean
) {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
        requireInstant(dueAt)
    }
}

@Serializable
internal data class V2AttachmentInput(
    val id: String,
    val blobId: String,
    val kind: AttachmentKind,
    val description: WireField<String> = WireField.Absent,
    val fileName: WireField<String> = WireField.Absent
) {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(blobId)) { "Invalid wire field" }
        fileName.ifPresent { value ->
            requireUtf8(value, 1024)
        }
    }
}

@Serializable
internal data class V2Attachment(
    val id: String,
    val blobId: String,
    val kind: AttachmentKind,
    val description: String,
    val fileName: String,
    val position: Int,
    val deleted: Boolean
) {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(blobId)) { "Invalid wire field" }
        requireUtf8(fileName, 1024)
        require(position >= 0)
    }
}

@Serializable
internal data class V2Note(
    val id: String,
    val kind: NoteKind,
    val title: String,
    val text: String,
    val archived: Boolean,
    val pinned: Boolean,
    val hidden: Boolean,
    val markdownEnabled: Boolean,
    val color: NoteColor,
    val notebookId: String?,
    val tagIds: List<String>,
    val primaryReminderId: String?,
    val items: List<Item>,
    val revision: String,
    val created: String,
    val updated: String,
    val authoredAt: String,
    val editedAt: String,
    val state: NoteState,
    val trashedAt: String?,
    val sourceTrashedAt: String?,
    val attachments: List<V2Attachment>,
    val reminders: List<V2Reminder>
) : WireSnapshot {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
        notebookId?.let { value -> require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(value)) { "Invalid wire field" } }
        require(tagIds.size <= 1000)
        require(tagIds.distinct().size == tagIds.size)
        tagIds.forEach { value -> require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(value)) { "Invalid wire field" } }
        primaryReminderId?.let { value -> require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(value)) { "Invalid wire field" } }
        require(items.size <= 1000)
        requireUniqueIds(items.map { it.id })
        requireDecimal(revision)
        requireInstant(created)
        requireInstant(updated)
        requireInstant(authoredAt)
        requireInstant(editedAt)
        trashedAt?.let { value -> requireInstant(value) }
        sourceTrashedAt?.let { value -> requireInstant(value) }
        require(attachments.size <= 1000)
        requireUniqueIds(attachments.map { it.id })
        require(reminders.size <= 1000)
        requireUniqueIds(reminders.map { it.id })
    }
}

@Serializable
internal data class V2Tag(
    val id: String,
    val name: String,
    val revision: String,
    val created: String,
    val updated: String,
    val deleted: Boolean
) : WireSnapshot {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
        requireUtf8(name, 4096)
        requireDecimal(revision)
        requireInstant(created)
        requireInstant(updated)
    }
}

@Serializable
internal data class V2Notebook(
    val id: String,
    val name: String,
    val revision: String,
    val created: String,
    val updated: String,
    val deleted: Boolean
) : WireSnapshot {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
        requireUtf8(name, 4096)
        requireDecimal(revision)
        requireInstant(created)
        requireInstant(updated)
    }
}

@Serializable
internal data class V2Blob(
    val id: String,
    val size: String,
    val sha256: String,
    val mediaType: String,
    val state: BlobState,
    val revision: String,
    val created: String
) : WireSnapshot {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
        requireDecimal(size)
        requireDecimal(size, "536870912")
        require(Regex("^[0-9a-f]{64}$").matches(sha256)) { "Invalid wire field" }
        requireMediaType(mediaType)
        requireDecimal(revision)
        requireInstant(created)
    }
}

@Serializable
internal data class V2Capabilities(
    val contractVersion: String,
    val realmId: String,
    val features: List<CapabilityFeature>,
    val limits: V2CapabilitiesLimits
) {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(realmId)) { "Invalid realm ID" }
        require(contractVersion == "2.0") { "Unsupported contract value" }
        require(features.size <= 6)
        require(features.size >= 6)
        require(features.distinct().size == features.size)
    }
}

@Serializable
internal data class V2Receipt(
    val resourceType: ResourceType,
    val resourceId: String,
    val revision: String,
    val state: WireField<BlobState> = WireField.Absent
) {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(resourceId)) { "Invalid wire field" }
        requireDecimal(revision)
        if (resourceType != ResourceType.BLOB) require(state is WireField.Absent)
    }
}

@Serializable
internal data class DiscardReceipt(
    val conflictId: String,
    val revision: String,
    val state: String
) {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(conflictId)) { "Invalid wire field" }
        requireDecimal(revision)
        require(state == "discarded") { "Unsupported contract value" }
    }
}

@Serializable
internal data class V2Changes(
    val events: List<V2Event>,
    val cursor: String,
    val until: String,
    val hasMore: Boolean
) {
    init {
        require(events.size <= 100)
        requireDecimal(cursor)
        requireDecimal(until)
    }
}

@Serializable
internal data class V2Conflicts(
    val conflicts: List<V2Conflict>,
    val cursor: String,
    val until: String,
    val hasMore: Boolean
) {
    init {
        require(conflicts.size <= 100)
        requireDecimal(cursor)
        requireDecimal(until)
    }
}

@Serializable
internal data class V2CapabilitiesLimits(
    val mutationBytes: String,
    val noteBytes: String,
    val attachmentBytes: String,
    val totalBlobBytes: String,
    val organizationNameBytes: String,
    val attachmentFileNameBytes: String,
    val lifetimeItemsPerNote: Int,
    val lifetimeAttachmentsPerNote: Int,
    val lifetimeRemindersPerNote: Int,
    val liveTagsPerNote: Int,
    val feedPageMaximum: Int
) {
    init {
        require(mutationBytes == "1048576") { "Unsupported contract value" }
        require(noteBytes == "1048576") { "Unsupported contract value" }
        require(attachmentBytes == "536870912") { "Unsupported contract value" }
        require(totalBlobBytes == "21474836480") { "Unsupported contract value" }
        require(organizationNameBytes == "4096") { "Unsupported contract value" }
        require(attachmentFileNameBytes == "1024") { "Unsupported contract value" }
        require(lifetimeItemsPerNote == 1000) { "Unsupported contract value" }
        require(lifetimeAttachmentsPerNote == 1000) { "Unsupported contract value" }
        require(lifetimeRemindersPerNote == 1000) { "Unsupported contract value" }
        require(liveTagsPerNote == 1000) { "Unsupported contract value" }
        require(feedPageMaximum == 100) { "Unsupported contract value" }
    }
}

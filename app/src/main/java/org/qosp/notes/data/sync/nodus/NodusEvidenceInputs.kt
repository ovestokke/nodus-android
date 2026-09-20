@file:UseSerializers(StrictStringSerializer::class, StrictBooleanSerializer::class, StrictIntSerializer::class)

package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// Read-only evidence variants never widen live create/reservation requests.
@Serializable
internal data class LegacyCreate(
    val deviceId: String,
    val requestId: String,
    val kind: NoteKind,
    val title: WireField<String> = WireField.Absent,
    val text: WireField<String> = WireField.Absent,
    val items: WireField<List<ItemInput>> = WireField.Absent,
    val reminder: WireField<Reminder?> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        items.ifPresent { value ->
            require(value.size <= 1000)
            requireUniqueIds(value.map { it.id })
        }
        if (kind == NoteKind.TEXT) require(items is WireField.Absent)
        if (kind == NoteKind.CHECKLIST) require(text is WireField.Absent)
    }
}

@Serializable
internal data class LegacyEdit(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val title: WireField<String> = WireField.Absent,
    val text: WireField<String> = WireField.Absent,
    val reminder: WireField<Reminder?> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
    }
}

@Serializable
internal data class LegacyAppend(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val itemId: String,
    val text: String,
    val checked: WireField<Boolean> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(itemId)) { "Invalid wire field" }
    }
}

@Serializable
internal data class LegacyItemEdit(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val text: WireField<String> = WireField.Absent,
    val checked: WireField<Boolean> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
    }
}

@Serializable
internal data class LegacyToggle(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val checked: Boolean
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
    }
}

@Serializable
internal data class LegacyReorder(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val order: WireField<List<String>> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        order.ifPresent { value ->
            require(value.size <= 1000)
            require(value.distinct().size == value.size)
            value.forEach { value -> require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(value)) { "Invalid wire field" } }
        }
    }
}

@Serializable
internal data class LegacyDelete(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
    }
}

@Serializable
internal data class V2CreateNoteApplyEvidenceInput(
    val deviceId: String,
    val requestId: String,
    val kind: NoteKind,
    val title: WireField<String> = WireField.Absent,
    val text: WireField<String> = WireField.Absent,
    val archived: WireField<Boolean> = WireField.Absent,
    val pinned: WireField<Boolean> = WireField.Absent,
    val hidden: WireField<Boolean> = WireField.Absent,
    val markdownEnabled: WireField<Boolean> = WireField.Absent,
    val color: WireField<NoteColor> = WireField.Absent,
    val notebookId: WireField<String?> = WireField.Absent,
    val tagIds: WireField<List<String>> = WireField.Absent,
    val primaryReminderId: WireField<String?> = WireField.Absent,
    val items: WireField<List<ItemInput>> = WireField.Absent,
    val attachments: WireField<List<V2AttachmentInput>> = WireField.Absent,
    val reminders: WireField<List<V2ReminderInput>> = WireField.Absent,
    val authoredAt: WireField<String> = WireField.Absent,
    val editedAt: WireField<String> = WireField.Absent,
    val state: WireField<InitialNoteState> = WireField.Absent,
    val sourceTrashedAt: WireField<String?> = WireField.Absent,
    val expectedRevision: String
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        notebookId.ifPresent { value ->
            value?.let { value -> require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(value)) { "Invalid wire field" } }
        }
        tagIds.ifPresent { value ->
            require(value.size <= 1000)
            require(value.distinct().size == value.size)
            value.forEach { value -> require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(value)) { "Invalid wire field" } }
        }
        primaryReminderId.ifPresent { value ->
            value?.let { value -> require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(value)) { "Invalid wire field" } }
        }
        items.ifPresent { value ->
            require(value.size <= 1000)
            requireUniqueIds(value.map { it.id })
        }
        attachments.ifPresent { value ->
            require(value.size <= 1000)
            requireUniqueIds(value.map { it.id })
        }
        reminders.ifPresent { value ->
            require(value.size <= 1000)
            requireUniqueIds(value.map { it.id })
        }
        authoredAt.ifPresent { value ->
            requireInstant(value)
        }
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
        sourceTrashedAt.ifPresent { value ->
            value?.let { value -> requireInstant(value) }
        }
        requireDecimal(expectedRevision)
        if (sourceTrashedAt is WireField.Present) require(state == WireField.Present(InitialNoteState.TRASH))
    }
}

@Serializable
internal data class V2OrganizationCreateApplyEvidenceInput(
    val deviceId: String,
    val requestId: String,
    val name: String,
    val expectedRevision: String
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireUtf8(name, 4096)
        requireDecimal(expectedRevision)
    }
}

@Serializable
internal data class V2BlobReserveApplyEvidenceInput(
    val deviceId: String,
    val requestId: String,
    val size: String,
    val sha256: String,
    val mediaType: String,
    val expectedRevision: String
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(size)
        requireDecimal(size, "536870912")
        require(Regex("^[0-9a-f]{64}$").matches(sha256)) { "Invalid wire field" }
        requireMediaType(mediaType)
        requireDecimal(expectedRevision)
    }
}

@Serializable
internal data class LegacyCreateApplyEvidenceInput(
    val deviceId: String,
    val requestId: String,
    val kind: NoteKind,
    val title: WireField<String> = WireField.Absent,
    val text: WireField<String> = WireField.Absent,
    val items: WireField<List<ItemInput>> = WireField.Absent,
    val reminder: WireField<Reminder?> = WireField.Absent,
    val expectedRevision: String
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        items.ifPresent { value ->
            require(value.size <= 1000)
            requireUniqueIds(value.map { it.id })
        }
        requireDecimal(expectedRevision)
        if (kind == NoteKind.TEXT) require(items is WireField.Absent)
        if (kind == NoteKind.CHECKLIST) require(text is WireField.Absent)
    }
}

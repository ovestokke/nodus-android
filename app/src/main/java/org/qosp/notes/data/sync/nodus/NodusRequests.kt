@file:UseSerializers(StrictStringSerializer::class, StrictBooleanSerializer::class, StrictIntSerializer::class)

package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// Operation-specific bodies; no production provider or DI registration.
internal sealed interface WireInput

@Serializable
internal data class V2CreateNote(
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
    val sourceTrashedAt: WireField<String?> = WireField.Absent
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
        if (sourceTrashedAt is WireField.Present) require(state == WireField.Present(InitialNoteState.TRASH))
    }
}

@Serializable
internal data class V2EditNote(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
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
    val editedAt: WireField<String> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
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
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2Content(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val kind: NoteKind,
    val text: WireField<String> = WireField.Absent,
    val items: WireField<List<ItemInput>> = WireField.Absent,
    val editedAt: WireField<String> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        items.ifPresent { value ->
            require(value.size <= 1000)
            requireUniqueIds(value.map { it.id })
        }
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2Trash(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val sourceTrashedAt: WireField<String?> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        sourceTrashedAt.ifPresent { value ->
            value?.let { value -> requireInstant(value) }
        }
    }
}

@Serializable
internal data class V2Lifecycle(
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
internal data class V2Append(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val itemId: String,
    val text: String,
    val checked: WireField<Boolean> = WireField.Absent,
    val editedAt: WireField<String> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(itemId)) { "Invalid wire field" }
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2ItemEdit(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val text: WireField<String> = WireField.Absent,
    val checked: WireField<Boolean> = WireField.Absent,
    val editedAt: WireField<String> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2Toggle(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val checked: Boolean,
    val editedAt: WireField<String> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2Order(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val order: WireField<List<String>> = WireField.Absent,
    val editedAt: WireField<String> = WireField.Absent
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
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2ChildDelete(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val editedAt: WireField<String> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2ReminderCreate(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val id: String,
    val name: WireField<String> = WireField.Absent,
    val dueAt: String,
    val makePrimary: WireField<Boolean> = WireField.Absent,
    val editedAt: WireField<String> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
        requireInstant(dueAt)
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2ReminderEdit(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val name: WireField<String> = WireField.Absent,
    val dueAt: WireField<String> = WireField.Absent,
    val editedAt: WireField<String> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        dueAt.ifPresent { value ->
            requireInstant(value)
        }
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2AttachmentCreate(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val id: String,
    val blobId: String,
    val kind: AttachmentKind,
    val description: WireField<String> = WireField.Absent,
    val fileName: WireField<String> = WireField.Absent,
    val editedAt: WireField<String> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(id)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(blobId)) { "Invalid wire field" }
        fileName.ifPresent { value ->
            requireUtf8(value, 1024)
        }
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2AttachmentEdit(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val kind: WireField<AttachmentKind> = WireField.Absent,
    val description: WireField<String> = WireField.Absent,
    val fileName: WireField<String> = WireField.Absent,
    val editedAt: WireField<String> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        fileName.ifPresent { value ->
            requireUtf8(value, 1024)
        }
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2AttachmentOrder(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val order: List<String>,
    val editedAt: WireField<String> = WireField.Absent
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        require(order.size <= 1000)
        require(order.distinct().size == order.size)
        order.forEach { value -> require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(value)) { "Invalid wire field" } }
        editedAt.ifPresent { value ->
            requireInstant(value)
        }
    }
}

@Serializable
internal data class V2OrganizationCreate(
    val deviceId: String,
    val requestId: String,
    val name: String
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireUtf8(name, 4096)
    }
}

@Serializable
internal data class V2OrganizationEdit(
    val deviceId: String,
    val requestId: String,
    val expectedRevision: String,
    val name: String
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(expectedRevision)
        requireUtf8(name, 4096)
    }
}

@Serializable
internal data class V2OrganizationDelete(
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
internal data class V2BlobReserve(
    val deviceId: String,
    val requestId: String,
    val size: String,
    val sha256: String,
    val mediaType: String
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
        requireDecimal(size)
        requireDecimal(size, "536870912")
        require(Regex("^[0-9a-f]{64}$").matches(sha256)) { "Invalid wire field" }
        requireMediaType(mediaType)
    }
}

@Serializable
internal data class V2Apply(
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
internal data class V2Discard(
    val deviceId: String,
    val requestId: String
) : WireInput {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(deviceId)) { "Invalid wire field" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "Invalid wire field" }
    }
}

@file:UseSerializers(StrictStringSerializer::class, StrictBooleanSerializer::class, StrictIntSerializer::class)

package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable(with = V2EventSerializer::class)
internal sealed interface V2Event {
    val revision: String
    @Serializable
    data class Note(override val revision: String, val resourceType: String, val resource: V2Note) : V2Event {
        init { require(resourceType == "note" && revision == resource.revision) }
    }
    @Serializable
    data class Tag(override val revision: String, val resourceType: String, val resource: V2Tag) : V2Event {
        init { require(resourceType == "tag" && revision == resource.revision) }
    }
    @Serializable
    data class Notebook(override val revision: String, val resourceType: String, val resource: V2Notebook) : V2Event {
        init { require(resourceType == "notebook" && revision == resource.revision) }
    }
}

internal object V2EventSerializer : JsonContentPolymorphicSerializer<V2Event>(V2Event::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<V2Event> =
        when (element.jsonObject["resourceType"]?.jsonPrimitive?.content) {
            "note" -> V2Event.Note.serializer()
            "tag" -> V2Event.Tag.serializer()
            "notebook" -> V2Event.Notebook.serializer()
            else -> throw SerializationException("Unknown event resource type")
        }
}

@Serializable
private data class ProposalEnvelope(
    val apiVersion: String,
    val method: String,
    val path: String,
    val input: JsonObject,
    val submittedBody: String
)

@Serializable(with = V2ProposalSerializer::class)
internal class V2Proposal(
    val apiVersion: String,
    val method: String,
    val path: String,
    val input: WireInput,
    val submittedBody: String
) {
    override fun toString() = "V2Proposal([REDACTED])"
}

internal object V2ProposalSerializer : KSerializer<V2Proposal> {
    override val descriptor: SerialDescriptor = ProposalEnvelope.serializer().descriptor
    @Suppress("UNCHECKED_CAST")
    private fun inputSerializer(version: String, method: String, path: String, hasExpectation: Boolean): KSerializer<WireInput> =
        (when {
        version == "v2" && method == "PUT" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}$").matches(path) -> if (hasExpectation) V2CreateNoteApplyEvidenceInput.serializer() else V2CreateNote.serializer()
        version == "v2" && method == "PATCH" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2EditNote.serializer()
        version == "v2" && method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/content$").matches(path) -> V2Content.serializer()
        version == "v2" && method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/trash$").matches(path) -> V2Trash.serializer()
        version == "v2" && method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/restore$").matches(path) -> V2Lifecycle.serializer()
        version == "v2" && method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/purge$").matches(path) -> V2Lifecycle.serializer()
        version == "v2" && method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/items$").matches(path) -> V2Append.serializer()
        version == "v2" && method == "PATCH" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/items/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2ItemEdit.serializer()
        version == "v2" && method == "DELETE" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/items/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2ChildDelete.serializer()
        version == "v2" && method == "PATCH" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/items/[A-Za-z0-9_-]{1,128}/checked$").matches(path) -> V2Toggle.serializer()
        version == "v2" && method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/order$").matches(path) -> V2Order.serializer()
        version == "v2" && method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/reminders$").matches(path) -> V2ReminderCreate.serializer()
        version == "v2" && method == "PATCH" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/reminders/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2ReminderEdit.serializer()
        version == "v2" && method == "DELETE" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/reminders/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2ChildDelete.serializer()
        version == "v2" && method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/attachments$").matches(path) -> V2AttachmentCreate.serializer()
        version == "v2" && method == "PATCH" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/attachments/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2AttachmentEdit.serializer()
        version == "v2" && method == "DELETE" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/attachments/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2ChildDelete.serializer()
        version == "v2" && method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/attachments/order$").matches(path) -> V2AttachmentOrder.serializer()
        version == "v2" && method == "PUT" && Regex("^/api/v2/tags/[A-Za-z0-9_-]{1,128}$").matches(path) -> if (hasExpectation) V2OrganizationCreateApplyEvidenceInput.serializer() else V2OrganizationCreate.serializer()
        version == "v2" && method == "PATCH" && Regex("^/api/v2/tags/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2OrganizationEdit.serializer()
        version == "v2" && method == "DELETE" && Regex("^/api/v2/tags/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2OrganizationDelete.serializer()
        version == "v2" && method == "PUT" && Regex("^/api/v2/notebooks/[A-Za-z0-9_-]{1,128}$").matches(path) -> if (hasExpectation) V2OrganizationCreateApplyEvidenceInput.serializer() else V2OrganizationCreate.serializer()
        version == "v2" && method == "PATCH" && Regex("^/api/v2/notebooks/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2OrganizationEdit.serializer()
        version == "v2" && method == "DELETE" && Regex("^/api/v2/notebooks/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2OrganizationDelete.serializer()
        version == "v2" && method == "PUT" && Regex("^/api/v2/blobs/[A-Za-z0-9_-]{1,128}$").matches(path) -> if (hasExpectation) V2BlobReserveApplyEvidenceInput.serializer() else V2BlobReserve.serializer()
        version == "v1" && method == "PUT" && Regex("^/api/v1/notes/[A-Za-z0-9_-]{1,128}$").matches(path) -> if (hasExpectation) LegacyCreateApplyEvidenceInput.serializer() else LegacyCreate.serializer()
        version == "v1" && method == "PATCH" && Regex("^/api/v1/notes/[A-Za-z0-9_-]{1,128}$").matches(path) -> LegacyEdit.serializer()
        version == "v1" && method == "DELETE" && Regex("^/api/v1/notes/[A-Za-z0-9_-]{1,128}$").matches(path) -> LegacyDelete.serializer()
        version == "v1" && method == "POST" && Regex("^/api/v1/notes/[A-Za-z0-9_-]{1,128}/items$").matches(path) -> LegacyAppend.serializer()
        version == "v1" && method == "POST" && Regex("^/api/v1/notes/[A-Za-z0-9_-]{1,128}/order$").matches(path) -> LegacyReorder.serializer()
        version == "v1" && method == "PATCH" && Regex("^/api/v1/notes/[A-Za-z0-9_-]{1,128}/items/[A-Za-z0-9_-]{1,128}$").matches(path) -> LegacyItemEdit.serializer()
        version == "v1" && method == "DELETE" && Regex("^/api/v1/notes/[A-Za-z0-9_-]{1,128}/items/[A-Za-z0-9_-]{1,128}$").matches(path) -> LegacyDelete.serializer()
        version == "v1" && method == "PATCH" && Regex("^/api/v1/notes/[A-Za-z0-9_-]{1,128}/items/[A-Za-z0-9_-]{1,128}/checked$").matches(path) -> LegacyToggle.serializer()
            else -> throw SerializationException("Unsupported proposal route")
        }) as KSerializer<WireInput>

    override fun deserialize(decoder: Decoder): V2Proposal {
        val json = decoder as JsonDecoder
        val envelope = json.decodeSerializableValue(ProposalEnvelope.serializer())
        val serializer = inputSerializer(envelope.apiVersion, envelope.method, envelope.path, "expectedRevision" in envelope.input)
        val input = json.json.decodeFromJsonElement(serializer, envelope.input)
        return V2Proposal(envelope.apiVersion, envelope.method, envelope.path, input, envelope.submittedBody)
    }
    override fun serialize(encoder: Encoder, value: V2Proposal) {
        val json = encoder as JsonEncoder
        val hasExpectation = value.input is V2CreateNoteApplyEvidenceInput ||
            value.input is V2OrganizationCreateApplyEvidenceInput || value.input is V2BlobReserveApplyEvidenceInput ||
            value.input is LegacyCreateApplyEvidenceInput
        val serializer = inputSerializer(value.apiVersion, value.method, value.path, hasExpectation)
        val input = json.json.encodeToJsonElement(serializer, value.input).jsonObject
        json.encodeSerializableValue(ProposalEnvelope.serializer(), ProposalEnvelope(value.apiVersion, value.method, value.path, input, value.submittedBody))
    }
}

@Serializable
internal enum class ConflictState {
    @SerialName("pending") PENDING,
    @SerialName("applied") APPLIED,
    @SerialName("discarded") DISCARDED
}

@Serializable
private data class ConflictEnvelope(
    val id: String,
    val state: ConflictState,
    val parent: String,
    val reason: String,
    val operation: V2Proposal,
    val snapshot: JsonElement
)

@Serializable(with = V2ConflictSerializer::class)
internal class V2Conflict(
    val id: String,
    val state: ConflictState,
    val parent: String,
    val reason: String,
    val operation: V2Proposal,
    val snapshot: WireSnapshot?
) {
    init {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(id))
        require(parent.isEmpty() || Regex("[A-Za-z0-9_-]{1,128}").matches(parent))
    }
    override fun toString() = "V2Conflict([REDACTED])"
}

internal object V2ConflictSerializer : KSerializer<V2Conflict> {
    override val descriptor = ConflictEnvelope.serializer().descriptor
    @Suppress("UNCHECKED_CAST")
    private fun snapshotSerializer(operation: V2Proposal): KSerializer<WireSnapshot> =
        (when (operation.path.split('/')[3]) {
            "notes" -> V2Note.serializer()
            "tags" -> V2Tag.serializer()
            "notebooks" -> V2Notebook.serializer()
            "blobs" -> V2Blob.serializer()
            else -> throw SerializationException("Unsupported snapshot target")
        }) as KSerializer<WireSnapshot>
    override fun deserialize(decoder: Decoder): V2Conflict {
        val json = decoder as JsonDecoder
        val e = json.decodeSerializableValue(ConflictEnvelope.serializer())
        val snapshot = if (e.snapshot == JsonNull) null else json.json.decodeFromJsonElement(snapshotSerializer(e.operation), e.snapshot)
        return V2Conflict(e.id, e.state, e.parent, e.reason, e.operation, snapshot)
    }
    override fun serialize(encoder: Encoder, value: V2Conflict) {
        val json = encoder as JsonEncoder
        val snapshot = value.snapshot?.let { json.json.encodeToJsonElement(snapshotSerializer(value.operation), it) } ?: JsonNull
        json.encodeSerializableValue(ConflictEnvelope.serializer(), ConflictEnvelope(value.id, value.state, value.parent, value.reason, value.operation, snapshot))
    }
}

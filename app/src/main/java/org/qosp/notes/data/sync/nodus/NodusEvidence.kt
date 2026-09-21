@file:UseSerializers(StrictStringSerializer::class, StrictBooleanSerializer::class, StrictIntSerializer::class)

package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
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
private data class NativeProposalEnvelope(
    val apiVersion: String,
    val method: String,
    val path: String,
    val input: JsonObject,
    val submittedBody: String
) { init { require(apiVersion == "v2") } }

@Serializable
private data class HistoricalProposalEnvelope(
    val apiVersion: String,
    val historical: Boolean,
    val rawOperation: String
) { init { require(apiVersion == "v1" && historical) } }

@Serializable(with = V2ProposalSerializer::class)
internal sealed interface V2Proposal {
    val apiVersion: String

    class Native(
        override val apiVersion: String,
        val method: String,
        val path: String,
        val input: WireInput,
        val submittedBody: String
    ) : V2Proposal {
        init { require(apiVersion == "v2") }
        override fun toString() = "V2Proposal.Native([REDACTED])"
    }

    class Historical(
        override val apiVersion: String,
        val historical: Boolean,
        val rawOperation: String
    ) : V2Proposal {
        init { require(apiVersion == "v1" && historical) }
        override fun toString() = "V2Proposal.Historical([REDACTED])"
    }
}

internal object V2ProposalSerializer : KSerializer<V2Proposal> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("V2Proposal")

    @Suppress("UNCHECKED_CAST")
    private fun inputSerializer(method: String, path: String, hasExpectation: Boolean): KSerializer<WireInput> =
        (when {
            method == "PUT" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}$").matches(path) -> if (hasExpectation) V2CreateNoteApplyEvidenceInput.serializer() else V2CreateNote.serializer()
            method == "PATCH" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2EditNote.serializer()
            method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/content$").matches(path) -> V2Content.serializer()
            method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/trash$").matches(path) -> V2Trash.serializer()
            method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/restore$").matches(path) -> V2Lifecycle.serializer()
            method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/purge$").matches(path) -> V2Lifecycle.serializer()
            method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/items$").matches(path) -> V2Append.serializer()
            method == "PATCH" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/items/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2ItemEdit.serializer()
            method == "DELETE" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/items/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2ChildDelete.serializer()
            method == "PATCH" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/items/[A-Za-z0-9_-]{1,128}/checked$").matches(path) -> V2Toggle.serializer()
            method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/order$").matches(path) -> V2Order.serializer()
            method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/reminders$").matches(path) -> V2ReminderCreate.serializer()
            method == "PATCH" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/reminders/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2ReminderEdit.serializer()
            method == "DELETE" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/reminders/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2ChildDelete.serializer()
            method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/attachments$").matches(path) -> V2AttachmentCreate.serializer()
            method == "PATCH" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/attachments/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2AttachmentEdit.serializer()
            method == "DELETE" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/attachments/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2ChildDelete.serializer()
            method == "POST" && Regex("^/api/v2/notes/[A-Za-z0-9_-]{1,128}/attachments/order$").matches(path) -> V2AttachmentOrder.serializer()
            method == "PUT" && Regex("^/api/v2/tags/[A-Za-z0-9_-]{1,128}$").matches(path) -> if (hasExpectation) V2OrganizationCreateApplyEvidenceInput.serializer() else V2OrganizationCreate.serializer()
            method == "PATCH" && Regex("^/api/v2/tags/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2OrganizationEdit.serializer()
            method == "DELETE" && Regex("^/api/v2/tags/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2OrganizationDelete.serializer()
            method == "PUT" && Regex("^/api/v2/notebooks/[A-Za-z0-9_-]{1,128}$").matches(path) -> if (hasExpectation) V2OrganizationCreateApplyEvidenceInput.serializer() else V2OrganizationCreate.serializer()
            method == "PATCH" && Regex("^/api/v2/notebooks/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2OrganizationEdit.serializer()
            method == "DELETE" && Regex("^/api/v2/notebooks/[A-Za-z0-9_-]{1,128}$").matches(path) -> V2OrganizationDelete.serializer()
            method == "PUT" && Regex("^/api/v2/blobs/[A-Za-z0-9_-]{1,128}$").matches(path) -> if (hasExpectation) V2BlobReserveApplyEvidenceInput.serializer() else V2BlobReserve.serializer()
            else -> throw SerializationException("Unsupported proposal route")
        }) as KSerializer<WireInput>

    override fun deserialize(decoder: Decoder): V2Proposal {
        val json = decoder as JsonDecoder
        val element = json.decodeJsonElement().jsonObject
        val version = element["apiVersion"]?.jsonPrimitive?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing proposal version")
        if (version == "v1") {
            val envelope = json.json.decodeFromJsonElement(HistoricalProposalEnvelope.serializer(), element)
            return V2Proposal.Historical(envelope.apiVersion, envelope.historical, envelope.rawOperation)
        }
        if (version != "v2") throw SerializationException("Unsupported proposal version")
        val envelope = json.json.decodeFromJsonElement(NativeProposalEnvelope.serializer(), element)
        val serializer = inputSerializer(envelope.method, envelope.path, "expectedRevision" in envelope.input)
        val input = json.json.decodeFromJsonElement(serializer, envelope.input)
        return V2Proposal.Native(envelope.apiVersion, envelope.method, envelope.path, input, envelope.submittedBody)
    }

    override fun serialize(encoder: Encoder, value: V2Proposal) {
        val json = encoder as JsonEncoder
        when (value) {
            is V2Proposal.Historical -> json.encodeSerializableValue(HistoricalProposalEnvelope.serializer(),
                HistoricalProposalEnvelope(value.apiVersion, value.historical, value.rawOperation))
            is V2Proposal.Native -> {
                val hasExpectation = value.input is V2CreateNoteApplyEvidenceInput ||
                    value.input is V2OrganizationCreateApplyEvidenceInput || value.input is V2BlobReserveApplyEvidenceInput
                val serializer = inputSerializer(value.method, value.path, hasExpectation)
                val input = json.json.encodeToJsonElement(serializer, value.input).jsonObject
                json.encodeSerializableValue(NativeProposalEnvelope.serializer(),
                    NativeProposalEnvelope(value.apiVersion, value.method, value.path, input, value.submittedBody))
            }
        }
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

@Serializable
private data class StoredConflictEnvelope(
    val id: String,
    val state: ConflictState,
    val parent: String,
    val reason: String,
    val operation: JsonObject,
    val snapshot: JsonElement
)

@Serializable(with = V2ConflictSerializer::class)
internal class V2Conflict(
    val id: String,
    val state: ConflictState,
    val parent: String,
    val reason: String,
    val operation: V2Proposal,
    val snapshot: WireSnapshot?,
    val historicalSnapshot: JsonElement? = null
) {
    init {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(id))
        require(parent.isEmpty() || Regex("[A-Za-z0-9_-]{1,128}").matches(parent))
        require((operation is V2Proposal.Native && historicalSnapshot == null) ||
            (operation is V2Proposal.Historical && snapshot == null))
    }
    override fun toString() = "V2Conflict([REDACTED])"
}

internal object V2ConflictSerializer : KSerializer<V2Conflict> {
    override val descriptor = ConflictEnvelope.serializer().descriptor

    @Suppress("UNCHECKED_CAST")
    private fun snapshotSerializer(operation: V2Proposal.Native): KSerializer<WireSnapshot> =
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
        return when (val operation = e.operation) {
            is V2Proposal.Native -> {
                val snapshot = if (e.snapshot == JsonNull) null else json.json.decodeFromJsonElement(snapshotSerializer(operation), e.snapshot)
                V2Conflict(e.id, e.state, e.parent, e.reason, operation, snapshot)
            }
            is V2Proposal.Historical -> V2Conflict(e.id, e.state, e.parent, e.reason, operation, null,
                e.snapshot.takeUnless { it == JsonNull })
        }
    }

    override fun serialize(encoder: Encoder, value: V2Conflict) {
        val json = encoder as JsonEncoder
        val snapshot = when (val operation = value.operation) {
            is V2Proposal.Native -> value.snapshot?.let { json.json.encodeToJsonElement(snapshotSerializer(operation), it) } ?: JsonNull
            is V2Proposal.Historical -> value.historicalSnapshot ?: JsonNull
        }
        json.encodeSerializableValue(ConflictEnvelope.serializer(),
            ConflictEnvelope(value.id, value.state, value.parent, value.reason, value.operation, snapshot))
    }
}

/** Storage-only discriminator for retained pre-cutover conflict bytes. It never parses v1 input into a command. */
internal fun isStoredHistoricalConflict(body: ByteArray): Boolean {
    val text = body.toString(Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).contentEquals(body)) }
    val envelope = NodusJson.decode(StoredConflictEnvelope.serializer(), text)
    return envelope.operation["apiVersion"]?.jsonPrimitive?.takeIf { it.isString }?.content == "v1"
}

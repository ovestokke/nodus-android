package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.time.LocalDateTime

/** Absent omits a key; Present(null) emits null only for explicitly nullable fields. */
@Serializable(with = WireFieldSerializer::class)
internal sealed interface WireField<out T> {
    data object Absent : WireField<Nothing>
    data class Present<T>(val value: T) : WireField<T>

    fun ifPresent(block: (T) -> Unit) {
        if (this is Present) block(value)
    }
}

internal class WireFieldSerializer<T>(private val valueSerializer: KSerializer<T>) : KSerializer<WireField<T>> {
    override val descriptor = valueSerializer.descriptor
    override fun serialize(encoder: Encoder, value: WireField<T>) {
        require(value is WireField.Present) { "Absent fields must be omitted" }
        encoder.encodeSerializableValue(valueSerializer, value.value)
    }
    override fun deserialize(decoder: Decoder): WireField<T> =
        WireField.Present(decoder.decodeSerializableValue(valueSerializer))
}

internal object StrictStringSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("WireString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
    override fun deserialize(decoder: Decoder): String {
        val value = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive
        if (value == null || !value.isString) throw SerializationException("Expected a JSON string")
        return value.content
    }
}

internal object StrictBooleanSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("WireBoolean", PrimitiveKind.BOOLEAN)
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
    override fun deserialize(decoder: Decoder): Boolean {
        val value = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive
        if (value == null || value.isString) throw SerializationException("Expected a JSON boolean")
        return value.booleanOrNull ?: throw SerializationException("Expected a JSON boolean")
    }
}

internal object StrictIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("WireInteger", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int {
        val value = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive
        if (value == null || value.isString || !Regex("-?(0|[1-9][0-9]*)").matches(value.content)) {
            throw SerializationException("Expected a JSON integer")
        }
        return value.intOrNull ?: throw SerializationException("Expected a JSON integer")
    }
}

internal fun requireDecimal(value: String, maximum: String = "9223372036854775807") {
    require(Regex("0|[1-9][0-9]*").matches(value) &&
        (value.length < maximum.length || value.length == maximum.length && value <= maximum)) {
        "Invalid decimal string"
    }
}
internal fun requireUtf8(value: String, maximum: Int) {
    require(value.toByteArray(Charsets.UTF_8).size <= maximum) { "Wire field too long" }
}
internal fun requireUniqueIds(ids: List<String>) { require(ids.distinct().size == ids.size) }
internal fun requireMediaType(value: String) { require(value.toMediaTypeOrNull() != null) { "Invalid media type" } }
internal fun requireInstant(value: String) {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-]([01][0-9]|2[0-3]):[0-5][0-9])").matches(value)) { "Invalid instant" }
    // Validate the local calendar independently: RFC3339 permits offsets up to 23:59.
    val local = value.substring(0, 19)
    try { LocalDateTime.parse(local) } catch (_: Exception) { throw IllegalArgumentException("Invalid calendar instant") }
}

internal object NodusJson {
    val format = Json {
        encodeDefaults = false
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    fun <T> decode(serializer: KSerializer<T>, text: String): T {
        try {
            rejectDuplicateKeys(text)
            return format.decodeFromString(serializer, text)
        } catch (_: Exception) {
            // Serialization exceptions may contain snippets of notes, proposals or credentials.
            throw SerializationException("Invalid Nodus wire document")
        }
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): String = format.encodeToString(serializer, value)

    private fun rejectDuplicateKeys(text: String) {
        var index = 0
        fun whitespace() { while (index < text.length && text[index] in " \n\r\t") index++ }
        fun string(): String {
            val start = index++
            while (index < text.length) {
                when (text[index++]) {
                    '\\' -> index++
                    '"' -> return format.decodeFromString(StrictStringSerializer, text.substring(start, index))
                }
            }
            error("Unterminated string")
        }
        fun value(depth: Int) {
            require(depth <= 64)
            whitespace()
            when (text[index]) {
                '{' -> {
                    index++; whitespace()
                    val keys = mutableSetOf<String>()
                    if (text[index] != '}') while (true) {
                        whitespace(); require(text[index] == '"')
                        require(keys.add(string()))
                        whitespace(); require(text[index++] == ':')
                        value(depth + 1); whitespace()
                        if (text[index] != ',') break
                        index++
                    }
                    require(text[index++] == '}')
                }
                '[' -> {
                    index++; whitespace()
                    if (text[index] != ']') while (true) {
                        value(depth + 1); whitespace()
                        if (text[index] != ',') break
                        index++
                    }
                    require(text[index++] == ']')
                }
                '"' -> string()
                else -> { while (index < text.length && text[index] !in ",]} \n\r\t") index++ }
            }
        }
        value(0); whitespace(); require(index == text.length)
    }
}

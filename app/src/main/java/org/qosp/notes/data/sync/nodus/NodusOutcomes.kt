@file:UseSerializers(StrictStringSerializer::class, StrictBooleanSerializer::class, StrictIntSerializer::class)

package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
internal class NodusError(
    val error: String,
    val code: WireField<String> = WireField.Absent,
    val conflictId: WireField<String> = WireField.Absent,
    val revision: WireField<String> = WireField.Absent
) {
    init {
        conflictId.ifPresent { require(Regex("[A-Za-z0-9_-]{1,128}").matches(it)) }
        revision.ifPresent { requireDecimal(it) }
    }
    override fun toString() = "NodusError([REDACTED])"
}

internal enum class OutcomeKind {
    SUCCESS, DISCARDED, DURABLE_CONFLICT, TERMINAL_CONFLICT, KEY_REUSE,
    BLOB_NOT_READY, BLOB_IMMUTABLE, QUOTA_EXCEEDED,
    UNAUTHORIZED, FORBIDDEN, RATE_LIMITED, UNAVAILABLE, REJECTED, UNKNOWN
}

/** Classification is not retry permission. Preserve exact bytes and credential epoch elsewhere
 * before sending; network loss, malformed responses and old epochs must not trigger resubmission.
 */
internal class NodusOutcome(
    val kind: OutcomeKind,
    val receipt: V2Receipt? = null,
    val discardReceipt: DiscardReceipt? = null,
    val error: NodusError? = null
) {
    override fun toString() = "NodusOutcome($kind)"

    companion object {
        fun classify(status: Int, body: String, discard: Boolean = false): NodusOutcome {
            try {
                if (status == 200) {
                    return if (discard) NodusOutcome(OutcomeKind.DISCARDED,
                        discardReceipt = NodusJson.decode(DiscardReceipt.serializer(), body))
                    else NodusOutcome(OutcomeKind.SUCCESS, receipt = NodusJson.decode(V2Receipt.serializer(), body))
                }
                val error = NodusJson.decode(NodusError.serializer(), body)
                val code = (error.code as? WireField.Present)?.value
                val hasId = error.conflictId is WireField.Present
                val hasRevision = error.revision is WireField.Present
                val kind = when {
                    status == 409 -> when (code) {
                        "durable_conflict" -> if (hasId && hasRevision) OutcomeKind.DURABLE_CONFLICT else OutcomeKind.UNKNOWN
                        "terminal_conflict" -> if (hasId && !hasRevision) OutcomeKind.TERMINAL_CONFLICT else OutcomeKind.UNKNOWN
                        "idempotency_key_reuse" -> if (!hasId && !hasRevision) OutcomeKind.KEY_REUSE else OutcomeKind.UNKNOWN
                        "blob_not_ready" -> if (!hasId && !hasRevision) OutcomeKind.BLOB_NOT_READY else OutcomeKind.UNKNOWN
                        "blob_immutable" -> if (!hasId && !hasRevision) OutcomeKind.BLOB_IMMUTABLE else OutcomeKind.UNKNOWN
                        else -> OutcomeKind.UNKNOWN
                    }
                    status == 507 && code == "storage_quota_exceeded" && !hasId && !hasRevision -> OutcomeKind.QUOTA_EXCEEDED
                    code != null || hasId || hasRevision -> OutcomeKind.UNKNOWN
                    status == 401 -> OutcomeKind.UNAUTHORIZED
                    status == 403 -> OutcomeKind.FORBIDDEN
                    status == 429 -> OutcomeKind.RATE_LIMITED
                    status == 503 -> OutcomeKind.UNAVAILABLE
                    status in setOf(400, 404, 405, 413, 415, 416) -> OutcomeKind.REJECTED
                    else -> OutcomeKind.UNKNOWN
                }
                return NodusOutcome(kind, error = error)
            } catch (_: Exception) {
                return NodusOutcome(OutcomeKind.UNKNOWN)
            }
        }
    }
}

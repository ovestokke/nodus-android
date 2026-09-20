package org.qosp.notes.data.sync.nodus

import org.junit.Assert.*
import org.junit.Test

class NodusOutcomesTest {
    @Test fun receiptsRemainContentFreeAndStrict() {
        for (type in listOf("note", "tag", "notebook", "blob")) {
            val receipt = """{"resourceType":"$type","resourceId":"n","revision":"9223372036854775807"}"""
            assertEquals(OutcomeKind.SUCCESS, NodusOutcome.classify(200, receipt).kind)
            assertEquals(OutcomeKind.UNKNOWN, NodusOutcome.classify(200, receipt.dropLast(1) + ",\"title\":\"private\"}").kind)
            if (type != "blob") assertEquals(OutcomeKind.UNKNOWN, NodusOutcome.classify(200, receipt.dropLast(1) + ",\"state\":\"ready\"}").kind)
        }
        for (state in listOf("pending", "ready")) assertEquals(OutcomeKind.SUCCESS,
            NodusOutcome.classify(200, """{"resourceType":"blob","resourceId":"b","revision":"1","state":"$state"}""").kind)
        val discard = """{"conflictId":"c","revision":"1","state":"discarded"}"""
        assertEquals(OutcomeKind.DISCARDED, NodusOutcome.classify(200, discard, discard = true).kind)
        assertEquals(OutcomeKind.UNKNOWN, NodusOutcome.classify(200, discard).kind)
    }

    @Test fun classifyCodesNotHumanMessagesAndNeverInferRetryPermission() {
        val cases = listOf(
            Triple(409, "\"code\":\"durable_conflict\",\"conflictId\":\"c\",\"revision\":\"1\"", OutcomeKind.DURABLE_CONFLICT),
            Triple(409, "\"code\":\"terminal_conflict\",\"conflictId\":\"c\"", OutcomeKind.TERMINAL_CONFLICT),
            Triple(409, "\"code\":\"idempotency_key_reuse\"", OutcomeKind.KEY_REUSE),
            Triple(409, "\"code\":\"blob_not_ready\"", OutcomeKind.BLOB_NOT_READY),
            Triple(409, "\"code\":\"blob_immutable\"", OutcomeKind.BLOB_IMMUTABLE),
            Triple(507, "\"code\":\"storage_quota_exceeded\"", OutcomeKind.QUOTA_EXCEEDED),
            Triple(409, "\"code\":\"future_code\"", OutcomeKind.UNKNOWN),
            Triple(409, "\"code\":\"durable_conflict\"", OutcomeKind.UNKNOWN),
            Triple(409, "\"code\":\"terminal_conflict\",\"conflictId\":\"c\",\"revision\":\"1\"", OutcomeKind.UNKNOWN),
            Triple(409, "\"code\":\"blob_immutable\",\"conflictId\":\"c\"", OutcomeKind.UNKNOWN)
        )
        for ((status, fields, expected) in cases) {
            val classified = NodusOutcome.classify(status, "{\"error\":\"sensitive human text\",$fields}")
            assertEquals(expected, classified.kind)
            assertFalse(classified.toString().contains("sensitive"))
            assertFalse(classified.error.toString().contains("sensitive"))
        }
        assertEquals(OutcomeKind.UNKNOWN, NodusOutcome.classify(409, """{"error":"durable_conflict","conflictId":"c","revision":"1"}""").kind)
        assertEquals(OutcomeKind.UNKNOWN, NodusOutcome.classify(200, "<html>login</html>").kind)
        assertEquals(OutcomeKind.UNKNOWN, NodusOutcome.classify(302, "").kind)
        assertEquals(OutcomeKind.UNAUTHORIZED, NodusOutcome.classify(401, """{"error":"unauthorized"}""").kind)
        assertEquals(OutcomeKind.FORBIDDEN, NodusOutcome.classify(403, """{"error":"forbidden"}""").kind)
        assertEquals(OutcomeKind.RATE_LIMITED, NodusOutcome.classify(429, """{"error":"rate limit"}""").kind)
        assertEquals(OutcomeKind.UNAVAILABLE, NodusOutcome.classify(503, """{"error":"unavailable"}""").kind)
        for (status in listOf(400,404,405,413,415,416)) assertEquals(OutcomeKind.REJECTED, NodusOutcome.classify(status, """{"error":"rejected"}""").kind)
    }
}

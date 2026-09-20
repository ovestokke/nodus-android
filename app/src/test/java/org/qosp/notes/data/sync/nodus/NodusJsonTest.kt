package org.qosp.notes.data.sync.nodus

import org.junit.Assert.*
import org.junit.Test

class NodusJsonTest {
    @Test fun decimalStringsAreCanonicalBoundedAndNeverJsonNumbers() {
        for (valid in listOf("0", "1", "9007199254740993", "9223372036854775807")) {
            val body = """{"deviceId":"d","requestId":"r","expectedRevision":"$valid"}"""
            assertEquals(valid, NodusJson.decode(V2Lifecycle.serializer(), body).expectedRevision)
            assertEquals(body, NodusJson.encode(V2Lifecycle.serializer(), V2Lifecycle("d", "r", valid)))
        }
        for (invalid in listOf("01", "-1", "+1", "1.0", "1e3", "9223372036854775808", " 1", "", "١")) {
            assertThrows(Exception::class.java) { V2Lifecycle("d", "r", invalid) }
        }
        for (value in listOf("1", "null", "true", "1e0")) {
            assertThrows(Exception::class.java) { NodusJson.decode(V2Lifecycle.serializer(), """{"deviceId":"d","requestId":"r","expectedRevision":$value}""") }
        }
    }

    @Test fun invalidDatesNestedDuplicatesTypesAndBoundsFailWithoutContentLeak() {
        for (time in listOf("2000-02-30T00:00:00Z", "2000-01-01T00:00:00.1234567890Z", "2000-01-01t00:00:00Z", "2000-01-01T00:00:00z", "2000-01-01T00:00:00", "2000-01-01T24:00:00Z")) {
            assertThrows(Exception::class.java) { V2ReminderInput("r", dueAt = time) }
        }
        V2ReminderInput("r", dueAt = "2000-02-29T00:00:00.123456789+23:59")
        for (body in listOf(
            """{"id":"a","text":"sensitive","\u0074ext":"second"}""",
            """{"id":"a","checked":"true"}""",
            """{"id":12}""",
            """{"id":"a"} {"id":"b"}"""
        )) {
            val error = assertThrows(Exception::class.java) { NodusJson.decode(ItemInput.serializer(), body) }
            assertFalse(error.toString().contains("sensitive"))
            assertNull(error.cause)
        }
        assertThrows(Exception::class.java) { V2OrganizationCreate("d", "r", "é".repeat(2049)) }
        assertThrows(Exception::class.java) { V2AttachmentInput("a", "b", AttachmentKind.IMAGE, fileName = WireField.Present("é".repeat(513))) }
        assertThrows(Exception::class.java) { V2BlobReserve("d", "r", "536870913", "a".repeat(64), "text/plain") }
        assertThrows(Exception::class.java) { V2BlobReserve("d", "r", "1", "A".repeat(64), "text/plain") }
        assertThrows(Exception::class.java) { V2BlobReserve("d", "r", "1", "a".repeat(64), "text") }
    }
}

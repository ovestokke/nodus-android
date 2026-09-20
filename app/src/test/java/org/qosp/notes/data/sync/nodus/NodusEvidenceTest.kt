package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NodusEvidenceTest {
    private fun evidence(version: String, method: String, path: String, input: String, snapshot: String = "null") =
        """{"id":"conflict","state":"pending","parent":"original","reason":"future_unknown_reason","operation":{"apiVersion":"$version","method":"$method","path":"/api/$version/$path","input":$input,"submittedBody":"{ exact apply bytes }"},"snapshot":$snapshot}"""

    @Test fun createsAndFailedApplyUseDistinctTypedEvidenceWithoutWideningLiveRequests() {
        val common = "\"deviceId\":\"d\",\"requestId\":\"r\""
        val cases = listOf(
            Triple("v2", "notes/n", "{$common,\"kind\":\"text\",\"expectedRevision\":\"12\"}"),
            Triple("v2", "tags/t", "{$common,\"name\":\"\",\"expectedRevision\":\"12\"}"),
            Triple("v2", "notebooks/b", "{$common,\"name\":\"\",\"expectedRevision\":\"12\"}"),
            Triple("v2", "blobs/b", "{$common,\"size\":\"0\",\"sha256\":\"${"a".repeat(64)}\",\"mediaType\":\"text/plain\",\"expectedRevision\":\"12\"}"),
            Triple("v1", "notes/n", "{$common,\"kind\":\"text\",\"expectedRevision\":\"12\"}")
        )
        cases.forEach { (version, path, input) ->
            val body = evidence(version, "PUT", path, input)
            val decoded = NodusJson.decode(V2Conflict.serializer(), body)
            assertEquals("future_unknown_reason", decoded.reason)
            assertEquals("{ exact apply bytes }", decoded.operation.submittedBody)
            assertEquals(NodusJson.format.parseToJsonElement(body), NodusJson.format.parseToJsonElement(NodusJson.encode(V2Conflict.serializer(), decoded)))
            assertFalse(decoded.toString().contains("exact apply"))
            val original = evidence(version, "PUT", path, input.replace(",\"expectedRevision\":\"12\"", ""))
            val originalDecoded = NodusJson.decode(V2Conflict.serializer(), original)
            assertNotEquals(originalDecoded.operation.input.javaClass, decoded.operation.input.javaClass)
        }
    }

    @Test fun originalVersionedRoutesDetermineInputAndSnapshotTypes() {
        val input = """{"deviceId":"d","requestId":"r","expectedRevision":"12","checked":true}"""
        val body = evidence("v2", "PATCH", "notes/n/items/i/checked", input, NodusFixtures.note)
        val decoded = NodusJson.decode(V2Conflict.serializer(), body)
        assertTrue(decoded.operation.input is V2Toggle)
        assertTrue(decoded.snapshot is V2Note)
        val legacy = NodusJson.decode(V2Conflict.serializer(), body.replace("v2", "v1"))
        assertTrue(legacy.operation.input is LegacyToggle)
        assertTrue(legacy.snapshot is V2Note)
        assertThrows(Exception::class.java) { NodusJson.decode(V2Conflict.serializer(), body.replace("/items/i/checked", "/attachments/i")) }
        assertThrows(Exception::class.java) { NodusJson.decode(V2Conflict.serializer(), body.replace("v2", "v3")) }
        assertThrows(Exception::class.java) { NodusJson.decode(V2Conflict.serializer(), evidence("v2", "PUT", "blobs/b/content", input)) }
        val org = evidence("v2", "DELETE", "tags/t", """{"deviceId":"d","requestId":"r","expectedRevision":"12"}""", NodusFixtures.tag)
        assertTrue(NodusJson.decode(V2Conflict.serializer(), org).snapshot is V2Tag)
        assertTrue(NodusJson.decode(V2Conflict.serializer(), org.replace("tags/t", "notebooks/t")).snapshot is V2Notebook)
    }
}

package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NodusEvidenceTest {
    private fun nativeEvidence(method: String, path: String, input: String, snapshot: String = "null") =
        """{"id":"conflict","state":"pending","parent":"original","reason":"future_unknown_reason","operation":{"apiVersion":"v2","method":"$method","path":"/api/v2/$path","input":$input,"submittedBody":"{ exact apply bytes }"},"snapshot":$snapshot}"""

    private fun operation(conflict: V2Conflict) = conflict.operation as V2Proposal.Native

    @Test fun createsAndFailedApplyUseDistinctTypedEvidenceWithoutWideningLiveRequests() {
        val common = "\"deviceId\":\"d\",\"requestId\":\"r\""
        val cases = listOf(
            Pair("notes/n", "{$common,\"kind\":\"text\",\"expectedRevision\":\"12\"}"),
            Pair("tags/t", "{$common,\"name\":\"\",\"expectedRevision\":\"12\"}"),
            Pair("notebooks/b", "{$common,\"name\":\"\",\"expectedRevision\":\"12\"}"),
            Pair("blobs/b", "{$common,\"size\":\"0\",\"sha256\":\"${"a".repeat(64)}\",\"mediaType\":\"text/plain\",\"expectedRevision\":\"12\"}")
        )
        cases.forEach { (path, input) ->
            val body = nativeEvidence("PUT", path, input)
            val decoded = NodusJson.decode(V2Conflict.serializer(), body)
            assertEquals("future_unknown_reason", decoded.reason)
            assertEquals("{ exact apply bytes }", operation(decoded).submittedBody)
            assertEquals(NodusJson.format.parseToJsonElement(body), NodusJson.format.parseToJsonElement(NodusJson.encode(V2Conflict.serializer(), decoded)))
            assertFalse(decoded.toString().contains("exact apply"))
            val original = nativeEvidence("PUT", path, input.replace(",\"expectedRevision\":\"12\"", ""))
            val originalDecoded = NodusJson.decode(V2Conflict.serializer(), original)
            assertNotEquals(operation(originalDecoded).input.javaClass, operation(decoded).input.javaClass)
        }
    }

    @Test fun nativeRoutesDetermineInputAndSnapshotTypes() {
        val input = """{"deviceId":"d","requestId":"r","expectedRevision":"12","checked":true}"""
        val body = nativeEvidence("PATCH", "notes/n/items/i/checked", input, NodusFixtures.note)
        val decoded = NodusJson.decode(V2Conflict.serializer(), body)
        assertTrue(operation(decoded).input is V2Toggle)
        assertTrue(decoded.snapshot is V2Note)
        assertThrows(Exception::class.java) { NodusJson.decode(V2Conflict.serializer(), body.replace("/items/i/checked", "/attachments/i")) }
        assertThrows(Exception::class.java) { NodusJson.decode(V2Conflict.serializer(), body.replace("\"v2\"", "\"v3\"")) }
        assertThrows(Exception::class.java) { NodusJson.decode(V2Conflict.serializer(), nativeEvidence("PUT", "blobs/b/content", input)) }
        val org = nativeEvidence("DELETE", "tags/t", """{"deviceId":"d","requestId":"r","expectedRevision":"12"}""", NodusFixtures.tag)
        assertTrue(NodusJson.decode(V2Conflict.serializer(), org).snapshot is V2Tag)
        assertTrue(NodusJson.decode(V2Conflict.serializer(), org.replace("tags/t", "notebooks/t")).snapshot is V2Notebook)
    }

    @Test fun historicalEvidenceIsOpaqueUnboundedAndNeverDecodedAsACommand() {
        val raw = "{malformed original" + "x".repeat(1_048_576)
        val body = """{"id":"history","state":"pending","parent":"","reason":"retained","operation":{"apiVersion":"v1","historical":true,"rawOperation":${JsonPrimitive(raw)}},"snapshot":${NodusFixtures.note}}"""
        val decoded = NodusJson.decode(V2Conflict.serializer(), body)
        val operation = decoded.operation as V2Proposal.Historical
        assertEquals(raw, operation.rawOperation)
        assertNull(decoded.snapshot)
        assertEquals(NodusJson.format.parseToJsonElement(NodusFixtures.note), decoded.historicalSnapshot)
        val encoded = NodusJson.encode(V2Conflict.serializer(), decoded)
        assertEquals(raw, (NodusJson.decode(V2Conflict.serializer(), encoded).operation as V2Proposal.Historical).rawOperation)
        assertFalse(decoded.toString().contains("malformed original"))
    }

    @Test fun historicalWireShapeIsStrictWhileOldStoredBytesRemainRecognizableAndUnchanged() {
        val canonical = """{"id":"history","state":"pending","parent":"","reason":"retained","operation":{"apiVersion":"v1","historical":true,"rawOperation":"not json"},"snapshot":null}"""
        assertTrue(NodusJson.decode(V2Conflict.serializer(), canonical).operation is V2Proposal.Historical)
        listOf(
            canonical.replace("\"historical\":true", "\"historical\":false"),
            canonical.replace("\"rawOperation\":", "\"method\":\"PATCH\",\"rawOperation\":"),
            canonical.replace("\"v1\"", "\"v2\"")
        ).forEach { assertThrows(Exception::class.java) { NodusJson.decode(V2Conflict.serializer(), it) } }

        val old = """{"id":"history","state":"pending","parent":"","reason":"retained","operation":{"apiVersion":"v1","method":"PATCH","path":"/api/v1/notes/n","input":{"deviceId":"d","requestId":"r","expectedRevision":"1"},"submittedBody":"exact"},"snapshot":null}""".toByteArray()
        val preserved = old.copyOf()
        assertTrue(isStoredHistoricalConflict(old))
        assertArrayEquals(preserved, old)
        assertThrows(Exception::class.java) { NodusJson.decode(V2Conflict.serializer(), old.toString(Charsets.UTF_8)) }
    }
}

package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.KSerializer
import org.junit.Assert.*
import org.junit.Test

class NodusRequestsTest {
    private val common = "\"deviceId\":\"d\",\"requestId\":\"r\""
    private val expected = "$common,\"expectedRevision\":\"9007199254740993\""

    @Test fun operationSpecificBodiesRoundTripExactly() {
        val cases: List<Pair<KSerializer<*>, String>> = listOf(
            V2CreateNote.serializer() to "{$common,\"kind\":\"text\"}",
            V2EditNote.serializer() to "{$expected,\"title\":\"\",\"pinned\":false}",
            V2Content.serializer() to "{$expected,\"kind\":\"checklist\",\"text\":\"# raw\\n\",\"items\":[]}",
            V2Trash.serializer() to "{$expected,\"sourceTrashedAt\":null}",
            V2Lifecycle.serializer() to "{$expected}",
            V2Append.serializer() to "{$expected,\"itemId\":\"i\",\"text\":\"\",\"checked\":false}",
            V2ItemEdit.serializer() to "{$expected,\"text\":\"\",\"checked\":true}",
            V2Toggle.serializer() to "{$expected,\"checked\":false}",
            V2Order.serializer() to "{$expected,\"order\":[\"b\",\"a\"]}",
            V2ChildDelete.serializer() to "{$expected,\"editedAt\":\"2000-01-01T01:00:00.123456789+01:00\"}",
            V2ReminderCreate.serializer() to "{$expected,\"id\":\"r1\",\"name\":\"\",\"dueAt\":\"2000-01-01T00:00:00Z\",\"makePrimary\":true}",
            V2ReminderEdit.serializer() to "{$expected,\"name\":\"\"}",
            V2AttachmentCreate.serializer() to "{$expected,\"id\":\"a\",\"blobId\":\"b\",\"kind\":\"generic\",\"description\":\"\",\"fileName\":\"../../raw.svg\"}",
            V2AttachmentEdit.serializer() to "{$expected,\"kind\":\"audio\",\"description\":\"\"}",
            V2AttachmentOrder.serializer() to "{$expected,\"order\":[]}",
            V2OrganizationCreate.serializer() to "{$common,\"name\":\"  duplicate allowed  \"}",
            V2OrganizationEdit.serializer() to "{$expected,\"name\":\"\"}",
            V2OrganizationDelete.serializer() to "{$expected}",
            V2BlobReserve.serializer() to "{$common,\"size\":\"536870912\",\"sha256\":\"${"a".repeat(64)}\",\"mediaType\":\"image/svg+xml\"}",
            V2Apply.serializer() to "{$expected}",
            V2Discard.serializer() to "{$common}"
        )
        cases.forEach { (serializer, body) ->
            @Suppress("UNCHECKED_CAST") val typed = serializer as KSerializer<Any>
            assertEquals(body, NodusJson.encode(typed, NodusJson.decode(typed, body)))
            assertThrows(Exception::class.java) { NodusJson.decode(typed, body.dropLast(1) + ",\"unknown\":false}") }
            assertThrows(Exception::class.java) { NodusJson.decode(typed, body.replace("\"deviceId\":\"d\"", "\"deviceId\":null")) }
        }
    }

    @Test fun omissionNullAndEmptyAreDifferent() {
        val omitted = V2EditNote("d", "r", "1")
        assertEquals("{$common,\"expectedRevision\":\"1\"}", NodusJson.encode(V2EditNote.serializer(), omitted))
        val clear = omitted.copy(notebookId = WireField.Present(null), primaryReminderId = WireField.Present(null), tagIds = WireField.Present(emptyList()), text = WireField.Present(""))
        val wire = NodusJson.encode(V2EditNote.serializer(), clear)
        assertEquals("{$common,\"expectedRevision\":\"1\",\"text\":\"\",\"notebookId\":null,\"tagIds\":[],\"primaryReminderId\":null}", wire)
        assertEquals(clear, NodusJson.decode(V2EditNote.serializer(), wire))
        for (field in listOf("title", "text", "pinned", "tagIds", "editedAt", "color")) {
            assertThrows(Exception::class.java) { NodusJson.decode(V2EditNote.serializer(), "{$expected,\"$field\":null}") }
        }
        assertEquals("{$expected}", NodusJson.encode(V2Order.serializer(), NodusJson.decode(V2Order.serializer(), "{$expected}")))
        assertThrows(Exception::class.java) { NodusJson.decode(V2AttachmentOrder.serializer(), "{$expected}") }
    }

    @Test fun pairingBodiesAndQrLinksAreStrictAndNeverContainCredentials() {
        val secret="A".repeat(43)
        val body="""{"code":"23456-789AB","deviceId":"device","requestId":"request","credentialSecret":"$secret"}"""
        assertEquals(body,NodusJson.encode(PairingRedeemInput.serializer(),NodusJson.decode(PairingRedeemInput.serializer(),body)))
        assertThrows(Exception::class.java){NodusJson.decode(PairingRedeemInput.serializer(),body.dropLast(1)+",\"extra\":true}")}
        assertThrows(Exception::class.java){NodusJson.decode(PairingRedeemInput.serializer(),body.replace("23456-789AB","12345-67890"))}
        val response=PairingRedeem("token.$secret","token")
        assertEquals(response,NodusJson.decode(PairingRedeem.serializer(),NodusJson.encode(PairingRedeem.serializer(),response)))
        val first=NodusPairingLink.parse("nodus://pair?code=23456-789AB&origin=https%3A%2F%2Fnotes.example")
        val reversed=NodusPairingLink.parse("nodus://pair?origin=https%3A%2F%2Fnotes.example&code=23456-789AB")
        assertEquals(first,reversed);assertEquals("https://notes.example/",first.origin)
        for(raw in listOf(
            "nodus://pair?origin=https%3A%2F%2Fnotes.example&code=23456-789AB&credential=token.secret",
            "nodus://pair?origin=https%3A%2F%2Fnotes.example&origin=https%3A%2F%2Fother.example&code=23456-789AB",
            "nodus://pair/path?origin=https%3A%2F%2Fnotes.example&code=23456-789AB",
            "nodus://pair?origin=http%3A%2F%2Fnotes.example&code=23456-789AB"
        )) assertThrows(Exception::class.java){NodusPairingLink.parse(raw)}
    }

    @Test fun operationBoundariesAndNestedObjectsAreStrict() {
        for (extra in listOf("\"expectedRevision\":\"0\"", "\"sourceTrashedAt\":null", "\"state\":\"purged\"", "\"localOnly\":true", "\"items\":[{\"id\":\"a\",\"revision\":\"1\"}]", "\"items\":[{\"id\":\"a\",\"id\":\"b\"}]", "\"items\":[{\"id\":\"a\"},{\"id\":\"a\"}]")) {
            assertThrows(Exception::class.java) { NodusJson.decode(V2CreateNote.serializer(), "{$common,\"kind\":\"text\",$extra}") }
        }
        for (extra in listOf("\"kind\":\"text\"", "\"items\":[]", "\"attachments\":[]", "\"state\":\"trash\"", "\"authoredAt\":\"2000-01-01T00:00:00Z\"")) {
            assertThrows(Exception::class.java) { NodusJson.decode(V2EditNote.serializer(), "{$expected,$extra}") }
        }
        assertThrows(Exception::class.java) { NodusJson.decode(V2AttachmentEdit.serializer(), "{$expected,\"blobId\":\"replacement\"}") }
        assertThrows(Exception::class.java) { NodusJson.decode(V2Discard.serializer(), "{$expected}") }
        val initial = "{$common,\"kind\":\"text\",\"text\":\"raw\",\"items\":[{\"id\":\"i\",\"checked\":false}],\"state\":\"trash\",\"sourceTrashedAt\":null}"
        assertEquals(initial, NodusJson.encode(V2CreateNote.serializer(), NodusJson.decode(V2CreateNote.serializer(), initial)))
    }
}

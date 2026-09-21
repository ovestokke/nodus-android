package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal object NodusFixtures {
    val note = """{"id":"n","kind":"checklist","title":"","text":"raw","archived":true,"pinned":true,"hidden":true,"markdownEnabled":false,"color":"purple","notebookId":null,"tagIds":[],"primaryReminderId":null,"items":[{"id":"i","text":"","checked":false,"position":0,"revision":"9007199254740993","deleted":true}],"revision":"9223372036854775807","created":"2000-01-01T00:00:00Z","updated":"2000-01-01T00:00:00Z","authoredAt":"1999-01-01T00:00:00.123456789Z","editedAt":"2000-01-01T00:00:00Z","state":"trash","trashedAt":"2000-01-01T00:00:00Z","sourceTrashedAt":null,"attachments":[{"id":"a","blobId":"b","kind":"image","description":"","fileName":"../../file.svg","position":0,"deleted":true}],"reminders":[{"id":"r","name":"","dueAt":"1999-01-01T00:00:00Z","deleted":true}]}"""
    val tag = """{"id":"t","name":"","revision":"12","created":"2000-01-01T00:00:00Z","updated":"2000-01-01T00:00:00Z","deleted":false}"""
    val capabilities = """{"contractVersion":"2.0","realmId":"realm-fixture","features":["note-metadata","content-conversion","organization","multiple-reminders","attachments","trash-restore"],"limits":{"mutationBytes":"1048576","noteBytes":"1048576","attachmentBytes":"536870912","totalBlobBytes":"21474836480","organizationNameBytes":"4096","attachmentFileNameBytes":"1024","lifetimeItemsPerNote":1000,"lifetimeAttachmentsPerNote":1000,"lifetimeRemindersPerNote":1000,"liveTagsPerNote":1000,"feedPageMaximum":100}}"""
}

class NodusModelsTest {
    @Test fun allFieldsAndTombstonesRoundTripWithoutTrimmingOrPrecisionLoss() {
        val note = NodusJson.decode(V2Note.serializer(), NodusFixtures.note)
        assertEquals(NodusFixtures.note, NodusJson.encode(V2Note.serializer(), note))
        assertEquals("9007199254740993", note.items.single().revision)
        assertEquals("1999-01-01T00:00:00.123456789Z", note.authoredAt)
        assertTrue(note.items.single().deleted && note.attachments.single().deleted && note.reminders.single().deleted)
        val noPointer = NodusFixtures.note.replace("\"notebookId\":null,", "")
        assertThrows(Exception::class.java) { NodusJson.decode(V2Note.serializer(), noPointer) }
    }

    @Test fun blobMetadataKeepsSizesAndRevisionsAsStrings() {
        for (state in listOf("pending", "ready")) {
            val body = """{"id":"b","size":"536870912","sha256":"${"a".repeat(64)}","mediaType":"image/svg+xml","state":"$state","revision":"9007199254740993","created":"2000-01-01T00:00:00Z"}"""
            val blob = NodusJson.decode(V2Blob.serializer(), body)
            assertEquals(body, NodusJson.encode(V2Blob.serializer(), blob))
            assertEquals("536870912", blob.size)
            assertThrows(Exception::class.java) { NodusJson.decode(V2Blob.serializer(), body.replace("\"536870912\"", "536870912")) }
            assertThrows(Exception::class.java) { NodusJson.decode(V2Blob.serializer(), body.dropLast(1) + ",\"url\":\"https://public.example\"}") }
        }
    }

    @Test fun everyColorKindAndStateHasExactSpelling() {
        val colors = listOf("default", "red", "orange", "yellow", "green", "teal", "cyan", "blue", "purple", "pink", "brown", "gray")
        assertEquals(colors, NoteColor.entries.map { NodusJson.encode(NoteColor.serializer(), it).trim('"') })
        assertEquals(listOf("text", "checklist"), NoteKind.entries.map { NodusJson.encode(NoteKind.serializer(), it).trim('"') })
        assertEquals(listOf("audio", "image", "video", "generic"), AttachmentKind.entries.map { NodusJson.encode(AttachmentKind.serializer(), it).trim('"') })
        assertEquals(listOf("live", "trash", "purged"), NoteState.entries.map { NodusJson.encode(NoteState.serializer(), it).trim('"') })
        assertEquals(listOf("pending", "ready"), BlobState.entries.map { NodusJson.encode(BlobState.serializer(), it).trim('"') })
        assertEquals(listOf("pending", "applied", "discarded"), ConflictState.entries.map { NodusJson.encode(ConflictState.serializer(), it).trim('"') })
        assertThrows(Exception::class.java) { NodusJson.decode(NoteColor.serializer(), "\"violet\"") }
    }

    @Test fun capabilitiesRequireExact20AndApprovedLimits() {
        NodusJson.decode(V2Capabilities.serializer(), NodusFixtures.capabilities)
        for (bad in listOf(
            NodusFixtures.capabilities.replace("\"realmId\":\"realm-fixture\",", ""),
            NodusFixtures.capabilities.replace("realm-fixture", ""),
            NodusFixtures.capabilities.replace("realm-fixture", "x".repeat(129)),
            NodusFixtures.capabilities.replace("realm-fixture", "bad/realm"),
            NodusFixtures.capabilities.replace("\"realm-fixture\"", "null"),
            NodusFixtures.capabilities.replace("\"realm-fixture\"", "123"),
            NodusFixtures.capabilities.replace("2.0", "1.0"),
            NodusFixtures.capabilities.replace("2.0", "2.1"),
            NodusFixtures.capabilities.replace("\"attachments\",", ""),
            NodusFixtures.capabilities.replace("trash-restore", "attachments"),
            NodusFixtures.capabilities.replace("21474836480", "21474836481"),
            NodusFixtures.capabilities.replace("\"attachmentBytes\"", "\"v1ProjectionBytes\":\"262144\",\"attachmentBytes\""),
            NodusFixtures.capabilities.replace("\"1048576\"", "1048576"),
            NodusFixtures.capabilities.replace(":1000", ":\"1000\"")
        )) assertThrows(Exception::class.java) { NodusJson.decode(V2Capabilities.serializer(), bad) }
    }

    @Test fun typedMixedFeedUsesResourceTypeAndRequiresMatchingRevision() {
        val page = """{"events":[{"revision":"9223372036854775807","resourceType":"note","resource":${NodusFixtures.note}},{"revision":"12","resourceType":"tag","resource":${NodusFixtures.tag}},{"revision":"12","resourceType":"notebook","resource":${NodusFixtures.tag}}],"cursor":"9223372036854775807","until":"9223372036854775807","hasMore":false}"""
        val parsed = NodusJson.decode(V2Changes.serializer(), page)
        assertTrue(parsed.events[0] is V2Event.Note)
        assertTrue(parsed.events[1] is V2Event.Tag)
        assertTrue(parsed.events[2] is V2Event.Notebook)
        assertEquals(NodusJson.format.parseToJsonElement(page), NodusJson.format.parseToJsonElement(NodusJson.encode(V2Changes.serializer(), parsed)))
        assertThrows(Exception::class.java) { NodusJson.decode(V2Changes.serializer(), page.replace("\"resourceType\":\"tag\"", "\"resourceType\":\"blob\"")) }
        assertThrows(Exception::class.java) { NodusJson.decode(V2Changes.serializer(), page.replace("\"revision\":\"12\",\"resourceType\"", "\"revision\":\"11\",\"resourceType\"")) }
    }
}

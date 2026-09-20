@file:UseSerializers(StrictStringSerializer::class, StrictBooleanSerializer::class, StrictIntSerializer::class)

package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import kotlinx.serialization.SerialName

@Serializable
internal enum class NoteKind {
    @SerialName("text") TEXT,
    @SerialName("checklist") CHECKLIST
}

@Serializable
internal enum class NoteColor {
    @SerialName("default") DEFAULT,
    @SerialName("red") RED,
    @SerialName("orange") ORANGE,
    @SerialName("yellow") YELLOW,
    @SerialName("green") GREEN,
    @SerialName("teal") TEAL,
    @SerialName("cyan") CYAN,
    @SerialName("blue") BLUE,
    @SerialName("purple") PURPLE,
    @SerialName("pink") PINK,
    @SerialName("brown") BROWN,
    @SerialName("gray") GRAY
}

@Serializable
internal enum class InitialNoteState {
    @SerialName("live") LIVE,
    @SerialName("trash") TRASH
}

@Serializable
internal enum class AttachmentKind {
    @SerialName("audio") AUDIO,
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("generic") GENERIC
}

@Serializable
internal enum class NoteState {
    @SerialName("live") LIVE,
    @SerialName("trash") TRASH,
    @SerialName("purged") PURGED
}

@Serializable
internal enum class BlobState {
    @SerialName("pending") PENDING,
    @SerialName("ready") READY
}

@Serializable
internal enum class CapabilityFeature {
    @SerialName("note-metadata") NOTE_METADATA,
    @SerialName("content-conversion") CONTENT_CONVERSION,
    @SerialName("organization") ORGANIZATION,
    @SerialName("multiple-reminders") MULTIPLE_REMINDERS,
    @SerialName("attachments") ATTACHMENTS,
    @SerialName("trash-restore") TRASH_RESTORE
}

@Serializable
internal enum class ResourceType {
    @SerialName("note") NOTE,
    @SerialName("tag") TAG,
    @SerialName("notebook") NOTEBOOK,
    @SerialName("blob") BLOB
}

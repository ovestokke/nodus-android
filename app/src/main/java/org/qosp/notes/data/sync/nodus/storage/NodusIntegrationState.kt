package org.qosp.notes.data.sync.nodus.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/** No credentials. A restored database cannot authorize this installation. */
@Entity(tableName = "nodus_integration")
data class NodusIntegrationState(
    @PrimaryKey val id: Int = 1,
    val activeConnectionId: String? = null,
    val configuredConnectionId: String? = null,
    val installationDeviceId: String? = null,
    val credentialEpoch: String? = null,
    val origin: String? = null,
    val generation: Long = 0,
    val intentRow: Long = 0,
    val operationRow: Long = 0,
    val intentEnded: Boolean = false,
    val operationEnded: Boolean = false,
    val retryAtMillis: Long? = null,
    val attachmentAfter: String = "",
    val projectionAfter: String = "0",
    val lastError: String? = null
)

/** Frozen local payload, not wire input. Invalid wire edits remain valid offline edits. */
@Entity(tableName = "nodus_captures")
data class NodusLocalCapture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectionId: String,
    val deviceId: String,
    val credentialEpoch: String,
    val generation: Long,
    val beforeBody: String,
    val afterBody: String,
    val removedReminders: String,
    val state: String = "PENDING",
    val errorCode: String? = null
)

package org.qosp.notes.data.sync.nodus.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only: legacy content, relations and cloud_ids are never rewritten. */
object NodusMigration5To6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `alarmFingerprint` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS `reminder_alarm_state` (`id` INTEGER NOT NULL, `noteId` INTEGER NOT NULL, `date` INTEGER NOT NULL, `name` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_integration` (`id` INTEGER NOT NULL, `activeConnectionId` TEXT, `configuredConnectionId` TEXT, `installationDeviceId` TEXT, `credentialEpoch` TEXT, `origin` TEXT, `generation` INTEGER NOT NULL, `intentRow` INTEGER NOT NULL, `operationRow` INTEGER NOT NULL, `intentEnded` INTEGER NOT NULL, `operationEnded` INTEGER NOT NULL, `retryAtMillis` INTEGER, `attachmentAfter` TEXT NOT NULL, `projectionAfter` TEXT NOT NULL, `lastError` TEXT, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_captures` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `connectionId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `credentialEpoch` TEXT NOT NULL, `generation` INTEGER NOT NULL, `beforeBody` TEXT NOT NULL, `afterBody` TEXT NOT NULL, `removedReminders` TEXT NOT NULL, `state` TEXT NOT NULL, `errorCode` TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_connections` (`connectionId` TEXT NOT NULL, `origin` TEXT NOT NULL, `ownerKey` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `credentialEpoch` TEXT NOT NULL, `realmId` TEXT, `inactiveGraph` TEXT, `checkpointDeviceId` TEXT, `checkpointEpoch` TEXT, `checkpointRemovedReminders` TEXT, PRIMARY KEY(`connectionId`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nodus_connections_origin_ownerKey` ON `nodus_connections` (`origin`, `ownerKey`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_mappings` (`connectionId` TEXT NOT NULL, `mappingId` TEXT NOT NULL, `resourceType` TEXT NOT NULL, `parentId` TEXT NOT NULL, `wireId` TEXT NOT NULL, `localKey` TEXT NOT NULL, `localRowId` INTEGER, `localRowDetached` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`connectionId`, `mappingId`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nodus_mappings_connectionId_resourceType_parentId_wireId` ON `nodus_mappings` (`connectionId`, `resourceType`, `parentId`, `wireId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nodus_mappings_connectionId_resourceType_parentId_localKey` ON `nodus_mappings` (`connectionId`, `resourceType`, `parentId`, `localKey`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_snapshots` (`connectionId` TEXT NOT NULL, `resourceType` TEXT NOT NULL, `wireId` TEXT NOT NULL, `revision` TEXT NOT NULL, `body` BLOB NOT NULL, `tombstone` INTEGER NOT NULL, PRIMARY KEY(`connectionId`, `resourceType`, `wireId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_tracking` (`connectionId` TEXT NOT NULL, `mappingId` TEXT NOT NULL, `generation` INTEGER NOT NULL, `baseRevision` TEXT, `baseBody` BLOB, PRIMARY KEY(`connectionId`, `mappingId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_intents` (`connectionId` TEXT NOT NULL, `intentId` TEXT NOT NULL, `mappingId` TEXT NOT NULL, `generation` INTEGER NOT NULL, `body` BLOB NOT NULL, PRIMARY KEY(`connectionId`, `intentId`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nodus_intents_connectionId_mappingId_generation` ON `nodus_intents` (`connectionId`, `mappingId`, `generation`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_outbox` (`connectionId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `intentId` TEXT NOT NULL, `mappingId` TEXT NOT NULL, `generation` INTEGER NOT NULL, `apiVersion` TEXT NOT NULL, `method` TEXT NOT NULL, `path` TEXT NOT NULL, `contentType` TEXT NOT NULL, `body` BLOB, `sourceFileId` TEXT, `sourceSha256` TEXT, `sourceSize` TEXT, `deviceId` TEXT NOT NULL, `requestId` TEXT NOT NULL, `credentialEpoch` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`connectionId`, `operationId`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nodus_outbox_connectionId_credentialEpoch_deviceId_requestId` ON `nodus_outbox` (`connectionId`, `credentialEpoch`, `deviceId`, `requestId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_nodus_outbox_connectionId_intentId` ON `nodus_outbox` (`connectionId`, `intentId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_cursors` (`connectionId` TEXT NOT NULL, `apiVersion` TEXT NOT NULL, `stream` TEXT NOT NULL, `cursor` TEXT NOT NULL, `until` TEXT, PRIMARY KEY(`connectionId`, `apiVersion`, `stream`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_evidence` (`connectionId` TEXT NOT NULL, `evidenceId` TEXT NOT NULL, `operationId` TEXT, `credentialEpoch` TEXT, `kind` TEXT NOT NULL, `httpStatus` INTEGER, `body` BLOB, `conflictId` TEXT, `errorCode` TEXT, `resourceType` TEXT, `resourceId` TEXT, `resourceRevision` TEXT, `resourceRevisionDigits` INTEGER, PRIMARY KEY(`connectionId`, `evidenceId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_nodus_evidence_connectionId_operationId` ON `nodus_evidence` (`connectionId`, `operationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_nodus_evidence_connectionId_resourceType_resourceId_resourceRevisionDigits_resourceRevision` ON `nodus_evidence` (`connectionId`, `resourceType`, `resourceId`, `resourceRevisionDigits`, `resourceRevision`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_conflicts` (`connectionId` TEXT NOT NULL, `conflictId` TEXT NOT NULL, `state` TEXT NOT NULL, `parentId` TEXT NOT NULL, `body` BLOB NOT NULL, PRIMARY KEY(`connectionId`, `conflictId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_blobs` (`connectionId` TEXT NOT NULL, `blobId` TEXT NOT NULL, `size` TEXT, `sha256` TEXT, `sourceUri` TEXT, `state` TEXT NOT NULL, `errorCode` TEXT, `reservationOperationId` TEXT, `uploadOperationId` TEXT, PRIMARY KEY(`connectionId`, `blobId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `nodus_attachments` (`connectionId` TEXT NOT NULL, `noteId` TEXT NOT NULL, `attachmentId` TEXT NOT NULL, `blobId` TEXT NOT NULL, `sourceUri` TEXT, `state` TEXT NOT NULL, `errorCode` TEXT, `draftBody` BLOB, `operationId` TEXT, PRIMARY KEY(`connectionId`, `noteId`, `attachmentId`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nodus_mappings_connectionId_resourceType_parentId_localRowId` ON `nodus_mappings` (`connectionId`, `resourceType`, `parentId`, `localRowId`)")
    }
}

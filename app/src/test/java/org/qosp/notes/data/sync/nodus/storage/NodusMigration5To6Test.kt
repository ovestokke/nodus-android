package org.qosp.notes.data.sync.nodus.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.data.AppDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NodusMigration5To6Test {
    private val tables = listOf("notes", "notebooks", "tags", "note_tags", "reminders", "cloud_ids")
    private fun createV5(context: Context, name: String): SupportSQLiteOpenHelper {
        val schema = javaClass.classLoader!!.getResourceAsStream("org.qosp.notes.data.AppDatabase/5.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject["database"]!!.jsonObject }
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema["entities"]!!.jsonArray.forEach { element ->
                        val table = element.jsonObject
                        val tableName = table["tableName"]!!.jsonPrimitive.content
                        db.execSQL(table["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
                        table["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName)) }
                    }
                    schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
    }
    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO notebooks VALUES (' duplicate ', 7), ('unused', 8)")
        db.execSQL("INSERT INTO tags VALUES (' duplicate ', 9), ('unused', 10)")
        db.execSQL("INSERT INTO notes VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
            "  title\n", "# inactive\n![raw](content://source)", 1,
            "[{\"id\":37,\"content\":\"checked\",\"isDone\":true}]",
            1, 1, 1, 1, 0, 1, 1, 1, 946684800L, 946684901L, 946684902L,
            "[{\"type\":\"AUDIO\",\"path\":\"content://private/1\",\"description\":\"label\",\"fileName\":\"../raw.mp3\"}]",
            "\"Purple\"", 7, 11
        ))
        db.execSQL("INSERT INTO notes VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
            "", "", 0, "[]", 0, 0, 0, 0, 1, 0, 0, 0, 2, 3, null, "[]", "\"Default\"", null, 12
        ))
        db.execSQL("INSERT INTO note_tags VALUES (9,11), (10,11)")
        db.execSQL("INSERT INTO reminders VALUES ('past',11,4,13), ('future',11,9999999999,14)")
        db.execSQL("INSERT INTO cloud_ids VALUES (1,11,99,'NEXTCLOUD','etag',1,1,'remote'), (2,11,NULL,'FILE_STORAGE',NULL,0,0,'content://tree'), (3,12,NULL,NULL,NULL,0,0,NULL)")
    }
    private fun rows(db: SupportSQLiteDatabase, table: String): List<List<String?>> = db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
    }

    @Test fun seededV5PreservesEveryColumnRelationAndLegacyMappingAndRoomValidatesV6() {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "migration-${UUID.randomUUID()}"
        val helper = createV5(context, name)
        val old = helper.writableDatabase
        seed(old)
        val before = tables.associateWith { rows(old, it) }
        val oldSchema = rows(old, "sqlite_master").filter { it[1] in tables || it[2] in tables }
        helper.close()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_5_6).allowMainThreadQueries().setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            val db = migrated.openHelper.writableDatabase // Room performs generated schema validation.
            assertEquals(6, db.version)
            db.query("SELECT COUNT(*) FROM nodus_integration").use { assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM nodus_captures").use { assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)) }
            tables.forEach { table ->
                val after=rows(db,table)
                if(table=="reminders") {
                    assertEquals(before[table],after.map { it.dropLast(1) })
                    assertTrue(after.all { it.last()=="" })
                } else assertEquals(table,before[table],after)
            }
            // The sole legacy-schema addition is the device alarm-delivery fingerprint.
            fun unchangedSchema(values:List<List<String?>>)=values.filterNot { it[0]=="table" && it[1]=="reminders" }
            assertEquals(unchangedSchema(oldSchema),unchangedSchema(rows(db,"sqlite_master").filter { it[1] in tables || it[2] in tables }))
            db.query("SELECT COUNT(*) FROM reminder_alarm_state").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
            db.query("PRAGMA table_info(nodus_evidence)").use { cursor ->
                val columns=mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertTrue(columns.containsAll(listOf("resourceType","resourceId","resourceRevision","resourceRevisionDigits")))
            }
            db.query("EXPLAIN QUERY PLAN SELECT resourceRevision FROM nodus_evidence WHERE connectionId='c' AND resourceType='NOTE' AND resourceId='n' AND resourceRevision IS NOT NULL ORDER BY resourceRevisionDigits DESC, resourceRevision DESC LIMIT 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(3).contains("index_nodus_evidence_connectionId_resourceType_resourceId_resourceRevisionDigits_resourceRevision"))
                assertFalse(cursor.getString(3).contains("TEMP B-TREE"))
            }
            db.query("PRAGMA table_info(nodus_mappings)").use { cursor ->
                var found=false
                while(cursor.moveToNext()) if(cursor.getString(cursor.getColumnIndexOrThrow("name"))=="localRowDetached") {
                    found=true; assertEquals("0",cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                }
                assertTrue(found)
            }
            for ((table,expected) in mapOf("nodus_blobs" to listOf("reservationOperationId","uploadOperationId"),"nodus_attachments" to listOf("draftBody","operationId"))) {
                db.query("PRAGMA table_info($table)").use { cursor ->
                    val names=mutableSetOf<String>(); while(cursor.moveToNext()) names+=cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    assertTrue(names.containsAll(expected))
                }
            }
            db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'nodus_%'").use { assertEquals(13, it.count) }
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun failedMigrationRollsBackNewTablesAndKeepsSeededV5Data() {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "rollback-${UUID.randomUUID()}"
        val helper = createV5(context, name)
        val db = helper.writableDatabase
        seed(db)
        val before = tables.associateWith { rows(db, it) }
        helper.close()
        val failing = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries().addMigrations(object : androidx.room.migration.Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    AppDatabase.MIGRATION_5_6.migrate(db)
                    db.execSQL("INSERT INTO nonexistent VALUES (1)")
                }
            }).build()
        assertThrows(Exception::class.java) { failing.openHelper.writableDatabase }
        failing.close()
        val reopened = createV5(context, name)
        val restored = reopened.writableDatabase
        assertEquals(5, restored.version)
        tables.forEach { assertEquals(before[it], rows(restored, it)) }
        restored.query("SELECT name FROM sqlite_master WHERE name LIKE 'nodus_%'").use { assertEquals(0, it.count) }
        reopened.close(); context.deleteDatabase(name)
    }
}

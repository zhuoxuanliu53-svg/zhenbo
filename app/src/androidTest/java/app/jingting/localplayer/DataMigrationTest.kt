package app.jingting.localplayer

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private inline fun <T> AppDatabase.useDatabase(block: (AppDatabase) -> T): T = try { block(this) } finally { close() }

    @Test fun versionOneUpgradePreservesCollectionsSchedulesAndExports() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        // Use the released V0.1 schema, rather than recreating its tables from V0.2 entities.
        val schema = JSONObject(instrumentation.context.assets.open("database-v1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\${TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO media VALUES ('one','content://media/external_primary/video/media/1','旧视频.mp4',9000,1,1,'store')")
            old.execSQL("INSERT INTO media VALUES ('two','content://media/external_primary/audio/media/2','旧音频.m4a',6000,0,1,'store')")
            old.execSQL("INSERT INTO collections VALUES ('collection','睡前',123)")
            old.execSQL("INSERT INTO entries VALUES ('entry-one','collection','one',1)")
            old.execSQL("INSERT INTO entries VALUES ('entry-two','collection','two',0)")
            old.execSQL("INSERT INTO schedules VALUES ('schedule','collection',22,30,31,NULL,'REPEAT_ALL','SKIP',30,1,9999999999999,'2026-09-19','已播放',124)")
            old.execSQL("INSERT INTO exports VALUES ('export','[\"one\"]','旧导出',0,'completed',100,'content://media/external_primary/audio/media/3',NULL,NULL,125)")
            old.version = 1
        }
        try {
            Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build().useDatabase { db ->
                val dao = db.dao() // The first query opens and validates the actual migration.
                assertEquals("睡前", dao.collection("collection")!!.name)
                assertEquals(listOf("two", "one"), dao.entries("collection").map { it.mediaId })
                val schedule = dao.schedules().single()
                assertEquals("collection", schedule.collectionId)
                assertEquals(31, schedule.days)
                assertEquals("REPEAT_ALL", schedule.mode)
                assertEquals(30, schedule.stopMinutes)
                assertEquals("2026-09-19", schedule.handledDate)
                assertTrue(schedule.enabled)
                assertEquals("[]", schedule.queueIds)
                assertEquals("completed", dao.export("export")!!.state)
                assertEquals("content://media/external_primary/audio/media/3", dao.export("export")!!.outputUri)
                assertEquals("ORIGINAL", dao.export("export")!!.exportMode)
                assertNull(dao.export("export")!!.strategy)
                assertEquals(0L, dao.export("export")!!.elapsedMs)
                dao.renameMedia("one", "雨夜")
                assertEquals("雨夜", dao.observeEntries("collection").first().last().title)
            }
            Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build().useDatabase { db ->
                assertEquals("雨夜", db.dao().mediaByIds(listOf("one")).single().title)
                assertEquals("旧视频.mp4", db.dao().rawMedia().first { it.id == "one" }.title)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionTwoUpgradePreservesExistingRecordsAndInitializesNewFields() = runBlocking {
        val name = "migration-v2-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        // This asset is copied verbatim from the released V0.2 Room schema.
        val schema = JSONObject(instrumentation.context.assets.open("database-v2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\${TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO media VALUES ('v2-media','content://media/external_primary/video/media/80','原始视频.mp4',10000,1,1,'store')")
            old.execSQL("INSERT INTO media_aliases VALUES ('v2-media','V0.2 改过的名称')")
            old.execSQL("INSERT INTO collections VALUES ('v2-collection','旧集合',200)")
            old.execSQL("INSERT INTO entries VALUES ('v2-entry','v2-collection','v2-media',0)")
            old.execSQL("INSERT INTO schedules VALUES ('v2-schedule','v2-collection',23,45,65,NULL,'SHUFFLE','REPLACE',45,1,9999999999999,NULL,'等待播放',201)")
            old.execSQL("INSERT INTO exports VALUES ('v2-complete','[\"v2-media\"]','已完成导出',1,'completed',100,'content://media/external_primary/audio/media/81',NULL,NULL,202)")
            old.execSQL("INSERT INTO exports VALUES ('v2-pending','[\"v2-media\"]','未完成导出',0,'publishing',90,NULL,'content://media/external_primary/audio/media/82',NULL,203)")
            old.version = 2
        }
        try {
            Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_2_3).build().useDatabase { db ->
                val dao = db.dao()
                assertEquals("旧集合", dao.collection("v2-collection")!!.name)
                assertEquals("v2-media", dao.entries("v2-collection").single().mediaId)
                assertEquals("V0.2 改过的名称", dao.observeEntries("v2-collection").first().single().title)
                val schedule = dao.schedules().single()
                assertEquals("v2-schedule", schedule.id)
                assertEquals("v2-collection", schedule.collectionId)
                assertEquals(23, schedule.hour)
                assertEquals(45, schedule.minute)
                assertEquals(65, schedule.days)
                assertEquals("SHUFFLE", schedule.mode)
                assertEquals("REPLACE", schedule.conflict)
                assertEquals(45, schedule.stopMinutes)
                assertEquals(9_999_999_999_999L, schedule.nextAt)
                assertTrue(schedule.enabled)
                assertEquals("[]", schedule.queueIds)
                val complete = dao.export("v2-complete")!!
                assertEquals("completed", complete.state)
                assertEquals("content://media/external_primary/audio/media/81", complete.outputUri)
                assertTrue(complete.concatenate)
                val pending = dao.export("v2-pending")!!
                assertEquals("publishing", pending.state)
                assertEquals(90, pending.progress)
                assertEquals("content://media/external_primary/audio/media/82", pending.pendingUri)
                for (export in listOf(complete, pending)) {
                    assertEquals("ORIGINAL", export.exportMode)
                    assertNull(export.strategy)
                    assertEquals(0L, export.elapsedMs)
                }
                // Verify the migrated table can also persist the new fields.
                dao.putSchedule(schedule.copy(collectionId = "", queueIds = "[\"v2-media\"]"))
                dao.putExport(complete.copy(strategy = "COPY_AAC", elapsedMs = 1234))
            }
            Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_2_3).build().useDatabase { db ->
                assertEquals("[\"v2-media\"]", db.dao().schedules().single().queueIds)
                assertEquals("COPY_AAC", db.dao().export("v2-complete")!!.strategy)
                assertEquals(1234L, db.dao().export("v2-complete")!!.elapsedMs)
                assertEquals("V0.2 改过的名称", db.dao().media().single().title)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun aliasSurvivesMetadataRefreshAndResetUsesCurrentSourceName() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().useDatabase { db ->
            val dao = db.dao()
            val original = MediaEntity("local-one", "content://media/external_primary/video/media/40", "原始.mp4", 5000, true)
            val collection = CollectionEntity(name = "睡前")
            dao.putMedia(listOf(original))
            dao.putCollection(collection)
            dao.putEntries(listOf(EntryEntity(collectionId = collection.id, mediaId = original.id, position = 0)))
            dao.renameMedia(original.id, "  雨声  ")
            // This is the same upsert used for MediaStore rescan results.
            dao.putMedia(listOf(original.copy(title = "文件管理器改过的名称.mp4", durationMs = 5200)))
            assertEquals("雨声", dao.media().single().title)
            assertEquals("雨声", dao.observeMedia().first().single().title)
            assertEquals("雨声", dao.mediaByIds(listOf(original.id)).single().title)
            val item = dao.observeEntries(collection.id).first().single()
            assertEquals("雨声", item.title)
            assertEquals(original.uri, item.uri)
            assertEquals(5200L, item.durationMs)
            assertEquals("文件管理器改过的名称.mp4", dao.rawMedia().single().title)
            dao.renameMedia(original.id, "  ")
            assertEquals("文件管理器改过的名称.mp4", dao.media().single().title)
        }
    }
}

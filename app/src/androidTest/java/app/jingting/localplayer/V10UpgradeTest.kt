package app.jingting.localplayer

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.core.ConflictPolicy
import app.jingting.localplayer.core.PlaybackMode
import app.jingting.localplayer.core.PlaybackSnapshot
import app.jingting.localplayer.data.CollectionEntity
import app.jingting.localplayer.data.EntryEntity
import app.jingting.localplayer.data.ExportEntity
import app.jingting.localplayer.data.MediaAliasEntity
import app.jingting.localplayer.data.MediaEntity
import app.jingting.localplayer.data.ScheduleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Run before on V0.5, install the signed V1.0 release with -r, then run after. */
@RunWith(AndroidJUnit4::class)
class V10UpgradeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.graph
    private val prefix = "v10-upgrade-fixture"
    private val legacyCertificate = "ad3e5ae8a5dd45a1a7d01f35efd4d0aadb60fe23f472464707bc638e55992f13"
    private val releaseCertificate = "505abbfaf165c1d57fe06798c8772ec0ab80863d2849205700291c291424ad0a"
    private val assets = listOf("stereo48.wav", "mono44.wav")
    private val sourceFiles get() = assets.mapIndexed { index, _ -> File(context.filesDir, "$prefix-$index.wav") }
    private val media get() = sourceFiles.mapIndexed { index, file ->
        MediaEntity("$prefix-media-$index", Uri.fromFile(file).toString(), "升级音源 ${index + 1}",
            if (index == 0) 3_000 else 2_000, false, source = "test")
    }
    private val collection get() = CollectionEntity("$prefix-collection", "升级保留集合", 1_700_000_000_001)
    private val entries get() = media.reversed().mapIndexed { index, item ->
        EntryEntity("$prefix-entry-$index", collection.id, item.id, index)
    }
    private val alias get() = MediaAliasEntity(media[0].id, "升级保留的显示名称")
    private val snapshot get() = PlaybackSnapshot(entries.map { it.mediaId }, 1, 1_234, PlaybackMode.REPEAT_ALL)
    private val schedule get() = ScheduleEntity(
        id = "$prefix-schedule", collectionId = collection.id, hour = 22, minute = 15, days = 21,
        mode = PlaybackMode.SHUFFLE.name, conflict = ConflictPolicy.REPLACE.name, stopMinutes = 45,
        enabled = false, nextAt = 0, handledDate = "2026-09-21", lastResult = "已关闭",
        createdAt = 1_700_000_000_002, queueIds = JSONArray(snapshot.ids).toString()
    )
    private val export get() = ExportEntity(
        id = "$prefix-export", sourceIds = JSONArray(media.map { it.id }).toString(),
        title = "升级保留导出记录", concatenate = true, state = "completed", progress = 100,
        outputUri = media[1].uri, createdAt = 1_700_000_000_003, exportMode = "ORIGINAL",
        strategy = "PCM_WAV", elapsedMs = 321
    )

    @Test fun upgradePreservesPrivateMediaAndUserData() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase")
        Assume.assumeTrue("Supply -e upgradePhase before or after for an explicit upgrade test", phase in setOf("before", "after"))
        if (phase == "before") {
            assertPackage("0.5.0", debuggable = true, current = legacyCertificate)
            // No Activity or playback service is started: preferences must remain exactly as seeded.
            withContext(Dispatchers.IO) {
                sourceFiles.forEachIndexed { index, file ->
                    instrumentation.context.assets.open(assets[index]).use { input -> file.outputStream().use { input.copyTo(it) } }
                }
            }
            graph.db.withTransaction {
                graph.dao.putMedia(media)
                graph.dao.putCollection(collection)
                graph.dao.putEntries(entries)
                graph.dao.putMediaAlias(alias)
                graph.dao.putSchedule(schedule)
                graph.dao.putExport(export)
            }
            graph.preferences.save(snapshot)
            verifyUserData()
        } else {
            val history = assertPackage("1.0.0", debuggable = false, current = releaseCertificate)
            assertTrue("Old certificate must remain in the signing lineage", legacyCertificate in history)
            assertTrue("Release certificate must be present in the signing lineage", releaseCertificate in history)
            verifyUserData()
            cleanFixtures()
        }
    }

    private suspend fun verifyUserData() {
        assertEquals(collection, graph.dao.collection(collection.id))
        assertEquals(entries, graph.dao.entries(collection.id))
        val raw = graph.dao.rawMedia().filter { it.id in media.map { source -> source.id } }.associateBy { it.id }
        media.forEach { assertEquals(it, raw[it.id]) }
        val displayed = graph.dao.mediaByIds(media.map { it.id }).associateBy { it.id }
        assertEquals(alias.title, displayed[alias.mediaId]?.title)
        assertEquals(media[1].title, displayed[media[1].id]?.title)
        assertEquals(schedule, graph.dao.schedules().single { it.id == schedule.id })
        assertEquals(export, graph.dao.export(export.id))
        assertEquals(snapshot, graph.preferences.snapshot())
        withContext(Dispatchers.IO) {
            sourceFiles.forEachIndexed { index, file ->
                assertTrue("Private audio fixture must survive replacement", file.isFile)
                val original = instrumentation.context.assets.open(assets[index]).use { it.readBytes() }
                assertArrayEquals("Private audio bytes must be unchanged", original, file.readBytes())
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun assertPackage(version: String, debuggable: Boolean, current: String): Set<String> {
        assertEquals("app.jingting.localplayer", context.packageName)
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        assertEquals(version, info.versionName)
        assertEquals(debuggable, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val signing = requireNotNull(info.signingInfo)
        val currentCertificates = signing.apkContentsSigners.map { digest(it.toByteArray()) }.toSet()
        assertEquals(setOf(current), currentCertificates)
        return signing.signingCertificateHistory.orEmpty().map { digest(it.toByteArray()) }.toSet()
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun cleanFixtures() {
        graph.db.withTransaction {
            graph.dao.deleteSchedule(schedule.id)
            graph.dao.deleteCollection(collection.id)
            graph.dao.deleteMediaAlias(alias.mediaId)
            graph.db.openHelper.writableDatabase.execSQL("DELETE FROM exports WHERE id = ?", arrayOf(export.id))
            media.forEach { graph.db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE id = ?", arrayOf(it.id)) }
        }
        graph.preferences.save(PlaybackSnapshot())
        withContext(Dispatchers.IO) { sourceFiles.forEach { assertTrue(it.delete()) } }
    }
}

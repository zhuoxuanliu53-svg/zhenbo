package app.jingting.localplayer

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.core.*
import app.jingting.localplayer.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import android.content.ContentValues
import android.provider.MediaStore
import app.jingting.localplayer.export.ExportManager
import app.jingting.localplayer.export.AudioExportService

@RunWith(AndroidJUnit4::class)
class MediaIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.graph
    private lateinit var scenario: ActivityScenario<MainActivity>
    @Before fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java); Thread.sleep(1200) }
    @After fun close() { runBlocking { withContext(Dispatchers.Main) { runCatching { graph.playback.connect().pause(); graph.playback.sleep(0) } } }; scenario.close() }
    private suspend fun fixture(name: String, duration: Long, video: Boolean = false): MediaEntity {
        val file = File(context.filesDir, "test-$name")
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        val item = MediaEntity("test:$name", Uri.fromFile(file).toString(), "测试音源 $name", duration, video, source = "test")
        graph.dao.putMedia(listOf(item)); return item
    }
    private suspend fun completed(id: String): ExportEntity = withTimeout(90_000) {
        while (true) {
            val task = graph.dao.export(id)!!
            if (task.state in setOf("completed", "failed", "cancelled")) return@withTimeout task
            delay(200)
        }
        error("unreachable")
    }
    private fun audioFormat(uri: String): MediaFormat {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(context, Uri.parse(uri), null); assertEquals(1, extractor.trackCount); val format = extractor.getTrackFormat(0); assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME)); return format }
        finally { extractor.release() }
    }
    @Test fun videoPlaybackContinuesWhenActivityStops() = runBlocking {
        val source = fixture("video.mp4", 10_000, true)
        withContext(Dispatchers.Main) { graph.playback.play(listOf(source.id), PlaybackMode.REPEAT_ONE) }
        delay(1500)
        assertTrue(graph.playback.state.value.isPlaying)
        val before = graph.playback.state.value.positionMs
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        delay(2000)
        assertTrue(graph.playback.state.value.isPlaying)
        assertTrue(graph.playback.state.value.positionMs > before)
        withContext(Dispatchers.Main) {
            val controller = graph.playback.connect()
            assertTrue(controller.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO))
            graph.playback.sleep(1)
        }
        delay(200)
        assertTrue(graph.playback.state.value.sleepDeadline > SystemClock.elapsedRealtime())
        withContext(Dispatchers.Main) { graph.playback.sleep(0) }
    }
    @Test fun extractsVideoAsAudioOnly() = runBlocking {
        val source = fixture("video.mp4", 10_000, true)
        val id = withContext(Dispatchers.Main) { graph.exports.enqueue(listOf(source.id), "test-extracted", false) }
        val result = completed(id)
        assertEquals(result.error, "completed", result.state)
        val format = audioFormat(result.outputUri!!)
        assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        assertTrue(format.getLong(MediaFormat.KEY_DURATION) in 9_800_000..10_300_000)
    }
    @Test fun concatenatesMixedRatesAndChannels() = runBlocking {
        val stereo = fixture("stereo48.wav", 3000)
        val mono = fixture("mono44.wav", 2000)
        val id = withContext(Dispatchers.Main) { graph.exports.enqueue(listOf(stereo.id, mono.id), "test-concatenated", true, "AAC") }
        val result = completed(id)
        assertEquals(result.error, "completed", result.state)
        val format = audioFormat(result.outputUri!!)
        assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        assertEquals(48000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertTrue(format.getLong(MediaFormat.KEY_DURATION) in 4_800_000..5_300_000)
    }
    @Test fun rejectsVideoWithoutAudioAndCleansTemporaryFiles() = runBlocking {
        val source = fixture("silent.mp4", 1000, true)
        val id = withContext(Dispatchers.Main) { graph.exports.enqueue(listOf(source.id), "test-no-audio", false) }
        val result = completed(id)
        assertEquals("failed", result.state)
        assertNull(result.outputUri)
        delay(100)
        assertFalse(graph.exports.taskDir(id).exists())
    }
    @Test fun cancellationDoesNotPublishPartialAudio() = runBlocking {
        val source = fixture("stereo48.wav", 3000)
        val id = withContext(Dispatchers.Main) {
            val task = graph.exports.enqueue(List(80) { source.id }, "test-cancelled", true, "AAC")
            graph.exports.cancel(task)
            task
        }
        val result = completed(id)
        assertEquals("cancelled", result.state)
        assertNull(result.outputUri)
        delay(300)
        assertFalse(graph.exports.taskDir(id).exists())
    }
    @Test fun runningExportCanBeCancelledWithoutBlockingNextTask() = runBlocking {
        val source = fixture("stereo48.wav", 3000)
        val first = withContext(Dispatchers.Main) { graph.exports.enqueue(List(80) { source.id }, "test-active-cancel", true, "AAC") }
        withTimeout(10_000) { while (graph.dao.export(first)?.state != "running") delay(20) }
        val next = withContext(Dispatchers.Main) {
            val nextId = graph.exports.enqueue(listOf(source.id), "test-after-cancel", false, "AAC")
            graph.exports.cancel(first)
            nextId
        }
        assertEquals("cancelled", completed(first).state)
        assertEquals("completed", completed(next).state)
        assertFalse(graph.exports.taskDir(first).exists())
    }
    @Test fun collectionReorderIsDurableAndDeleteDoesNotDeleteSource() = runBlocking {
        val stereo = fixture("stereo48.wav", 3000)
        val mono = fixture("mono44.wav", 2000)
        val collection = CollectionEntity(name = "测试集合")
        graph.dao.putCollection(collection)
        val one = EntryEntity(collectionId = collection.id, mediaId = stereo.id, position = 0)
        val two = EntryEntity(collectionId = collection.id, mediaId = mono.id, position = 1)
        graph.dao.putEntries(listOf(one, two))
        graph.dao.putEntries(listOf(one.copy(position = 1), two.copy(position = 0)))
        assertEquals(listOf(mono.id, stereo.id), graph.dao.entries(collection.id).map { it.mediaId })
        graph.dao.deleteCollection(collection.id)
        assertTrue(graph.dao.entries(collection.id).isEmpty())
        assertTrue(graph.media.canRead(stereo.uri))
    }
    @Test fun scheduledConflictPolicySkipsOrReplaces() = runBlocking {
        Assume.assumeTrue(graph.schedules.canSchedule())
        val first = fixture("stereo48.wav", 3000)
        val second = fixture("mono44.wav", 2000)
        val collection = CollectionEntity(name = "定时策略测试")
        graph.dao.putCollection(collection)
        graph.dao.putEntries(listOf(EntryEntity(collectionId = collection.id, mediaId = second.id, position = 0)))
        withContext(Dispatchers.Main) { graph.playback.play(listOf(first.id), PlaybackMode.REPEAT_ONE) }
        withTimeout(10_000) { graph.playback.state.first { it.isPlaying } }
        val skip = ScheduleEntity(collectionId = collection.id, hour = 0, minute = 0, nextAt = System.currentTimeMillis() - 500, conflict = ConflictPolicy.SKIP.name)
        graph.dao.putSchedule(skip)
        withContext(Dispatchers.Main) { graph.schedules.fire() }
        assertEquals(first.id, graph.playback.state.value.ids.first())
        assertEquals("正在播放，已跳过", graph.dao.schedules().first { it.id == skip.id }.lastResult)
        val replace = skip.copy(id = java.util.UUID.randomUUID().toString(), conflict = ConflictPolicy.REPLACE.name, mode = PlaybackMode.REPEAT_ONE.name, nextAt = System.currentTimeMillis() - 100, stopMinutes = 1)
        graph.dao.putSchedule(replace)
        withContext(Dispatchers.Main) { graph.schedules.fire() }
        delay(300)
        assertEquals(second.id, graph.playback.state.value.ids.first())
        assertTrue(graph.playback.state.value.sleepDeadline > SystemClock.elapsedRealtime())
        val handled = graph.dao.schedules().first { it.id == replace.id }
        assertFalse(handled.enabled)
        assertNotNull(handled.handledDate)
        graph.dao.deleteCollection(collection.id)
    }
    @Test fun exactAlarmStartsPlaybackWithActivityInBackground() = runBlocking {
        Assume.assumeTrue(graph.schedules.canSchedule())
        val source = fixture("video.mp4", 10_000, true)
        val collection = CollectionEntity(name = "实际闹钟测试")
        graph.dao.putCollection(collection)
        graph.dao.putEntries(listOf(EntryEntity(collectionId = collection.id, mediaId = source.id, position = 0)))
        withContext(Dispatchers.Main) { graph.playback.connect().pause() }
        val next = LocalDateTime.now().plusMinutes(1).withSecond(0).withNano(0)
        val schedule = ScheduleEntity(collectionId = collection.id, hour = next.hour, minute = next.minute, date = next.toLocalDate().toString(), mode = PlaybackMode.REPEAT_ONE.name)
        withContext(Dispatchers.Main) { graph.schedules.save(schedule) }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        withTimeout(80_000) { graph.playback.state.first { it.isPlaying && it.ids.firstOrNull() == source.id } }
        assertTrue(graph.playback.state.value.isPlaying)
        graph.dao.deleteCollection(collection.id)
    }
    @Test fun sleepTimerStopsPlaybackInBackground() = runBlocking {
        val source = fixture("video.mp4", 10_000, true)
        withContext(Dispatchers.Main) { graph.playback.play(listOf(source.id), PlaybackMode.REPEAT_ONE); graph.playback.sleep(1) }
        withTimeout(10_000) { graph.playback.state.first { it.isPlaying && it.sleepDeadline > 0 } }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        withTimeout(70_000) { graph.playback.state.first { !it.isPlaying && it.sleepDeadline == 0L } }
        assertFalse(graph.playback.state.value.isPlaying)
    }
    @Test fun recoveryCleansPendingButPreservesPublishedOutput() = runBlocking {
        withTimeout(10_000) { while (AudioExportService.running) delay(100) }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fun pending(name: String) = context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/静听/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        val orphan = pending("test-orphan.wav")
        val published = pending("test-recovery-kept.wav")
        context.contentResolver.openOutputStream(published)!!.use { out -> instrumentation.context.assets.open("mono44.wav").use { it.copyTo(out) } }
        context.contentResolver.update(published, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val task = ExportEntity(sourceIds = "[]", title = "recovery", concatenate = false, state = "publishing", pendingUri = published.toString())
        graph.dao.putExport(task)
        val dir = graph.exports.taskDir(task.id).apply { mkdirs() }
        File(dir, "audio.m4a").writeText("unfinished")
        ExportManager(context, graph).recover()
        assertEquals("completed", graph.dao.export(task.id)?.state)
        assertEquals(100, graph.dao.export(task.id)?.progress)
        assertEquals(published.toString(), graph.dao.mediaByIds(listOf(published.toString())).single().id)
        assertTrue(graph.media.canRead(published.toString()))
        assertFalse(graph.media.canRead(orphan.toString()))
        assertFalse(dir.exists())
    }
}

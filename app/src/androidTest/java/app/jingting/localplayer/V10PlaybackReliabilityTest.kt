@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.jingting.localplayer

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.core.*
import app.jingting.localplayer.data.*
import app.jingting.localplayer.playback.toMediaItem
import app.jingting.localplayer.schedule.TimerTask
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V10PlaybackReliabilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.graph
    private val token = UUID.randomUUID().toString()
    private val files = mutableListOf<File>()
    private val mediaIds = mutableListOf<String>()
    private val collectionIds = mutableListOf<String>()
    private val scheduleIds = mutableListOf<String>()
    private val publicUris = mutableListOf<Uri>()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launch() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        withContext(Dispatchers.Main) {
            graph.timers.cancel()
            graph.playback.connect().apply { pause(); clearMediaItems() }
        }
        delay(300)
    }

    @After fun cleanUp() = runBlocking {
        withContext(Dispatchers.Main) {
            graph.timers.cancel()
            graph.playback.connect().apply { pause(); clearMediaItems() }
        }
        scheduleIds.forEach { graph.schedules.remove(it) }
        collectionIds.forEach { graph.dao.deleteCollection(it) }
        withContext(Dispatchers.IO) {
            mediaIds.forEach { graph.db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE id = ?", arrayOf(it)) }
            publicUris.forEach { context.contentResolver.delete(it, null, null) }
            files.forEach { it.delete() }
        }
        scenario.close()
    }

    private suspend fun fixture(name: String, corrupt: Boolean = false, missing: Boolean = false): MediaEntity {
        val file = File(context.filesDir, "v10-$token-$name.wav").also { files += it }
        if (corrupt) file.writeText("This is not an audio file.")
        else if (!missing) instrumentation.context.assets.open("stereo48.wav").use { source -> file.outputStream().use { source.copyTo(it) } }
        return MediaEntity("v10-$token-$name", Uri.fromFile(file).toString(), name, 3_000, false, source = "test")
            .also { mediaIds += it.id; graph.dao.putMedia(listOf(it)) }
    }

    @Test fun clearingQueueAlsoClearsSavedSnapshot() = runBlocking {
        val source = fixture("clear")
        withContext(Dispatchers.Main) { graph.playback.play(listOf(source.id), PlaybackMode.REPEAT_ONE) }
        withTimeout(10_000) { graph.playback.state.first { it.isPlaying && it.ids == listOf(source.id) } }
        withTimeout(5_000) { while (graph.preferences.snapshot().ids != listOf(source.id)) delay(20) }
        withContext(Dispatchers.Main) { graph.playback.connect().apply { pause(); clearMediaItems() } }
        withTimeout(5_000) { while (graph.preferences.snapshot().ids.isNotEmpty()) delay(20) }
        withContext(Dispatchers.Main) { graph.playback.toggle(); assertEquals(0, graph.playback.connect().mediaItemCount) }
    }

    @Test fun missingRestoredItemDoesNotSeekOrPlayAnotherFile() = runBlocking {
        val valid = fixture("valid")
        val missing = fixture("deleted", missing = true)
        graph.preferences.save(PlaybackSnapshot(listOf(valid.id, missing.id), 1, 2_600, PlaybackMode.REPEAT_ONE))
        withContext(Dispatchers.Main) {
            graph.playback.resumeCurrent(PlaybackMode.REPEAT_ONE, resume = false)
            val c = graph.playback.connect()
            assertEquals(listOf(valid.id), (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId })
            assertEquals(0L, c.currentPosition)
            assertFalse(c.playWhenReady)
        }
        delay(300)
        assertFalse(graph.playback.state.value.isPlaying)
    }

    @Test fun resumingSavedMiddleItemKeepsTheOriginalQueueOrder() = runBlocking {
        val sources = (0..2).map { fixture("order-$it") }
        graph.preferences.save(PlaybackSnapshot(sources.map { it.id }, 1, 1_200, PlaybackMode.REPEAT_ONE))
        withContext(Dispatchers.Main) {
            graph.playback.toggle()
            val c = graph.playback.connect()
            c.pause()
            assertEquals(sources.map { it.id }, (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId })
            assertEquals(1, c.currentMediaItemIndex)
            assertEquals(1_200L, c.currentPosition)
        }
    }

    @Test fun corruptItemIsSkippedInSingleRepeatWithoutUnpausing() = runBlocking {
        val bad = fixture("corrupt", corrupt = true)
        val good = fixture("good")
        withContext(Dispatchers.Main) {
            graph.playback.connect().apply {
                pause()
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItems(listOf(bad.ref().toMediaItem(), good.ref().toMediaItem()))
                prepare()
            }
        }
        withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) {
                graph.playback.connect().let { it.mediaItemCount == 1 && it.currentMediaItem?.mediaId == good.id && it.playbackState == Player.STATE_READY }
            }) delay(20)
        }
        withContext(Dispatchers.Main) { assertFalse(graph.playback.connect().playWhenReady) }
        assertFalse(graph.playback.state.value.isPlaying)
    }

    @Test fun allCorruptItemsTerminateEvenWithShuffleAndRepeatEnabled() = runBlocking {
        val sources = (0..2).map { fixture("broken-$it", corrupt = true) }
        withContext(Dispatchers.Main) { graph.playback.play(sources.map { it.id }, PlaybackMode.SHUFFLE) }
        withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { graph.playback.connect().let { it.mediaItemCount == 0 && !it.playWhenReady } }) delay(20)
        }
        withTimeout(5_000) { while (graph.preferences.snapshot().ids.isNotEmpty()) delay(20) }
        assertFalse(graph.playback.state.value.isPlaying)
    }

    @Test fun completedQueueCanBePlayedAgain() = runBlocking {
        val source = fixture("ended")
        withContext(Dispatchers.Main) { graph.playback.play(listOf(source.id), PlaybackMode.ORDERED) }
        withTimeout(10_000) { graph.playback.state.first { it.isPlaying } }
        withContext(Dispatchers.Main) { graph.playback.seek(2_900) }
        withTimeout(5_000) {
            while (!withContext(Dispatchers.Main) { graph.playback.connect().playbackState == Player.STATE_ENDED }) delay(20)
        }
        withContext(Dispatchers.Main) {
            // Ended players retain playWhenReady: the play button must still restart.
            graph.playback.toggle()
        }
        withTimeout(5_000) { graph.playback.state.first { it.isPlaying && it.positionMs < 2_900 } }
    }

    @Test fun trashedCollectionCannotReplacePlaybackOrItsTimer() = runBlocking {
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 30)
        Assume.assumeTrue(graph.schedules.canSchedule())
        val current = fixture("current")
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "v10-$token.wav")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/静听/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!.also { publicUris += it }
        context.contentResolver.openOutputStream(uri)!!.use { output -> instrumentation.context.assets.open("stereo48.wav").use { it.copyTo(output) } }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val trashed = MediaEntity(uri.toString(), uri.toString(), "回收站音频", 3_000, false).also { mediaIds += it.id; graph.dao.putMedia(listOf(it)) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
        val collection = CollectionEntity(name = "回收站集合").also { collectionIds += it.id; graph.dao.putCollection(it) }
        graph.dao.putEntries(listOf(EntryEntity(collectionId = collection.id, mediaId = trashed.id, position = 0)))
        withContext(Dispatchers.Main) {
            graph.playback.play(listOf(current.id), PlaybackMode.REPEAT_ONE)
            graph.timers.startCountdown(null, PlaybackMode.REPEAT_ONE, 30)
        }
        val timer = graph.preferences.timerTask()!!
        val rejected = withContext(Dispatchers.Main) { runCatching { graph.timers.startCountdown(collection.id, PlaybackMode.REPEAT_ALL, 15) }.isFailure }
        assertTrue(rejected)
        assertEquals(timer.id, graph.preferences.timerTask()!!.id)
        val schedule = ScheduleEntity(collectionId = collection.id, hour = 0, minute = 0,
            nextAt = System.currentTimeMillis() - 100, conflict = ConflictPolicy.REPLACE.name,
            mode = PlaybackMode.REPEAT_ONE.name, stopMinutes = 10).also { scheduleIds += it.id }
        graph.dao.putSchedule(schedule)
        withContext(Dispatchers.Main) { graph.schedules.fire() }
        assertEquals("内容为空或文件不可访问", graph.dao.schedules().first { it.id == schedule.id }.lastResult)
        assertEquals(timer.id, graph.preferences.timerTask()!!.id)
        assertEquals(listOf(current.id), graph.playback.state.value.ids)
    }

    @Test fun failedScheduledStartPausesItsRequestAndCancelsItsTimer() = runBlocking {
        val source = fixture("failed-start")
        val schedule = ScheduleEntity(collectionId = "", hour = 0, minute = 0, mode = PlaybackMode.REPEAT_ONE.name, stopMinutes = 30)
        withContext(Dispatchers.Main) {
            val request = graph.playback.playRequest(listOf(source.id), PlaybackMode.REPEAT_ONE)!!
            val timer = graph.timers.armForSchedule(schedule)!!
            assertTrue(graph.schedules.cancelFailedStart(request, timer))
            assertFalse(graph.playback.connect().playWhenReady)
            assertEquals(TimerTask.CANCELLED, graph.preferences.timerTask()!!.status)
            assertFalse(graph.schedules.cancelFailedStart(request, timer))
        }
    }

    @Test fun failedScheduledStartDoesNotPauseNewManualPlayback() = runBlocking {
        val oldSource = fixture("old-start")
        val newSource = fixture("manual-start")
        val schedule = ScheduleEntity(collectionId = "", hour = 0, minute = 0, mode = PlaybackMode.REPEAT_ONE.name, stopMinutes = 30)
        withContext(Dispatchers.Main) {
            val request = graph.playback.playRequest(listOf(oldSource.id), PlaybackMode.REPEAT_ONE)!!
            val timer = graph.timers.armForSchedule(schedule)!!
            graph.playback.play(listOf(newSource.id), PlaybackMode.REPEAT_ONE)
            assertFalse(graph.schedules.cancelFailedStart(request, timer))
            assertTrue(graph.playback.connect().playWhenReady)
            assertEquals(newSource.id, graph.playback.connect().currentMediaItem!!.mediaId)
            assertEquals(timer, graph.preferences.timerTask())
        }
    }

    @Test fun failedScheduledStartDoesNotCancelAnEditedTimerWithTheSameId() = runBlocking {
        val source = fixture("edited-timer")
        val schedule = ScheduleEntity(collectionId = "", hour = 0, minute = 0, mode = PlaybackMode.REPEAT_ONE.name, stopMinutes = 30)
        withContext(Dispatchers.Main) {
            val request = graph.playback.playRequest(listOf(source.id), PlaybackMode.REPEAT_ONE)!!
            val timer = graph.timers.armForSchedule(schedule)!!
            graph.timers.startCountdown(null, PlaybackMode.REPEAT_ONE, 60)
            val edited = graph.preferences.timerTask()!!
            assertEquals(timer.id, edited.id)
            assertEquals(60, edited.durationMinutes)
            assertTrue(graph.playback.isCurrentRequest(request))
            assertFalse(graph.schedules.cancelFailedStart(request, timer))
            assertTrue(graph.playback.connect().playWhenReady)
            assertEquals(edited, graph.preferences.timerTask())
        }
    }
}

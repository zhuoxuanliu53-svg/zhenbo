package app.jingting.localplayer

import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.core.ConflictPolicy
import app.jingting.localplayer.core.PlaybackMode
import app.jingting.localplayer.data.CollectionEntity
import app.jingting.localplayer.data.EntryEntity
import app.jingting.localplayer.data.MediaEntity
import app.jingting.localplayer.data.ScheduleEntity
import app.jingting.localplayer.schedule.TimerTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class UnifiedTimerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.graph
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val schedules = mutableListOf<String>()
    private val collections = mutableListOf<String>()

    @Before fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    @After fun close() {
        runBlocking {
            withContext(Dispatchers.Main) { graph.timers.cancel(); graph.playback.connect().pause() }
            schedules.forEach { graph.schedules.remove(it) }
            collections.forEach { graph.dao.deleteCollection(it) }
        }
        scenario.close()
    }

    private suspend fun fixture(name: String, durationMs: Long, video: Boolean): MediaEntity {
        val file = File(context.filesDir, "v03-timer-$name")
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return MediaEntity("v03timer:$name", Uri.fromFile(file).toString(), "计时测试", durationMs, video, source = "test").also {
            graph.dao.putMedia(listOf(it))
        }
    }

    private suspend fun playingVideo(): MediaEntity {
        val source = fixture("video.mp4", 10_000, true)
        withContext(Dispatchers.Main) { graph.playback.play(listOf(source.id), PlaybackMode.REPEAT_ONE) }
        withTimeout(10_000) { graph.playback.state.first { it.isPlaying && it.ids == listOf(source.id) } }
        return source
    }

    @Test fun currentQueueAndLegacySleepShareOneTimerAndCancelKeepsPlayback() = runBlocking {
        playingVideo()
        withContext(Dispatchers.Main) {
            graph.playback.seek(2_000)
            graph.timers.startCountdown(null, PlaybackMode.REPEAT_ALL, 15)
            assertTrue(graph.playback.connect().currentPosition >= 2_000)
        }
        val task = graph.timers.state.value!!
        assertEquals(15, task.durationMinutes)
        assertEquals(PlaybackMode.REPEAT_ALL, task.mode)
        withTimeout(5_000) { graph.playback.state.first { it.sleepDeadline == task.deadlineElapsedMs } }
        assertEquals(task, graph.preferences.timerTask())
        withContext(Dispatchers.Main) { graph.playback.sleep(30) }
        val changed = graph.timers.state.value!!
        assertEquals(30, changed.durationMinutes)
        withTimeout(5_000) { graph.playback.state.first { it.sleepDeadline == changed.deadlineElapsedMs } }
        withContext(Dispatchers.Main) { graph.timers.cancel() }
        withTimeout(5_000) { graph.playback.state.first { it.sleepDeadline == 0L } }
        assertEquals(TimerTask.CANCELLED, graph.timers.state.value!!.status)
        withTimeout(5_000) { graph.playback.state.first { it.isPlaying } }
        Unit
    }

    @Test fun collectionCountdownChangesSourceButEditingPreservesPausedPosition() = runBlocking {
        val video = fixture("video.mp4", 10_000, true)
        val audio = fixture("stereo48.wav", 3_000, false)
        val collection = CollectionEntity(name = "计时集合").also { collections += it.id }
        graph.dao.putCollection(collection)
        graph.dao.putEntries(listOf(EntryEntity(collectionId = collection.id, mediaId = audio.id, position = 0),
            EntryEntity(collectionId = collection.id, mediaId = video.id, position = 1)))
        withContext(Dispatchers.Main) { graph.timers.startCountdown(collection.id, PlaybackMode.REPEAT_ALL, 30) }
        withTimeout(10_000) { graph.playback.state.first { it.isPlaying && it.ids == listOf(audio.id, video.id) } }
        val first = graph.timers.state.value!!
        withContext(Dispatchers.Main) {
            val c = graph.playback.connect()
            c.pause(); c.seekTo(1, 2_000)
            graph.timers.startCountdown(collection.id, PlaybackMode.SHUFFLE, 45)
            assertFalse(c.playWhenReady)
            assertEquals(1, c.currentMediaItemIndex)
            assertEquals(2_000L, c.currentPosition)
            assertTrue(c.shuffleModeEnabled)
        }
        val edited = graph.timers.state.value!!
        assertEquals(first.id, edited.id)
        assertEquals(collection.id, edited.collectionId)
        assertEquals(collection.name, edited.sourceTitle)
        assertEquals(45, edited.durationMinutes)
        assertEquals(PlaybackMode.SHUFFLE, edited.mode)
    }

    @Test fun elapsedTimerCompletesDuringPauseAndSurvivesWallClockMetadataChanges() = runBlocking {
        playingVideo()
        withContext(Dispatchers.Main) { graph.timers.startCountdown(null, PlaybackMode.REPEAT_ONE, 1); graph.playback.connect().pause() }
        val original = graph.timers.state.value!!
        val shortened = original.copy(deadlineElapsedMs = SystemClock.elapsedRealtime() + 800, createdAt = original.createdAt + 86_400_000)
        graph.preferences.saveTimer(shortened)
        withTimeout(5_000) { graph.timers.state.first { it?.status == TimerTask.COMPLETED } }
        withTimeout(5_000) { graph.playback.state.first { it.sleepDeadline == 0L } }
        assertFalse(graph.playback.state.value.isPlaying)
        assertEquals("计时结束", graph.preferences.timerTask()!!.result)
    }

    @Test fun restartInvalidatesElapsedTimerWithoutStartingPlayback() = runBlocking {
        playingVideo()
        withContext(Dispatchers.Main) { graph.timers.startCountdown(null, PlaybackMode.REPEAT_ONE, 30); graph.playback.connect().pause() }
        graph.preferences.saveTimer(graph.timers.state.value!!.copy(bootCount = graph.preferences.bootCount() - 1))
        withContext(Dispatchers.Main) { graph.timers.reconcile() }
        val task = graph.timers.state.value!!
        assertEquals(TimerTask.CANCELLED, task.status)
        assertEquals("设备重启，计时已取消", task.result)
        assertFalse(graph.playback.state.value.isPlaying)
    }

    @Test fun clockTaskKeepsQueueSnapshotAndOnlyReplacesTimerWhenTriggered() = runBlocking {
        Assume.assumeTrue(graph.schedules.canSchedule())
        val first = playingVideo()
        val second = fixture("stereo48.wav", 3_000, false)
        withContext(Dispatchers.Main) { graph.timers.startCountdown(null, PlaybackMode.REPEAT_ONE, 10) }
        val before = graph.timers.state.value!!
        val next = LocalDateTime.now().plusHours(1)
        val schedule = ScheduleEntity(collectionId = "", queueIds = JSONArray(listOf(second.id)).toString(),
            hour = next.hour, minute = next.minute, date = next.toLocalDate().toString(),
            conflict = ConflictPolicy.REPLACE.name, mode = PlaybackMode.REPEAT_ALL.name, stopMinutes = 20)
        schedules += schedule.id
        withContext(Dispatchers.Main) { graph.schedules.save(schedule) }
        withContext(Dispatchers.Main) { graph.schedules.reconcile() }
        val afterResume = graph.dao.schedules().first { it.id == schedule.id }
        assertTrue(afterResume.enabled)
        assertEquals(schedule.queueIds, afterResume.queueIds)
        assertEquals(before, graph.timers.state.value)
        assertEquals(first.id, graph.playback.state.value.ids.first())
        graph.dao.putSchedule(graph.dao.schedules().first { it.id == schedule.id }.copy(nextAt = System.currentTimeMillis() - 100))
        withContext(Dispatchers.Main) { graph.schedules.fire() }
        withTimeout(5_000) { graph.playback.state.first { it.ids == listOf(second.id) && it.isPlaying } }
        val active = graph.timers.state.value!!
        assertEquals(TimerTask.SCHEDULE, active.origin)
        assertEquals(schedule.id, active.scheduleId)
        assertEquals(20, active.durationMinutes)
        assertEquals(PlaybackMode.REPEAT_ALL, active.mode)
        assertTrue(active.deadlineElapsedMs > before.deadlineElapsedMs)
        val afterFire = graph.dao.schedules().first { it.id == schedule.id }
        withContext(Dispatchers.Main) { graph.schedules.fire() }
        assertEquals(afterFire, graph.dao.schedules().first { it.id == schedule.id })
        assertEquals(active.id, graph.timers.state.value!!.id)
    }

    @Test fun editingCollectionAfterManualQueueChangeRestoresChosenCollection() = runBlocking {
        val video = fixture("video.mp4", 10_000, true)
        val audio = fixture("stereo48.wav", 3_000, false)
        val collection = CollectionEntity(name = "明确选择的集合").also { collections += it.id }
        graph.dao.putCollection(collection)
        graph.dao.putEntries(listOf(EntryEntity(collectionId = collection.id, mediaId = video.id, position = 0)))
        withContext(Dispatchers.Main) { graph.timers.startCountdown(collection.id, PlaybackMode.REPEAT_ONE, 30) }
        withTimeout(10_000) { graph.playback.state.first { it.isPlaying && it.ids == listOf(video.id) } }
        withContext(Dispatchers.Main) { graph.playback.play(listOf(audio.id), PlaybackMode.REPEAT_ONE) }
        withTimeout(10_000) { graph.playback.state.first { it.isPlaying && it.ids == listOf(audio.id) } }
        withContext(Dispatchers.Main) { graph.timers.startCountdown(collection.id, PlaybackMode.REPEAT_ALL, 45) }
        withTimeout(10_000) { graph.playback.state.first { it.isPlaying && it.ids == listOf(video.id) } }
        assertEquals(collection.id, graph.timers.state.value!!.collectionId)
        assertEquals(PlaybackMode.REPEAT_ALL, graph.playback.state.value.mode)
    }

    @Test fun oldExpiryCannotStopAReplacementStartedDuringItsPauseCallback() = runBlocking {
        playingVideo()
        withContext(Dispatchers.Main) { graph.timers.startCountdown(null, PlaybackMode.REPEAT_ONE, 30) }
        var callbackReached = false
        var replacement: kotlinx.coroutines.Deferred<Unit>? = null
        // The callback deliberately starts a new timer before the old expiration write returns.
        // It must wait for the expiration's lock, then resume playback with its own deadline.
        withContext(Dispatchers.Main) {
            graph.preferences.saveTimer(graph.timers.state.value!!.copy(deadlineElapsedMs = SystemClock.elapsedRealtime() - 1))
            graph.timers.finishIfDue {
                callbackReached = true
                replacement = async(start = CoroutineStart.UNDISPATCHED) {
                    graph.timers.startCountdown(null, PlaybackMode.REPEAT_ONE, 60)
                }
                graph.playback.pauseIfConnected()
            }
        }
        if (callbackReached) replacement!!.await()
        else {
            // A real service tick may win the expiration race; replacing its result is equivalent.
            withContext(Dispatchers.Main) { graph.timers.startCountdown(null, PlaybackMode.REPEAT_ONE, 60) }
        }
        delay(800)
        withTimeout(5_000) { graph.playback.state.first { it.isPlaying } }
        assertEquals(TimerTask.ACTIVE, graph.timers.state.value!!.status)
        assertEquals(60, graph.timers.state.value!!.durationMinutes)
        assertTrue(graph.timers.state.value!!.deadlineElapsedMs > SystemClock.elapsedRealtime() + 59 * 60_000L)
    }
}

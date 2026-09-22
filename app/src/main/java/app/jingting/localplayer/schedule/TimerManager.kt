package app.jingting.localplayer.schedule

import android.content.Context
import android.os.SystemClock
import app.jingting.localplayer.AppGraph
import app.jingting.localplayer.core.PlaybackMode
import app.jingting.localplayer.data.ScheduleEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One stopping timer shared by the player, task page and clock-triggered playback. */
class TimerManager(private val context: Context, private val graph: AppGraph) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<TimerTask?>(null)
    val state: StateFlow<TimerTask?> = mutableState

    init {
        graph.scope.launch {
            reconcile()
            graph.preferences.timerTasks.collect { mutableState.value = it }
        }
    }

    /** Starting/adjusting a current-queue timer preserves its position. Same-source edits do too. */
    suspend fun startCountdown(collectionId: String?, mode: PlaybackMode, durationMinutes: Int) = mutex.withLock {
        require(durationMinutes in 1..1440) { "计时时长需在 1 至 1440 分钟之间" }
        val previous = graph.preferences.timerTask()
        val collection = collectionId?.let { requireNotNull(graph.dao.collection(it)) { "集合已删除" } }
        val controller = graph.playback.connect()
        val currentIds = if (controller.mediaItemCount > 0) (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }
            else graph.preferences.snapshot().ids
        val collectionIds = collectionId?.let { id ->
            graph.media.resolve(graph.dao.entries(id).map { it.mediaId }).filter { graph.media.isVisibleMedia(it.locator) }.map { it.id }
        }
        if (collectionIds != null) require(collectionIds.isNotEmpty()) { "集合为空或文件不可访问" }
        else require(graph.media.resolve(currentIds).any { graph.media.isVisibleMedia(it.locator) }) { "当前队列没有可播放的内容" }
        // File checks suspend for I/O. A manual selection may have changed the queue meanwhile.
        // Compare the live controller immediately before deciding whether this is a same-source edit.
        val latestIds = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }
        val editingSameSource = previous?.active == true && previous.collectionId == collectionId &&
            (collectionIds == null || collectionIds == latestIds)
        if (collectionId != null && !editingSameSource) {
            val ids = collectionIds.orEmpty()
            // Remove the prior stop deadline before replacing its playback queue.
            cancelLocked("已替换")
            graph.playback.play(ids, mode)
        } else {
            graph.playback.resumeCurrent(mode, resume = !editingSameSource)
        }
        val now = SystemClock.elapsedRealtime()
        persist(TimerTask(
            id = previous?.takeIf { editingSameSource }?.id ?: UUID.randomUUID().toString(),
            collectionId = collectionId, sourceTitle = collection?.name ?: "当前队列", mode = mode,
            durationMinutes = durationMinutes, startedElapsedMs = now,
            deadlineElapsedMs = now + durationMinutes * 60_000L, bootCount = graph.preferences.bootCount(),
            createdAt = previous?.takeIf { editingSameSource }?.createdAt ?: System.currentTimeMillis()
        ))
    }

    /** Compatibility entry for an already-playing queue; never starts or rewinds it. */
    suspend fun setForCurrentPlayback(minutes: Int) = mutex.withLock {
        require(minutes in 0..1440)
        if (minutes == 0) cancelLocked("已取消") else {
            val now = SystemClock.elapsedRealtime()
            persist(TimerTask(UUID.randomUUID().toString(), mode = graph.playback.state.value.mode,
                durationMinutes = minutes, startedElapsedMs = now, deadlineElapsedMs = now + minutes * 60_000L,
                bootCount = graph.preferences.bootCount()))
        }
    }

    suspend fun armForSchedule(schedule: ScheduleEntity): TimerTask? = mutex.withLock {
        if (schedule.stopMinutes == 0) { cancelLocked("已替换"); return@withLock graph.preferences.timerTask() }
        val now = SystemClock.elapsedRealtime()
        val collection = schedule.collectionId.takeIf { it.isNotBlank() }?.let { graph.dao.collection(it) }
        val task = TimerTask(UUID.randomUUID().toString(), collection?.id, collection?.name ?: "当前队列",
            PlaybackMode.valueOf(schedule.mode), schedule.stopMinutes, now, now + schedule.stopMinutes * 60_000L,
            graph.preferences.bootCount(), origin = TimerTask.SCHEDULE, scheduleId = schedule.id)
        persist(task)
        task
    }

    /** A failed clock start must not cancel a timer edited or replaced during its wait. */
    suspend fun cancelFailedStart(expected: TimerTask?, stopOwnPlayback: () -> Boolean): Boolean = mutex.withLock {
        val current = graph.preferences.timerTask()
        if (current != expected || !stopOwnPlayback()) return@withLock false
        if (current?.active == true) persist(current.copy(status = TimerTask.CANCELLED, result = "播放未启动，计时已取消"))
        true
    }

    suspend fun cancel() = mutex.withLock { cancelLocked("已取消") }

    /** Atomically claim the due timer so a concurrent edit cannot pause its replacement. */
    suspend fun finishIfDue(stopPlayback: () -> Unit): Boolean = mutex.withLock {
        val task = graph.preferences.timerTask() ?: return@withLock false
        if (!task.active || task.bootCount != graph.preferences.bootCount() || task.deadlineElapsedMs > SystemClock.elapsedRealtime()) return@withLock false
        // Pause while still holding the claim; a newly started timer cannot be paused by this one.
        stopPlayback()
        persist(task.copy(status = TimerTask.COMPLETED, result = "计时结束"))
        true
    }

    suspend fun reconcile() = mutex.withLock {
        val task = graph.preferences.timerTask() ?: return@withLock
        if (!task.active) { mutableState.value = task; return@withLock }
        when {
            task.bootCount != graph.preferences.bootCount() -> persist(task.copy(status = TimerTask.CANCELLED, result = "设备重启，计时已取消"))
            task.deadlineElapsedMs <= SystemClock.elapsedRealtime() -> {
                graph.playback.pauseIfConnected()
                persist(task.copy(status = TimerTask.COMPLETED, result = "计时结束"))
            }
            else -> persist(task)
        }
    }

    suspend fun cancelForReboot() = mutex.withLock { cancelLocked("设备重启，计时已取消") }

    private suspend fun cancelLocked(result: String) {
        val task = graph.preferences.timerTask()
        if (task?.active == true) persist(task.copy(status = TimerTask.CANCELLED, result = result))
    }

    private suspend fun persist(task: TimerTask) { graph.preferences.saveTimer(task); mutableState.value = task }
}

package app.jingting.localplayer.schedule

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.content.ContextCompat
import app.jingting.localplayer.*
import app.jingting.localplayer.core.*
import app.jingting.localplayer.data.ScheduleEntity
import app.jingting.localplayer.playback.PlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import java.time.*
import org.json.JSONArray

class ScheduleManager(private val context: Context, private val graph: AppGraph) : SchedulePort {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()
    private fun alarmIntent() = PendingIntent.getBroadcast(context, 77, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun canSchedule() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    suspend fun save(item: ScheduleEntity) = mutex.withLock {
        if (item.collectionId.isBlank()) require(queueIds(item).isNotEmpty()) { "请先选择要播放的内容" }
        else require(graph.dao.collection(item.collectionId) != null) { "请选择有效集合" }
        require(item.stopMinutes in 0..1440) { "停止时长需在 0 至 1440 分钟之间" }
        val next = calculate(item.copy(handledDate = null), System.currentTimeMillis())
        require(!item.enabled || next > 0) { "请选择未来的日期和时间" }
        graph.dao.putSchedule(item.copy(nextAt = next, handledDate = null, lastResult = if (canSchedule()) "等待播放" else "未启用：需要精确闹钟权限"))
        register()
    }
    suspend fun remove(id: String) = mutex.withLock { graph.dao.deleteSchedule(id); register() }
    suspend fun enable(item: ScheduleEntity, enabled: Boolean) = mutex.withLock {
        val next = if (enabled) calculate(item, System.currentTimeMillis()) else 0
        graph.dao.putSchedule(item.copy(enabled = enabled && next > 0, nextAt = next, lastResult = if (enabled && next == 0L) "已过期，请编辑日期" else if (enabled) "等待播放" else "已关闭"))
        register()
    }
    override suspend fun reconcile() = mutex.withLock {
        val now = System.currentTimeMillis()
        for (s in graph.dao.schedules()) {
            if (!s.enabled) continue
            val next = calculate(s, now)
            val missing = if (s.collectionId.isBlank()) queueIds(s).isEmpty() else graph.dao.collection(s.collectionId) == null
            graph.dao.putSchedule(s.copy(enabled = !missing && next > 0, nextAt = if (missing) 0 else next, lastResult = when { missing -> "集合已删除，任务已关闭"; !canSchedule() -> "未启用：需要精确闹钟权限"; s.nextAt in 1..now -> "已错过，未补播"; else -> s.lastResult }))
        }
        register()
    }
    suspend fun fire() = mutex.withLock {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val due = graph.dao.schedules().filter { it.enabled && it.nextAt in 1..now }.sortedWith(compareBy<ScheduleEntity> { it.nextAt }.thenBy { it.createdAt })
        var selected = false
        for (s in due) {
            val occurrence = ScheduleMath.occurrence(s.nextAt, zone)
            val result = when {
                !canSchedule() -> "未启用：需要精确闹钟权限"
                s.handledDate == occurrence -> "忽略重复触发"
                ScheduleMath.isLate(s.nextAt, now) -> "已错过，未补播"
                selected -> "同刻任务冲突，已跳过"
                else -> { selected = true; "正在启动" }
            }
            // Durable claim before side effects: duplicate broadcasts cannot play twice.
            var updated = s.copy(handledDate = occurrence, enabled = s.days != 0, lastResult = result)
            updated = updated.copy(nextAt = if (updated.enabled) calculate(updated, now) else 0)
            graph.dao.putSchedule(updated)
            if (result != "正在启动") continue
            val state = graph.playback.state.value
            val finalResult = if ((state.isPlaying || state.preparing) && s.conflict == ConflictPolicy.SKIP.name) "正在播放，已跳过" else {
                val ids = if (s.collectionId.isBlank()) queueIds(s) else graph.dao.entries(s.collectionId).map { it.mediaId }
                val playable = graph.media.resolve(ids).filter { graph.media.isVisibleMedia(it.locator) }.map { it.id }
                if (playable.isEmpty()) "内容为空或文件不可访问" else startScheduled(s, playable)
            }
            graph.dao.putSchedule(updated.copy(lastResult = finalResult))
        }
        register()
    }
    private suspend fun startScheduled(schedule: ScheduleEntity, playable: List<String>): String {
        var request: Long? = null
        var timer: TimerTask? = null
        return try {
            graph.timers.cancel()
            timer = graph.preferences.timerTask()
            request = graph.playback.playRequest(playable, PlaybackMode.valueOf(schedule.mode))
                ?: return "内容为空或文件不可访问"
            timer = graph.timers.armForSchedule(schedule)
            val started = withTimeoutOrNull(10_000) {
                graph.playback.state.first { it.isPlaying && it.ids == playable }
            } != null
            when {
                !graph.playback.isCurrentRequest(request) -> "播放任务已变更"
                started -> "已启动播放"
                cancelFailedStart(request, timer) -> "未能开始播放，请检查音频焦点或后台限制"
                else -> "播放任务已变更"
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { request?.let { cancelFailedStart(it, timer) } }
            throw cancelled
        } catch (_: Exception) {
            request?.let { cancelFailedStart(it, timer) }
            "启动失败，请检查系统后台限制"
        }
    }
    /** Also used by regression tests to exercise timeout cleanup without relying on OS focus timing. */
    internal suspend fun cancelFailedStart(request: Long, timer: TimerTask?): Boolean =
        graph.timers.cancelFailedStart(timer) { graph.playback.pauseIfCurrentRequest(request) }

    suspend fun launchFailed() = mutex.withLock {
        val now = System.currentTimeMillis()
        for (s in graph.dao.schedules().filter { it.enabled && it.nextAt in 1..now }) {
            val updated = s.copy(handledDate = ScheduleMath.occurrence(s.nextAt, ZoneId.systemDefault()), enabled = s.days != 0, lastResult = "系统阻止后台启动")
            graph.dao.putSchedule(updated.copy(nextAt = if (updated.enabled) calculate(updated, now) else 0))
        }
        register()
    }
    private fun calculate(s: ScheduleEntity, now: Long): Long = ScheduleMath.next(s.hour, s.minute, s.days, s.date, Instant.ofEpochMilli(now), ZoneId.systemDefault(), s.handledDate)?.toEpochMilli() ?: 0
    private fun queueIds(s: ScheduleEntity): List<String> = runCatching {
        val ids = JSONArray(s.queueIds)
        List(ids.length()) { ids.getString(it) }
    }.getOrDefault(emptyList())
    private suspend fun register() {
        alarms.cancel(alarmIntent())
        if (!canSchedule()) return
        val next = graph.dao.schedules().filter { it.enabled && it.nextAt > 0 }.minOfOrNull { it.nextAt } ?: return
        try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent()) } catch (_: SecurityException) { /* Rechecked when the page resumes. */ }
    }
}
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java).setAction(PlaybackService.SCHEDULED)) }
        catch (_: Exception) { val pending = goAsync(); context.graph.scope.launch { try { context.graph.schedules.launchFailed() } finally { pending.finish() } } }
    }
}
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.graph.scope.launch { try { if (intent.action == Intent.ACTION_BOOT_COMPLETED) context.graph.timers.cancelForReboot(); context.graph.schedules.reconcile() } finally { pending.finish() } }
    }
}

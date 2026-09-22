package app.jingting.localplayer.data

import android.content.Context
import android.provider.Settings
import android.os.SystemClock
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import app.jingting.localplayer.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.jingting.localplayer.schedule.TimerTask
import org.json.JSONArray
import org.json.JSONObject

private val Context.preferences by preferencesDataStore("player")
class PlayerPreferences(private val context: Context) {
    private val queue = stringPreferencesKey("queue")
    private val index = intPreferencesKey("index")
    private val position = longPreferencesKey("position")
    private val mode = stringPreferencesKey("mode")
    private val deadline = longPreferencesKey("sleep_deadline")
    private val boot = intPreferencesKey("sleep_boot")
    private val timer = stringPreferencesKey("timer_task")
    suspend fun snapshot(): PlaybackSnapshot {
        val p = context.preferences.data.first()
        val ids = runCatching { val a = JSONArray(p[queue] ?: "[]"); List(a.length()) { a.getString(it) } }.getOrDefault(emptyList())
        return PlaybackSnapshot(ids, (p[index] ?: 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0)), p[position] ?: 0, runCatching { PlaybackMode.valueOf(p[mode] ?: "ORDERED") }.getOrDefault(PlaybackMode.ORDERED))
    }
    suspend fun save(value: PlaybackSnapshot) { context.preferences.edit { p -> p[queue] = JSONArray(value.ids).toString(); p[index] = value.index; p[position] = value.positionMs; p[mode] = value.mode.name } }
    // Keep the old keys synchronized for an in-place V0.2 upgrade.
    suspend fun setSleep(value: Long) {
        val existing = timerTask()
        if (value == 0L) {
            saveTimer(existing?.copy(status = TimerTask.CANCELLED, result = "已取消"))
        } else {
            val now = SystemClock.elapsedRealtime()
            saveTimer(TimerTask(existing?.id ?: java.util.UUID.randomUUID().toString(), mode = snapshot().mode,
                durationMinutes = ((value - now + 59_999) / 60_000).toInt().coerceIn(1, 1440),
                startedElapsedMs = now, deadlineElapsedMs = value, bootCount = bootCount()))
        }
    }
    suspend fun sleep(): Long {
        val p = context.preferences.data.first()
        return (p[deadline] ?: 0).takeIf { p[boot] == bootCount() && it > SystemClock.elapsedRealtime() } ?: 0
    }
    val timerTasks: Flow<TimerTask?> = context.preferences.data.map(::decodeTimer)
    suspend fun timerTask(): TimerTask? = decodeTimer(context.preferences.data.first())
    suspend fun saveTimer(value: TimerTask?) {
        context.preferences.edit { p ->
            if (value == null) p.remove(timer) else p[timer] = JSONObject().apply {
                put("id", value.id); put("collectionId", value.collectionId); put("sourceTitle", value.sourceTitle)
                put("mode", value.mode.name); put("durationMinutes", value.durationMinutes)
                put("startedElapsedMs", value.startedElapsedMs); put("deadlineElapsedMs", value.deadlineElapsedMs)
                put("bootCount", value.bootCount); put("status", value.status); put("origin", value.origin)
                put("scheduleId", value.scheduleId); put("createdAt", value.createdAt); put("result", value.result)
            }.toString()
            p[deadline] = value?.takeIf { it.active }?.deadlineElapsedMs ?: 0L
            p[boot] = value?.bootCount ?: bootCount()
        }
    }
    private fun decodeTimer(p: Preferences): TimerTask? {
        p[timer]?.let { raw ->
            return runCatching {
                val j = JSONObject(raw)
                TimerTask(j.getString("id"), j.optString("collectionId").takeIf { it.isNotBlank() && it != "null" },
                    j.optString("sourceTitle", "当前队列"), PlaybackMode.valueOf(j.optString("mode", "ORDERED")),
                    j.getInt("durationMinutes"), j.getLong("startedElapsedMs"), j.getLong("deadlineElapsedMs"),
                    j.getInt("bootCount"), j.optString("status", TimerTask.ACTIVE), j.optString("origin", TimerTask.COUNTDOWN),
                    j.optString("scheduleId").takeIf { it.isNotBlank() && it != "null" }, j.optLong("createdAt"),
                    j.optString("result", "计时中"))
            }.getOrNull()
        }
        val oldDeadline = p[deadline] ?: 0L
        if (oldDeadline <= 0) return null
        val now = SystemClock.elapsedRealtime()
        return TimerTask("migrated-sleep", mode = runCatching { PlaybackMode.valueOf(p[mode] ?: "ORDERED") }.getOrDefault(PlaybackMode.ORDERED),
            durationMinutes = ((oldDeadline - now + 59_999) / 60_000).toInt().coerceIn(1, 1440),
            startedElapsedMs = now, deadlineElapsedMs = oldDeadline, bootCount = p[boot] ?: -1)
    }
    fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}

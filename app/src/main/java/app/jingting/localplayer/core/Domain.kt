package app.jingting.localplayer.core

import java.time.*

enum class PlaybackMode(val label: String, val description: String) {
    ORDERED("播完停止", "按列表顺序播放，最后一项结束后停止"),
    REPEAT_ONE("单曲循环", "重复播放当前这一项"),
    REPEAT_ALL("列表循环", "按列表顺序播放，播完后从头开始"),
    SHUFFLE("随机播放", "打乱顺序连续播放，一轮内不重复")
}
enum class ConflictPolicy(val label: String) { SKIP("继续当前播放"), REPLACE("替换当前播放") }
data class MediaRef(val id: String, val locator: String, val title: String, val durationMs: Long, val isVideo: Boolean, val available: Boolean = true)
data class PlaybackSnapshot(val ids: List<String> = emptyList(), val index: Int = 0, val positionMs: Long = 0, val mode: PlaybackMode = PlaybackMode.ORDERED)
data class PlaybackState(val ids: List<String> = emptyList(), val index: Int = 0, val title: String = "还没有开始播放", val isPlaying: Boolean = false, val preparing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val mode: PlaybackMode = PlaybackMode.ORDERED, val sleepDeadline: Long = 0, val message: String? = null)
interface MediaAccess { suspend fun listMedia(): List<MediaRef>; suspend fun resolve(ids: List<String>): List<MediaRef> }
interface PlaybackPort { suspend fun play(ids: List<String>, mode: PlaybackMode, positionMs: Long = 0) }
interface ExportPort { suspend fun enqueue(ids: List<String>, title: String, concatenate: Boolean, exportMode: String = "ORIGINAL"): String; fun cancel(id: String) }
interface SchedulePort { fun canSchedule(): Boolean; suspend fun reconcile() }

/** Local-time recurrence, independent of Android. Monday is bit 0. */
object ScheduleMath {
    const val GRACE_MS = 5 * 60_000L
    fun next(hour: Int, minute: Int, days: Int, date: String?, after: Instant, zone: ZoneId, handledDate: String? = null): Instant? {
        require(hour in 0..23 && minute in 0..59)
        val today = after.atZone(zone).toLocalDate()
        val dates = if (days == 0) listOfNotNull(date?.let(LocalDate::parse)) else (0L..8L).map { today.plusDays(it) }.filter { days and (1 shl (it.dayOfWeek.value - 1)) != 0 }
        return dates.asSequence().filter { it.toString() != handledDate }.map { ZonedDateTime.of(it, LocalTime.of(hour, minute), zone).toInstant() }.firstOrNull { it > after }
    }
    fun occurrence(epochMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()
    fun isLate(scheduled: Long, now: Long): Boolean = now - scheduled > GRACE_MS
}

object ExportNames {
    fun normalize(input: String): String = input.trim().removeSuffix(".m4a").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).ifBlank { "枕波音频" }
}

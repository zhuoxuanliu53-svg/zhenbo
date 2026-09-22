package app.jingting.localplayer.schedule

import app.jingting.localplayer.core.PlaybackMode

/** A stopping deadline always uses elapsed time, never a wall clock or time zone. */
data class TimerTask(
    val id: String,
    val collectionId: String? = null,
    val sourceTitle: String = "当前队列",
    val mode: PlaybackMode = PlaybackMode.ORDERED,
    val durationMinutes: Int,
    val startedElapsedMs: Long,
    val deadlineElapsedMs: Long,
    val bootCount: Int,
    val status: String = ACTIVE,
    val origin: String = COUNTDOWN,
    val scheduleId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val result: String = "计时中"
) {
    val active: Boolean get() = status == ACTIVE
    fun remainingMs(nowElapsedMs: Long): Long = if (active) (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0) else 0
    companion object {
        const val ACTIVE = "active"
        const val COMPLETED = "completed"
        const val CANCELLED = "cancelled"
        const val COUNTDOWN = "countdown"
        const val SCHEDULE = "schedule"
    }
}

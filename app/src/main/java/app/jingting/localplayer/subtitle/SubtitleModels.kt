package app.jingting.localplayer.subtitle

import java.util.Locale

data class SubtitleCue(val startUs: Long, val endUs: Long, val text: String)
data class SubtitleRegion(val left: Float = 0f, val top: Float = 2f / 3, val right: Float = 1f, val bottom: Float = 1f) {
    init { require(left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right > left && bottom > top) }
}
data class SubtitleSource(val mediaId: String, val mode: String = "NONE", val trackId: Int = -1, val region: SubtitleRegion = SubtitleRegion())
data class SubtitleExportOptions(val directory: String, val sources: List<SubtitleSource>)
data class SubtitleTrack(val id: Int, val mime: String, val language: String?, val label: String?, val supported: Boolean)
data class ExportSegment(val mediaId: String, val sourceStartUs: Long, val durationUs: Long, val outputStartUs: Long)
data class ExportTimeline(val segments: List<ExportSegment>) {
    fun map(source: Map<String, List<SubtitleCue>>): List<SubtitleCue> = segments.flatMap { segment ->
        source[segment.mediaId].orEmpty().mapNotNull { cue ->
            val start = maxOf(cue.startUs, segment.sourceStartUs)
            val end = minOf(cue.endUs, segment.sourceStartUs + segment.durationUs)
            if (end <= start) null else SubtitleCue(start - segment.sourceStartUs + segment.outputStartUs, end - segment.sourceStartUs + segment.outputStartUs, cue.text)
        }
    }.sortedBy { it.startUs }
}

object SubRip {
    fun encode(cues: List<SubtitleCue>): String = cues.filter { it.endUs > it.startUs && it.text.isNotBlank() }.sortedBy { it.startUs }.mapIndexed { index, cue ->
        "${index + 1}\n${time(cue.startUs)} --> ${time(cue.endUs)}\n${cue.text.trim()}\n"
    }.joinToString("\n")
    private fun time(us: Long): String {
        val ms = us.coerceAtLeast(0) / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }
    fun decode(value: String): List<SubtitleCue> {
        require(value.length <= 16 * 1024 * 1024) { "字幕文件过大" }
        val pattern = Regex("(\\d+):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d+):(\\d{2}):(\\d{2})[,.](\\d{3})[^\\n]*\\n([\\s\\S]*?)(?=\\n\\s*\\n|$)")
        return pattern.findAll(value.removePrefix("\uFEFF").replace("\r\n", "\n")).mapNotNull { match ->
            fun stamp(i: Int): Long = ((match.groupValues[i].toLong() * 3600 + match.groupValues[i + 1].toLong() * 60 + match.groupValues[i + 2].toLong()) * 1000 + match.groupValues[i + 3].toLong()) * 1000
            val start = stamp(1); val end = stamp(5)
            val text = match.groupValues[9].replace(Regex("<[^>]*>"), "").trim()
            if (end > start && text.isNotBlank()) SubtitleCue(start, end, text) else null
        }.sortedBy { it.startUs }.toList()
    }
    fun current(cues: List<SubtitleCue>, positionUs: Long): String = cues.asSequence().takeWhile { it.startUs <= positionUs }.filter { positionUs < it.endUs }.map { it.text }.distinct().joinToString("\n")
}

/** A blank sample closes a cue. Repeated text after a blank is a separate occurrence. */
class SubtitleStabilizer {
    private val result = mutableListOf<SubtitleCue>()
    private var text = ""
    private var start = 0L
    private var last = 0L
    private var count = 0
    fun accept(timeUs: Long, recognized: String) {
        val normalized = recognized.trim().replace(Regex("[ \\t]+"), " ")
        if (normalized == text) { count++; last = timeUs; return }
        flush(timeUs)
        text = normalized; start = timeUs; last = timeUs; count = 1
    }
    private fun flush(endUs: Long) {
        if (text.isNotBlank() && count >= 2 && endUs > start) result += SubtitleCue(start, endUs, text)
    }
    fun finish(durationUs: Long): List<SubtitleCue> { flush(minOf(durationUs, last + 250000)); text = ""; return result.toList() }
}

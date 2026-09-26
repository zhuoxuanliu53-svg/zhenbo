@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.jingting.localplayer.subtitle

import android.content.Context
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.*
import androidx.media3.extractor.*
import androidx.media3.extractor.text.CueDecoder
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** Media3 performs container-specific subtitle parsing, including ASS codec initialization. */
class EmbeddedSubtitles(private val context: Context) {
    suspend fun inspect(uri: String): List<SubtitleTrack> = read(uri, null, 0).first
    suspend fun extract(uri: String, trackId: Int, durationUs: Long): List<SubtitleCue> = read(uri, trackId, durationUs).second
    private suspend fun read(locator: String, selected: Int?, durationUs: Long): Pair<List<SubtitleTrack>, List<SubtitleCue>> = withContext(Dispatchers.IO) {
        val tracks = linkedMapOf<Int, SubtitleTrack>()
        val raw = mutableListOf<SubtitleCue>()
        var endedTracks = false
        val uri = Uri.parse(locator)
        val source = DefaultDataSource.Factory(context).createDataSource()
        fun input(position: Long): DefaultExtractorInput {
            source.close()
            val length = source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
            return DefaultExtractorInput(source, position, if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + length)
        }
        var stream = input(0)
        val candidates = DefaultExtractorsFactory().setTextTrackTranscodingEnabled(true).createExtractors(uri, emptyMap())
        val extractor = try { candidates.firstOrNull { candidate ->
            try { candidate.sniff(stream) } catch (_: java.io.EOFException) { false } finally { stream.resetPeekPosition() }
        } ?: error("暂不支持此视频容器的字幕") } catch (e: Exception) { source.close(); throw e }
        extractor.init(object : ExtractorOutput {
            override fun endTracks() { endedTracks = true }
            override fun seekMap(map: SeekMap) {}
            override fun track(id: Int, type: Int): TrackOutput {
                if (type != C.TRACK_TYPE_TEXT) return DiscardingTrackOutput()
                return object : TrackOutput {
                    private var data = ByteArrayOutputStream()
                    private var decoded = false
                    override fun format(format: Format) {
                        val mime = format.codecs ?: format.sampleMimeType.orEmpty()
                        decoded = format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES
                        val supported = decoded && mime in setOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.APPLICATION_TX3G, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_MP4VTT)
                        tracks[id] = SubtitleTrack(id, mime, format.language, format.label, supported)
                    }
                    override fun sampleData(reader: DataReader, length: Int, allowEndOfInput: Boolean, part: Int): Int {
                        val bytes = ByteArray(minOf(length, 65536)); val count = reader.read(bytes, 0, bytes.size)
                        if (count == -1 && !allowEndOfInput) throw java.io.EOFException()
                        if (count > 0 && selected == id) { check(data.size() + count <= 4 * 1024 * 1024) { "字幕数据过大" }; data.write(bytes, 0, count) }
                        return count
                    }
                    override fun sampleData(bytes: ParsableByteArray, length: Int, part: Int) {
                        if (selected != id) { bytes.skipBytes(length); return }
                        check(data.size() + length <= 4 * 1024 * 1024) { "字幕数据过大" }
                        val buffer = ByteArray(length); bytes.readBytes(buffer, 0, length); data.write(buffer)
                    }
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
                        if (selected != id) return
                        val bytes = data.toByteArray(); val start = bytes.size - offset - size
                        check(start >= 0 && crypto == null) { "字幕数据无法解析" }
                        if (decoded && tracks[id]?.supported == true) {
                            val cues = CueDecoder().decode(timeUs, bytes, start, size)
                            val text = cues.cues.mapNotNull { it.text?.toString() }.joinToString("\n").trim()
                            if (raw.lastOrNull()?.endUs == Long.MAX_VALUE) raw[raw.lastIndex] = raw.last().copy(endUs = timeUs)
                            if (text.isNotBlank()) raw += SubtitleCue(cues.startTimeUs, if (cues.durationUs == C.TIME_UNSET) Long.MAX_VALUE else cues.endTimeUs, text)
                            check(raw.size <= 200000) { "字幕条目过多" }
                        }
                        data = ByteArrayOutputStream().apply { if (offset > 0) write(bytes, bytes.size - offset, offset) }
                    }
                }
            }
        })
        try {
            val seek = PositionHolder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val result = extractor.read(stream, seek)
                if (selected == null && endedTracks) break
                if (result == Extractor.RESULT_END_OF_INPUT) break
                if (result == Extractor.RESULT_SEEK) stream = input(seek.position)
            }
            if (selected != null) require(tracks[selected]?.supported == true) { "此字幕轨暂不支持，请选择文本字幕" }
            tracks.values.toList() to raw.map { it.copy(startUs = it.startUs.coerceAtLeast(0), endUs = minOf(it.endUs, durationUs)) }.filter { it.endUs > it.startUs }.sortedBy { it.startUs }
        } finally { extractor.release(); source.close() }
    }
}

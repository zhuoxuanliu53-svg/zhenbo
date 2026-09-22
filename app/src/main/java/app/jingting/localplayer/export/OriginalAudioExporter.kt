package app.jingting.localplayer.export

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import app.jingting.localplayer.core.MediaRef
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decisions are made from the audio track, independently of the source's video container. */
data class AudioExportPlan(
    val strategy: String,
    val extension: String,
    val mimeType: String,
    val estimatedBytes: Long,
    val tracks: List<AudioTrack>,
) {
    val description: String get() = when (strategy) {
        "COPY_AAC" -> "M4A · 原音轨快速复制"
        "PCM_WAV" -> "WAV · 保留解码音质，文件较大"
        else -> "M4A · AAC 256 kbps"
    }
}

data class AudioTrack(val source: MediaRef, val index: Int, val format: MediaFormat, val wave: WavePcmSource? = null) {
    val rate: Int get() = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    val channels: Int get() = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val durationUs: Long get() = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else source.durationMs * 1000
    val mime: String get() = format.getString(MediaFormat.KEY_MIME)!!
}

object OriginalAudioExporter {
    private const val AAC = "audio/mp4a-latm"
    suspend fun inspect(context: Context, sources: List<MediaRef>, mode: String): AudioExportPlan {
        require(sources.isNotEmpty()) { "请先选择文件" }
        require(mode in setOf("ORIGINAL", "AAC")) { "未知导出格式" }
        val tracks = sources.map { source ->
            currentCoroutineContext().ensureActive()
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, Uri.parse(source.locator), null)
                val index = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("所选文件没有音轨")
                val format = extractor.getTrackFormat(index)
                val wave = if (mode == "ORIGINAL" && format.getString(MediaFormat.KEY_MIME) == "audio/raw") WavePcmSource.inspect(context, source.locator) else null
                val track = AudioTrack(source, index, format, wave)
                require(track.channels in 1..2) { "暂不支持多声道导出，请选择单声道或双声道文件" }
                require(track.rate > 0 && track.durationUs > 0) { "无法读取音轨时长或采样率" }
                require(extractor.drmInitData == null) { "加密音轨不能导出" }
                track
            } finally { extractor.release() }
        }
        if (mode == "AAC") return AudioExportPlan("ENCODE_AAC", "m4a", "audio/mp4", tracks.sumOf { it.durationUs } / 1_000_000 * 32_000 + 1_048_576, tracks)
        if (tracks.all { it.mime == AAC } && tracks.all { compatibleAac(tracks.first().format, it.format) }) {
            val estimate = tracks.sumOf { it.durationUs / 1_000_000 * (integer(it.format, MediaFormat.KEY_BIT_RATE, 512_000) / 8).coerceAtLeast(32_000) } + 1_048_576
            return AudioExportPlan("COPY_AAC", "m4a", "audio/mp4", estimate, tracks)
        }
        require(tracks.map { it.rate }.distinct().size == 1) { "采样率不同，无法原样拼接；请选择兼容 AAC" }
        // 32-bit integer PCM cannot be represented exactly by float32. Never silently reduce it.
        require(tracks.none { it.wave?.encoding == AudioFormat.ENCODING_PCM_32BIT }) { "此音轨无法保证原始位深；可选择兼容 AAC" }
        for (track in tracks.filter { it.mime == "audio/raw" && it.wave == null }) {
            val bits = sourceBits(track.format)
            val encoding = integer(track.format, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            require(bits != null && (bits <= 24 || encoding == AudioFormat.ENCODING_PCM_FLOAT)) { "无法确认或保留原始位深；可选择兼容 AAC" }
            require(pcmPrecision(encoding) >= minOf(bits, 24)) { "系统提取器降低了位深；可选择兼容 AAC" }
        }
        for (track in tracks.filter { it.mime != "audio/raw" }) {
            val bits = sourceBits(track.format)
            val encoding = integer(track.format, MediaFormat.KEY_PCM_ENCODING, -1)
            require(bits == null || bits <= 24 || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "此音轨无法保证原始位深；可选择兼容 AAC" }
            if (track.mime in setOf("audio/flac", "audio/x-flac", "audio/alac", "audio/x-alac", "audio/true-hd", "audio/vnd.dolby.mlp", "audio/vnd.dts.hd", "audio/vnd.dts.uhd")) {
                require(bits != null) { "无法确认无损音轨位深；可选择兼容 AAC" }
            }
        }
        val channels = tracks.maxOf { it.channels }
        val estimate = tracks.sumOf { (it.durationUs + 999_999) / 1_000_000 * it.rate * channels * 4L } + 80
        require(estimate < 0xfffffff0L) { "原音质 WAV 将超过 4 GB，请减少文件或选择兼容 AAC" }
        return AudioExportPlan("PCM_WAV", "wav", "audio/wav", estimate, tracks)
    }

    internal fun compatibleAac(a: MediaFormat, b: MediaFormat): Boolean {
        if (a.getString(MediaFormat.KEY_MIME) != AAC || b.getString(MediaFormat.KEY_MIME) != AAC) return false
        for (key in listOf(MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT, MediaFormat.KEY_AAC_PROFILE)) {
            if (integer(a, key, -1) != integer(b, key, -1)) return false
        }
        // CSD encodes the AAC object type, sample rate and channel layout. Missing CSD is not proof of compatibility.
        if (!a.containsKey("csd-0") || !b.containsKey("csd-0")) return false
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            val left = bytes(a, key); val right = bytes(b, key)
            if (left == null && right == null) continue
            if (left == null || right == null || !left.contentEquals(right)) return false
        }
        return true
    }

    suspend fun write(context: Context, plan: AudioExportPlan, output: File, progress: suspend (Int) -> Unit) {
        when (plan.strategy) {
            "COPY_AAC" -> copyAac(context, plan, output, progress)
            "PCM_WAV" -> writePcm(context, plan, output, progress)
            else -> error("此模式需要 AAC 编码器")
        }
    }

    private suspend fun copyAac(context: Context, plan: AudioExportPlan, output: File, progress: suspend (Int) -> Unit) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val first = plan.tracks.first()
            val trackIndex = muxer.addTrack(first.format)
            muxer.start(); started = true
            var offsetUs = 0L
            val totalUs = plan.tracks.sumOf { it.durationUs }.coerceAtLeast(1)
            var reported = -1
            var buffer = ByteBuffer.allocateDirect(256 * 1024)
            val info = MediaCodec.BufferInfo()
            for (track in plan.tracks) {
                currentCoroutineContext().ensureActive()
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, Uri.parse(track.source.locator), null)
                    extractor.selectTrack(track.index)
                    require(extractor.sampleTrackIndex >= 0) { "音轨没有可读取的数据" }
                    val firstPts = extractor.sampleTime
                    var lastPts = -1L
                    var frameDurationUs = 1024L * 1_000_000 / track.rate
                    while (extractor.sampleTrackIndex >= 0) {
                        currentCoroutineContext().ensureActive()
                        val flags = extractor.sampleFlags
                        require(flags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) == 0) { "此音轨不能直接复制，请选择兼容 AAC" }
                        val size = extractor.sampleSize
                        require(size in 1..(16 * 1024 * 1024).toLong()) { "音轨数据损坏" }
                        if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                        buffer.clear()
                        val read = extractor.readSampleData(buffer, 0)
                        check(read == size.toInt()) { "音轨数据读取不完整" }
                        val localPts = extractor.sampleTime - firstPts
                        require(localPts > lastPts) { "音轨时间顺序异常，请选择兼容 AAC" }
                        if (lastPts >= 0) frameDurationUs = localPts - lastPts
                        info.set(0, read, offsetUs + localPts, if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(trackIndex, buffer, info)
                        lastPts = localPts
                        val percent = ((offsetUs + localPts) * 98 / totalUs).toInt().coerceIn(0, 98)
                        if (percent != reported) { reported = percent; progress(percent) }
                        extractor.advance()
                    }
                    // Keep every compressed access unit; do not truncate priming/padding at joins.
                    offsetUs += lastPts + frameDurationUs
                } finally { extractor.release() }
            }
            info.set(0, 0, offsetUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            muxer.writeSampleData(trackIndex, ByteBuffer.allocate(0), info)
            muxer.stop(); started = false
        } finally {
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private suspend fun writePcm(context: Context, plan: AudioExportPlan, output: File, progress: suspend (Int) -> Unit) {
        val rate = plan.tracks.first().rate
        val channels = plan.tracks.maxOf { it.channels }
        val totalUs = plan.tracks.sumOf { it.durationUs }.coerceAtLeast(1)
        var elapsedUs = 0L
        var reported = -1
        FloatWaveWriter(output, rate, channels).use { writer ->
            for (track in plan.tracks) {
                val baseUs = elapsedUs
                decode(context, track) { bytes, encoding, outputChannels, outputRate, pts ->
                    check(outputRate == rate && outputChannels == track.channels) { "解码器改变了音轨参数，未执行原音质导出" }
                    writer.append(bytes, encoding, outputChannels)
                    val percent = ((baseUs + pts.coerceAtLeast(0)) * 98 / totalUs).toInt().coerceIn(0, 98)
                    if (percent != reported) { reported = percent; progress(percent) }
                }
                elapsedUs += track.durationUs
            }
        }
    }

    private suspend fun decode(context: Context, track: AudioTrack, consume: suspend (ByteBuffer, Int, Int, Int, Long) -> Unit) {
        // Android's native WAV extractor converts 24-bit / float samples to 16-bit for apps.
        // Read the RIFF PCM payload ourselves so the original samples never pass through that conversion.
        track.wave?.let { wave ->
            context.contentResolver.openInputStream(Uri.parse(track.source.locator))!!.use { stream ->
                stream.skipExactly(wave.dataOffset)
                val alignment = wave.channels * wave.bits / 8
                val bytes = ByteArray(64 * 1024 / alignment * alignment)
                var remaining = wave.dataSize
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(remaining, bytes.size.toLong()).toInt()
                    stream.readExactly(bytes, count)
                    consume(ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN), wave.encoding, wave.channels, wave.rate,
                        (wave.dataSize - remaining) / alignment * 1_000_000 / wave.rate)
                    remaining -= count
                }
            }
            return
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(track.source.locator), null)
            extractor.selectTrack(track.index)
            if (track.mime == "audio/raw") {
                val encoding = integer(track.format, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                var buffer = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                while (extractor.sampleTrackIndex >= 0) {
                    currentCoroutineContext().ensureActive()
                    val size = extractor.sampleSize.toInt()
                    require(size > 0 && size <= 16 * 1024 * 1024) { "音轨数据损坏" }
                    if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.clear(); check(extractor.readSampleData(buffer, 0) == size)
                    buffer.position(0); buffer.limit(size)
                    consume(buffer, encoding, track.channels, track.rate, extractor.sampleTime)
                    extractor.advance()
                }
                return
            }
            val format = extractor.getTrackFormat(track.index)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            codec = MediaCodec.createDecoderByType(track.mime)
            codec.configure(format, null, null, 0); codec.start()
            var inputEnded = false
            var outputEnded = false
            var outputFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            var lastActivity = android.os.SystemClock.elapsedRealtime()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                check(android.os.SystemClock.elapsedRealtime() - lastActivity < 30_000) { "设备解码器无响应" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else {
                            require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "加密音轨不能导出" }
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance()
                        }
                        lastActivity = android.os.SystemClock.elapsedRealtime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val actual = outputFormat ?: codec.outputFormat
                                val encoding = integer(actual, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                                // Preserve every native decoder sample. Some Android AAC/MP3
                                // decoders natively return 16-bit even when float is requested.
                                // For a lossless source, its known precision must survive decoding.
                                val requiredBits = sourceBits(track.format)
                                val actualBits = pcmPrecision(encoding)
                                require(requiredBits == null || actualBits >= minOf(requiredBits, 24)) { "设备解码器降低了位深，未执行原音质导出" }
                                require(integer(track.format, MediaFormat.KEY_PCM_ENCODING, -1) != AudioFormat.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "设备解码器改变了浮点精度，未执行原音质导出" }
                                val buffer = codec.getOutputBuffer(index)!!
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                consume(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), encoding, actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT), actual.getInteger(MediaFormat.KEY_SAMPLE_RATE), info.presentationTimeUs)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastActivity = android.os.SystemClock.elapsedRealtime()
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
        } finally { codec?.let { runCatching { it.stop() }; it.release() }; extractor.release() }
    }

    private fun integer(format: MediaFormat, key: String, fallback: Int) = if (format.containsKey(key)) format.getInteger(key) else fallback
    private fun pcmPrecision(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT -> 32
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_8BIT -> 8
        else -> 0
    }
    private fun sourceBits(format: MediaFormat): Int? {
        for (key in listOf("bits-per-sample", "bit-depth")) if (format.containsKey(key)) return format.getInteger(key)
        return when (integer(format, MediaFormat.KEY_PCM_ENCODING, -1)) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
    }
    private fun bytes(format: MediaFormat, key: String): ByteArray? = if (format.containsKey(key)) format.getByteBuffer(key)?.duplicate()?.let { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } } else null
}

data class WavePcmSource(val dataOffset: Long, val dataSize: Long, val channels: Int, val rate: Int, val bits: Int, val encoding: Int) {
    companion object {
        suspend fun inspect(context: Context, locator: String): WavePcmSource? = context.contentResolver.openInputStream(Uri.parse(locator))?.use { stream ->
            val header = ByteArray(12)
            stream.readExactly(header, 12)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return@use null
            var position = 12L
            var fmt: ByteArray? = null
            repeat(1024) {
                currentCoroutineContext().ensureActive()
                val chunk = ByteArray(8); stream.readExactly(chunk, 8); position += 8
                val size = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong() and 0xffffffffL
                when (String(chunk, 0, 4, Charsets.US_ASCII)) {
                    "fmt " -> {
                        require(size in 16..1024) { "WAV 格式信息损坏" }
                        fmt = ByteArray(size.toInt()).also { stream.readExactly(it, it.size) }
                    }
                    "data" -> {
                        val bytes = fmt ?: error("WAV 缺少格式信息")
                        val info = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        var code = info.getShort(0).toInt() and 65535
                        val channels = info.getShort(2).toInt() and 65535
                        val rate = info.getInt(4); val bits = info.getShort(14).toInt() and 65535
                        if (code == 65534) {
                            require(bytes.size >= 40) { "WAV 扩展格式信息损坏" }
                            val validBits = info.getShort(18).toInt() and 65535
                            require(validBits == bits || validBits == 0) { "此 WAV 有效位深暂不支持" }
                            code = info.getShort(24).toInt() and 65535
                        }
                        val encoding = when {
                            code == 3 && bits == 32 -> AudioFormat.ENCODING_PCM_FLOAT
                            code == 1 && bits == 8 -> AudioFormat.ENCODING_PCM_8BIT
                            code == 1 && bits == 16 -> AudioFormat.ENCODING_PCM_16BIT
                            code == 1 && bits == 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                            code == 1 && bits == 32 -> AudioFormat.ENCODING_PCM_32BIT
                            else -> return@use null
                        }
                        require(channels in 1..2 && rate > 0 && size % (channels * bits / 8) == 0L) { "WAV 音轨参数不完整" }
                        return@use WavePcmSource(position, size, channels, rate, bits, encoding)
                    }
                    else -> stream.skipExactly(size)
                }
                if (size and 1L != 0L) stream.skipExactly(1)
                position += size + (size and 1L)
            }
            error("WAV 未找到音频数据")
        }
    }
}

private fun InputStream.readExactly(bytes: ByteArray, length: Int) {
    var position = 0
    while (position < length) {
        val read = read(bytes, position, length - position)
        check(read > 0) { "音轨数据读取不完整" }
        position += read
    }
}
private fun InputStream.skipExactly(length: Long) {
    var remaining = length
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) remaining -= skipped else { check(read() >= 0) { "音轨数据读取不完整" }; remaining-- }
    }
}

/** IEEE float WAV keeps all 16/24-bit integer PCM samples exactly, without dithering or a second lossy codec. */
private class FloatWaveWriter(file: File, private val rate: Int, private val channels: Int) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    private var size = 0L
    init { output.setLength(0); output.write(ByteArray(56)) }
    fun append(data: ByteBuffer, encoding: Int, inputChannels: Int) {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            else -> error("此 PCM 位深暂不支持原音质导出")
        }
        require(data.remaining() % (bytesPerSample * inputChannels) == 0) { "PCM 音轨数据不完整" }
        val frames = data.remaining() / (bytesPerSample * inputChannels)
        val converted = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        fun sample(): Float = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> ((data.get().toInt() and 255) - 128) / 128f
            AudioFormat.ENCODING_PCM_16BIT -> data.short / 32768f
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val value = (data.get().toInt() and 255) or ((data.get().toInt() and 255) shl 8) or (data.get().toInt() shl 16)
                value / 8388608f
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val value = data.int
                val sample = value / 2147483648f
                require(sample.toDouble() * 2147483648.0 == value.toDouble()) { "解码 PCM 超出可精确保存的位深" }
                sample
            }
            else -> data.float
        }
        repeat(frames) {
            val left = sample(); converted.putFloat(left)
            if (channels == 2) converted.putFloat(if (inputChannels == 2) sample() else left)
        }
        size += converted.position()
        require(size + 48 <= 0xffffffffL) { "WAV 超过 4 GB，请分批导出" }
        output.write(converted.array())
    }
    override fun close() {
        try {
            val header = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt((size + 48).toInt()).put("WAVEfmt ".toByteArray())
            header.putInt(16).putShort(3).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 4).putShort((channels * 4).toShort()).putShort(32)
            header.put("fact".toByteArray()).putInt(4).putInt((size / (channels * 4)).toInt())
            header.put("data".toByteArray()).putInt(size.toInt())
            output.seek(0); output.write(header.array())
        } finally { output.close() }
    }
}

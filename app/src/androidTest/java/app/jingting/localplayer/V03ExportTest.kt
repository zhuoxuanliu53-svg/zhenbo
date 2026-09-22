package app.jingting.localplayer

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.data.*
import app.jingting.localplayer.export.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class V03ExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.graph
    private lateinit var scenario: ActivityScenario<MainActivity>
    @Before fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java); Thread.sleep(500) }
    @After fun close() { scenario.close() }
    private suspend fun fixture(name: String, ms: Long, video: Boolean = false): MediaEntity {
        val file = File(context.filesDir, "v03-$name")
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return MediaEntity("v03:$name", Uri.fromFile(file).toString(), "V03 $name", ms, video, source = "test").also { graph.dao.putMedia(listOf(it)) }
    }
    private suspend fun completed(id: String): ExportEntity = withTimeout(180_000) {
        while (true) {
            val item = graph.dao.export(id)!!
            if (item.state in setOf("completed", "failed", "cancelled")) return@withTimeout item
            delay(50)
        }
        error("unreachable")
    }
    private suspend fun export(sources: List<MediaEntity>, mode: String = "ORIGINAL"): ExportEntity {
        val id = withContext(Dispatchers.Main) { graph.exports.enqueue(sources.map { it.id }, "v03-quality", sources.size > 1, mode) }
        return completed(id).also { assertEquals(it.error, "completed", it.state) }
    }
    private fun packets(locator: String): List<String> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(locator), null)
            val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(index)
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val result = mutableListOf<String>()
            var previous = Long.MIN_VALUE
            while (extractor.sampleTrackIndex >= 0) {
                assertTrue("monotonic audio samples", extractor.sampleTime > previous); previous = extractor.sampleTime
                buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                val bytes = ByteArray(size); buffer.position(0); buffer.get(bytes)
                result += MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                extractor.advance()
            }
            return result
        } finally { extractor.release() }
    }
    private fun format(locator: String): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(locator), null); assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertTrue("non-empty output duration", format.getLong(MediaFormat.KEY_DURATION) > 0)
            assertTrue("valid output sample rate", format.getInteger(MediaFormat.KEY_SAMPLE_RATE) > 0)
            assertTrue("mono or stereo output", format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) in 1..2)
            extractor.selectTrack(0)
            assertTrue("readable output sample", extractor.sampleSize > 0)
            return format
        }
        finally { extractor.release() }
    }
    @Test fun videoExtractionCopiesEveryAacPacketExactly() = runBlocking {
        val source = fixture("video.mp4", 10000, true)
        val output = export(listOf(source))
        assertEquals("COPY_AAC", output.strategy)
        assertEquals(packets(source.uri), packets(output.outputUri!!))
        assertEquals("audio/mp4a-latm", format(output.outputUri).getString(MediaFormat.KEY_MIME))
        withTimeout(5_000) { while (graph.dao.mediaByIds(listOf(output.outputUri)).isEmpty()) delay(20) }
        assertFalse(graph.dao.mediaByIds(listOf(output.outputUri)).single().isVideo)
    }
    @Test fun selectedVideoVideoAndAudioOrderIsPreservedWithoutEncoding() = runBlocking {
        val first = fixture("video.mp4", 10000, true)
        val second = fixture("video-second.mp4", 4000, true)
        val audio = fixture("audio-second.m4a", 4000)
        val sources = listOf(second, first, audio)
        val preview = graph.exports.preview(sources.map { it.id })
        assertTrue(preview.originalAvailable)
        val output = export(sources)
        assertEquals("COPY_AAC", output.strategy)
        assertEquals(sources.flatMap { packets(it.uri) }, packets(output.outputUri!!))
        assertTrue(format(output.outputUri).getLong(MediaFormat.KEY_DURATION) in 17_900_000L..18_300_000L)
    }
    @Test fun pcm24BitAndMonoArePreservedSampleForSample() = runBlocking {
        val stereo = fixture("exact24.wav", 100)
        val mono = fixture("mono48.wav", 100)
        val output = export(listOf(stereo, mono))
        assertEquals("PCM_WAV", output.strategy)
        val bytes = context.contentResolver.openInputStream(Uri.parse(output.outputUri))!!.use { it.readBytes() }
        assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WAVE", String(bytes, 8, 4))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3, buffer.getShort(20).toInt()); assertEquals(2, buffer.getShort(22).toInt()); assertEquals(48000, buffer.getInt(24))
        assertEquals(4800 * 2 * 2 * 4, buffer.getInt(52))
        buffer.position(56)
        repeat(4800) { i ->
            assertEquals(((i * 7919 % 16777216) - 8388608) / 8388608f, buffer.float, 0f)
            assertEquals((8388607 - (i * 3571 % 16777216)) / 8388608f, buffer.float, 0f)
        }
        repeat(4800) { i -> val sample = ((i * 37 % 65536) - 32768) / 32768f; assertEquals(sample, buffer.float, 0f); assertEquals(sample, buffer.float, 0f) }
        // Reinterpret an existing synthetic PCM payload as integer32 to exercise the
        // preflight precision boundary without another large binary fixture.
        val integer32 = instrumentation.context.assets.open("exact24.wav").use { it.readBytes() }
        ByteBuffer.wrap(integer32).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(28, 48000 * 2 * 4); putShort(32, 8); putShort(34, 32) }
        val file32 = File(context.filesDir, "v03-integer32.wav").apply { writeBytes(integer32) }
        val media32 = MediaEntity("v03:integer32", Uri.fromFile(file32).toString(), "V03 integer32", 75, false, source = "test")
        graph.dao.putMedia(listOf(media32))
        val preview32 = graph.exports.preview(listOf(media32.id))
        assertFalse("integer32 may not be silently quantized into float32", preview32.originalAvailable)
        assertTrue(preview32.reason!!.contains("位深"))
    }
    @Test fun differentRatesRequireExplicitEncodingAndMixedVideoStillExports() = runBlocking {
        val video = fixture("video.mp4", 10000, true)
        val mono = fixture("mono44.wav", 2000)
        val preview = graph.exports.preview(listOf(video.id, mono.id))
        assertFalse(preview.originalAvailable); assertTrue(preview.reason!!.contains("采样率"))
        val rejectedId = withContext(Dispatchers.Main) { graph.exports.enqueue(listOf(video.id, mono.id), "v03-no-silent-loss", true, "ORIGINAL") }
        val rejected = completed(rejectedId)
        assertEquals("failed", rejected.state); assertNull(rejected.outputUri)
        assertFalse(graph.exports.taskDir(rejected.id).exists())
        val output = export(listOf(video, mono), "AAC")
        assertEquals("ENCODE_AAC", output.strategy)
        assertEquals(48000, format(output.outputUri!!).getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertTrue(format(output.outputUri).getLong(MediaFormat.KEY_DURATION) in 11_800_000..12_300_000)
    }
    @Test fun sameRateDifferentCodecsUsePcmWithoutAnotherLossyCodec() = runBlocking {
        val video = fixture("video-second.mp4", 4000, true)
        val pcm = fixture("mono48.wav", 100)
        val result = export(listOf(video, pcm))
        assertEquals("PCM_WAV", result.strategy)
        val audio = format(result.outputUri!!)
        assertEquals("audio/raw", audio.getString(MediaFormat.KEY_MIME))
        assertEquals(2, audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        assertTrue(audio.getLong(MediaFormat.KEY_DURATION) in 4_000_000..4_300_000)
    }
    @Test fun cancellingPacketCopyKeepsSourceAndCleansUnpublishedOutput() = runBlocking {
        val source = fixture("video.mp4", 10000, true)
        val before = packets(source.uri)
        val id = withContext(Dispatchers.Main) { graph.exports.enqueue(List(100) { source.id }, "v03-copy-cancel", true, "ORIGINAL") }
        withTimeout(15_000) { while (graph.dao.export(id)?.strategy != "COPY_AAC") delay(5) }
        graph.exports.cancel(id)
        val result = completed(id)
        assertEquals("cancelled", result.state); assertNull(result.outputUri)
        withTimeout(5_000) { while (graph.exports.taskDir(id).exists()) delay(20) }
        assertEquals(before, packets(source.uri))
    }
    @Test fun benchmarkPacketCopyAgainstPreviousEncodingPath() = runBlocking {
        val source = fixture("video.mp4", 10000, true)
        val sources = List(12) { source }
        val copy = export(sources)
        val encoded = export(sources, "AAC")
        val expected = sources.flatMap { packets(it.uri) }
        assertEquals(expected, packets(copy.outputUri!!))
        val report = JSONObject().put("durationSeconds", 120).put("copyMs", copy.elapsedMs).put("encodeMs", encoded.elapsedMs)
            .put("speedup", encoded.elapsedMs.toDouble() / copy.elapsedMs.coerceAtLeast(1)).put("packetCount", expected.size)
            .put("packetPayloadIdentical", true).put("copyOutputUri", copy.outputUri).put("encodedOutputUri", encoded.outputUri)
            .put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
        File(context.filesDir, "v03-export-benchmark.json").writeText(report.toString(2))
    }
}

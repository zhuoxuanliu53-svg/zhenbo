package app.jingting.localplayer.subtitle

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import kotlinx.coroutines.*

object VideoFrames {
    suspend fun preview(context: Context, uri: String, positionUs: Long): Bitmap = withContext(Dispatchers.IO) {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, Uri.parse(uri))
            retriever.getScaledFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST, 1280, 720) ?: error("无法读取视频画面")
        }
    }
    fun crop(bitmap: Bitmap, region: SubtitleRegion): Bitmap {
        val x = (region.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (region.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val w = ((region.right * bitmap.width).toInt() - x).coerceIn(1, bitmap.width - x)
        val h = ((region.bottom * bitmap.height).toInt() - y).coerceIn(1, bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }
    suspend fun scan(context: Context, uri: String, durationUs: Long, region: SubtitleRegion, progress: suspend (Int) -> Unit): List<SubtitleCue> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor(); var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: error("没有视频画面")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var inputEnded = false; var ended = false; var nextUs = 0L; var lastProgress = -1
            val info = MediaCodec.BufferInfo(); val stabilizer = SubtitleStabilizer()
            OfflineOcr(context).use { ocr ->
                while (!ended) {
                    currentCoroutineContext().ensureActive()
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index)!!; val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                            else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10000)
                    if (index >= 0) {
                        try {
                            if (info.size > 0 && info.presentationTimeUs >= nextUs) {
                                val image = decoder.getOutputImage(index) ?: error("此设备无法读取视频画面用于识别")
                                val frame = image.use { toBitmap(it) }
                                val rotated = if (rotation != 0) Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, true) else frame
                                val crop = crop(rotated, region)
                                try { stabilizer.accept(info.presentationTimeUs, ocr.recognize(crop)) } finally { if (crop !== rotated) crop.recycle(); if (rotated !== frame) rotated.recycle(); frame.recycle() }
                                nextUs = (info.presentationTimeUs / 250000 + 1) * 250000
                                val percent = (info.presentationTimeUs * 100 / durationUs.coerceAtLeast(1)).toInt().coerceIn(0, 99)
                                if (percent != lastProgress) { lastProgress = percent; progress(percent) }
                            }
                            ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            progress(100); stabilizer.finish(durationUs)
        } finally { codec?.let { runCatching { it.stop() }; it.release() }; extractor.release() }
    }
    private fun toBitmap(image: Image): Bitmap {
        val rect = image.cropRect
        // Decode buffers can be 4K, but OCR must not allocate multiple full-resolution
        // ARGB copies in the application heap. Sample YUV directly into a bounded image.
        val scale = (maxOf(rect.width(), rect.height()) / 1600f).coerceAtLeast(1f)
        val w = (rect.width() / scale).toInt().coerceAtLeast(1); val h = (rect.height() / scale).toInt().coerceAtLeast(1)
        val pixels = IntArray(w * h)
        val planes = image.planes
        fun value(p: Int, x: Int, y: Int): Int = planes[p].buffer.get(y * planes[p].rowStride + x * planes[p].pixelStride).toInt() and 255
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x * rect.width() / w + rect.left; val sy = y * rect.height() / h + rect.top
            val yy = (value(0, sx, sy) - 16).coerceAtLeast(0); val u = value(1, sx / 2, sy / 2) - 128; val v = value(2, sx / 2, sy / 2) - 128
            val r = ((298 * yy + 409 * v + 128) shr 8).coerceIn(0,255)
            val g = ((298 * yy - 100 * u - 208 * v + 128) shr 8).coerceIn(0,255)
            val b = ((298 * yy + 516 * u + 128) shr 8).coerceIn(0,255)
            pixels[y * w + x] = Color.rgb(r,g,b)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}

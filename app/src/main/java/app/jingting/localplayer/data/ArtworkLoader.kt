package app.jingting.localplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Local, bounded artwork decoding shared by library, collections and player. */
object ArtworkLoader {
    private const val EDGE = 512
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(2)
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val missing = LruCache<String, Long>(256)

    suspend fun load(context: Context, uri: String, isVideo: Boolean): Bitmap? {
        val parsed = Uri.parse(uri)
        if (parsed.scheme !in setOf("content", "file")) return null
        val key = "$isVideo:$uri"
        cache.get(key)?.let { return it }
        if (recentlyMissing(key)) return null
        return withContext(decodeDispatcher) {
            currentCoroutineContext().ensureActive()
            // Check again after waiting for a decoding slot.
            cache.get(key)?.let { return@withContext it }
            if (recentlyMissing(key)) return@withContext null
            val bitmap = try {
                if (isVideo) video(context.applicationContext, parsed) else audio(context.applicationContext, parsed)
            } catch (_: Exception) {
                // Deleted files, revoked grants and malformed media use the placeholder.
                null
            }
            currentCoroutineContext().ensureActive()
            if (bitmap != null) cache.put(key, bitmap)
            else missing.put(key, SystemClock.elapsedRealtime())
            bitmap
        }
    }

    fun clear() {
        cache.evictAll()
        missing.evictAll()
    }

    private fun recentlyMissing(key: String): Boolean =
        missing.get(key)?.let { SystemClock.elapsedRealtime() - it < 30_000L } ?: false

    private fun video(context: Context, uri: Uri): Bitmap? {
        val thumbnail = if (uri.scheme == "content") {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(EDGE, EDGE), null) }
                .getOrNull()?.let(::bounded)
        } else null
        if (thumbnail != null && !mostlyDark(thumbnail)) return thumbnail

        val retriever = MediaMetadataRetriever()
        var firstFrame: Bitmap? = thumbnail
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: EDGE
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: EDGE
            val ratio = minOf(1f, EDGE.toFloat() / maxOf(width, height))
            val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
            val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
            val times = listOf(minOf(1000L, durationMs / 2), durationMs / 2, 0L).distinct()
            for (timeMs in times) {
                val frame = runCatching {
                    retriever.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                        targetWidth, targetHeight)
                }.getOrNull()?.let(::bounded) ?: continue
                if (firstFrame == null) firstFrame = frame
                if (!mostlyDark(frame)) return frame
            }
            return firstFrame
        } catch (_: Exception) {
            return firstFrame
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun audio(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > EDGE * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let(::bounded)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun bounded(source: Bitmap): Bitmap {
        val ratio = minOf(1f, EDGE.toFloat() / maxOf(source.width, source.height))
        return if (ratio == 1f) source else Bitmap.createScaledBitmap(source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun mostlyDark(source: Bitmap): Boolean {
        // Sampling a small grid avoids retaining a second bitmap or scanning every pixel.
        if (source.config == Bitmap.Config.HARDWARE) return false
        var visible = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val pixel = source.getPixel(x * source.width / 8, y * source.height / 8)
            if (maxOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255) > 24) visible++
        }
        return visible < 4
    }
}

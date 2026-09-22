package app.jingting.localplayer

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.data.ArtworkLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArtworkIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val inserted = mutableListOf<Uri>()
    private val files = mutableListOf<File>()

    @After fun cleanUp() {
        ArtworkLoader.clear()
        inserted.forEach { resolver.delete(it, null, null) }
        files.forEach { it.delete() }
    }

    @Test fun videoContentUriProducesBoundedArtworkAndUsesCache() = runBlocking {
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "artwork-test-${System.nanoTime()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/静听测试/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
        inserted += uri
        resolver.openOutputStream(uri)!!.use { output ->
            instrumentation.context.assets.open("artwork-video.mp4").use { it.copyTo(output) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val bitmap = ArtworkLoader.load(context, uri.toString(), true)
        assertNotNull(bitmap)
        assertTrue(bitmap!!.width in 1..512 && bitmap.height in 1..512)
        assertVisiblePixels(bitmap)
        assertSame(bitmap, ArtworkLoader.load(context, uri.toString(), true))
    }

    @Test fun localVideoFallbackProducesArtwork() = runBlocking {
        val file = fixture("artwork-video.mp4")
        val bitmap = ArtworkLoader.load(context, Uri.fromFile(file).toString(), true)
        assertNotNull(bitmap)
        assertVisiblePixels(bitmap!!)
    }

    @Test fun audioEmbeddedCoverIsDecodedAndBounded() = runBlocking {
        val file = fixture("artwork-audio.mp3")
        val bitmap = ArtworkLoader.load(context, Uri.fromFile(file).toString(), false)
        assertNotNull(bitmap)
        assertEquals(512, bitmap!!.width)
        assertEquals(512, bitmap.height)
        assertVisiblePixels(bitmap)
    }

    @Test fun absentCoverAndInaccessibleUrisUsePlaceholder() = runBlocking {
        val file = fixture("mono44.wav")
        assertNull(ArtworkLoader.load(context, Uri.fromFile(file).toString(), false))
        assertNull(ArtworkLoader.load(context, "content://media/external/video/media/9223372036854775807", true))
        assertNull(ArtworkLoader.load(context, "https://example.invalid/cover.png", true))
    }

    private fun fixture(name: String): File {
        val file = File(context.cacheDir, "artwork-${System.nanoTime()}-$name")
        files += file
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun assertVisiblePixels(bitmap: android.graphics.Bitmap) {
        val source = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE)
            bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)!! else bitmap
        var bright = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val pixel = source.getPixel(x * source.width / 8, y * source.height / 8)
            if (maxOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255) > 48) bright++
        }
        assertTrue("An initially black video should use a visible frame", bright > 8)
    }
}

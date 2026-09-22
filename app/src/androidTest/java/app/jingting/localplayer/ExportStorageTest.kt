package app.jingting.localplayer

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.export.createPendingAudio
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExportStorageTest {
    @Test fun sameFilenameCreatesANewFileWithoutReplacingPublishedAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val name = "collision-${UUID.randomUUID()}.m4a"
        val firstBytes = instrumentation.context.assets.open("collision-stereo.m4a").use { it.readBytes() }
        val secondBytes = instrumentation.context.assets.open("collision-mono.m4a").use { it.readBytes() }
        val created = mutableListOf<Uri>()
        fun publish(bytes: ByteArray): Uri {
            val uri = createPendingAudio(resolver, name).also { created += it }
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            return uri
        }
        try {
            val old = publish(firstBytes)
            val newer = publish(secondBytes)
            assertNotEquals(old, newer)
            assertArrayEquals(firstBytes, resolver.openInputStream(old)!!.use { it.readBytes() })
            assertArrayEquals(secondBytes, resolver.openInputStream(newer)!!.use { it.readBytes() })
        } finally { created.forEach { resolver.delete(it, null, null) } }
    }
}

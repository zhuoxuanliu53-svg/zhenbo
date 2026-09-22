package app.jingting.localplayer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.data.MediaEntity
import app.jingting.localplayer.export.AudioExportService
import app.jingting.localplayer.export.createPendingAudio
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V10MediaExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.graph

    @Test fun importingMediaStoreAliasKeepsOneLibraryIdentityAndItsCustomDisplayName() = runBlocking {
        val resolver = context.contentResolver
        val uri = createPendingAudio(resolver, "v10-alias-${UUID.randomUUID()}.m4a")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                instrumentation.context.assets.open("collision-stereo.m4a").use { it.copyTo(out) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            graph.media.refresh()
            graph.dao.renameMedia(uri.toString(), "V10 保留的名称")
            val alias = ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), ContentUris.parseId(uri))
            assertNotEquals(uri, alias)
            // A directly readable MediaStore row has no persistable document grant to take.
            val imported = graph.media.importLocal(alias, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(uri.toString(), imported.id)
            graph.media.refresh()
            val matching = graph.dao.observeMedia().first().filter { it.id == uri.toString() || it.id == alias.toString() }
            assertEquals(1, matching.size)
            assertEquals("V10 保留的名称", matching.single().title)
            assertTrue(graph.media.canRead(matching.single().uri))
        } finally {
            resolver.delete(uri, null, null)
            graph.dao.deleteMediaAlias(uri.toString())
            graph.media.refresh()
        }
    }

    @Test fun cancellingAnEncodingInProgressCleansItBeforeTheNextExport() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val token = UUID.randomUUID().toString()
        val file = File(context.filesDir, "v10-cancel-$token.wav")
        val exported = mutableListOf<String>()
        try {
            instrumentation.context.assets.open("stereo48.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            val before = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val item = MediaEntity("v10:$token", Uri.fromFile(file).toString(), "V10 export", 3000, false, source = "test")
            graph.dao.putMedia(listOf(item))
            val first = withContext(Dispatchers.Main) { graph.exports.enqueue(List(50) { item.id }, "v10-cancel", true, "AAC") }
            exported += first
            withTimeout(60_000) {
                while (true) {
                    val task = graph.dao.export(first)!!
                    check(task.state !in setOf("failed", "completed", "cancelled")) { "Encoding ended before cancellation: ${task.state}" }
                    if (task.strategy == "ENCODE_AAC" && task.progress in 1..97) break
                    delay(20)
                }
            }
            val next = withContext(Dispatchers.Main) {
                graph.exports.cancel(first)
                graph.exports.enqueue(listOf(item.id), "v10-after-cancel", false, "AAC")
            }
            exported += next
            withTimeout(60_000) {
                while (graph.dao.export(next)?.state !in setOf("completed", "failed", "cancelled")) delay(50)
            }
            assertEquals("cancelled", graph.dao.export(first)?.state)
            assertNull(graph.dao.export(first)?.outputUri)
            assertFalse(graph.exports.taskDir(first).exists())
            val completed = graph.dao.export(next)!!
            assertEquals(completed.error, "completed", completed.state)
            assertTrue(graph.media.canRead(completed.outputUri!!))
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally {
            withContext(Dispatchers.Main) { exported.forEach { graph.exports.cancel(it) } }
            withTimeout(10_000) { while (AudioExportService.running) delay(50) }
            exported.forEach { id -> graph.dao.export(id)?.outputUri?.let { context.contentResolver.delete(Uri.parse(it), null, null) } }
            graph.dao.availability("v10:$token", false)
            file.delete()
            scenario.close()
        }
    }
}

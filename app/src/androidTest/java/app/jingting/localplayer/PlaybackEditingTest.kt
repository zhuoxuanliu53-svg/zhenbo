package app.jingting.localplayer

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.core.PlaybackMode
import app.jingting.localplayer.data.CollectionEntity
import app.jingting.localplayer.data.EntryEntity
import app.jingting.localplayer.data.MediaEntity
import app.jingting.localplayer.ui.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaybackEditingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.graph
    private val token = UUID.randomUUID().toString()
    private val files = mutableListOf<File>()
    private val items = mutableListOf<MediaEntity>()
    private val collection = CollectionEntity(id = "editing-$token", name = "播放编辑测试 $token")
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var viewModel: PlayerViewModel

    @Before fun setUp() = runBlocking {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { viewModel = ViewModelProvider(it)[PlayerViewModel::class.java] }
        withContext(Dispatchers.Main) { graph.playback.connect().apply { pause(); clearMediaItems() }; graph.playback.sleep(0) }
        repeat(3) { index ->
            val file = File(context.filesDir, "editing-$token-$index.wav")
            instrumentation.context.assets.open("stereo48.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            files += file
            items += MediaEntity("editing-$token-$index", Uri.fromFile(file).toString(), "队列音频 ${index + 1}", 3000, false, source = "editing-test")
        }
        graph.db.withTransaction {
            graph.dao.putMedia(items)
            graph.dao.putCollection(collection)
            graph.dao.putEntries(items.mapIndexed { index, media ->
                EntryEntity(collectionId = collection.id, mediaId = media.id, position = index)
            })
        }
    }

    @After fun cleanUp() = runBlocking {
        withContext(Dispatchers.Main) { runCatching { graph.playback.connect().apply { pause(); clearMediaItems() }; graph.playback.sleep(0) } }
        withContext(Dispatchers.IO) {
            graph.db.withTransaction {
                graph.dao.deleteCollection(collection.id)
                items.forEach { item ->
                    graph.dao.deleteMediaAlias(item.id)
                    graph.db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE id = ?", arrayOf(item.id))
                }
            }
        }
        files.forEach { it.delete() }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun startingAtSecondEntryKeepsQueueAndRenameUpdatesCurrentPlayer() = runBlocking {
        withContext(Dispatchers.Main) { graph.playback.setMode(PlaybackMode.ORDERED) }
        await { graph.playback.state.value.mode == PlaybackMode.ORDERED }
        withContext(Dispatchers.Main) { viewModel.playCollection(collection.id, index = 1) }
        await { graph.playback.state.value.ids == items.map { it.id } && graph.playback.state.value.index == 1 }
        withContext(Dispatchers.Main) {
            val controller = graph.playback.connect()
            controller.pause()
            assertEquals(3, controller.mediaItemCount)
            assertEquals(items.map { it.id }, (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
            assertEquals(items[1].id, controller.currentMediaItem!!.mediaId)
        }

        val newTitle = "改名后的当前音频"
        withContext(Dispatchers.Main) { viewModel.renameMedia(items[1].id, newTitle) }
        await { graph.playback.state.value.title == newTitle }
        assertEquals(newTitle, graph.dao.mediaByIds(listOf(items[1].id)).single().title)
        withContext(Dispatchers.Main) {
            val controller = graph.playback.connect()
            assertEquals(newTitle, controller.currentMediaItem!!.mediaMetadata.title.toString())
            assertEquals(1, controller.currentMediaItemIndex)
            assertEquals(3, controller.mediaItemCount)
            assertFalse(controller.playWhenReady)
            // The preceding entry is still reachable after a non-leading item is selected.
            graph.playback.skip(false)
        }
        await { graph.playback.state.value.index == 0 }
        assertEquals(items[0].id, graph.playback.state.value.ids[graph.playback.state.value.index])
        assertTrue(files.all { it.exists() })
    }

    private suspend fun await(check: () -> Boolean) {
        withTimeout(15_000) { while (!check()) delay(50) }
    }
}

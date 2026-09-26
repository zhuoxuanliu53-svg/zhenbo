package app.jingting.localplayer

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.core.PlaybackMode
import app.jingting.localplayer.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V05InteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.graph
    private val token = UUID.randomUUID().toString().take(8)
    private val media = mutableListOf<MediaEntity>()
    private val files = mutableListOf<File>()
    private val publicUris = mutableListOf<Uri>()
    private lateinit var collection: CollectionEntity
    private val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    @Before fun seed() = runBlocking {
        withContext(Dispatchers.Main) { graph.timers.cancel(); graph.playback.connect().apply { pause(); clearMediaItems() } }
        repeat(30) { i ->
            val file = File(context.filesDir, "v05-$token-$i.mp4")
            instrumentation.context.assets.open("artwork-video.mp4").use { src -> file.outputStream().use { src.copyTo(it) } }
            files += file
            media += MediaEntity("v05-$token-$i", Uri.fromFile(file).toString(), "%02d 海边晚风 $token".format(i), 3_000, true, source = "test")
        }
        graph.dao.putMedia(media)
        collection = CollectionEntity("v05-collection-$token", "夜间收藏 $token", 0)
        graph.dao.putCollection(collection)
        graph.dao.putEntries(media.mapIndexed { i, m -> EntryEntity("v05-entry-$token-$i", collection.id, m.id, i) })
    }
    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { graph.timers.cancel(); graph.playback.connect().apply { pause(); clearMediaItems() } }
        graph.dao.deleteCollection(collection.id)
        media.forEach { graph.dao.deleteMediaAlias(it.id) }
        withContext(Dispatchers.IO) {
            media.forEach { graph.db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE id=?", arrayOf(it.id)) }
            publicUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            files.forEach { it.delete() }
        }
    }
    private fun library() {
        compose.onNode(tab and hasText("媒体库")).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(token)
        Espresso.closeSoftKeyboard(); compose.waitForIdle()
        compose.onNodeWithTag("library-grid").performScrollToIndex(0)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-${media[0].id}").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openCollection() {
        compose.onNode(tab and hasText("集合")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("collection-${collection.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("collection-${collection.id}").performClick()
        compose.waitForIdle()
    }
    private fun node(index: Int) = compose.onNodeWithTag("media-${media[index].id}")
    private fun order() = runBlocking { graph.dao.entries(collection.id).map { it.mediaId } }
    private fun screenshot(name: String) {
        Espresso.closeSoftKeyboard(); compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun galleryHasThreeColumnsAndLongPressStartsNumberedBatchEditing() {
        library()
        val bounds = (0..3).map { node(it).fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].top, bounds[1].top, 1f); assertEquals(bounds[1].top, bounds[2].top, 1f)
        assertTrue(bounds[0].left < bounds[1].left && bounds[1].left < bounds[2].left)
        assertTrue(bounds[3].top > bounds[0].bottom)
        screenshot("v05-gallery.png")
        node(1).performTouchInput { longClick(center) }
        compose.onNodeWithText("已选 1").assertExists()
        node(0).performClick()
        compose.onNodeWithContentDescription("选择${media[1].title}").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已选第1项"))
        compose.onNodeWithContentDescription("选择${media[0].title}").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已选第2项"))
        compose.onNodeWithContentDescription("批量操作").performClick()
        compose.onNodeWithText("重命名").performClick()
        compose.onNodeWithText("批量重命名").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
    }
    @Test fun collectionDragDownThenUpPersistsWithoutChangingPlayback() {
        openCollection()
        val original = order()
        drag(0, 4)
        screenshot("v05-drag-after-down.png")
        compose.waitUntil(10_000) { order() != original }
        assertEquals("Drag order: ${order().map { id -> media.indexOfFirst { it.id == id } }}", 4, order().indexOf(media[0].id))
        assertEquals(original.drop(1).take(4) + original.first() + original.drop(5), order())
        assertFalse(graph.playback.state.value.isPlaying)
        drag(0, 1)
        compose.waitUntil(10_000) { order().first() == media[0].id }
        assertEquals(original, order())
        compose.onNodeWithContentDescription("返回集合").performClick()
        compose.onNodeWithTag("collection-${collection.id}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-${media[0].id}").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(node(0).fetchSemanticsNode().boundsInRoot.left < node(1).fetchSemanticsNode().boundsInRoot.left)
        screenshot("v05-collection.png")
    }
    private fun drag(from: Int, to: Int) {
        val start = node(from).fetchSemanticsNode().boundsInRoot.center
        val end = node(to).fetchSemanticsNode().boundsInRoot.center
        physicalDrag(start, end)
        compose.waitForIdle()
    }
    /** Real pointer timing; never ask Compose to become idle while a finger drives auto-scroll. */
    private fun physicalDrag(start: Offset, end: Offset, holdAtEndMs: Long = 220) {
        compose.mainClock.autoAdvance = false
        val downAt = SystemClock.uptimeMillis()
        fun event(action: Int, point: Offset) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        fun frames(ms: Long) {
            val until = SystemClock.uptimeMillis() + ms
            while (SystemClock.uptimeMillis() < until) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(16) }
        }
        try {
            event(MotionEvent.ACTION_DOWN, start); frames(750)
            for (step in 1..20) { event(MotionEvent.ACTION_MOVE, start + (end - start) * (step / 20f)); frames(16) }
            frames(holdAtEndMs)
            event(MotionEvent.ACTION_UP, end)
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun holdingDraggedTileAtEdgeScrollsBeyondFirstScreen() {
        openCollection()
        val grid = compose.onNodeWithTag("collection-grid")
        val box = grid.fetchSemanticsNode().boundsInRoot
        val lastInitiallyVisible = media.indices.filter { i ->
            compose.onAllNodesWithTag("media-${media[i].id}").fetchSemanticsNodes().any { it.boundsInRoot.height > 0 && it.boundsInRoot.top < box.bottom }
        }.maxOrNull()!!
        val start = node(0).fetchSemanticsNode().boundsInRoot.center
        val edge = Offset(start.x, box.bottom - 8)
        physicalDrag(start, edge, holdAtEndMs = 1500)
        compose.waitUntil(10_000) { order().indexOf(media[0].id) > lastInitiallyVisible }
        assertEquals(30, order().distinct().size)
        assertTrue(files.all { it.exists() })
    }
    @Test fun longPressCollectionAndPlayerExposeEditingAndNavigationKeepsSearch() {
        library()
        compose.onNode(tab and hasText("集合")).performClick()
        compose.onNodeWithTag("collection-${collection.id}").performTouchInput { longClick(center) }
        compose.onNodeWithText("重命名").performClick()
        compose.onNodeWithText("重命名集合").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNode(tab and hasText("媒体库")).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains(token)
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("枕波 ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains(token)
        runBlocking { withContext(Dispatchers.Main) { graph.playback.play(listOf(media[0].id), PlaybackMode.REPEAT_ALL); graph.playback.connect().pause() } }
        compose.onNode(tab and hasText("集合")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(media[0].title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(media[0].title).performTouchInput { longClick(center) }
        compose.onNodeWithText("名称（仅在枕波中）").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
    }
    @Test fun addFilesPanelUsesThreeColumnsAndLongPressSelection() {
        runBlocking { graph.dao.entries(collection.id).forEach { graph.dao.deleteEntry(it.id) } }
        openCollection()
        compose.onNodeWithText("添加文件").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(token)
        Espresso.closeSoftKeyboard(); compose.waitForIdle()
        node(2).performTouchInput { longClick(center) }
        node(0).performClick()
        compose.onNodeWithText("添加 (2)").performClick()
        compose.waitUntil(10_000) { order() == listOf(media[2].id, media[0].id) }
    }
    @Test fun refreshHidesDeletedVideoAudioAndDocumentFromLibraryCollectionAndQueue() = runBlocking {
        val resolver = context.contentResolver
        for (video in listOf(false, true)) {
            val base = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(base, ContentValues().apply {
                put("_display_name", "v05-delete-$token.${if (video) "mp4" else "wav"}")
                put("mime_type", if (video) "video/mp4" else "audio/wav")
                put("relative_path", if (video) "Movies/枕波测试/" else "Music/枕波测试/")
                put("is_pending", 1)
            })!!
            publicUris += uri
            resolver.openOutputStream(uri)!!.use { dst -> instrumentation.context.assets.open(if (video) "artwork-video.mp4" else "mono44.wav").use { it.copyTo(dst) } }
            resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
        }
        val document = media[0].copy(source = "document")
        graph.dao.putMedia(listOf(document)); graph.media.refresh()
        val publicIds = publicUris.map { it.toString() }
        assertTrue(graph.dao.observeMedia().first().map { it.id }.containsAll(publicIds))
        graph.dao.putEntries(publicIds.mapIndexed { i, id -> EntryEntity(collectionId = collection.id, mediaId = id, position = 30 + i) })
        withContext(Dispatchers.Main) { graph.playback.play(publicIds, PlaybackMode.REPEAT_ALL) }
        publicUris.forEach { resolver.delete(it, null, null) }; files[0].delete()
        graph.media.refresh()
        withContext(Dispatchers.Main) { graph.playback.pruneUnavailable() }
        val invisible = publicIds + document.id
        assertTrue(graph.dao.observeMedia().first().none { it.id in invisible })
        assertTrue(graph.dao.observeEntries(collection.id).first().none { it.entry.mediaId in invisible })
        withContext(Dispatchers.Main) { assertEquals(0, graph.playback.connect().mediaItemCount) }
        // Restore a temporarily inaccessible document: its original collection membership is retained.
        instrumentation.context.assets.open("artwork-video.mp4").use { src -> files[0].outputStream().use { src.copyTo(it) } }
        graph.media.refresh()
        assertTrue(graph.dao.observeEntries(collection.id).first().any { it.entry.mediaId == document.id })
    }
    @Test fun trashedAndPendingFilesStayHiddenUntilPublishedOrRestored() = runBlocking {
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 30)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put("_display_name", "v05-trash-$token.wav"); put("mime_type", "audio/wav"); put("relative_path", "Music/枕波测试/"); put("is_pending", 1)
        })!!
        publicUris += uri
        resolver.openOutputStream(uri)!!.use { dst -> instrumentation.context.assets.open("mono44.wav").use { it.copyTo(dst) } }
        graph.media.refresh(); assertTrue(graph.dao.observeMedia().first().none { it.id == uri.toString() })
        resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
        graph.media.refresh(); assertTrue(graph.dao.observeMedia().first().any { it.id == uri.toString() })
        resolver.update(uri, ContentValues().apply { put("is_trashed", 1) }, null, null)
        graph.media.refresh(); assertTrue(graph.dao.observeMedia().first().none { it.id == uri.toString() })
        resolver.update(uri, ContentValues().apply { put("is_trashed", 0) }, null, null)
        graph.media.refresh(); assertTrue(graph.dao.observeMedia().first().any { it.id == uri.toString() })
    }
    @Test fun renamedApplicationKeepsUpgradeIdentityAndRendersAdaptiveIcon() {
        assertEquals("app.jingting.localplayer", context.packageName)
        assertEquals("枕波", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals(BuildConfig.VERSION_NAME, context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val icon = context.applicationInfo.loadIcon(context.packageManager)
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 432, 432); icon.draw(android.graphics.Canvas(bitmap))
        File(context.filesDir, "v05-icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

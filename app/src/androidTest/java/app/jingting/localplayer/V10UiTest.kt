package app.jingting.localplayer

import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
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

/** Regression coverage for restored selections, cancelled gestures and repeated submissions. */
@RunWith(AndroidJUnit4::class)
class V10UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.graph
    private val token = UUID.randomUUID().toString().take(8)
    private val media = mutableListOf<MediaEntity>()
    private val files = mutableListOf<File>()
    private lateinit var collection: CollectionEntity
    private val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    @Before fun seed() = runBlocking {
        withContext(Dispatchers.Main) { graph.timers.cancel(); graph.playback.connect().apply { pause(); clearMediaItems() } }
        repeat(6) { i ->
            val file = File(context.filesDir, "v10-ui-$token-$i.mp4")
            instrumentation.context.assets.open("video.mp4").use { src -> file.outputStream().use { src.copyTo(it) } }
            files += file
            media += MediaEntity("v10-ui-$token-$i", Uri.fromFile(file).toString(), "$i 夜间回归 $token", 10_000, true, source = "test")
        }
        graph.dao.putMedia(media)
        collection = CollectionEntity("v10-ui-collection-$token", "V10 集合 $token", 0)
        graph.dao.putCollection(collection)
        graph.dao.putEntries(media.mapIndexed { i, item -> EntryEntity("v10-ui-entry-$token-$i", collection.id, item.id, i) })
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { graph.timers.cancel(); graph.playback.connect().apply { pause(); clearMediaItems() } }
        graph.dao.observeCollections().first().filter { token in it.name }.forEach { graph.dao.deleteCollection(it.id) }
        withContext(Dispatchers.IO) {
            media.forEach { graph.dao.deleteMediaAlias(it.id); graph.db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE id=?", arrayOf(it.id)) }
            files.forEach { it.delete() }
        }
    }

    private fun library() {
        compose.onNode(tab and hasText("媒体库")).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(token)
        Espresso.closeSoftKeyboard()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-${media[0].id}").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openCollection() {
        compose.onNode(tab and hasText("集合")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("collection-${collection.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("collection-${collection.id}").performClick()
        compose.waitForIdle()
    }

    private fun node(index: Int) = compose.onNodeWithTag("media-${media[index].id}")
    private fun number(index: Int, order: Int) = compose.onNodeWithContentDescription("选择${media[index].title}")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已选第${order}项"))
    private fun order() = runBlocking { graph.dao.entries(collection.id).map { it.mediaId } }

    @Test fun librarySelectionSurvivesNavigationAndActivityRecreationAndExcludesMissingMedia() {
        library()
        node(2).performTouchInput { longClick(center) }; node(0).performClick()
        compose.onNode(tab and hasText("集合")).performClick()
        compose.onNode(tab and hasText("媒体库")).performClick()
        compose.onNodeWithText("已选 2").assertIsDisplayed()
        number(2, 1); number(0, 2)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已选 2").fetchSemanticsNodes().isNotEmpty() }
        number(2, 1); number(0, 2)
        runBlocking { graph.dao.availability(media[2].id, false) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已选 1").fetchSemanticsNodes().isNotEmpty() }
        node(2).assertDoesNotExist(); number(0, 1)
        compose.onNodeWithText("播放", substring = false).performClick()
        compose.waitUntil(10_000) { graph.playback.state.value.ids == listOf(media[0].id) }
    }

    @Test fun collectionSelectionAndAddFilesSheetSurviveActivityRecreation() {
        openCollection()
        node(1).performTouchInput { longClick(center) }; node(0).performClick()
        compose.onNodeWithText("已选 2").assertIsDisplayed()
        number(1, 1); number(0, 2)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已选 2").fetchSemanticsNodes().isNotEmpty() }
        number(1, 1); number(0, 2)
        compose.onNodeWithText("完成").performClick()
        runBlocking { graph.dao.entries(collection.id).forEach { graph.dao.deleteEntry(it.id) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("集合为空").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("添加文件").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(token)
        Espresso.closeSoftKeyboard()
        node(2).performTouchInput { longClick(center) }; node(0).performClick()
        compose.onNodeWithText("添加 (2)").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("添加 (2)").fetchSemanticsNodes().isNotEmpty() }
        number(2, 1); number(0, 2)
        compose.onNodeWithText("添加 (2)").performClick()
        compose.waitUntil(10_000) { order() == listOf(media[2].id, media[0].id) }
    }

    @Test fun interruptedCollectionDragDoesNotPersistOrBlockTheNextGesture() {
        openCollection()
        val original = order()
        val start = node(0).fetchSemanticsNode().boundsInRoot.center
        val end = node(4).fetchSemanticsNode().boundsInRoot.center
        gesture(start, end, cancel = true)
        compose.waitForIdle()
        assertEquals(original, order())
        assertTrue(node(0).fetchSemanticsNode().boundsInRoot.left < node(1).fetchSemanticsNode().boundsInRoot.left)
        gesture(node(0).fetchSemanticsNode().boundsInRoot.center, node(4).fetchSemanticsNode().boundsInRoot.center)
        compose.waitUntil(10_000) { order().indexOf(media[0].id) == 4 }
        assertEquals(original.toSet(), order().toSet())
    }

    @Test fun repeatedCreateCollectionEventOnlyCreatesOneCollection() {
        compose.onNode(tab and hasText("集合")).performClick()
        compose.onNodeWithText("新建").performClick()
        val name = "重复提交回归 $token"
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        Espresso.closeSoftKeyboard()
        // Capture the still-displayed callback and invoke twice before the next frame.
        val submit = compose.onNodeWithText("保存").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { submit(); submit() }
        compose.waitUntil(10_000) { runBlocking { graph.dao.observeCollections().first().count { it.name == name } } > 0 }
        compose.waitForIdle()
        assertEquals(1, runBlocking { graph.dao.observeCollections().first().count { it.name == name } })
    }

    private fun gesture(start: Offset, end: Offset, cancel: Boolean = false) {
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
            frames(200)
            event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end)
        } finally { compose.mainClock.autoAdvance = true }
    }
}

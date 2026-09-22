package app.jingting.localplayer

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.core.PlaybackMode
import app.jingting.localplayer.data.CollectionEntity
import app.jingting.localplayer.data.EntryEntity
import app.jingting.localplayer.data.ExportEntity
import app.jingting.localplayer.data.MediaEntity
import app.jingting.localplayer.ui.NightColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises user-visible actions against real Room, playback, timers and exporters. */
@RunWith(AndroidJUnit4::class)
class V03UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.graph
    private val token = UUID.randomUUID().toString().take(8)
    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val files = mutableListOf<File>()
    private val media = mutableListOf<MediaEntity>()
    private lateinit var audio: MediaEntity
    private lateinit var secondAudio: MediaEntity
    private lateinit var video: MediaEntity
    private lateinit var secondVideo: MediaEntity
    private lateinit var collection: CollectionEntity

    @Before fun seed() = runBlocking {
        withContext(Dispatchers.Main) {
            graph.timers.cancel()
            graph.playback.connect().apply { pause(); clearMediaItems() }
        }
        audio = fixture("mono44.wav", "A 音频", false)
        secondAudio = fixture("stereo48.wav", "B 音频", false)
        video = fixture("video.mp4", "C 视频", true)
        secondVideo = fixture("video.mp4", "D 视频", true)
        collection = CollectionEntity(id = "v03-ui-$token", name = "V03集合 $token", createdAt = 0L)
        graph.dao.putCollection(collection)
        graph.dao.putMedia(media)
        compose.waitForIdle()
    }

    @After fun cleanUp() = runBlocking {
        withContext(Dispatchers.Main) {
            runCatching { graph.timers.cancel(); graph.playback.connect().apply { pause(); clearMediaItems() } }
        }
        val exports = graph.dao.observeExports().first().filter { it.sourceIds.contains(token) }
        exports.filter { it.state in setOf("queued", "running", "publishing") }.forEach { graph.exports.cancel(it.id) }
        withContext(Dispatchers.IO) {
            exports.filter { it.state == "completed" }.forEach { it.outputUri?.let { uri -> runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) } } }
            graph.db.withTransaction {
                if (::collection.isInitialized) graph.dao.deleteCollection(collection.id)
                media.forEach { item ->
                    graph.dao.deleteMediaAlias(item.id)
                    graph.db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE id = ?", arrayOf(item.id))
                }
                exports.forEach { task -> graph.db.openHelper.writableDatabase.execSQL("DELETE FROM exports WHERE id = ?", arrayOf(task.id)) }
            }
        }
        files.forEach { it.delete() }
    }

    @Test fun numberedSelectionPreservesTapOrderAcrossFiltersAndReselection() {
        chooseLibrary()
        select(secondVideo); select(video); select(audio)
        assertNumber(secondVideo, 1); assertNumber(video, 2); assertNumber(audio, 3)

        compose.onNodeWithText("视频", substring = false).performClick()
        compose.onNodeWithTag("media-${audio.id}").assertDoesNotExist()
        assertNumber(secondVideo, 1); assertNumber(video, 2)
        search().performTextReplacement(video.title)
        assertNumber(video, 2)
        search().performTextReplacement(token)
        compose.onNodeWithText("全部", substring = false).performClick()

        select(secondVideo)
        assertNumber(video, 1); assertNumber(audio, 2)
        select(secondVideo)
        assertNumber(secondVideo, 3)
        capture("v03-selection.png").recycle()
        compose.onNodeWithText("拼接音频").performClick()
        waitText("保留原音质")
        val order = compose.onAllNodesWithContentDescription("试听第1项").fetchSemanticsNodes()
        assertEquals(1, order.size)
        dialogText(video.title).assertExists()
        dialogText(audio.title).assertExists()
        dialogText(secondVideo.title).assertExists()
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun twoVideosConcatenateDirectlyInSelectionOrderAndPublishAudioOnly() {
        chooseLibrary()
        select(secondVideo); select(video)
        compose.onNodeWithText("拼接音频").assertIsEnabled().performClick()
        waitForExportEnabled()
        compose.onNodeWithText("保留原音质").assertIsDisplayed()
        field("文件名称").performTextReplacement("v03-joined-$token")
        compose.onNodeWithTag("confirm-export").performClick()
        val result = completedExports(1).single()
        assertTrue(result.concatenate)
        assertEquals(listOf(secondVideo.id, video.id), decode(result.sourceIds))
        assertEquals("ORIGINAL", result.exportMode)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(result.outputUri!!), null)
            assertEquals("Joined output must not contain a video track", 1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) in 19_500_000..20_500_000)
        } finally { extractor.release() }
    }

    @Test fun batchActionsPlayRenameAddAndExportEachSelectedItem() {
        chooseLibrary()
        select(video); select(audio)
        compose.onNodeWithText("播放", substring = false).performClick()
        compose.waitUntil(10_000) { graph.playback.state.value.ids == listOf(video.id, audio.id) && graph.playback.state.value.isPlaying }
        runBlocking { withContext(Dispatchers.Main) { graph.playback.connect().pause() } }

        batch("重命名")
        field("1. 名称").performTextReplacement("新视频 $token")
        field("2. 名称").performTextReplacement("新音频 $token")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.dao.mediaByIds(listOf(video.id)).single().title == "新视频 $token" } }
        assertEquals("新音频 $token", runBlocking { graph.dao.mediaByIds(listOf(audio.id)).single().title })

        batch("加入集合")
        compose.onNodeWithText(collection.name).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { graph.dao.entries(collection.id).map { it.mediaId } == listOf(video.id, audio.id) } }

        batch("分别导出音频")
        waitForExportEnabled()
        compose.onAllNodes(hasSetTextAction() and hasText("文件名称")).assertCountEquals(2)
        compose.onNodeWithTag("confirm-export").performClick()
        val results = completedExports(2)
        assertEquals(setOf(video.id, audio.id), results.map { decode(it.sourceIds).single() }.toSet())
        assertEquals(setOf("新视频 $token", "新音频 $token"), results.map { it.title }.toSet())
        assertTrue(results.all { !it.concatenate && it.exportMode == "ORIGINAL" })
        results.forEach { assertTrue(runBlocking { graph.media.canRead(it.outputUri!!) }) }
    }

    @Test fun collectionReordersBySelectionThenRemovesInBulkWithoutDeletingFiles() {
        runBlocking { graph.dao.putEntries(listOf(audio, video, secondAudio, secondVideo).mapIndexed { i, item -> EntryEntity(collectionId = collection.id, mediaId = item.id, position = i) }) }
        openCollection()
        compose.onNodeWithText("编辑").performClick()
        select(secondVideo); select(video)
        assertNumber(secondVideo, 1); assertNumber(video, 2)
        batch("按所选顺序移到最前")
        compose.waitUntil(10_000) { runBlocking { graph.dao.entries(collection.id).map { it.mediaId } == listOf(secondVideo.id, video.id, audio.id, secondAudio.id) } }
        compose.onNodeWithText("已选 0").assertExists()
        select(secondVideo); select(video)
        batch("移出集合")
        compose.waitUntil(10_000) { runBlocking { graph.dao.entries(collection.id).map { it.mediaId } == listOf(audio.id, secondAudio.id) } }
        assertTrue(files.all { it.exists() })
        assertEquals(4, runBlocking { graph.dao.mediaByIds(media.map { it.id }).size })
        compose.onNodeWithContentDescription("添加文件").assertIsDisplayed()
    }

    @Test fun playerAndTaskPageShareEditableCountdownAndClockEditor() {
        runBlocking { graph.dao.putEntries(listOf(EntryEntity(collectionId = collection.id, mediaId = video.id, position = 0))) }
        compose.onNode(tabRole and hasText("集合")).performClick()
        runBlocking { withContext(Dispatchers.Main) { graph.playback.play(listOf(video.id), PlaybackMode.REPEAT_ALL); graph.playback.connect().pause() } }
        waitText(video.title)
        compose.onNodeWithText(video.title).performClick()
        compose.onNodeWithText("计时任务").performScrollTo().performClick()
        waitText("播放任务")
        compose.onNodeWithText("现在播放，计时结束后停止").assertIsDisplayed()
        compose.onNodeWithText("当前队列", substring = false).performClick()
        compose.onNode(hasText(collection.name) and hasAnyAncestor(isPopup())).performClick()
        dialogText(collection.name).assertIsDisplayed()
        compose.onNodeWithTag("task-duration").performScrollTo().performTextReplacement("15")
        compose.onNodeWithTag("task-duration").assertTextContains("15")
        capture("v03-task-before-save.png", hideKeyboard = false).recycle()
        compose.onNodeWithTag("save-task").assertIsEnabled().assertIsDisplayed().performClick()
        try {
            compose.waitUntil(10_000) { graph.timers.state.value?.let { it.active && it.collectionId == collection.id && it.durationMinutes == 15 && it.mode == PlaybackMode.REPEAT_ALL } == true }
        } catch (failure: Throwable) {
            capture("v03-task-failed.png", hideKeyboard = false).recycle()
            val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
            val tree = (0 until roots).joinToString("\n") { compose.onAllNodes(isRoot())[it].printToString() }
            val diagnostic = "Expected active timer: collection=${collection.id}, duration=15, mode=REPEAT_ALL\nActual timer: ${graph.timers.state.value}\nPlayer: ${graph.playback.state.value}\n$tree"
            File(context.filesDir, "v03-task-failure.txt").writeText(diagnostic)
            throw AssertionError(diagnostic, failure)
        }
        val timerId = graph.timers.state.value!!.id
        // The player sheet remains under the editor; dismiss it through Android Back.
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("定时与导出").performClick()
        waitText("取消计时")
        compose.onAllNodesWithText("编辑", substring = false).onFirst().performClick()
        waitText("播放任务")
        compose.onNodeWithTag("task-duration").performScrollTo().performTextReplacement("30")
        compose.onNodeWithTag("save-task").performClick()
        compose.waitUntil(10_000) { graph.timers.state.value?.durationMinutes == 30 }
        assertEquals(timerId, graph.timers.state.value!!.id)
        compose.onNodeWithContentDescription("新建计时任务").performClick()
        dialogText("定时任务").performClick()
        compose.onNodeWithTag("task-hour").assertExists()
        compose.onNodeWithTag("task-minute").assertExists()
        compose.onNodeWithText("保存任务").assertIsDisplayed()
        dialogText("计时任务").performScrollTo().performClick()
        compose.onNodeWithTag("task-hour").assertDoesNotExist()
        capture("v03-task-editor.png").recycle()
        dialogText("取消计时").performScrollTo().performClick()
        compose.waitUntil(10_000) { graph.timers.state.value?.active == false }
        compose.onAllNodesWithText("取消计时").assertCountEquals(0)
    }

    @Test fun actualExportDialogUsesWarmSurfaceInsteadOfDefaultPurple() {
        chooseLibrary()
        select(video); select(secondVideo)
        compose.onNodeWithText("拼接音频").performClick()
        waitForExportEnabled()
        val bitmap = capture("v03-export-dialog.png")
        val warmSurface = NightColorScheme.surfaceContainerHigh.toArgb()
        assertEquals(0xFF26211D.toInt(), warmSurface)
        var warmPixels = 0
        var defaultPurplePixels = 0
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            val pixel = bitmap.getPixel(x, y)
            if (pixel == warmSurface) warmPixels++
            if (pixel == 0xFF2B2930.toInt() || pixel == 0xFF1D1B20.toInt()) defaultPurplePixels++
        }
        assertTrue("Visible dialog must contain the configured warm surface", warmPixels > bitmap.width * bitmap.height / 200)
        assertEquals("Old Material purple container colours must not render", 0, defaultPurplePixels)
        bitmap.recycle()
        compose.onNodeWithText("取消").performClick()
    }

    private fun chooseLibrary() {
        compose.onNode(tabRole and hasText("媒体库")).performClick()
        search().performTextReplacement(token)
        waitText(video.title)
        compose.onNodeWithText("选择", substring = false).performClick()
    }
    // "搜索" is a placeholder and leaves the semantics tree after text is entered.
    // LibraryPage has one text field, fixed above (outside) the scrolling media list.
    private fun search() = compose.onNode(hasSetTextAction())
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo()
    private fun dialogText(text: String) = compose.onNode(hasText(text, substring = false) and hasAnyAncestor(isDialog()))
    private fun select(item: MediaEntity) { compose.onNodeWithTag("media-${item.id}").performScrollTo().performClick() }
    private fun assertNumber(item: MediaEntity, number: Int) {
        compose.onNodeWithContentDescription("选择${item.title}").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已选第${number}项"))
    }
    private fun batch(action: String) {
        compose.onNodeWithContentDescription("批量操作").performClick()
        compose.onNodeWithText(action, substring = false).performClick()
    }
    private fun openCollection() {
        compose.onNode(tabRole and hasText("集合")).performClick()
        waitText(collection.name)
        compose.onNodeWithText(collection.name).performClick()
        waitText("添加文件")
    }
    private fun waitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitForExportEnabled() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("confirm-export").fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
        }
    }
    private fun completedExports(count: Int): List<ExportEntity> {
        var results = emptyList<ExportEntity>()
        compose.waitUntil(90_000) {
            results = runBlocking { graph.dao.observeExports().first().filter { it.sourceIds.contains(token) } }
            results.size == count && results.all { it.state in setOf("completed", "failed", "cancelled") }
        }
        results.forEach { assertEquals(it.error, "completed", it.state) }
        return results
    }
    private fun decode(json: String): List<String> = JSONArray(json).let { a -> List(a.length()) { a.getString(it) } }
    private fun fixture(asset: String, suffix: String, isVideo: Boolean): MediaEntity {
        val name = "v03-$token-${media.size}"
        val file = File(context.filesDir, "$name-$asset")
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        files += file
        return MediaEntity(name, Uri.fromFile(file).toString(), "V03 $suffix $token",
            if (isVideo) 10_000 else if (asset == "stereo48.wav") 3_000 else 2_000,
            isVideo, source = "v03-ui-test").also(media::add)
    }
    private fun capture(name: String, hideKeyboard: Boolean = true): Bitmap {
        if (hideKeyboard) Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
}

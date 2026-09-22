package app.jingting.localplayer

import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.core.PlaybackMode
import app.jingting.localplayer.data.CollectionEntity
import app.jingting.localplayer.data.EntryEntity
import app.jingting.localplayer.data.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V02UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.graph
    private val token = UUID.randomUUID().toString().take(8)
    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val files = mutableListOf<File>()
    private lateinit var audio: MediaEntity
    private lateinit var secondAudio: MediaEntity
    private lateinit var video: MediaEntity
    private lateinit var collection: CollectionEntity

    @Before fun seedIndependentMedia() = runBlocking {
        withContext(Dispatchers.Main) {
            graph.playback.connect().apply { pause(); clearMediaItems() }
            graph.playback.sleep(0)
        }
        audio = fixture("mono44.wav", false)
        secondAudio = fixture("stereo48.wav", false)
        video = fixture("video.mp4", true)
        collection = CollectionEntity(id = "ui-collection-$token", name = "UI集合 $token", createdAt = 0L)
        graph.dao.putCollection(collection)
        graph.dao.putMedia(listOf(audio, secondAudio, video))
        compose.waitForIdle()
    }

    @After fun cleanUp() = runBlocking {
        withContext(Dispatchers.Main) {
            runCatching { graph.playback.connect().apply { pause(); clearMediaItems() }; graph.playback.sleep(0) }
        }
        withContext(Dispatchers.IO) {
            graph.db.withTransaction {
                if (::collection.isInitialized) graph.dao.deleteCollection(collection.id)
                listOfNotNull(if (::audio.isInitialized) audio else null, if (::secondAudio.isInitialized) secondAudio else null,
                    if (::video.isInitialized) video else null).forEach { item ->
                    graph.dao.deleteMediaAlias(item.id)
                    graph.db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE id = ?", arrayOf(item.id))
                }
            }
        }
        files.forEach { it.delete() }
    }

    @Test fun navigationHasTwoDestinationsAndSettingsUsesGear() {
        compose.onAllNodes(tabRole).assertCountEquals(2)
        compose.onNode(tabRole and hasText("媒体库")).assertIsSelected()
        compose.onNode(tabRole and hasText("集合")).assertIsDisplayed()
        compose.onNodeWithContentDescription("设置").assertHasClickAction().performClick()
        compose.onNodeWithText("媒体访问权限").assertIsDisplayed()
        compose.onAllNodes(tabRole).assertCountEquals(0)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onAllNodes(tabRole).assertCountEquals(2)
        compose.onNodeWithText("声音留在身边").assertDoesNotExist()
        compose.onNodeWithText("完全离线").assertDoesNotExist()
    }

    @Test fun collectionCanAddRenameAndRemoveMediaWithoutLeaving() {
        compose.onNode(tabRole and hasText("集合")).performClick()
        waitForText(collection.name)
        compose.onNodeWithText(collection.name).performClick()
        compose.onNodeWithText("添加文件").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(token)
        waitForText(audio.title)
        compose.onNodeWithText(audio.title).performClick()
        compose.onNodeWithText("添加 (1)").performClick()
        waitForRow(audio.id)

        compose.onNodeWithContentDescription("${audio.title}的选项").performClick()
        compose.onNodeWithText("重命名").performClick()
        val renamed = "改名后 $token"
        compose.onNode(hasSetTextAction()).performTextReplacement(renamed)
        compose.onNodeWithText("保存").performClick()
        waitForText(renamed)
        compose.onNodeWithText("添加文件").assertIsDisplayed()
        assertEquals(renamed, runBlocking { graph.dao.mediaByIds(listOf(audio.id)).single().title })

        compose.onNodeWithContentDescription("${renamed}的选项").performClick()
        compose.onNodeWithText("移出集合").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.dao.entries(collection.id).isEmpty() } }
        waitForText("集合为空")
        compose.onNodeWithText("集合为空").assertIsDisplayed()
        assertTrue("Removing a collection entry must preserve its source", files.first().exists())
        assertEquals(1, runBlocking { graph.dao.mediaByIds(listOf(audio.id)).size })
    }

    @Test fun videoExtractionIsAnExplicitTextAction() {
        compose.onNode(hasSetTextAction()).performTextInput(video.title)
        waitForRow(video.id)
        compose.onNodeWithContentDescription("${video.title}的选项").performClick()
        compose.onNodeWithText("提取音频").assertIsDisplayed().performClick()
        compose.onNodeWithText("开始导出").assertIsDisplayed()
        compose.onNodeWithText("文件名称").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("media-${video.id}").assertIsDisplayed()
    }

    @Test fun collectionReorderingFeedsSelectedAudioToConcatenation() {
        runBlocking {
            graph.dao.putEntries(listOf(
                EntryEntity(collectionId = collection.id, mediaId = audio.id, position = 0),
                EntryEntity(collectionId = collection.id, mediaId = secondAudio.id, position = 1),
            ))
        }
        compose.onNode(tabRole and hasText("集合")).performClick()
        waitForText(collection.name)
        compose.onNodeWithText(collection.name).performClick()
        waitForRow(audio.id)
        waitForRow(secondAudio.id)
        compose.onNodeWithText("编辑").performClick()
        // V0.3 moves selected rows together in tap order, instead of one-step arrows.
        compose.onNodeWithTag("media-${secondAudio.id}").performClick()
        compose.onNodeWithContentDescription("批量操作").performClick()
        compose.onNodeWithText("按所选顺序移到最前").performClick()
        compose.waitUntil(10_000) {
            runBlocking { graph.dao.entries(collection.id).map { it.mediaId } == listOf(secondAudio.id, audio.id) }
        }
        compose.onNodeWithTag("media-${secondAudio.id}").performClick()
        compose.onNodeWithTag("media-${audio.id}").performClick()
        compose.onNodeWithText("已选 2").assertIsDisplayed()
        compose.onNodeWithText("拼接音频").performClick()
        compose.onNode(hasText(secondAudio.title) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText(audio.title) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithContentDescription("试听第1项").assertIsDisplayed()
        compose.onNodeWithContentDescription("试听第2项").assertIsDisplayed()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("兼容 M4A") and hasClickAction()).fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
        }
        compose.onNode(hasText("兼容 M4A") and hasClickAction()).performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("confirm-export").fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
        }
        compose.onNodeWithTag("confirm-export").assertIsEnabled()
        // This test covers entry/order selection; export encoding is exercised separately.
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("完成").assertIsDisplayed()
        assertEquals(listOf(secondAudio.id, audio.id), runBlocking { graph.dao.entries(collection.id).map { it.mediaId } })
    }

    @Test fun playbackModeExplainsWhatHappensAtEndOfList() {
        // Keep the library row off screen so the mini player's title is unambiguous.
        compose.onNode(tabRole and hasText("集合")).performClick()
        runBlocking {
            withContext(Dispatchers.Main) {
                graph.playback.play(listOf(video.id), PlaybackMode.REPEAT_ONE)
                graph.playback.connect().pause()
                graph.playback.setMode(PlaybackMode.ORDERED)
            }
        }
        waitForText(video.title)
        compose.onNodeWithText(video.title).performClick()
        waitForText("播完停止")
        compose.onNodeWithText("播完停止").performScrollTo().performClick()
        compose.onNodeWithText("按列表顺序播放，最后一项结束后停止").assertIsDisplayed()
        compose.onNodeWithText("按列表顺序播放，播完后从头开始").assertIsDisplayed()
        compose.onNodeWithText("列表循环").performClick()
        compose.waitUntil(10_000) { graph.playback.state.value.mode == PlaybackMode.REPEAT_ALL }
        compose.onNodeWithText("列表循环").assertIsDisplayed()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForRow(id: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-$id").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun fixture(asset: String, isVideo: Boolean): MediaEntity {
        val file = File(context.filesDir, "ui-$token-$asset")
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        files += file
        return MediaEntity("ui-$token-$asset", Uri.fromFile(file).toString(),
            "UI${if (isVideo) "视频" else if (asset == "stereo48.wav") "第二音频" else "音频"} $token",
            if (isVideo) 10_000 else if (asset == "stereo48.wav") 3000 else 2000, isVideo, source = "ui-test")
    }
}

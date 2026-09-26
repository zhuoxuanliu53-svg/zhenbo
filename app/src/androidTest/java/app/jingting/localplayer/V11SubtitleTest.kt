package app.jingting.localplayer

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jingting.localplayer.subtitle.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.content.Intent
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import app.jingting.localplayer.data.MediaEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@RunWith(AndroidJUnit4::class)
class V11SubtitleTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun offlineOcrRecognizesChineseEnglishJapanese() {
        val started=android.os.SystemClock.elapsedRealtime()
        OfflineOcr(context).use { ocr ->
            for((language,expected) in mapOf("zh" to "请闭上眼睛，慢慢放松。","en" to "Close your eyes and relax.","ja" to "目を閉じて、ゆっくり休んでください。")) {
                val bitmap=instrumentation.context.assets.open("subtitles/$language.png").use(BitmapFactory::decodeStream)
                val crop=VideoFrames.crop(bitmap,SubtitleRegion())
                val result=ocr.recognize(crop)
                android.util.Log.i("V11_TEST","$language OCR: $result")
                assertEquals(language,expected,result)
                crop.recycle();bitmap.recycle()
            }
            instrumentation.sendStatus(0,android.os.Bundle().apply {putLong("ocrThreeImagesElapsedMs",android.os.SystemClock.elapsedRealtime()-started);putLong("ocrProcessPssKb",android.os.Debug.getPss())})
        }
    }
    @Test fun embeddedSubRipAssTx3gPreserveTextAndTiming() = runBlocking {
        val reader=EmbeddedSubtitles(context)
        for(name in listOf("embedded-srt.mkv","embedded-ass.mkv","embedded-mov_text.mp4","embedded-wvtt.mp4")) {
            val file=fixture(name)
            try {
                val tracks=reader.inspect(Uri.fromFile(file).toString())
                assertEquals("$name: $tracks",1,tracks.count {it.supported})
                val cues=reader.extract(Uri.fromFile(file).toString(),tracks.single{it.supported}.id,10000000)
                assertEquals(name,2,cues.size)
                assertEquals("First subtitle",cues[0].text)
                assertTrue("$name ${cues[0]}",kotlin.math.abs(cues[0].startUs-1000000)<50000)
                assertTrue("$name first end ${cues[0]}",kotlin.math.abs(cues[0].endUs-2500000)<50000)
                assertEquals("第二行字幕\nAnother line",cues[1].text)
                assertTrue("$name second ${cues[1]}",kotlin.math.abs(cues[1].startUs-3000000)<50000)
                assertTrue("$name second end ${cues[1]}",kotlin.math.abs(cues[1].endUs-4500000)<50000)
            } finally {file.delete()}
        }
    }
    @Test fun sequentialVideoOcrWorksWithoutGoogleServices()= runBlocking {
        val file=fixture("burned.mp4")
        val started=android.os.SystemClock.elapsedRealtime()
        try {
            val cues=VideoFrames.scan(context,Uri.fromFile(file).toString(),3000000,SubtitleRegion()) { }
            assertEquals(1,cues.size)
            assertEquals("请闭上眼睛，慢慢放松。",cues.single().text)
            assertTrue(cues.single().endUs>=2500000)
            instrumentation.sendStatus(0,android.os.Bundle().apply {putLong("ocrThreeSecondVideoElapsedMs",android.os.SystemClock.elapsedRealtime()-started);putLong("videoOcrProcessPssKb",android.os.Debug.getPss())})
        } finally {file.delete()}
    }
    private fun fixture(name:String):File = File(context.cacheDir,name).apply {instrumentation.context.assets.open("subtitles/$name").use {input -> outputStream().use {input.copyTo(it)}}}

    @Test fun migrationFromVersionThreePreservesCollections() = runBlocking {
        val name="v11-migration-test.db";context.deleteDatabase(name)
        val schema=org.json.JSONObject(instrumentation.context.assets.open("subtitles/schema3.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use {db ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i);val table=e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=e.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length())db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES(42,?)",arrayOf(schema.getString("identityHash")))
            db.execSQL("INSERT INTO collections VALUES('keep','保留集合',123)")
            db.version=3
        }
        val migrated=androidx.room.Room.databaseBuilder(context,app.jingting.localplayer.data.AppDatabase::class.java,name).addMigrations(app.jingting.localplayer.data.AppDatabase.MIGRATION_3_4).build()
        try {
            assertEquals("保留集合",migrated.dao().collection("keep")?.name)
            migrated.dao().putSubtitle(app.jingting.localplayer.data.SubtitleEntity("test","1\n00:00:00,000 --> 00:00:01,000\nhello\n"))
            assertNotNull(migrated.dao().subtitle("test"))
        } finally {migrated.close();context.deleteDatabase(name)}
    }

    @Test fun pairedExportAndSubtitleRetryPreserveAudio() = runBlocking {
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val graph=context.graph
        val file=fixture("embedded-mov_text.mp4")
        val id="v11-pair"
        try {
            instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Music/ZhenboSubtitleTests").close()
            var selected: Uri?=null
            scenario.onActivity {activity ->
                val launcher=activity.activityResultRegistry.register("v11-tree",ActivityResultContracts.OpenDocumentTree()) {uri -> if(uri!=null){graph.subtitles.authorizeDirectory(uri);selected=uri} }
                launcher.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Music/ZhenboSubtitleTests"))
            }
            withTimeout(30000) {
                fun findButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if(node == null) return null
                    if(node.text?.toString()?.uppercase() in setOf("USE THIS FOLDER","ALLOW")) return node
                    for(i in 0 until node.childCount) findButton(node.getChild(i))?.let { return it }
                    return null
                }
                while(selected==null) {
                    findButton(instrumentation.uiAutomation.rootInActiveWindow)?.let { label ->
                        generateSequence(label) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    delay(300)
                }
            }
            graph.dao.putMedia(listOf(MediaEntity(id,Uri.fromFile(file).toString(),"字幕测试",10000,true,source="test")))
            val tracks=graph.subtitles.embedded.inspect(Uri.fromFile(file).toString())
            val sources=listOf(SubtitleSource(id,"EMBEDDED",tracks.single{it.supported}.id))
            val taskId=withContext(Dispatchers.Main) {graph.exports.enqueueSubtitles(listOf(id),"字幕配对测试",false,"ORIGINAL",SubtitleExportOptions(selected.toString(),sources))}
            withTimeout(120000) {while(graph.dao.export(taskId)!!.state !in setOf("completed","failed")) delay(200)}
            val task=graph.dao.export(taskId)!!
            assertEquals(task.error,"completed",task.state)
            assertNotNull(task.subtitleUri)
            val exported=context.contentResolver.openInputStream(Uri.parse(task.subtitleUri))!!.bufferedReader().use{it.readText()}
            assertEquals(2,SubRip.decode(exported).size)
            val noCaptionId=withContext(Dispatchers.Main) {graph.exports.enqueueSubtitles(listOf(id),"无字幕同目录测试",false,"ORIGINAL",SubtitleExportOptions(selected.toString(),listOf(SubtitleSource(id))))}
            withTimeout(120000) {while(graph.dao.export(noCaptionId)!!.state !in setOf("completed","failed"))delay(200)}
            val noCaption=graph.dao.export(noCaptionId)!!
            assertEquals(noCaption.error,"completed",noCaption.state)
            assertNull(noCaption.subtitleUri)
            assertTrue(noCaption.outputUri!!.startsWith(selected.toString()+"/document/"))
            val failedId=withContext(Dispatchers.Main) {graph.exports.enqueueSubtitles(listOf(id),"字幕失败测试",false,"ORIGINAL",SubtitleExportOptions(selected.toString(),listOf(SubtitleSource(id,"EMBEDDED",9999))))}
            withTimeout(120000) {while(graph.dao.export(failedId)!!.state !in setOf("completed","failed")) delay(200)}
            val failed=graph.dao.export(failedId)!!
            assertEquals("failed",failed.state);assertNotNull(failed.outputUri)
            val before=context.contentResolver.openInputStream(Uri.parse(failed.outputUri))!!.use{java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes())}
            graph.dao.putExport(failed.copy(subtitleOptions=SubtitleOptionsJson.encode(SubtitleExportOptions(selected.toString(),sources))))
            withContext(Dispatchers.Main){graph.exports.retrySubtitles(failedId)}
            withTimeout(120000) {while(graph.dao.export(failedId)!!.state !in setOf("completed","failed")) delay(200)}
            val retried=graph.dao.export(failedId)!!
            assertEquals(retried.error,"completed",retried.state);assertEquals(failed.outputUri,retried.outputUri)
            val after=context.contentResolver.openInputStream(Uri.parse(retried.outputUri))!!.use{java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes())}
            assertArrayEquals(before,after)
            val secondId="v11-pair-second"; val silentId="v11-pair-gap"
            graph.dao.putMedia(listOf(MediaEntity(secondId,Uri.fromFile(file).toString(),"字幕第二段",10000,true,source="test"),MediaEntity(silentId,Uri.fromFile(file).toString(),"无字幕间隔",10000,true,source="test")))
            for(mode in listOf("ORIGINAL","AAC")) {
                val mixed=listOf(sources.single(),SubtitleSource(silentId),sources.single().copy(mediaId=secondId))
                val concatId=withContext(Dispatchers.Main) {graph.exports.enqueueSubtitles(listOf(id,silentId,secondId),"字幕拼接-$mode",true,mode,SubtitleExportOptions(selected.toString(),mixed))}
                withTimeout(180000){while(graph.dao.export(concatId)!!.state !in setOf("completed","failed"))delay(200)}
                val concatenated=graph.dao.export(concatId)!!
                assertEquals(concatenated.error,"completed",concatenated.state)
                val concatCues=SubRip.decode(context.contentResolver.openInputStream(Uri.parse(concatenated.subtitleUri))!!.bufferedReader().use{it.readText()})
                assertEquals(4,concatCues.size)
                assertTrue(concatCues[2].startUs in 20900000..21300000)
                assertEquals("",SubRip.current(concatCues,15000000))
            }
            withTimeout(10000) { while(app.jingting.localplayer.export.AudioExportService.running) delay(100) }
            val root=androidx.documentfile.provider.DocumentFile.fromTreeUri(context,selected!!)!!
            val partial=root.createFile("application/x-subrip","interrupted-${System.nanoTime()}.srt")!!
            context.contentResolver.openOutputStream(partial.uri)!!.use { it.write("unfinished".toByteArray()) }
            val interrupted=task.copy(id="v11-interrupted",state="running",subtitleUri=null,publication=org.json.JSONObject().put("pending",partial.uri.toString()).put("subtitleName","recovered.srt").toString())
            graph.dao.putExport(interrupted)
            app.jingting.localplayer.export.ExportManager(context,graph).recover()
            assertFalse(partial.exists())
            assertEquals(task.outputUri,graph.dao.export(interrupted.id)!!.outputUri)
            assertEquals("failed",graph.dao.export(interrupted.id)!!.state)
            val pairedMedia=graph.media.importLocal(Uri.parse(task.outputUri),Intent.FLAG_GRANT_READ_URI_PERMISSION)
            graph.db.openHelper.writableDatabase.execSQL("DELETE FROM subtitles WHERE mediaId=?",arrayOf(pairedMedia.id))
            assertNull(graph.dao.subtitle(pairedMedia.id))
            assertTrue(graph.subtitles.matchDirectory(selected!!) > 0)
            assertEquals(2,SubRip.decode(graph.dao.subtitle(pairedMedia.id)!!.srt).size)
            val base="ambiguous-${System.nanoTime()}"
            val ambiguousAudio=root.createFile("audio/mp4","$base.m4a")!!
            val ambiguousWave=root.createFile("audio/wav","$base.wav")!!
            val ambiguousSrt=root.createFile("application/x-subrip","$base.srt")!!
            try {
                context.contentResolver.openInputStream(Uri.parse(task.outputUri))!!.use { input -> context.contentResolver.openOutputStream(ambiguousAudio.uri)!!.use { input.copyTo(it) } }
                instrumentation.context.assets.open("stereo48.wav").use { input -> context.contentResolver.openOutputStream(ambiguousWave.uri)!!.use { input.copyTo(it) } }
                context.contentResolver.openOutputStream(ambiguousSrt.uri)!!.use { it.write(exported.toByteArray()) }
                val a=graph.media.importLocal(ambiguousAudio.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val b=graph.media.importLocal(ambiguousWave.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                graph.subtitles.matchDirectory(selected!!)
                assertNull(graph.dao.subtitle(a.id));assertNull(graph.dao.subtitle(b.id))
            } finally {ambiguousAudio.delete();ambiguousWave.delete();ambiguousSrt.delete()}
        } finally {scenario.close();file.delete();graph.dao.availability(id,false)}
    }

    @Test fun purePageRemainsVisibleBehindSecureKeyguardWithoutUnlocking() = runBlocking<Unit> {
        // This case is intentionally restricted to an emulator, never a user's phone.
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        fun shell(command:String):String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText()}
        val keyguard=context.getSystemService(android.app.KeyguardManager::class.java)
        org.junit.Assume.assumeFalse(keyguard.isDeviceSecure)
        val initiallyDisabled=shell("locksettings get-disabled").trim()=="true"
        val home=ActivityScenario.launch(MainActivity::class.java)
        val mediaFile=fixture("embedded-mov_text.mp4")
        context.graph.dao.putMedia(listOf(MediaEntity("v11-lock",Uri.fromFile(mediaFile).toString(),"锁屏测试",10000,true,source="test")))
        context.graph.dao.putSubtitle(app.jingting.localplayer.data.SubtitleEntity("v11-lock",SubRip.encode(listOf(SubtitleCue(0,10000000,"锁屏字幕测试")))))
        withContext(Dispatchers.Main){context.graph.playback.play(listOf("v11-lock"),app.jingting.localplayer.core.PlaybackMode.REPEAT_ONE)}
        withTimeout(10000) { context.graph.playback.state.first { it.isPlaying && it.ids.getOrNull(it.index)=="v11-lock" } }
        val scenario=ActivityScenario.launch(app.jingting.localplayer.ui.PurePlaybackActivity::class.java)
        try {
            shell("locksettings set-pin 1234")
            assertTrue(keyguard.isDeviceSecure)
            shell("input keyevent 223");delay(500);shell("input keyevent 224")
            withTimeout(10000){while(!keyguard.isKeyguardLocked)delay(100)}
            delay(1000)
            assertTrue(keyguard.isDeviceLocked)
            scenario.onActivity { assertTrue(it.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED));assertEquals(0,it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val root=instrumentation.uiAutomation.rootInActiveWindow
            assertEquals(context.packageName,root?.packageName?.toString())
            // Compose exposes virtual accessibility children; search the actual tree
            // rather than relying on a provider's optional find-by-text implementation.
            fun containsCaption(node: AccessibilityNodeInfo?): Boolean {
                if(node == null) return false
                if(node.text?.toString()?.contains("锁屏字幕测试") == true) return true
                return (0 until node.childCount).any { containsCaption(node.getChild(it)) }
            }
            val captionVisible=withTimeoutOrNull(5000) {
                while(!containsCaption(instrumentation.uiAutomation.rootInActiveWindow)) delay(100)
                true
            } == true
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                File(context.getExternalFilesDir(null),"v11-pure-locked.png").outputStream().use {screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                screenshot.recycle()
            }
            assertTrue("Caption missing; playback=${context.graph.playback.state.value}; cached=${context.graph.dao.subtitle("v11-lock") != null}",captionVisible)
            assertTrue(keyguard.isDeviceLocked)
            fun playbackButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if(node == null) return null
                if(node.contentDescription?.toString()=="播放或暂停") return node
                for(i in 0 until node.childCount) playbackButton(node.getChild(i))?.let { return it }
                return null
            }
            fun toggleOnLockedPage() {
                val icon=playbackButton(instrumentation.uiAutomation.rootInActiveWindow) ?: error("Missing playback control")
                val button=generateSequence(icon) { it.parent }.first { it.isClickable }
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            toggleOnLockedPage()
            withTimeout(5000) { context.graph.playback.state.first { !it.isPlaying } }
            assertTrue(keyguard.isDeviceLocked)
            toggleOnLockedPage()
            withTimeout(5000) { context.graph.playback.state.first { it.isPlaying } }
            assertTrue(keyguard.isDeviceLocked)
        } finally {
            shell("locksettings clear --old 1234")
            shell("locksettings set-disabled $initiallyDisabled")
            withTimeout(5000) { while(keyguard.isDeviceSecure) delay(100) }
            shell("wm dismiss-keyguard")
            scenario.close();withContext(Dispatchers.Main){context.graph.playback.pauseIfConnected()};home.close();mediaFile.delete()
        }
    }
}

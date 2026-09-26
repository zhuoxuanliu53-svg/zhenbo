package app.jingting.localplayer.export

import android.content.*
import android.media.*
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.jingting.localplayer.*
import app.jingting.localplayer.core.MediaRef
import app.jingting.localplayer.data.*
import app.jingting.localplayer.subtitle.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File

/** Captioned exports retain their audio and timing checkpoint when only subtitle work fails. */
class SubtitleExportRunner(private val context: Context, private val encode: suspend (AudioExportPlan, File, String) -> Unit, private val notify: (String, String, Int) -> Unit) {
    private val graph = context.graph
    suspend fun run(initial: ExportEntity) {
        val options = SubtitleOptionsJson.decode(initial.subtitleOptions!!)
        var task = initial
        val dir = File(context.noBackupFilesDir, "subtitle_jobs/${initial.id}").apply { mkdirs() }
        val checkpoint = File(dir, "timeline.json")
        var audioUri = initial.outputUri?.let(Uri::parse)
        val started = android.os.SystemClock.elapsedRealtime()
        suspend fun update(phase: String, percent: Int) {
            task = task.copy(state = "running", phase = phase, progress = percent)
            graph.dao.putExport(task); notify(phase, task.id, percent)
        }
        try {
            val rootNames = SubtitlePairStorage(context, options.directory)
            task = task.copy(publication = withContext(Dispatchers.IO) { rootNames.clearPending(task.publication) })
            graph.dao.putExport(task)
            val ids = JSONArray(task.sourceIds).let { array -> List(array.length()) { array.getString(it) } }
            val sources = graph.media.resolve(ids); require(sources.size == ids.size) { "部分源文件已移除" }
            val fullPlan = withContext(Dispatchers.IO) { OriginalAudioExporter.inspect(context, sources, task.exportMode) }
            task = task.copy(strategy = fullPlan.strategy)
            val output = File(dir, "audio.${fullPlan.extension}")
            val timeline: ExportTimeline
            if (audioUri != null && checkpoint.isFile) {
                context.contentResolver.openAssetFileDescriptor(audioUri, "r")?.close() ?: error("已导出的音频不可访问")
                val a = JSONArray(checkpoint.readText()); timeline = ExportTimeline(List(a.length()) { i -> a.getJSONObject(i).let { ExportSegment(it.getString("id"), it.getLong("start"), it.getLong("duration"), it.getLong("offset")) } })
            } else {
                require(context.filesDir.usableSpace > fullPlan.estimatedBytes * 3 + 32 * 1024 * 1024) { "存储空间不足" }
                val parts = mutableListOf<MediaRef>(); val segments = mutableListOf<ExportSegment>(); var offset = 0L
                for ((index, source) in sources.withIndex()) {
                    update("转换音频 ${index + 1}/${sources.size}", 0)
                    val inspected = withContext(Dispatchers.IO) { OriginalAudioExporter.inspect(context, listOf(source), task.exportMode) }
                    val partPlan = if (fullPlan.strategy == "PCM_WAV") inspected.copy(strategy="PCM_WAV",extension="wav",mimeType="audio/wav") else inspected
                    val part = File(dir, "part-$index.${partPlan.extension}")
                    if (partPlan.strategy == "ENCODE_AAC") encode(partPlan, part, task.id)
                    else withContext(Dispatchers.IO) { OriginalAudioExporter.write(context, partPlan, part) { update("转换音频 ${index + 1}/${sources.size}", it) } }
                    val sourceStart = withContext(Dispatchers.IO) { audioBounds(source.locator).first }
                    val bounds = withContext(Dispatchers.IO) { audioBounds(Uri.fromFile(part).toString()) }
                    segments += ExportSegment(source.id, sourceStart, bounds.second, offset); offset += bounds.second
                    parts += MediaRef("part-$index", Uri.fromFile(part).toString(), "", bounds.second / 1000, false)
                }
                update("合并音频", 0)
                if (parts.size == 1) withContext(Dispatchers.IO) { File(Uri.parse(parts.single().locator).path!!).copyTo(output, overwrite = true) }
                else withContext(Dispatchers.IO) { OriginalAudioExporter.write(context, OriginalAudioExporter.inspect(context, parts, "ORIGINAL"), output) { update("合并音频", it) } }
                timeline = ExportTimeline(segments)
                checkpoint.writeText(JSONArray().apply { segments.forEach { put(JSONObject().put("id",it.mediaId).put("start",it.sourceStartUs).put("duration",it.durationUs).put("offset",it.outputStartUs)) } }.toString())
                update("保存音频", 0)
                val (root, name, subtitleName) = withContext(Dispatchers.IO) { rootNames.createNames(task.title, fullPlan.extension) }
                val document = withContext(Dispatchers.IO) { root.createFile(fullPlan.mimeType, name) ?: error("无法创建音频文件") }
                task = task.copy(publication = JSONObject().put("pending",document.uri.toString()).put("subtitleName",subtitleName).toString()); graph.dao.putExport(task)
                rootNames.copy(output, document.uri)
                withContext(NonCancellable) {
                    audioUri = document.uri
                    task = task.copy(outputUri = audioUri.toString(), publication = JSONObject().put("subtitleName",subtitleName).toString()); graph.dao.putExport(task)
                }
                // The checkpoint and completed audio are enough for subtitle-only retries.
                withContext(Dispatchers.IO) { dir.listFiles()?.filter { it.extension != "json" }?.forEach { it.delete() } }
            }
            val media = graph.media.importLocal(audioUri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // A batch may mix captioned videos with explicitly caption-free files.
            // All outputs still belong in the directory selected for that batch.
            if (options.sources.all { it.mode == "NONE" }) {
                task = task.copy(state="completed", subtitleState="none", progress=100, phase="", error=null, publication=null, elapsedMs=android.os.SystemClock.elapsedRealtime()-started)
                graph.dao.putExport(task)
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
                return
            }
            val extracted = graph.subtitles.extract(options) { phase, percent -> update(phase, percent) }
            val cues = timeline.map(extracted)
            require(cues.isNotEmpty()) { "未提取到字幕" }
            val srt = SubRip.encode(cues); val file = File(dir,"subtitle.srt"); withContext(Dispatchers.IO) { file.writeText(srt) }
            update("保存字幕", 99)
            val root = DocumentFile.fromTreeUri(context, Uri.parse(options.directory)) ?: error("保存目录不可访问")
            val subtitleName = task.publication?.let { JSONObject(it).optString("subtitleName") }.orEmpty().ifBlank { task.title + ".srt" }
            require(root.findFile(subtitleName) == null) { "同名字幕已存在，请先重命名该文件再重试" }
            val document = root.createFile("application/x-subrip", subtitleName) ?: error("无法创建字幕文件")
            task = task.copy(publication = JSONObject().put("pending",document.uri.toString()).put("subtitleName",subtitleName).toString()); graph.dao.putExport(task)
            rootNames.copy(file, document.uri)
            withContext(NonCancellable) {
                graph.dao.putSubtitle(SubtitleEntity(media.id, srt, document.uri.toString()))
                task = task.copy(state="completed", subtitleState="completed", subtitleUri=document.uri.toString(), progress=100, phase="", error=null, publication=null, elapsedMs=android.os.SystemClock.elapsedRealtime()-started)
                graph.dao.putExport(task)
            }
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                // Keep the journal when permission was revoked; retry after reauthorization
                // must still know which incomplete document belongs to this task.
                val remainingPublication = withContext(Dispatchers.IO) {
                    runCatching { SubtitlePairStorage(context, options.directory).clearPending(task.publication) }.getOrDefault(task.publication)
                }
                audioUri?.let { runCatching { graph.media.importLocal(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                val cancelled = e is CancellationException
                graph.dao.putExport(task.copy(state=if(cancelled) "cancelled" else "failed", outputUri=audioUri?.toString(), subtitleState=if(audioUri != null) "failed" else "pending", error=if(audioUri != null) "音频已保存，字幕${if(cancelled) "已取消" else "失败"}" else "导出未完成，请检查文件或保存目录", phase="", publication=remainingPublication))
                if(audioUri == null) withContext(Dispatchers.IO) { dir.deleteRecursively() }
            }
            if (e is CancellationException) throw e
        }
    }
    private suspend fun audioBounds(uri: String): Pair<Long, Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val format = extractor.getTrackFormat(index); extractor.selectTrack(index)
            val first = extractor.sampleTime.coerceAtLeast(0)
            if (format.getString(MediaFormat.KEY_MIME) == "audio/raw") return first to format.getLong(MediaFormat.KEY_DURATION)
            var previous = first; var last = first; var step = 1024L * 1000000 / format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            while (extractor.sampleTrackIndex >= 0) { currentCoroutineContext().ensureActive(); last = extractor.sampleTime; if(last > previous) step = last - previous; previous=last; extractor.advance() }
            return first to (last - first + step)
        } finally { extractor.release() }
    }
}

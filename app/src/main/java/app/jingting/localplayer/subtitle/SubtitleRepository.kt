package app.jingting.localplayer.subtitle

import android.content.*
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.jingting.localplayer.AppGraph
import app.jingting.localplayer.data.SubtitleEntity
import kotlinx.coroutines.*
import org.json.*
import java.io.File

object SubtitleOptionsJson {
    fun encode(options: SubtitleExportOptions): String = JSONObject().put("directory", options.directory).put("sources", JSONArray().apply {
        options.sources.forEach { s -> put(JSONObject().put("id", s.mediaId).put("mode", s.mode).put("track", s.trackId).put("region", JSONArray(listOf(s.region.left, s.region.top, s.region.right, s.region.bottom)))) }
    }).toString()
    fun decode(value: String): SubtitleExportOptions {
        val json = JSONObject(value); val sources = json.getJSONArray("sources")
        return SubtitleExportOptions(json.getString("directory"), List(sources.length()) { i ->
            val s = sources.getJSONObject(i); val r = s.getJSONArray("region")
            SubtitleSource(s.getString("id"), s.getString("mode"), s.getInt("track"), SubtitleRegion(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat()))
        })
    }
}

class SubtitleRepository(private val context: Context, private val graph: AppGraph) {
    val embedded = EmbeddedSubtitles(context)
    private val prefs = context.getSharedPreferences("subtitle_options", Context.MODE_PRIVATE)
    val directory: String? get() = prefs.getString("directory", null)
    fun authorizeDirectory(uri: Uri) {
        require(uri.authority == "com.android.externalstorage.documents") { "请选择手机本地文件夹" }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(DocumentFile.fromTreeUri(context, uri)?.canWrite() == true) { "文件夹不可写入" }
        prefs.edit().putString("directory", uri.toString()).apply()
    }
    suspend fun importSrt(mediaId: String, uri: Uri) = withContext(Dispatchers.IO) {
        require(uri.authority in setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents", "com.android.providers.media.documents")) { "请选择本地字幕文件" }
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192); val output = java.io.ByteArrayOutputStream()
            while (true) { currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0)break; require(output.size()+n<=1024*1024) { "字幕文件过大" }; output.write(buffer,0,n) }
            output.toByteArray().toString(Charsets.UTF_8)
        } ?: error("字幕文件无法读取")
        val cues = SubRip.decode(text); require(cues.isNotEmpty()) { "没有可读取的 SRT 字幕" }
        graph.dao.putSubtitle(SubtitleEntity(mediaId, SubRip.encode(cues), uri.toString()))
    }
    suspend fun setOffset(mediaId: String, delta: Long) { graph.dao.subtitle(mediaId)?.let { graph.dao.putSubtitle(it.copy(offsetMs = (it.offsetMs + delta).coerceIn(-60000,60000))) } }
    suspend fun extract(options: SubtitleExportOptions, progress: suspend (String, Int) -> Unit): Map<String, List<SubtitleCue>> {
        val result = linkedMapOf<String, List<SubtitleCue>>()
        for ((index, option) in options.sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (option.mode == "NONE") { result[option.mediaId] = emptyList(); continue }
            val item = graph.media.resolve(listOf(option.mediaId)).single()
            progress("提取字幕 ${index + 1}/${options.sources.size}", 0)
            val cues = when (option.mode) {
                "EMBEDDED" -> embedded.extract(item.locator, option.trackId, item.durationMs * 1000)
                "OCR" -> VideoFrames.scan(context, item.locator, item.durationMs * 1000, option.region) { progress("识别字幕 ${index + 1}/${options.sources.size}", it) }
                else -> error("未知字幕来源")
            }
            require(cues.isNotEmpty()) { "未提取到字幕，请检查字幕轨或识别区域" }
            result[item.id] = cues
            graph.dao.putSubtitle(SubtitleEntity(item.id, SubRip.encode(cues)))
        }
        return result
    }
    suspend fun matchDirectory(uri: Uri): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, uri) ?: error("文件夹不可访问")
        val files = root.listFiles().filter { it.isFile }
        val subtitles = files.filter { it.name?.endsWith(".srt", true) == true }.groupBy { it.name!!.substringBeforeLast('.').lowercase() }
        val audioFiles = files.filter { it.type?.startsWith("audio/") == true && it.name != null }
        val audioGroups = audioFiles.groupBy { it.name!!.substringBeforeLast('.').lowercase() }
        var matched = 0
        for (audio in audioFiles) {
            val base = audio.name!!.substringBeforeLast('.').lowercase()
            val candidates = subtitles[base].orEmpty()
            // A same-name MP3 and M4A are ambiguous even if there is only one SRT.
            if (candidates.size != 1 || audioGroups[base]?.size != 1) continue
            try {
                val media = graph.media.importLocal(audio.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                importSrt(media.id, candidates.single().uri); matched++
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Manual import remains available for ambiguous or inaccessible files. */ }
        }
        matched
    }
}

/** Journals each created URI before writing. Existing user files are never opened for output. */
class SubtitlePairStorage(private val context: Context, private val directory: String) {
    fun clearPending(publication: String?): String? {
        if (publication == null) return null
        val journal = JSONObject(publication)
        if (!journal.has("pending")) return publication
        val root = DocumentFile.fromTreeUri(context, Uri.parse(directory)) ?: error("请重新授权保存目录")
        require(root.canWrite()) { "请重新授权保存目录" }
        val pending = DocumentFile.fromSingleUri(context, Uri.parse(journal.getString("pending"))) ?: error("无法清理未完成文件")
        require(!pending.exists() || pending.delete()) { "无法清理未完成文件" }
        journal.remove("pending")
        return journal.toString()
    }
    fun createNames(title: String, extension: String): Triple<DocumentFile, String, String> {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(directory)) ?: error("请重新授权保存目录")
        require(root.canWrite()) { "请重新授权保存目录" }
        val names = root.listFiles().mapNotNull { it.name?.lowercase() }.toSet()
        var base = title; var number = 1
        while ("$base.$extension".lowercase() in names || "$base.srt".lowercase() in names) { base = "$title (${number++})" }
        return Triple(root, "$base.$extension", "$base.srt")
    }
    suspend fun copy(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(bytes); if (n < 0) break; output.write(bytes, 0, n) }
            output.flush()
        } } ?: error("无法写入保存目录")
    }
}

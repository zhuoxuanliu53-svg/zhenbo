package app.jingting.localplayer.export

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.jingting.localplayer.*
import app.jingting.localplayer.core.*
import app.jingting.localplayer.data.ExportEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ExportPreview(val originalAvailable: Boolean, val originalDescription: String, val reason: String?, val aacDescription: String = "M4A · AAC 256 kbps，重新编码")

class ExportManager(private val context: Context, private val graph: AppGraph) : ExportPort {
    private val recoveryMutex = Mutex()
    private var recovered = false
    private val cancelRequests = ConcurrentHashMap.newKeySet<String>()
    fun cancellationRequested(id: String) = id in cancelRequests
    suspend fun preview(ids: List<String>): ExportPreview = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val sources = graph.media.resolve(ids)
        require(sources.size == ids.size) { "部分源文件已移除" }
        OriginalAudioExporter.inspect(context, sources, "AAC")
        try {
            val plan = OriginalAudioExporter.inspect(context, sources, "ORIGINAL")
            ExportPreview(true, plan.description, null)
        } catch (e: IllegalArgumentException) {
            ExportPreview(false, "不能原样合并", e.message)
        }
    }
    override suspend fun enqueue(ids: List<String>, title: String, concatenate: Boolean, exportMode: String): String {
        recover()
        require(ids.isNotEmpty() && (!concatenate || ids.size >= 2)) { "拼接至少需要两个文件" }
        require(exportMode in setOf("ORIGINAL", "AAC")) { "未知导出格式" }
        val item = ExportEntity(sourceIds = JSONArray(ids).toString(), title = ExportNames.normalize(title), concatenate = concatenate, exportMode = exportMode)
        graph.dao.putExport(item)
        try { ContextCompat.startForegroundService(context, Intent(context, AudioExportService::class.java)) }
        catch (_: Exception) { graph.dao.putExport(item.copy(state = "failed", error = "系统阻止导出任务，请在应用内重试")) }
        return item.id
    }
    suspend fun enqueueSubtitles(ids: List<String>, title: String, concatenate: Boolean, mode: String, options: app.jingting.localplayer.subtitle.SubtitleExportOptions): String {
        recover()
        require(ids.isNotEmpty() && options.sources.map { it.mediaId } == ids)
        val item = ExportEntity(sourceIds = JSONArray(ids).toString(), title = ExportNames.normalize(title), concatenate = concatenate, exportMode = mode, subtitleOptions = app.jingting.localplayer.subtitle.SubtitleOptionsJson.encode(options), subtitleState = "pending")
        graph.dao.putExport(item)
        try { ContextCompat.startForegroundService(context, Intent(context, AudioExportService::class.java)) }
        catch (_: Exception) { graph.dao.putExport(item.copy(state = "failed", error = "系统阻止任务启动，请重试")) }
        return item.id
    }
    suspend fun retrySubtitles(id: String) {
        val item = graph.dao.export(id) ?: return
        require(item.subtitleOptions != null && item.state in setOf("failed", "cancelled"))
        cancelRequests.remove(id)
        graph.dao.putExport(item.copy(state = "queued", error = null))
        try { ContextCompat.startForegroundService(context, Intent(context, AudioExportService::class.java)) }
        catch (_: Exception) { graph.dao.putExport(item.copy(state = "failed", error = "系统阻止任务启动")) }
    }
    override fun cancel(id: String) {
        // Remember synchronously: the foreground service may not have been created yet.
        cancelRequests += id
        if (AudioExportService.running) context.startService(Intent(context, AudioExportService::class.java).setAction(AudioExportService.CANCEL).putExtra("id", id))
        else graph.scope.launch { graph.dao.export(id)?.let { if (it.state in setOf("queued", "running", "publishing")) graph.dao.putExport(it.copy(state = "cancelled")) } }
    }
    suspend fun recover() = recoveryMutex.withLock {
        if (recovered) return@withLock
        val finished = withContext(Dispatchers.IO) {
        if (AudioExportService.running) return@withContext false
        for (e in graph.dao.activeExports()) {
            if (e.subtitleOptions != null) {
                val remainingPublication = runCatching {
                    val options = app.jingting.localplayer.subtitle.SubtitleOptionsJson.decode(e.subtitleOptions)
                    app.jingting.localplayer.subtitle.SubtitlePairStorage(context, options.directory).clearPending(e.publication)
                }.getOrDefault(e.publication)
                graph.dao.putExport(e.copy(state="failed", subtitleState="failed", error=if(e.outputUri != null) "音频已保存，字幕处理被中断" else "上次任务被中断，请重试", publication=remainingPublication))
                continue
            }
            var published: String? = null
            e.pendingUri?.let { locator ->
                runCatching {
                    val uri = Uri.parse(locator)
                    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { c ->
                        if (c.moveToFirst()) { if (c.getInt(0) == 0) published = locator else context.contentResolver.delete(uri, null, null) }
                    }
                }
            }
            taskDir(e.id).deleteRecursively()
            graph.dao.putExport(e.copy(state = if (published != null) "completed" else "failed", progress = if (published != null) 100 else e.progress,
                outputUri = published, pendingUri = null, error = if (published == null) "上次导出被中断，请重新导出" else null))
            published?.let { runCatching { graph.media.registerExport(Uri.parse(it)) } }
        }
        // Also recover the small crash windows before a pending URI is recorded
        // and after publication but before the private temporary directory is removed.
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            @Suppress("DEPRECATION")
            val queryUri = if (Build.VERSION.SDK_INT >= 30) collection else MediaStore.setIncludePending(collection)
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName, "Music/静听/"))
                if (Build.VERSION.SDK_INT >= 30) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(queryUri, arrayOf(MediaStore.Audio.Media._ID), args, null)?.use { cursor ->
                val orphaned = mutableListOf<Uri>()
                while (cursor.moveToNext()) orphaned += ContentUris.withAppendedId(collection, cursor.getLong(0))
                orphaned.forEach { context.contentResolver.delete(it, null, null) }
            }
        }
        val temporaryRoot = File(context.noBackupFilesDir, "exports").canonicalFile
        temporaryRoot.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("[a-zA-Z0-9-]+")) && it.canonicalFile.parentFile == temporaryRoot }?.forEach { it.deleteRecursively() }
        true
        }
        recovered = finished
    }
    fun taskDir(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(context.noBackupFilesDir, "exports/$id")
    }
}

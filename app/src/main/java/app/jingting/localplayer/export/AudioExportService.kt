@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.jingting.localplayer.export

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.*
import androidx.media3.common.audio.*
import androidx.media3.transformer.*
import app.jingting.localplayer.*
import app.jingting.localplayer.R
import app.jingting.localplayer.data.ExportEntity
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AudioExportService : Service() {
    companion object { const val CANCEL = "jingting.cancel.export"; @Volatile var running = false; private const val CHANNEL = "audio_exports" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null
    private var currentJob: Job? = null
    private var currentId: String? = null
    private var transformer: Transformer? = null
    private var timedOut = false
    private var latestStartId = 0
    private val cancelledIds = mutableSetOf<String>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); running = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "音频导出", NotificationManager.IMPORTANCE_LOW))
        startForeground(201, notification("正在准备导出", null, 0), if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == CANCEL) {
            val id = intent.getStringExtra("id")
            if (id != null) cancelledIds += id
            if (id == currentId) currentJob?.cancel() else scope.launch { id?.let { graph.dao.export(it) }?.let { if (it.state == "queued") graph.dao.putExport(it.copy(state = "cancelled")) } }
        }
        if (runner?.isActive != true) runner = scope.launch {
            while (isActive && !timedOut) {
                val observedStartId = latestStartId
                val next = graph.dao.activeExports().firstOrNull { it.state == "queued" }
                if (next == null) {
                    if (observedStartId != latestStartId) continue
                    // A start request delivered after this point sees a null runner.
                    // stopSelfResult also protects requests not yet delivered to onStartCommand.
                    runner = null
                    if (stopSelfResult(observedStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
                    return@launch
                }
                currentId = next.id
                cancelledIds.remove(next.id)
                currentJob = launch { process(next) }
                currentJob?.join()
                currentId = null
            }
            runner = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(latestStartId)
        }
        return START_NOT_STICKY
    }
    private suspend fun process(initial: ExportEntity) {
        if (initial.id in cancelledIds || graph.exports.cancellationRequested(initial.id) || graph.dao.export(initial.id)?.state != "queued") {
            if (initial.id in cancelledIds || graph.exports.cancellationRequested(initial.id)) graph.dao.putExport(initial.copy(state = "cancelled"))
            return
        }
        if (initial.subtitleOptions != null) {
            SubtitleExportRunner(this, { plan, output, id -> encodeAac(plan, output, id) }, { phase, id, percent -> getSystemService(NotificationManager::class.java).notify(201, notification(phase, id, percent)) }).run(initial)
            return
        }
        var task = initial
        val startedAt = SystemClock.elapsedRealtime()
        var pending: Uri? = null
        var published = false
        val dir = graph.exports.taskDir(task.id).apply { mkdirs() }
        try {
            task = task.copy(state = "running"); graph.dao.putExport(task)
            val array = JSONArray(task.sourceIds)
            val ids = List(array.length()) { array.getString(it) }
            val sources = graph.media.resolve(ids)
            require(sources.size == ids.size) { "部分源文件已移除" }
            val plan = withContext(Dispatchers.IO) {
                sources.forEach { require(graph.media.isVisibleMedia(it.locator)) { "源文件不可访问，请重新授权" } }
                OriginalAudioExporter.inspect(this@AudioExportService, sources, task.exportMode)
            }
            task = task.copy(strategy = plan.strategy); graph.dao.putExport(task)
            val output = File(dir, "audio.${plan.extension}")
            require(filesDir.usableSpace > plan.estimatedBytes * 2 + 16 * 1024 * 1024) { "存储空间不足，需要同时容纳临时文件和导出文件" }
            if (plan.strategy == "ENCODE_AAC") encodeAac(plan, output, task.id)
            else withContext(Dispatchers.IO) {
                var reportedAt = 0L
                OriginalAudioExporter.write(this@AudioExportService, plan, output) { percent ->
                    currentCoroutineContext().ensureActive()
                    val now = SystemClock.elapsedRealtime()
                    if (now - reportedAt >= 300 || percent == 98) {
                        reportedAt = now
                        graph.dao.export(task.id)?.let { graph.dao.putExport(it.copy(progress = percent)) }
                        getSystemService(NotificationManager::class.java).notify(201, notification("正在导出音频", task.id, percent))
                    }
                }
            }
            withContext(Dispatchers.IO) {
                validate(output, plan)
                require(filesDir.usableSpace > output.length() + 4 * 1024 * 1024) { "存储空间不足，无法发布导出文件" }
                pending = createPendingAudio(contentResolver, "${task.title}_${System.currentTimeMillis()}.${plan.extension}", plan.mimeType)
                task = task.copy(state = "publishing", progress = 99, pendingUri = pending.toString())
                graph.dao.putExport(task)
                contentResolver.openOutputStream(pending!!, "w")?.use { dst -> output.inputStream().use { src ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); val count = src.read(buffer); if (count < 0) break; dst.write(buffer, 0, count) }
                } } ?: error("无法写入音乐目录")
                currentCoroutineContext().ensureActive()
                // Non-cancellable commit prevents cancellation from deleting a successfully published file.
                withContext(NonCancellable) {
                    check(contentResolver.update(pending!!, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1)
                    published = true
                    graph.dao.putExport(task.copy(state = "completed", progress = 100, outputUri = pending.toString(), pendingUri = null, error = null, elapsedMs = SystemClock.elapsedRealtime() - startedAt))
                }
            }
            // The export is already committed; scan failure must not change its successful state.
            pending?.let { runCatching { graph.media.registerExport(it) } }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { if (!published) graph.dao.putExport(task.copy(state = if (timedOut) "failed" else "cancelled", error = if (timedOut) "系统处理时限已到，请重新导出" else null)) }
        } catch (e: Exception) {
            val message = when (e) { is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf { !it.contains("content:") && !it.contains("/") } ?: "文件处理失败"; is java.io.IOException -> "无法读写文件，请检查剩余空间"; is SecurityException -> "文件访问授权已失效"; else -> "导出失败，请检查文件和剩余空间" }
            withContext(NonCancellable) { if (!published) graph.dao.putExport(task.copy(state = "failed", error = message)) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { if (!published) pending?.let { runCatching { contentResolver.delete(it, null, null) } }; dir.deleteRecursively() }
        }
    }
    private suspend fun encodeAac(plan: AudioExportPlan, output: File, id: String) {
        val edits = plan.tracks.map { track ->
            val processors = listOf<AudioProcessor>(
                ToInt16PcmAudioProcessor(),
                ChannelMixingAudioProcessor().apply { putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(track.channels, 2)) },
                SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) }
            )
            EditedMediaItem.Builder(MediaItem.fromUri(track.source.locator)).setRemoveVideo(true).setEffects(Effects(processors, emptyList())).build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence.withAudioFrom(edits)).build()
        val export = Transformer.Builder(this).setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(DefaultEncoderFactory.Builder(this).setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(256_000).build()).build()).build()
        transformer = export
        var completed = false
        val progressJob = scope.launch {
            val holder = ProgressHolder()
            while (isActive) {
                val percent = if (export.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress.coerceIn(0, 98) else 0
                graph.dao.export(id)?.let { graph.dao.putExport(it.copy(progress = percent)) }
                getSystemService(NotificationManager::class.java).notify(201, notification("正在导出音频", id, percent)); delay(750)
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                export.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { completed = true; if (continuation.isActive) continuation.resume(Unit) }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("设备无法处理此音轨或文件已损坏")) }
                })
                export.start(composition, output.absolutePath)
            }
        } finally {
            // Cancellation reaches this block with an already cancelled Job. Finish native
            // encoder teardown on its application looper before deleting the temporary output.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                try { if (!completed) export.cancel() }
                finally { progressJob.cancelAndJoin(); if (transformer === export) transformer = null }
            }
        }
    }
    private fun validate(file: File, plan: AudioExportPlan) {
        check(file.length() > 0) { "导出结果为空" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            check(extractor.trackCount == 1) { "导出结果校验失败" }
            val format = extractor.getTrackFormat(0)
            val expectedMime = if (plan.strategy == "PCM_WAV") "audio/raw" else MimeTypes.AUDIO_AAC
            val expectedRate = if (plan.strategy == "ENCODE_AAC") 48000 else plan.tracks.first().rate
            val expectedChannels = if (plan.strategy == "ENCODE_AAC") 2 else plan.tracks.maxOf { it.channels }
            check(format.getString(MediaFormat.KEY_MIME) == expectedMime &&
                format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > 0 &&
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == expectedRate &&
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == expectedChannels) { "导出结果音轨不完整或参数错误" }
            extractor.selectTrack(0)
            check(extractor.sampleTrackIndex == 0 && extractor.sampleSize > 0) { "导出音轨没有有效数据" }
        } finally { extractor.release() }
    }
    private fun notification(title: String, id: String?, percent: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setOngoing(true).setSilent(true).setProgress(100, percent, percent == 0).setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (id != null) builder.addAction(0, "取消", PendingIntent.getService(this, 201, Intent(this, AudioExportService::class.java).setAction(CANCEL).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return builder.build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        currentJob?.cancel()
        // The runner stops the service, which cancels scope in onDestroy. Persist the
        // remaining queue's terminal state even when that happens before join resumes.
        scope.launch(NonCancellable) {
            runner?.join()
            graph.dao.activeExports().forEach { graph.dao.putExport(it.copy(state = "failed", error = "系统处理时限已到，请稍后重新导出")) }
            stopSelf()
        }
    }
    override fun onDestroy() { transformer?.cancel(); scope.cancel(); running = false; super.onDestroy() }
}

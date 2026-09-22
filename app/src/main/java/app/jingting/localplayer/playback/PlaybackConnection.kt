package app.jingting.localplayer.playback

import android.content.*
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.session.*
import androidx.core.content.ContextCompat
import app.jingting.localplayer.*
import app.jingting.localplayer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PlaybackConnection(private val context: Context, private val graph: AppGraph) : PlaybackPort {
    val state = MutableStateFlow(PlaybackState())
    private var controller: MediaController? = null
    private var connecting: Deferred<MediaController>? = null
    private var requestGeneration = 0L
    val currentRequest: Long get() = requestGeneration
    fun isCurrentRequest(request: Long) = request == requestGeneration
    fun pauseIfCurrentRequest(request: Long): Boolean {
        if (!isCurrentRequest(request)) return false
        pauseIfConnected()
        return true
    }
    suspend fun connect(): MediaController {
        controller?.let { return it }
        connecting?.let { return it.await() }
        val operation = graph.scope.async {
            val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).setListener(object : MediaController.Listener {
                override fun onDisconnected(c: MediaController) { if (controller === c) controller = null; state.value = state.value.copy(isPlaying = false, preparing = false) }
            }).buildAsync()
            suspendCancellableCoroutine<MediaController> { c ->
                future.addListener({ try { val value = future.get(); controller = value; if (c.isActive) c.resume(value) } catch (e: Exception) { if (c.isActive) c.resumeWithException(e) } }, ContextCompat.getMainExecutor(context))
                c.invokeOnCancellation { MediaController.releaseFuture(future) }
            }
        }
        connecting = operation
        return try { operation.await() } finally { connecting = null }
    }
    override suspend fun play(ids: List<String>, mode: PlaybackMode, positionMs: Long) {
        playFrom(ids, mode, 0, positionMs)
    }
    suspend fun playFrom(ids: List<String>, mode: PlaybackMode, startIndex: Int, positionMs: Long = 0) {
        loadQueue(ids, mode, startIndex, positionMs, resume = true)
    }
    suspend fun playRequest(ids: List<String>, mode: PlaybackMode): Long? =
        if (loadQueue(ids, mode, 0, 0, resume = true)) currentRequest else null
    private suspend fun loadQueue(ids: List<String>, mode: PlaybackMode, startIndex: Int, positionMs: Long, resume: Boolean): Boolean {
        val items = graph.media.resolve(ids)
        val valid = items.filter {
            val readable = graph.media.isVisibleMedia(it.locator)
            if (it.available != readable) graph.dao.availability(it.id, readable)
            readable
        }
        if (valid.isEmpty()) { state.value = state.value.copy(message = "没有可访问的媒体文件"); return false }
        val c = connect()
        val target = ids.getOrNull(startIndex)
        val targetIndex = valid.indexOfFirst { it.id == target }
        requestGeneration++
        // A removed item owns its old position; never seek another file to that offset.
        c.setMediaItems(valid.map { it.toMediaItem() }, targetIndex.coerceAtLeast(0), if (targetIndex >= 0) positionMs.coerceAtLeast(0) else 0)
        setMode(mode)
        c.playWhenReady = resume
        c.prepare()
        return true
    }
    suspend fun updateTitle(id: String, title: String) {
        val c = connect()
        for (index in 0 until c.mediaItemCount) {
            val item = c.getMediaItemAt(index)
            if (item.mediaId == id) c.replaceMediaItem(index, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(title).build()).build())
        }
    }
    suspend fun toggle() {
        val c = connect()
        // Buffering and a temporary focus loss are still a pending play request.
        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) { requestGeneration++; c.pause() } else {
            if (c.mediaItemCount == 0) {
                val s = graph.preferences.snapshot()
                if (s.ids.isEmpty()) return
                loadQueue(s.ids, s.mode, s.index, s.positionMs, resume = true)
            } else {
                requestGeneration++
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                c.prepare(); c.play()
            }
        }
    }
    suspend fun resumeCurrent(mode: PlaybackMode, resume: Boolean = true) {
        val c = connect()
        if (c.mediaItemCount == 0) {
            val snapshot = graph.preferences.snapshot()
            require(snapshot.ids.isNotEmpty()) { "请先选择要播放的内容" }
            require(loadQueue(snapshot.ids, mode, snapshot.index, snapshot.positionMs, resume)) { "当前队列没有可播放的内容" }
        } else {
            setMode(mode)
            if (resume) {
                requestGeneration++
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                c.prepare(); c.play()
            }
        }
    }
    fun pauseIfConnected() { requestGeneration++; controller?.pause() }
    suspend fun pruneUnavailable() {
        val c = controller ?: return
        val inspected = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
        val visible = graph.dao.mediaByIds(inspected.toList()).filter { it.available }.map { it.id }.toSet()
        if (controller !== c) return
        for (index in c.mediaItemCount - 1 downTo 0) {
            val id = c.getMediaItemAt(index).mediaId
            if (id in inspected && id !in visible) c.removeMediaItem(index)
        }
        if (c.mediaItemCount == 0) c.stop()
    }
    suspend fun seek(position: Long) { val c = connect(); requestGeneration++; c.seekTo(position.coerceAtLeast(0)) }
    suspend fun skip(forward: Boolean) { val c = connect(); requestGeneration++; if (forward) c.seekToNextMediaItem() else c.seekToPreviousMediaItem() }
    suspend fun jump(delta: Long) { val c = connect(); requestGeneration++; c.seekTo((c.currentPosition + delta).coerceIn(0, c.duration.coerceAtLeast(0))) }
    suspend fun setMode(mode: PlaybackMode) {
        val c = connect()
        c.shuffleModeEnabled = mode == PlaybackMode.SHUFFLE
        c.repeatMode = when (mode) { PlaybackMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE; PlaybackMode.REPEAT_ALL, PlaybackMode.SHUFFLE -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
    }
    suspend fun sleep(minutes: Int) { connect(); graph.timers.setForCurrentPlayback(minutes) }
    fun clearMessage() { state.value = state.value.copy(message = null) }
}
fun MediaRef.toMediaItem(): MediaItem = MediaItem.Builder().setMediaId(id).setUri(Uri.parse(locator)).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(if (isVideo) "视频 · 只听声音" else "本地音频").build()).build()

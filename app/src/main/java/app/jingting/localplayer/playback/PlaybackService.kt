@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.jingting.localplayer.playback

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import app.jingting.localplayer.*
import app.jingting.localplayer.R
import app.jingting.localplayer.core.*
import com.google.common.util.concurrent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class PlaybackService : MediaSessionService() {
    companion object { const val SLEEP = "jingting.sleep"; const val SCHEDULED = "jingting.scheduled" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private var sleepDeadline = 0L
    private var restoring = false
    private var snapshotLoaded = false
    private var lastSavedSnapshot: PlaybackSnapshot? = null
    private var generation = 0
    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true).setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("subtitle_entry", "纯播放页", NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        val pureIntent = PendingIntent.getActivity(this, 110, Intent(this, app.jingting.localplayer.ui.PurePlaybackActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if(isPlaying) runCatching { notifications.notify(110, NotificationCompat.Builder(this@PlaybackService,"subtitle_entry").setSmallIcon(R.drawable.ic_notification).setContentTitle("枕波 · 纯播放页").setContentText("点击查看字幕与播放控制").setContentIntent(pureIntent).setSilent(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()) }
                else notifications.cancel(110)
            }
        })
        session = MediaSession.Builder(this, player).setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).setCallback(object : MediaSession.Callback {
            override fun onConnect(s: MediaSession, c: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (c.packageName != packageName && !c.isTrusted) return MediaSession.ConnectionResult.reject()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(s).setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SessionCommand(SLEEP, Bundle.EMPTY)).build()).build()
            }
            override fun onCustomCommand(s: MediaSession, c: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (command.customAction != SLEEP || c.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                val minutes = args.getInt("minutes").coerceIn(0, 1440)
                scope.launch { graph.timers.setForCurrentPlayback(minutes) }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) { if (!restoring) { generation++; publish(); save() } }
            override fun onPlayerError(error: PlaybackException) {
                val badId = player.currentMediaItem?.mediaId
                if (badId != null) scope.launch { graph.dao.availability(badId, false) }
                graph.playback.state.value = graph.playback.state.value.copy(message = "文件无法播放，已跳过")
                val resume = player.playWhenReady
                val failedIndex = player.currentMediaItemIndex
                // Remove the failed item once, including in single-item repeat mode.
                // Preparing a paused queue must never turn into audible playback.
                if (failedIndex in 0 until player.mediaItemCount) player.removeMediaItem(failedIndex)
                if (player.mediaItemCount > 0) { player.playWhenReady = resume; player.prepare() }
                else { player.pause(); player.stop(); graph.playback.state.value = graph.playback.state.value.copy(message = "队列中没有可播放的文件") }
            }
        })
        scope.launch {
            val initialGeneration = generation
            val snapshot = graph.preferences.snapshot()
            val items = graph.media.resolve(snapshot.ids).filter { it.available && graph.media.isVisibleMedia(it.locator) }.map { it.toMediaItem() }
            if (player.mediaItemCount == 0 && generation == initialGeneration && items.isNotEmpty()) {
                restoring = true
                val restoredIndex = items.indexOfFirst { it.mediaId == snapshot.ids.getOrNull(snapshot.index) }
                player.setMediaItems(items, restoredIndex.coerceAtLeast(0), if (restoredIndex >= 0) snapshot.positionMs else 0)
                applyMode(snapshot.mode)
                restoring = false
            }
            snapshotLoaded = true
            save()
            graph.timers.reconcile(); publish()
        }
        scope.launch {
            graph.timers.state.collect { timer ->
                sleepDeadline = timer?.takeIf { it.active }?.deadlineElapsedMs ?: 0L
                publish()
            }
        }
        scope.launch {
            var ticks = 0
            while (isActive) {
                if (sleepDeadline > 0 && SystemClock.elapsedRealtime() >= sleepDeadline) graph.timers.finishIfDue { player.pause() }
                publish(); if (++ticks % 5 == 0) save()
                delay(500)
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == SCHEDULED) {
            // Establish the foreground lifecycle BEFORE requesting audio focus.
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("scheduled_playback", "定时播放", NotificationManager.IMPORTANCE_LOW))
            if (!player.isPlaying) startForeground(1001, NotificationCompat.Builder(this, "scheduled_playback").setSmallIcon(R.drawable.ic_notification).setContentTitle("枕波 · 准备定时播放").setSilent(true).setOngoing(true).build())
            scope.launch {
                try { graph.schedules.fire() } catch (_: Exception) { graph.playback.state.value = graph.playback.state.value.copy(message = "定时播放未成功，请检查任务状态") }
                if (!player.playWhenReady) { stopForeground(STOP_FOREGROUND_REMOVE); if (player.mediaItemCount == 0) stopSelf() }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }
    fun applyMode(mode: PlaybackMode) {
        player.shuffleModeEnabled = mode == PlaybackMode.SHUFFLE
        player.repeatMode = when (mode) { PlaybackMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE; PlaybackMode.REPEAT_ALL, PlaybackMode.SHUFFLE -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
    }
    private fun mode() = if (player.shuffleModeEnabled) PlaybackMode.SHUFFLE else when (player.repeatMode) { Player.REPEAT_MODE_ONE -> PlaybackMode.REPEAT_ONE; Player.REPEAT_MODE_ALL -> PlaybackMode.REPEAT_ALL; else -> PlaybackMode.ORDERED }
    private fun ids() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
    private fun publish() { graph.playback.state.value = PlaybackState(ids(), player.currentMediaItemIndex.coerceAtLeast(0), player.mediaMetadata.title?.toString() ?: "还没有开始播放", player.isPlaying, player.playWhenReady && player.playbackState == Player.STATE_BUFFERING, player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0), mode(), sleepDeadline, graph.playback.state.value.message) }
    private fun save() {
        if (restoring || !snapshotLoaded) return
        val snapshot = PlaybackSnapshot(ids(), player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0), mode())
        if (snapshot == lastSavedSnapshot) return
        lastSavedSnapshot = snapshot
        scope.launch { graph.preferences.save(snapshot) }
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.playWhenReady && sleepDeadline == 0L) super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        val snapshot = PlaybackSnapshot(ids(), player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0), mode())
        if (snapshotLoaded) graph.scope.launch { graph.preferences.save(snapshot) }
        getSystemService(NotificationManager::class.java).cancel(110)
        session.release(); player.release(); scope.cancel(); super.onDestroy()
    }
}

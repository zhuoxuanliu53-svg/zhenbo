@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.jingting.localplayer

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.jingting.localplayer.data.*
import app.jingting.localplayer.playback.PlaybackConnection
import app.jingting.localplayer.schedule.ScheduleManager
import app.jingting.localplayer.schedule.TimerManager
import app.jingting.localplayer.export.ExportManager
import kotlinx.coroutines.*

class JingTingApp : Application() {
    lateinit var graph: AppGraph
        private set
    override fun onCreate() {
        super.onCreate()
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        graph = AppGraph(this)
    }
}
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db = Room.databaseBuilder(context, AppDatabase::class.java, "jingting.db").addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4).build()
    val dao = db.dao()
    val preferences = PlayerPreferences(context)
    val media = MediaRepository(context, db)
    val playback = PlaybackConnection(context, this)
    val timers = TimerManager(context, this)
    val schedules = ScheduleManager(context, this)
    val exports = ExportManager(context, this)
    val subtitles = app.jingting.localplayer.subtitle.SubtitleRepository(context, this)
}
val Context.graph: AppGraph get() = (applicationContext as JingTingApp).graph

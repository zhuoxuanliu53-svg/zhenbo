package app.jingting.localplayer.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import app.jingting.localplayer.core.*
import java.util.UUID

@Entity(tableName = "media")
data class MediaEntity(@PrimaryKey val id: String, val uri: String, val title: String, val durationMs: Long, val isVideo: Boolean, val available: Boolean = true, val source: String = "store") {
    fun ref() = MediaRef(id, uri, title, durationMs, isVideo, available)
}
// Display names belong to the library, independent of MediaStore's source metadata.
@Entity(tableName = "media_aliases")
data class MediaAliasEntity(@PrimaryKey val mediaId: String, val title: String)
@Entity(tableName = "collections")
data class CollectionEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "entries", indices = [Index("collectionId"), Index("mediaId")], foreignKeys = [ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE)])
data class EntryEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val collectionId: String, val mediaId: String, val position: Int)
data class CollectionItem(@Embedded val entry: EntryEntity, val title: String, val durationMs: Long, val isVideo: Boolean, val available: Boolean, val uri: String)
@Entity(tableName = "schedules")
data class ScheduleEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val collectionId: String, val hour: Int, val minute: Int, val days: Int = 0, val date: String? = null, val mode: String = PlaybackMode.ORDERED.name, val conflict: String = ConflictPolicy.SKIP.name, val stopMinutes: Int = 0, val enabled: Boolean = true, val nextAt: Long = 0, val handledDate: String? = null, val lastResult: String = "等待启用", val createdAt: Long = System.currentTimeMillis(), @ColumnInfo(defaultValue = "'[]'") val queueIds: String = "[]")
@Entity(tableName = "exports")
data class ExportEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val sourceIds: String, val title: String, val concatenate: Boolean, val state: String = "queued", val progress: Int = 0, val outputUri: String? = null, val pendingUri: String? = null, val error: String? = null, val createdAt: Long = System.currentTimeMillis(), @ColumnInfo(defaultValue = "'ORIGINAL'") val exportMode: String = "ORIGINAL", val strategy: String? = null, @ColumnInfo(defaultValue = "0") val elapsedMs: Long = 0, val subtitleOptions: String? = null, @ColumnInfo(defaultValue = "'none'") val subtitleState: String = "none", val subtitleUri: String? = null, @ColumnInfo(defaultValue = "''") val phase: String = "", val publication: String? = null)

@Entity(tableName = "subtitles")
data class SubtitleEntity(@PrimaryKey val mediaId: String, val srt: String, val sourceUri: String? = null, val offsetMs: Long = 0)

private const val MEDIA_DISPLAY_QUERY = "SELECT m.id, m.uri, COALESCE(a.title, m.title) AS title, m.durationMs, m.isVideo, m.available, m.source FROM media m LEFT JOIN media_aliases a ON a.mediaId=m.id"

@Dao
interface AppDao {
    @Query("SELECT * FROM subtitles WHERE mediaId = :id") fun observeSubtitle(id: String): Flow<SubtitleEntity?>
    @Query("SELECT * FROM subtitles WHERE mediaId = :id") suspend fun subtitle(id: String): SubtitleEntity?
    @Upsert suspend fun putSubtitle(item: SubtitleEntity)

    @Query(MEDIA_DISPLAY_QUERY + " WHERE m.available = 1 ORDER BY title COLLATE NOCASE") fun observeMedia(): Flow<List<MediaEntity>>
    @Query(MEDIA_DISPLAY_QUERY + " ORDER BY title COLLATE NOCASE") suspend fun media(): List<MediaEntity>
    @Query(MEDIA_DISPLAY_QUERY + " WHERE m.id IN (:ids)") suspend fun mediaByIds(ids: List<String>): List<MediaEntity>
    @Query("SELECT * FROM media") suspend fun rawMedia(): List<MediaEntity>
    @Upsert suspend fun putMedia(items: List<MediaEntity>)
    @Upsert suspend fun putMediaAlias(item: MediaAliasEntity)
    @Query("DELETE FROM media_aliases WHERE mediaId = :id") suspend fun deleteMediaAlias(id: String)
    @Transaction suspend fun renameMedia(id: String, title: String) {
        val name = title.trim().take(160)
        if (name.isBlank()) deleteMediaAlias(id) else putMediaAlias(MediaAliasEntity(id, name))
    }
    @Query("UPDATE media SET available = 0 WHERE source = 'store'") suspend fun markScannedUnavailable()
    @Query("UPDATE media SET available = :available WHERE id = :id") suspend fun availability(id: String, available: Boolean)
    @Query("SELECT * FROM collections ORDER BY createdAt") fun observeCollections(): Flow<List<CollectionEntity>>
    @Query("SELECT * FROM collections WHERE id = :id") suspend fun collection(id: String): CollectionEntity?
    @Upsert suspend fun putCollection(item: CollectionEntity)
    @Query("DELETE FROM collections WHERE id = :id") suspend fun deleteCollection(id: String)
    @Query("SELECT e.*, COALESCE(a.title, m.title) AS title, m.durationMs, m.isVideo, m.available, m.uri FROM entries e JOIN media m ON e.mediaId=m.id LEFT JOIN media_aliases a ON a.mediaId=m.id WHERE e.collectionId = :id AND m.available = 1 ORDER BY e.position") fun observeEntries(id: String): Flow<List<CollectionItem>>
    @Query("SELECT * FROM entries WHERE collectionId = :id ORDER BY position") suspend fun entries(id: String): List<EntryEntity>
    @Upsert suspend fun putEntries(items: List<EntryEntity>)
    @Query("DELETE FROM entries WHERE id = :id") suspend fun deleteEntry(id: String)
    @Query("SELECT * FROM schedules ORDER BY createdAt") fun observeSchedules(): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM schedules ORDER BY nextAt, createdAt") suspend fun schedules(): List<ScheduleEntity>
    @Upsert suspend fun putSchedule(item: ScheduleEntity)
    @Query("DELETE FROM schedules WHERE id = :id") suspend fun deleteSchedule(id: String)
    @Query("SELECT * FROM exports ORDER BY createdAt DESC") fun observeExports(): Flow<List<ExportEntity>>
    @Query("SELECT * FROM exports WHERE id = :id") suspend fun export(id: String): ExportEntity?
    @Query("SELECT * FROM exports WHERE state IN ('queued','running','publishing') ORDER BY createdAt") suspend fun activeExports(): List<ExportEntity>
    @Upsert suspend fun putExport(item: ExportEntity)
}
@Database(entities = [MediaEntity::class, MediaAliasEntity::class, CollectionEntity::class, EntryEntity::class, ScheduleEntity::class, ExportEntity::class, SubtitleEntity::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS subtitles (mediaId TEXT NOT NULL, srt TEXT NOT NULL, sourceUri TEXT, offsetMs INTEGER NOT NULL, PRIMARY KEY(mediaId))")
                db.execSQL("ALTER TABLE exports ADD COLUMN subtitleOptions TEXT")
                db.execSQL("ALTER TABLE exports ADD COLUMN subtitleState TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("ALTER TABLE exports ADD COLUMN subtitleUri TEXT")
                db.execSQL("ALTER TABLE exports ADD COLUMN phase TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE exports ADD COLUMN publication TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedules ADD COLUMN queueIds TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE exports ADD COLUMN exportMode TEXT NOT NULL DEFAULT 'ORIGINAL'")
                db.execSQL("ALTER TABLE exports ADD COLUMN strategy TEXT")
                db.execSQL("ALTER TABLE exports ADD COLUMN elapsedMs INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_aliases` (`mediaId` TEXT NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`mediaId`))")
            }
        }
    }
}

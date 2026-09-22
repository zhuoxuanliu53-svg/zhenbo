package app.jingting.localplayer.data

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import app.jingting.localplayer.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MediaPermissions(val audio: Boolean, val video: Boolean, val partial: Boolean)
class MediaRepository(private val context: Context, private val db: AppDatabase) : MediaAccess {
    private val resolver = context.contentResolver
    private val mutex = Mutex()
    fun permissions(): MediaPermissions {
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33) MediaPermissions(has(Manifest.permission.READ_MEDIA_AUDIO), has(Manifest.permission.READ_MEDIA_VIDEO), Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else has(Manifest.permission.READ_EXTERNAL_STORAGE).let { MediaPermissions(it, it, false) }
    }
    fun requestedPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    suspend fun refresh() = mutex.withLock { withContext(Dispatchers.IO) {
        val found = mutableListOf<MediaEntity>()
        // Without read permission the platform still exposes this app's own outputs.
        for (video in listOf(false, true)) {
            val base = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            try {
                val selection = "is_pending=0" + if (Build.VERSION.SDK_INT >= 30) " AND is_trashed=0" else ""
                resolver.query(base, arrayOf("_id", "_display_name", "duration", "volume_name"), selection, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val volume = c.string("volume_name") ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                        val actual = if (video) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Audio.Media.getContentUri(volume)
                        val uri = ContentUris.withAppendedId(actual, c.getLong(c.getColumnIndexOrThrow("_id"))).toString()
                        if (canRead(uri)) found += MediaEntity(uri, uri, c.string("_display_name") ?: "未命名媒体", c.getLong(c.getColumnIndexOrThrow("duration")), video)
                    }
                }
            } catch (_: SecurityException) { /* A denied category must not hide the other category. */ }
        }
        val imports = db.dao().rawMedia().filter { it.source == "document" }.associateBy { it.id }
        // A MediaStore refresh must not discard a document's durable URI permission.
        val merged = found.map { scan -> imports[scan.id]?.copy(title = scan.title, durationMs = scan.durationMs, available = true) ?: scan }
        val scannedImports = merged.associateBy { it.id }
        val checkedImports = imports.values.map { (scannedImports[it.id] ?: it).copy(available = isVisibleMedia(it.uri)) }
        db.withTransaction {
            db.dao().markScannedUnavailable()
            db.dao().putMedia(merged)
            db.dao().putMedia(checkedImports)
        }
    } }
    suspend fun importLocal(uri: Uri, flags: Int): MediaEntity = mutex.withLock { withContext(Dispatchers.IO) {
        val allowed = setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents", "com.android.providers.media.documents", "media")
        require(uri.scheme == "content" && uri.authority in allowed) { "请选择系统本地存储中的音频或视频，暂不接受云端来源" }
        var name = "未命名媒体"
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.string(OpenableColumns.DISPLAY_NAME) ?: name
                val i = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                require(i < 0 || c.getInt(i) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0) { "不支持虚拟文档，请先保存为本地文件" }
            }
        }
        val mime = resolver.getType(uri).orEmpty()
        require(mime.startsWith("audio/") || mime.startsWith("video/")) { "请选择音频或视频文件" }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            require(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "该文件没有可播放音轨" }
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val video = mime.startsWith("video/")
            val canonical = canonicalMediaUri(uri, video)
            // Validate before retaining a grant: rejected selections must not fill the OS grant limit.
            val document = DocumentsContract.isDocumentUri(context, uri)
            if (document) resolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val item = MediaEntity(canonical.toString(), uri.toString(), name, duration, video, source = if (document) "document" else "store")
            db.dao().putMedia(listOf(item)); item
        } finally { r.release() }
    } }
    /** Document, Files and category URIs can identify the same row; use the scanner's identity. */
    internal fun canonicalMediaUri(uri: Uri, video: Boolean): Uri {
        val media = if (uri.authority == MediaStore.AUTHORITY) uri else runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() ?: return uri
        return runCatching {
            resolver.query(media, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val volume = c.string(MediaStore.MediaColumns.VOLUME_NAME) ?: return@use null
                val base = if (video) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Audio.Media.getContentUri(volume)
                ContentUris.withAppendedId(base, c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)))
            }
        }.getOrNull() ?: uri
    }
    /** Register only a newly published output; a batch must not rescan the user's entire library per file. */
    suspend fun registerExport(uri: Uri): MediaEntity = mutex.withLock { withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) { "导出文件地址无效" }
        val item = resolver.query(uri, arrayOf("_id", "_display_name", "duration", "volume_name"), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "导出文件已不可访问" }
            val volume = cursor.string("volume_name") ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
            val canonical = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
            var duration = cursor.getLong(cursor.getColumnIndexOrThrow("duration"))
            if (duration <= 0) {
                val retriever = MediaMetadataRetriever()
                try { retriever.setDataSource(context, uri); duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0 }
                finally { retriever.release() }
            }
            MediaEntity(canonical.toString(), canonical.toString(), cursor.string("_display_name") ?: "枕波音频", duration, false)
        } ?: error("无法读取导出文件信息")
        db.dao().putMedia(listOf(item)); item
    } }
    suspend fun canRead(locator: String): Boolean = withContext(Dispatchers.IO) {
        try { resolver.openFileDescriptor(Uri.parse(locator), "r")?.use { true } ?: false } catch (_: Exception) { false }
    }
    suspend fun isVisibleMedia(locator: String): Boolean = withContext(Dispatchers.IO) {
        if (!canRead(locator)) return@withContext false
        val uri = Uri.parse(locator)
        val mediaUri = if (uri.authority == MediaStore.AUTHORITY) uri else runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
        if (mediaUri != null && Build.VERSION.SDK_INT >= 30) {
            return@withContext runCatching { resolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { it.moveToFirst() && it.getInt(0) == 0 && it.getInt(1) == 0 } ?: false }.getOrDefault(false)
        }
        true
    }
    override suspend fun listMedia() = db.dao().media().map { it.ref() }
    override suspend fun resolve(ids: List<String>): List<MediaRef> {
        val found = db.dao().mediaByIds(ids).associateBy { it.id }
        return ids.mapNotNull { found[it]?.ref() }
    }
    private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }
}

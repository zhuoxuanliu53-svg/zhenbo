package app.jingting.localplayer.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore

/** Always creates a new MediaStore row. It never opens an existing filename for writing. */
internal fun createPendingAudio(resolver: ContentResolver, displayName: String, mimeType: String = "audio/mp4"): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/静听/")
        put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    return resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: error("无法创建音频文件")
}

package app.jingting.localplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.jingting.localplayer.data.ArtworkLoader

/** The caller owns size and clipping; artwork is decorative beside the media title. */
@Composable
fun MediaArtwork(
    uri: String?,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    available: Boolean = true,
) {
    val context = LocalContext.current.applicationContext
    val image by produceState<ImageBitmap?>(null, uri, isVideo, available) {
        value = null
        if (available && !uri.isNullOrBlank()) {
            value = ArtworkLoader.load(context, uri, isVideo)?.asImageBitmap()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val artwork = image
        if (artwork != null && available) {
            Image(artwork, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        } else {
            Icon(if (!available) QuietIcons.HideImage else if (isVideo) QuietIcons.Movie else QuietIcons.MusicNote,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.42f))
        }
    }
}

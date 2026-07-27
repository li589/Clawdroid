package com.clawdroid.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.clawdroid.app.data.model.ChatMedia

/**
 * Renders a list of [ChatMedia] attachments inside a chat bubble.
 *
 * Images/GIFs are loaded via Coil; videos show a thumbnail with a play button
 * and switch to an embedded [PlayerView] when tapped.
 */
@Composable
internal fun ChatMediaContent(
    media: List<ChatMedia>,
    modifier: Modifier = Modifier
) {
    if (media.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        media.forEach { item ->
            if (item.isVideo) {
                VideoThumbnail(media = item, modifier = Modifier.fillMaxWidth())
            } else {
                AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }
    }
}

/**
 * Shows a video thumbnail with a play-button overlay. Tapping it switches to an
 * inline [PlayerView] backed by a short-lived [ExoPlayer].
 *
 * The placeholder uses Coil's [AsyncImage] so that, when Coil can decode a video
 * frame (via [coil.decode.VideoFrameDecoder] if registered), a real frame is shown.
 * A dark overlay with a play icon and the "视频" label is always rendered on top so
 * the affordance stays clear even when no frame can be decoded.
 */
@Composable
internal fun VideoThumbnail(
    media: ChatMedia,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    val exoPlayer = remember(playing) {
        if (!playing) return@remember null
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(media.uri)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.release()
        }
    }

    Box(
        modifier = modifier
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1B1F))
            .then(if (playing) Modifier else Modifier.clickable { playing = true })
    ) {
        if (playing && exoPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Attempt to load a real frame via Coil; falls back to the dark background
            // underneath when a frame cannot be decoded.
            AsyncImage(
                model = media.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            )
            // Dim + play button overlay always rendered on top so the affordance is clear
            // even when Coil cannot decode a video frame.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "播放视频",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "视频",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

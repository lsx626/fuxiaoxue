package edu.fudan.elearning.sync.preview

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import edu.fudan.elearning.sync.R
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun MediaPreviewScreen(file: File, initialPositionSec: Int = -1, onPositionChanged: (Int, Int) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val fileKey = file.absolutePath
    var playing by remember(fileKey) { mutableStateOf(false) }
    var ready by remember(fileKey) { mutableStateOf(false) }
    var position by remember(fileKey) { mutableLongStateOf(0) }
    var duration by remember(fileKey) { mutableLongStateOf(0) }
    var error by remember(fileKey) { mutableStateOf<String?>(null) }
    var restored by remember(fileKey) { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(1f) }
    var loop by remember { mutableStateOf(false) }
    val report by rememberUpdatedState(onPositionChanged)
    val resume = remember(fileKey) { ResumeOnce() }
    val player = remember(fileKey) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            this.volume = volume
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onPlaybackStateChanged(state: Int) {
                    ready = state == Player.STATE_READY || state == Player.STATE_ENDED
                    if (ready) duration = this@apply.duration.coerceAtLeast(0)
                }
                override fun onPlayerError(cause: PlaybackException) {
                    error = when (cause.errorCode) {
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "本地媒体文件不存在或无法读取"
                        else -> "无法播放此媒体，文件可能损坏或设备不支持该编码（${cause.errorCode}）"
                    }
                }
            })
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file))); prepare(); playWhenReady = false
        }
    }
    LaunchedEffect(ready, player) {
        if (ready) { resume.seekPosition(true, initialPositionSec)?.let(player::seekTo); restored = true }
    }
    DisposableEffect(lifecycle, player) {
        var resumeOnForeground = false
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_PAUSE -> { resumeOnForeground = player.isPlaying; player.pause() }
            Lifecycle.Event.ON_STOP -> player.pause()
            Lifecycle.Event.ON_RESUME -> if (resumeOnForeground) { player.play(); resumeOnForeground = false }
            else -> Unit
        } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            if (restored && duration > 0) report((player.currentPosition / 1000).toInt(), (duration / 1000).toInt())
            player.release()
        }
    }
    LaunchedEffect(player) {
        var lastReported = -1L
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            if (restored && duration > 0 && kotlin.math.abs(position / 1000 - lastReported) >= 2) {
                lastReported = position / 1000; report(lastReported.toInt(), (duration / 1000).toInt())
            }
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } },
                update = { it.player = player }, modifier = Modifier.fillMaxSize())
            if (FileTypes.extOf(file.name) in setOf("mp3", "wav", "m4a", "flac", "aac", "ogg", "opus")) {
                VolumeHighIcon(Color.White.copy(alpha = 0.75f), Modifier.size(80.dp))
            }
            if (error != null) Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { PreviewError(error!!) }
            else if (!ready) CircularProgressIndicator()
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            val content = MaterialTheme.colorScheme.onSurface
            val canSeek = duration > 0 && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            val playLabel = stringResource(if (playing) R.string.media_pause else R.string.media_play)
            val backLabel = stringResource(R.string.media_previous)
            val stopLabel = stringResource(R.string.media_stop)
            val nextLabel = stringResource(R.string.media_next)
            val loopLabel = stringResource(R.string.media_loop)
            val volumeLabel = stringResource(R.string.media_volume)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Slider(value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { position = (it * duration).toLong(); player.seekTo(position) }, enabled = canSeek)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), style = MaterialTheme.typography.labelMedium)
                    Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seekTo((position - 10_000).coerceAtLeast(0)) }, enabled = canSeek,
                        modifier = Modifier.semantics { contentDescription = backLabel }) { SkipBackwardIcon(content) }
                    FilledIconButton(onClick = {
                        if (playing) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
                    }, enabled = ready, modifier = Modifier.size(56.dp).semantics { contentDescription = playLabel }) {
                        if (playing) PauseIcon(MaterialTheme.colorScheme.onPrimary) else PlayIcon(MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = { player.pause(); player.seekTo(0); position = 0; report(0, (duration / 1000).toInt()) }, enabled = ready,
                        modifier = Modifier.semantics { contentDescription = stopLabel }) { StopIcon(content) }
                    IconButton(onClick = { player.seekTo((position + 10_000).coerceAtMost(duration)) }, enabled = canSeek,
                        modifier = Modifier.semantics { contentDescription = nextLabel }) { SkipForwardIcon(content) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconToggleButton(checked = loop, onCheckedChange = { loop = it; player.repeatMode = if (it) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF },
                        modifier = Modifier.semantics { contentDescription = loopLabel }) {
                        LoopIcon(if (loop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    VolumeLowIcon(MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(volume, { volume = it; player.volume = it }, Modifier.weight(1f).semantics { contentDescription = volumeLabel })
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}

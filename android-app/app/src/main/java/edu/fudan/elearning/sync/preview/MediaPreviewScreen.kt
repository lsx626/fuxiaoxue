package edu.fudan.elearning.sync.preview

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File

/**
 * 应用内音视频预览（Media3/ExoPlayer）。
 *
 * 与桌面端播放器规范对齐：播放/暂停、停止并归零、后退/前进 10 秒、
 * 可拖动进度条（不可 seek 时禁用）、0-100 音量、单曲循环、播放错误界面可见。
 * 生命周期结束时主动释放播放器，避免后台泄漏。
 */
@Composable
fun MediaPreviewScreen(
    file: File,
    initialPositionSec: Int = -1,
    onPositionChanged: (sec: Int, totalSec: Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // v1.2.1：这些状态必须随文件切换重置，否则翻文件时旧文件的播放状态/错误
    // 会冒充新文件（预览内翻文件 navigatePreviewSibling 会换掉 file）。
    val fileKey = file.absolutePath
    var isPlaying by remember(fileKey) { mutableStateOf(false) }
    var position by remember(fileKey) { mutableLongStateOf(0L) }
    var duration by remember(fileKey) { mutableLongStateOf(0L) }
    var errorMessage by remember(fileKey) { mutableStateOf<String?>(null) }
    var isReady by remember(fileKey) { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(1f) }
    var looping by remember { mutableStateOf(false) }

    // v1.2.1：player 以 fileKey 键化——翻文件切换音视频时旧 player 随组合丢弃；
    // 配合下面的 DisposableEffect(player) 在切换瞬间释放旧实例，避免旧媒体继续出声。
    val player = remember(fileKey) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    isReady = state == Player.STATE_READY
                    if (state == Player.STATE_READY) {
                        duration = this@apply.duration.coerceAtLeast(0L)
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlayerError(error: PlaybackException) {
                    errorMessage = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                            "设备不支持该音视频编码，无法在应用内播放"
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                            "该音视频超出设备解码能力，无法在应用内播放"
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                            "文件不存在或无法读取"
                        else -> "播放失败（${error.errorCode}）：${error.message ?: "未知错误"}"
                    }
                }
            })
        }
    }

    // 生命周期：后台暂停，离开组合时释放；player 切换（翻文件）时也释放旧实例
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    // v1.1.2：恢复上次播放位置（就绪后 seekOnce）；fileKey 变化时重新触发
    LaunchedEffect(isReady, initialPositionSec, fileKey) {
        if (isReady && initialPositionSec > 0) {
            // 恢复到上次位置的前 2 秒，避免从句子正中间断开
            player.seekTo((initialPositionSec - 2).coerceAtLeast(0) * 1000L)
        }
    }

    // 进度刷新：currentPosition 可直接读取，无需命令可用性判断
    LaunchedEffect(player) {
        var lastReportedSec = -1
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            // v1.1.2：每 2 秒上报一次播放位置（离开页面时已留下近 2 秒内的记录）
            val sec = (position / 1000).toInt()
            val totalSec = (duration / 1000).toInt()
            if (totalSec > 0 && sec != lastReportedSec && sec % 2 == 0) {
                lastReportedSec = sec
                onPositionChanged(sec, totalSec)
            }
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (errorMessage != null) {
            PreviewError(errorMessage!!, modifier = Modifier.align(Alignment.Center))
        } else {
            MediaControls(
                player = player,
                isPlaying = isPlaying,
                isReady = isReady,
                position = position,
                duration = duration,
                volume = volume,
                looping = looping,
                onPositionChange = { position = it },
                onVolumeChange = {
                    volume = it
                    player.volume = it
                },
                onToggleLooping = {
                    looping = !looping
                    player.repeatMode =
                        if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun MediaControls(
    player: Player,
    isPlaying: Boolean,
    isReady: Boolean,
    position: Long,
    duration: Long,
    volume: Float,
    looping: Boolean,
    onPositionChange: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleLooping: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSeek = duration > 0 &&
        player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    Column(
        modifier.fillMaxWidth().background(Color(0xCC000000)).padding(8.dp)
    ) {
        Slider(
            value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { fraction ->
                if (canSeek) {
                    val target = (fraction * duration).toLong()
                    onPositionChange(target)
                    player.seekTo(target)
                }
            },
            enabled = canSeek,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(
                onClick = {
                    val target = (position - 10_000).coerceAtLeast(0)
                    onPositionChange(target)
                    player.seekTo(target)
                },
                enabled = isReady
            ) {
                SkipBackwardIcon(Color.White)
            }
            IconButton(
                onClick = { if (isPlaying) player.pause() else player.play() },
                enabled = isReady
            ) {
                if (isPlaying) PauseIcon(Color.White, Modifier.size(36.dp))
                else PlayIcon(Color.White, Modifier.size(36.dp))
            }
            IconButton(
                onClick = {
                    player.pause()
                    player.seekTo(0)
                    onPositionChange(0)
                },
                enabled = isReady
            ) {
                StopIcon(Color.White)
            }
            IconButton(
                onClick = {
                    val target = if (duration > 0) (position + 10_000).coerceAtMost(duration)
                    else position + 10_000
                    onPositionChange(target)
                    player.seekTo(target)
                },
                enabled = isReady
            ) {
                SkipForwardIcon(Color.White)
            }
            IconButton(onClick = onToggleLooping) {
                LoopIcon(if (looping) MaterialTheme.colorScheme.primary else Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                VolumeLowIcon(Color.White)
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.width(88.dp)
                )
                VolumeHighIcon(Color.White)
            }
        }
        Text(
            "${formatTime(position)} / ${formatTime(duration)}",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp)
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
package com.maynkudu.yoinkd.feature.player

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.core.design.MediaArtwork
import com.maynkudu.yoinkd.core.design.RuneIcon
import com.maynkudu.yoinkd.core.model.MediaItem
import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.core.model.formatBytes
import com.maynkudu.yoinkd.core.model.formatDuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(model: PlayerViewModel, item: MediaItem, onClose: () -> Unit, onShare: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var fullscreen by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    LaunchedEffect(item.id) { model.open(item) }
    BackHandler { onClose() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text(item.kind.label, style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = onClose) { RuneIcon(R.drawable.rune_back, "Close player") } },
            actions = {
                IconButton(onClick = { info = true }) { RuneIcon(R.drawable.rune_info, "File info") }
                IconButton(onClick = onShare) { RuneIcon(R.drawable.rune_share, "Share file") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (item.kind == MediaKind.AUDIO) MediaArtwork(item.thumbnailPath, item.kind,
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
            else if (state.player != null && !fullscreen) VideoSurface(state.player!!,
                Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(18.dp)))
            if (item.kind == MediaKind.VIDEO) TextButton(onClick = { fullscreen = true }, enabled = state.player != null) { Text("Fullscreen") }
            Text(item.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            item.artist?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text("Preparing playback", style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            PlaybackControls(state, model)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuneIcon(R.drawable.rune_volume, "Volume")
                Spacer(Modifier.width(12.dp))
                Slider(state.volume, model::volume, Modifier.weight(1f).semantics { contentDescription = "Volume" })
            }
        }
    }
    if (fullscreen && state.player != null) FullscreenVideo(state, model, onClose = { fullscreen = false })
    if (info) AlertDialog(onDismissRequest = { info = false }, title = { Text("File info") },
        confirmButton = { TextButton(onClick = { info = false }) { Text("Done") } }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text("${item.kind.label} · ${item.fileName.substringAfterLast('.').uppercase()}")
                Text("${formatDuration(item.duration)} · ${formatBytes(item.bytes)}")
                item.artist?.let { Text(it) }
                Text("Added " + Instant.ofEpochMilli(item.addedAt).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                item.sourceUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        })
}

@Composable
private fun PlaybackControls(state: PlaybackState, model: PlayerViewModel) {
    var seeking by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Slider(seeking ?: state.position.toFloat(), { seeking = it },
            Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" },
            enabled = state.player != null && state.duration > 0, valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(),
            onValueChangeFinished = { seeking?.let { model.seek(it.toLong()) }; seeking = null })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration((seeking?.toLong() ?: state.position) / 1_000.0), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(state.duration / 1_000.0), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { model.skip(-15_000) }, enabled = state.player != null) { RuneIcon(R.drawable.rune_back, "Back 15 seconds"); Text("15") }
            FilledIconButton(onClick = model::toggle, Modifier.padding(horizontal = 16.dp).size(64.dp), enabled = state.player != null) {
                RuneIcon(if (state.playing) R.drawable.rune_pause else R.drawable.rune_play, if (state.playing) "Pause" else "Play", Modifier.size(30.dp))
            }
            TextButton(onClick = { model.skip(15_000) }, enabled = state.player != null) { Text("15"); RuneIcon(R.drawable.rune_next, "Forward 15 seconds") }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoSurface(player: ExoPlayer, modifier: Modifier) {
    AndroidView(factory = { context -> PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        this.player = player
    } }, modifier = modifier, onReset = null, onRelease = { it.player = null }, update = { it.player = player })
}

@Composable
private fun FullscreenVideo(state: PlaybackState, model: PlayerViewModel, onClose: () -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (previous != null) activity.requestedOrientation = previous }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(state.player!!, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 28.dp, vertical = 8.dp)) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) { PlaybackControls(state, model) }
            }
            IconButton(onClick = onClose, Modifier.align(Alignment.TopStart).padding(8.dp)) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.rune_close), "Exit fullscreen", tint = Color.White)
            }
        }
    }
}

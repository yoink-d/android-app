package com.maynkudu.yoinkd.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.core.design.MediaArtwork
import com.maynkudu.yoinkd.core.design.RuneIcon
import com.maynkudu.yoinkd.core.model.DownloadJob
import com.maynkudu.yoinkd.core.model.DownloadPhase
import com.maynkudu.yoinkd.core.model.formatBytes

@Composable
fun DownloadCard(job: DownloadJob, compact: Boolean = false, saved: Boolean = false,
    onPause: () -> Unit = {}, onCancel: () -> Unit = {}, onResume: () -> Unit = {},
    onOpen: () -> Unit = {}, onRemove: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (compact) MediaArtwork(job.thumbnail, job.selection.kind,
                Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(12.dp)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!compact) MediaArtwork(job.thumbnail, job.selection.kind, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(start = if (compact) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${job.selection.kind.label} · ${job.selection.quality} · ${job.selection.output.label}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { RuneIcon(R.drawable.rune_menu, "Download actions") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        if (job.phase.canCancel) DropdownMenuItem(text = { Text("Cancel download") }, onClick = { menu = false; onCancel() },
                            leadingIcon = { RuneIcon(R.drawable.rune_close) })
                        if (job.phase.terminal) DropdownMenuItem(text = { Text("Remove from history") }, onClick = { menu = false; onRemove() },
                            leadingIcon = { RuneIcon(R.drawable.rune_trash) })
                    }
                }
            }
            if (job.phase.occupiesSlot) {
                if (job.progress != null && job.phase in setOf(DownloadPhase.DOWNLOADING, DownloadPhase.PROCESSING))
                    LinearProgressIndicator(progress = { job.progress!! }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(job.phase.label, style = MaterialTheme.typography.bodyMedium,
                        color = if (job.phase == DownloadPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (job.receivedBytes > 0) Text(formatBytes(job.receivedBytes) +
                        (job.expectedBytes?.let { " / ${formatBytes(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (job.phase == DownloadPhase.COMPLETED && !saved) Text("File removed", style = MaterialTheme.typography.bodySmall)
                }
                when {
                    job.phase.canPause -> IconButton(onClick = onPause) { RuneIcon(R.drawable.rune_pause, "Pause download") }
                    job.phase == DownloadPhase.PAUSED -> TextButton(onClick = onResume) { Text("Resume") }
                    job.phase == DownloadPhase.FAILED || job.phase == DownloadPhase.CANCELLED -> TextButton(onClick = onResume) { Text("Retry") }
                    job.phase == DownloadPhase.COMPLETED && saved -> IconButton(onClick = onOpen) { RuneIcon(R.drawable.rune_play, "Open saved media") }
                }
            }
            job.message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (job.phase == DownloadPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

package com.maynkudu.yoinkd.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maynkudu.yoinkd.core.model.formatBytes
import com.maynkudu.yoinkd.data.extraction.RuntimeSetupPhase
import com.maynkudu.yoinkd.data.extraction.RuntimeSetupState
import com.maynkudu.yoinkd.data.updates.AppUpdatePhase
import com.maynkudu.yoinkd.data.updates.AppUpdateState

@Composable
fun MediaSetupBanner(state: RuntimeSetupState, onRetry: () -> Unit, onPause: () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.message, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.phase == RuntimeSetupPhase.DOWNLOADING && state.total != null)
                    Text("${formatBytes(state.received)} of ${formatBytes(state.total)}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = if (state.running) onPause else onRetry) {
                Text(if (state.running) "Pause" else if (state.phase == RuntimeSetupPhase.PAUSED) "Resume" else "Retry")
            }
        }
        if (state.running) {
            if (state.progress != null) LinearProgressIndicator(progress = { state.progress!! }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun MediaSetupContent(state: RuntimeSetupState, onRetry: () -> Unit, onPause: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.message, style = MaterialTheme.typography.titleMedium)
        if (state.running) {
            if (state.progress != null) LinearProgressIndicator(progress = { state.progress!! }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.phase == RuntimeSetupPhase.DOWNLOADING && state.total != null)
            Text("${formatBytes(state.received)} of ${formatBytes(state.total)}", style = MaterialTheme.typography.bodyMedium)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (state.running) TextButton(onClick = onPause) { Text("Pause setup") }
        else if (!state.ready) Button(onClick = onRetry) { Text(if (state.phase == RuntimeSetupPhase.PAUSED) "Resume setup" else "Retry setup") }
    }
}

@Composable
fun AppUpdateProgress(state: AppUpdateState) {
    if (state.progress != null) {
        LinearProgressIndicator(progress = { state.progress!! }, modifier = Modifier.fillMaxWidth())
        Text("${formatBytes(state.received)} of ${formatBytes(state.candidate!!.bytes)}", style = MaterialTheme.typography.bodyMedium)
    } else if (state.downloading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Verifying app update", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun AppUpdateDialog(state: AppUpdateState, onDownload: () -> Unit, onPause: () -> Unit, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val candidate = state.candidate ?: return
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(when {
            state.ready -> "Update ready"
            state.phase == AppUpdatePhase.VERIFYING -> "Verifying update"
            state.downloading -> "Downloading update"
            else -> "Update available"
        }) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("yoink'd ${candidate.versionName}")
            if (state.ready) Text("Android will ask you to confirm installation.")
            else if (!state.downloading) Text("${formatBytes(candidate.bytes)} download for this device.")
            AppUpdateProgress(state)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {
            if (state.downloading) TextButton(onClick = onPause) { Text("Pause download") }
            else TextButton(onClick = if (state.ready) onInstall else onDownload, enabled = state.ready || !state.checking) {
                Text(if (state.ready) "Install update" else "Download update")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } })
}

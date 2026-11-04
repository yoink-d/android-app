package com.maynkudu.yoinkd.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.core.design.EmptyState
import com.maynkudu.yoinkd.core.design.RuneIcon
import com.maynkudu.yoinkd.core.model.DownloadPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(model: DownloadsViewModel, onCapture: () -> Unit) {
    val jobs by model.jobs.collectAsStateWithLifecycle()
    val active = jobs.filter { !it.phase.terminal }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text("Downloads", style = MaterialTheme.typography.headlineLarge) },
            actions = {
                if (active.any { it.phase.canPause }) IconButton(onClick = { model.pauseAll() }) { RuneIcon(R.drawable.rune_pause, "Pause all downloads") }
                IconButton(onClick = onCapture) { RuneIcon(R.drawable.rune_plus, "Add media") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }) { padding ->
        if (active.isEmpty()) EmptyState("No active downloads", "Add a link to download videos or audio.", Modifier.padding(padding).padding(28.dp)) {
            Button(onClick = onCapture) { RuneIcon(R.drawable.rune_link); Spacer(Modifier.width(10.dp)); Text("yoink!") }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val groups = listOf("Active" to active.filter { it.phase.occupiesSlot },
                "Queued" to active.filter { it.phase == DownloadPhase.QUEUED }, "Paused" to active.filter { it.phase == DownloadPhase.PAUSED })
            groups.filter { it.second.isNotEmpty() }.forEach { (name, group) ->
                item { Text("$name (${group.size})", style = MaterialTheme.typography.titleMedium) }
                items(group, key = { it.id }) { job -> DownloadCard(job, onPause = { model.pause(job.id) },
                    onCancel = { model.cancel(job.id) }, onResume = { model.resume(job.id) }) }
            }
        }
    }
}

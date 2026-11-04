package com.maynkudu.yoinkd.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.core.design.CollectionHeader
import com.maynkudu.yoinkd.core.design.EmptyState
import com.maynkudu.yoinkd.core.design.MediaFilters
import com.maynkudu.yoinkd.core.design.RuneIcon
import com.maynkudu.yoinkd.core.design.SortMenu
import com.maynkudu.yoinkd.core.model.DisplayMode
import com.maynkudu.yoinkd.core.model.MediaItem
import com.maynkudu.yoinkd.core.model.filterAndSort
import com.maynkudu.yoinkd.feature.downloads.DownloadCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HistoryScreen(model: HistoryViewModel, onOpen: (MediaItem) -> Unit) {
    val jobs by model.jobs.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val preferences by model.preferences.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val terminal = jobs.filter { it.phase.terminal }
    val visible = filterAndSort(terminal, query, preferences.historyFilter, preferences.historySort,
        { it.title }, { it.selection.kind }, { it.createdAt })
    val groups = visible.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate() }
    val saved = library.associateBy { it.id }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.background,
        topBar = { CollectionHeader("History", query, searching, { query = it }, {
            searching = it; if (!it) { query = ""; focus.clearFocus() }
        }) {
            SortMenu(preferences.historySort, preferences.historyDisplay, model::sort, model::display)
            if (terminal.isNotEmpty()) IconButton(onClick = { clearing = true }) { RuneIcon(R.drawable.rune_trash, "Clear history") }
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (terminal.isNotEmpty()) MediaFilters(preferences.historyFilter, model::filter, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            if (visible.isEmpty()) EmptyState(if (terminal.isEmpty()) "No download history" else "No matching downloads",
                if (terminal.isEmpty()) "Completed, failed, and cancelled downloads appear here." else "Try another search or filter.", Modifier.padding(28.dp))
            else if (preferences.historyDisplay == DisplayMode.LIST) LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                groups.forEach { (date, entries) ->
                    item { Text(dateLabel(date), style = MaterialTheme.typography.titleMedium) }
                    items(entries, key = { it.id }) { job -> DownloadCard(job, saved = job.storedItemId in saved,
                        onResume = { model.retry(job.id) }, onOpen = { saved[job.storedItemId]?.let(onOpen) }, onRemove = { model.remove(job.id) }) }
                }
            } else {
                val width = if (LocalDensity.current.fontScale >= 1.5f) 280.dp else 160.dp
                LazyVerticalGrid(GridCells.Adaptive(width), Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    groups.forEach { (date, entries) ->
                        item(span = { GridItemSpan(maxLineSpan) }) { Text(dateLabel(date), style = MaterialTheme.typography.titleMedium) }
                        items(entries, key = { it.id }) { job -> DownloadCard(job, compact = true, saved = job.storedItemId in saved,
                            onResume = { model.retry(job.id) }, onOpen = { saved[job.storedItemId]?.let(onOpen) }, onRemove = { model.remove(job.id) }) }
                    }
                }
            }
        }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("Clear download history?") },
        text = { Text("Saved files stay in your library.") },
        confirmButton = { TextButton(onClick = { model.clear(); clearing = false }) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } })
}

private fun dateLabel(date: LocalDate): String = when (date) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

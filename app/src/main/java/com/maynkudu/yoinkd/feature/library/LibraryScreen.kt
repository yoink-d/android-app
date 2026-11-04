package com.maynkudu.yoinkd.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.core.design.CollectionHeader
import com.maynkudu.yoinkd.core.design.EmptyState
import com.maynkudu.yoinkd.core.design.MediaArtwork
import com.maynkudu.yoinkd.core.design.MediaFilters
import com.maynkudu.yoinkd.core.design.RuneIcon
import com.maynkudu.yoinkd.core.design.SortMenu
import com.maynkudu.yoinkd.core.model.DisplayMode
import com.maynkudu.yoinkd.core.model.MediaItem
import com.maynkudu.yoinkd.core.model.filterAndSort
import com.maynkudu.yoinkd.core.model.formatBytes
import com.maynkudu.yoinkd.core.model.formatDuration

@Composable
fun LibraryScreen(model: LibraryViewModel, onCapture: () -> Unit, onImport: () -> Unit,
    onOpen: (MediaItem) -> Unit, onShare: (MediaItem) -> Unit) {
    val items by model.items.collectAsStateWithLifecycle()
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val storage by model.storage.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<MediaItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<MediaItem?>(null) }
    val focus = LocalFocusManager.current
    val visible = remember(items, query, preferences) { filterAndSort(items, query, preferences.libraryFilter,
        preferences.librarySort, { it.title }, { it.kind }, { it.addedAt }) }
    fun open(item: MediaItem) { searching = false; query = ""; focus.clearFocus(); onOpen(item) }
    val actions: @Composable (MediaItem) -> Unit = { item ->
        ItemMenu(item, onShare = { onShare(item) }, onRename = { renaming = item; renameText = item.title }, onDelete = { deleting = item })
    }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.background,
        topBar = { CollectionHeader("Library", query, searching, { query = it }, {
            searching = it; if (!it) { query = ""; focus.clearFocus() }
        }) {
            SortMenu(preferences.librarySort, preferences.libraryDisplay, model::sort, model::display)
            Box {
                IconButton(onClick = { menu = true }) { RuneIcon(R.drawable.rune_plus, "Add media") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("yoink!") }, onClick = { menu = false; onCapture() }, leadingIcon = { RuneIcon(R.drawable.rune_link) })
                    DropdownMenuItem(text = { Text("Import files") }, enabled = !storage.busy,
                        onClick = { menu = false; onImport() }, leadingIcon = { RuneIcon(R.drawable.rune_folder) })
                }
            }
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (storage.importing) Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Importing ${storage.name}", maxLines = 2)
                    Text("${storage.index} of ${storage.total}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (items.isNotEmpty()) {
                if (!searching) Text("${items.size} ${if (items.size == 1) "file" else "files"}, ${formatBytes(items.sumOf { it.bytes })}",
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                MediaFilters(preferences.libraryFilter, model::filter, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            when {
                items.isEmpty() -> EmptyState("No saved media", "Add a link or import files to save video and audio.", Modifier.padding(28.dp)) {
                    Button(onClick = onCapture) { RuneIcon(R.drawable.rune_link); Spacer(Modifier.width(10.dp)); Text("yoink!") }
                    TextButton(onClick = onImport, enabled = !storage.busy) { Text("Import files") }
                }
                visible.isEmpty() -> EmptyState("No matching media", "Try another search or filter.", Modifier.padding(28.dp))
                preferences.libraryDisplay == DisplayMode.LIST -> LazyColumn(Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
                    items(visible, key = { it.id }) { item ->
                        Surface(shape = RoundedCornerShape(18.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                MediaArtwork(item.thumbnailPath, item.kind, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).clickable { open(item) })
                                Column(Modifier.weight(1f).clickable { open(item) }.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${item.kind.label} · ${formatDuration(item.duration)} · ${formatBytes(item.bytes)}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                actions(item)
                            }
                        }
                    }
                }
                else -> {
                    val minimum = if (LocalDensity.current.fontScale >= 1.5f) 280.dp else 160.dp
                    LazyVerticalGrid(GridCells.Adaptive(minimum), Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(visible, key = { it.id }) { item ->
                            Column {
                                Column(Modifier.clickable { open(item) }) {
                                    MediaArtwork(item.thumbnailPath, item.kind, Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(18.dp)))
                                    Spacer(Modifier.height(8.dp))
                                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${item.kind.label} · ${formatDuration(item.duration)}\n${formatBytes(item.bytes)}", Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    actions(item)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { item -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename media") },
        text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("Title") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { model.rename(item, renameText); renaming = null }, enabled = renameText.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }) }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this file?") },
        text = { Text("This removes the saved copy from yoink'd.") },
        confirmButton = { TextButton(onClick = { model.delete(item); deleting = null }) { Text("Delete file", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun ItemMenu(item: MediaItem, onShare: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { RuneIcon(R.drawable.rune_menu, "Actions for ${item.title}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Share file") }, onClick = { expanded = false; onShare() }, leadingIcon = { RuneIcon(R.drawable.rune_share) })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text("Delete file", color = MaterialTheme.colorScheme.error) }, onClick = { expanded = false; onDelete() },
                leadingIcon = { RuneIcon(R.drawable.rune_trash) })
        }
    }
}

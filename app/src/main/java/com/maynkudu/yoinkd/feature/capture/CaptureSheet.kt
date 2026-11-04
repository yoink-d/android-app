package com.maynkudu.yoinkd.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.core.design.RuneIcon
import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.core.model.OutputFormat
import com.maynkudu.yoinkd.core.model.SourceMedia
import com.maynkudu.yoinkd.core.model.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(model: CaptureViewModel, onClose: () -> Unit, onQueued: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    var qualityOpen by remember { mutableStateOf(false) }
    var outputOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("yoink!", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { RuneIcon(R.drawable.rune_close, "Close capture") }
            }
            OutlinedTextField(state.input, model::input, Modifier.fillMaxWidth(), label = { Text("Media link") },
                placeholder = { Text("https://") }, singleLine = true,
                trailingIcon = { if (state.input.isNotEmpty()) IconButton(onClick = { model.input("") }) { RuneIcon(R.drawable.rune_close, "Clear link") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); model.inspect() }),
                shape = RoundedCornerShape(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.input(clipboard.getText()?.text.orEmpty()); focus.clearFocus() }) {
                    RuneIcon(R.drawable.rune_link); Spacer(Modifier.width(8.dp)); Text("Paste link")
                }
                if (state.checking) TextButton(onClick = model::cancelInspection) { Text("Cancel check") }
                else Button(onClick = { focus.clearFocus(); model.inspect() }, enabled = state.input.isNotBlank() && !state.enqueuing) { Text("Check link") }
            }
            if (state.checking) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp)); Text("Checking source...")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            state.media?.let { media ->
                SourceSummary(media)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    MediaKind.entries.forEachIndexed { index, kind ->
                        SegmentedButton(state.kind == kind, onClick = { model.kind(kind) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2), enabled = media.choices(kind).isNotEmpty()) { Text(kind.label) }
                    }
                }
                Surface(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Source quality", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { qualityOpen = true }, Modifier.fillMaxWidth()) {
                            Text(if (state.formatId == null) "Best available" else media.selected(state.kind, state.formatId)?.label(state.kind).orEmpty(),
                                modifier = Modifier.weight(1f))
                            RuneIcon(R.drawable.rune_menu)
                        }
                        Text(media.size(state.kind, state.formatId)?.let { "${it.label} source download" } ?: "Source size unavailable",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saved format", style = MaterialTheme.typography.titleMedium)
                        Box {
                            TextButton(onClick = { outputOpen = true }, Modifier.fillMaxWidth()) {
                                Text(state.output.label, modifier = Modifier.weight(1f)); RuneIcon(R.drawable.rune_menu)
                            }
                            DropdownMenu(outputOpen, onDismissRequest = { outputOpen = false }) {
                                (state.support?.available(state.kind) ?: listOf(OutputFormat.AUTOMATIC)).forEach { format ->
                                    DropdownMenuItem(text = { Text(format.label) }, onClick = { model.output(format); outputOpen = false },
                                        trailingIcon = { if (state.output == format) RuneIcon(R.drawable.rune_check) })
                                }
                            }
                        }
                        Text(if (state.output == OutputFormat.FLAC) "FLAC preserves decoded audio. It cannot restore quality lost in a lossy source."
                            else "Conversion can change the saved file size.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onClick = { focus.clearFocus(); model.download(onQueued) },
                    enabled = !state.enqueuing && state.support != null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (state.enqueuing) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else RuneIcon(R.drawable.rune_download)
                    Spacer(Modifier.width(10.dp)); Text(if (state.enqueuing) "Adding download" else "Download ${state.kind.label.lowercase()}")
                }
            }
        }
    }
    if (qualityOpen && state.media != null) QualityDialog(state.media!!, state.kind, state.formatId,
        onChoose = { model.quality(it); qualityOpen = false }, onClose = { qualityOpen = false })
}

@Composable
private fun SourceSummary(media: SourceMedia) {
    Surface(shape = RoundedCornerShape(18.dp)) {
        Column {
            if (media.thumbnail != null) AsyncImage(media.thumbnail, null, Modifier.fillMaxWidth().height(175.dp), contentScale = ContentScale.Crop)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(media.title, style = MaterialTheme.typography.titleLarge)
                Text(listOfNotNull(media.platform, media.uploader, media.duration?.let { formatDuration(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QualityDialog(media: SourceMedia, kind: MediaKind, selected: String?, onChoose: (String?) -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("${kind.label} source quality") },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } }, text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChoose(null) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == null, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Best available", style = MaterialTheme.typography.titleMedium)
                            Text("Highest available source", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(media.choices(kind), key = { it.id }) { format ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(
                        if (selected == format.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { onChoose(format.id) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == format.id, onClick = null)
                        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(format.label(kind), style = MaterialTheme.typography.titleMedium)
                            Text(format.details(kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(format.size(media.duration)?.label ?: "Size unavailable", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        })
}

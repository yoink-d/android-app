package com.maynkudu.yoinkd.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maynkudu.yoinkd.BuildConfig
import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.core.design.BrandMark
import com.maynkudu.yoinkd.core.design.RuneIcon
import com.maynkudu.yoinkd.core.design.themeColors
import com.maynkudu.yoinkd.core.model.AccentTheme
import com.maynkudu.yoinkd.core.model.Appearance
import com.maynkudu.yoinkd.core.model.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: SettingsViewModel, onFolder: () -> Unit, onNotices: () -> Unit,
    onAppUpdate: () -> Unit, onInstallUpdate: () -> Unit) {
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val media by model.media.collectAsStateWithLifecycle()
    val jobs by model.jobs.collectAsStateWithLifecycle()
    val storage by model.storage.collectAsStateWithLifecycle()
    val updates by model.updates.collectAsStateWithLifecycle()
    val initialized by model.initialized.collectAsStateWithLifecycle()
    val setup by model.setup.collectAsStateWithLifecycle()
    val appUpdates by model.appUpdates.collectAsStateWithLifecycle()
    val folderBlocked = !initialized || storage.busy || jobs.any { !it.phase.terminal }
    val dark = MaterialTheme.colorScheme.background.red < 0.5f
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text("Settings", style = MaterialTheme.typography.headlineLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { SettingsSection("Appearance") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Appearance.entries.forEachIndexed { index, appearance ->
                        SegmentedButton(preferences.appearance == appearance, onClick = { model.appearance(appearance) },
                            shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(appearance.label) }
                    }
                }
                AccentTheme.entries.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { theme ->
                            val selected = theme == preferences.theme
                            val color = if (theme == AccentTheme.DYNAMIC && selected) MaterialTheme.colorScheme.primary
                                else themeColors(theme.hue, theme.saturation, dark).accent
                            Surface(onClick = { model.theme(theme) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                                color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                                Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(Modifier.size(32.dp), color = color, shape = CircleShape) {
                                        if (selected) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.rune_check), null,
                                            Modifier.padding(7.dp), tint = themeColors(theme.hue, theme.saturation, dark).onAccent)
                                    }
                                    Text(theme.label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            } }
            item { SettingsSection("Saved media") {
                SettingValue("Files", media.size.toString())
                SettingValue("Media size", formatBytes(media.sumOf { it.bytes }))
                SettingValue("Folder", preferences.folderName)
                if (storage.moving) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Moving ${storage.name}", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onFolder, enabled = !folderBlocked) { RuneIcon(R.drawable.rune_folder); Spacer(Modifier.width(8.dp)); Text("Choose folder") }
                TextButton(onClick = { model.folder(null) }, enabled = !folderBlocked && preferences.folderUri != null) {
                    RuneIcon(R.drawable.rune_refresh); Spacer(Modifier.width(8.dp)); Text("Use Downloads folder")
                }
                if (folderBlocked && !storage.busy) Text("Finish or cancel unfinished downloads to change folders.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item { SettingsSection("Media links") {
                Text("Download public media from YouTube, Instagram, X, and other supported sites. Private links and sources that require sign-in may be unavailable.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item { SettingsSection("App updates") {
                SettingValue("Installed", BuildConfig.VERSION_NAME)
                appUpdates.candidate?.let { SettingValue("Available", it.versionName) }
                if (appUpdates.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                AppUpdateProgress(appUpdates)
                appUpdates.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                appUpdates.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = model::checkAppUpdates, enabled = !appUpdates.checking && !appUpdates.downloading) { Text("Check for app updates") }
                if (appUpdates.downloading) TextButton(onClick = model::pauseAppUpdate) { Text("Pause download") }
                else if (appUpdates.ready) Button(onClick = onInstallUpdate) { Text("Install update") }
                else if (appUpdates.candidate != null) Button(onClick = onAppUpdate) { Text("View update") }
            } }
            item { SettingsSection("Media tools") {
                MediaSetupContent(setup, model::retrySetup, model::pauseSetup)
                if (setup.ready) SettingValue("Package version", model.runtimeVersion)
            } }
            item { SettingsSection("Extraction updates") {
                SettingValue("Installed", updates.installed)
                updates.latest?.let { SettingValue("Latest", it) }
                if (updates.checking || updates.updating) {
                    if (updates.progress != null) LinearProgressIndicator(progress = { updates.progress!! }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                updates.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                updates.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = { model.checkUpdates() }, enabled = setup.ready && !updates.checking && !updates.updating) { Text("Check updates") }
                if (updates.updating && model.canCancelUpdate) TextButton(onClick = model::cancelUpdate) { Text("Cancel update") }
                else if (!updates.updating) Button(onClick = model::update, enabled = setup.ready && !updates.checking && updates.latest != updates.installed) { Text("Update extractor") }
                if (updates.canRollback) TextButton(onClick = { model.rollback() }, enabled = setup.ready && !updates.checking && !updates.updating) { Text("Restore previous extractor") }
            } }
            item { Surface(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(Modifier.size(48.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("yoink'd", style = MaterialTheme.typography.titleLarge)
                            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = onNotices) { Text("Third-party notices") }
                }
            } }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Surface(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Composable
private fun SettingValue(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

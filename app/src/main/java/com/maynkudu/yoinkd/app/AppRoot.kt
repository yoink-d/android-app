package com.maynkudu.yoinkd.app

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.core.design.RuneIcon
import com.maynkudu.yoinkd.core.design.YoinkTheme
import com.maynkudu.yoinkd.feature.capture.CaptureSheet
import com.maynkudu.yoinkd.feature.capture.CaptureViewModel
import com.maynkudu.yoinkd.feature.downloads.DownloadsScreen
import com.maynkudu.yoinkd.feature.downloads.DownloadsViewModel
import com.maynkudu.yoinkd.feature.history.HistoryScreen
import com.maynkudu.yoinkd.feature.history.HistoryViewModel
import com.maynkudu.yoinkd.feature.library.LibraryScreen
import com.maynkudu.yoinkd.feature.library.LibraryViewModel
import com.maynkudu.yoinkd.feature.player.PlayerScreen
import com.maynkudu.yoinkd.feature.player.PlayerViewModel
import com.maynkudu.yoinkd.feature.settings.SettingsScreen
import com.maynkudu.yoinkd.feature.settings.SettingsViewModel

@Composable
fun AppRoot(model: AppViewModel) {
    val container = model.container
    val preferences by container.preferences.preferences.collectAsStateWithLifecycle()
    val artwork by container.artwork.seed.collectAsStateWithLifecycle()
    val request by model.request.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val error by container.error.collectAsStateWithLifecycle()
    val initialized by container.initialized.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var notices by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val stateHolder = rememberSaveableStateHolder()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) model.import(uris) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                model.folder(uri.toString())
            } catch (cause: Exception) { container.report(cause) }
        }
    }
    LaunchedEffect(initialized) {
        if (initialized && container.jobs.all().any { it.phase == com.maynkudu.yoinkd.core.model.DownloadPhase.QUEUED })
            com.maynkudu.yoinkd.download.DownloadService.requestStart(context)
    }
    YoinkTheme(preferences.appearance, preferences.theme, artwork) {
        val dark = MaterialTheme.colorScheme.background.red < 0.5f
        SideEffect { activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        } }
        if (selected != null) {
            val item = selected!!
            val player = scopedViewModel("player-${item.id}") { PlayerViewModel(context.applicationContext, container.library) }
            PlayerScreen(player, item, model::closePlayer) { try { shareMedia(context, item) } catch (cause: Exception) { container.report(cause) } }
        } else Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val titles = listOf("Library", "Downloads", "History", "Settings")
                    val icons = listOf(R.drawable.rune_folder, R.drawable.rune_download, R.drawable.rune_history, R.drawable.rune_settings)
                    titles.forEachIndexed { index, title -> NavigationBarItem(tab == index, onClick = { tab = index },
                        icon = { RuneIcon(icons[index]) }, label = { Text(title, style = MaterialTheme.typography.labelMedium) }) }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (!initialized) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else stateHolder.SaveableStateProvider(tab) {
                    when (tab) {
                        0 -> LibraryScreen(screenViewModel { LibraryViewModel(container) }, model::capture,
                            onImport = { files.launch(arrayOf("video/*", "audio/*", "application/octet-stream")) }, onOpen = model::open,
                            onShare = { try { shareMedia(context, it) } catch (cause: Exception) { container.report(cause) } })
                        1 -> DownloadsScreen(screenViewModel { DownloadsViewModel(container) }, model::capture)
                        2 -> HistoryScreen(screenViewModel { HistoryViewModel(container) }, model::open)
                        3 -> SettingsScreen(screenViewModel { SettingsViewModel(container) }, onFolder = { folder.launch(null) }, onNotices = { notices = true })
                    }
                }
            }
        }
        when (val incoming = request) {
            is IncomingRequest.Link -> CaptureSheet(scopedViewModel(incoming.id) { CaptureViewModel(container, incoming) }, model::finishRequest,
                onQueued = {
                    model.finishRequest(); tab = 1
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
            is IncomingRequest.Files -> AlertDialog(onDismissRequest = model::finishRequest, title = { Text("Import shared media?") },
                text = { Text("Save ${incoming.uris.size} ${if (incoming.uris.size == 1) "file" else "files"} to your library.") },
                confirmButton = { TextButton(onClick = { model.import(incoming.uris); model.finishRequest(); tab = 0 }) { Text("Import") } },
                dismissButton = { TextButton(onClick = model::finishRequest) { Text("Cancel") } })
            null -> Unit
        }
        error?.let { message -> AlertDialog(onDismissRequest = { container.error.value = null }, title = { Text("Could not finish") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { container.error.value = null }) { Text("OK") } }) }
        if (notices) AlertDialog(onDismissRequest = { notices = false }, title = { Text("Third-party notices") },
            text = { Text(context.assets.open("third-party-notices.txt").bufferedReader().use { it.readText() },
                Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { notices = false }) { Text("Done") } })
    }
}

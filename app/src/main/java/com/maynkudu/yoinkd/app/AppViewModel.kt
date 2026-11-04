package com.maynkudu.yoinkd.app

import android.net.Uri
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maynkudu.yoinkd.core.model.MediaItem
import com.maynkudu.yoinkd.data.updates.AppUpdateCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AppViewModel(val container: AppContainer) : ViewModel() {
    val request = MutableStateFlow<IncomingRequest?>(null)
    val selected = MutableStateFlow<MediaItem?>(null)
    val updatePrompt = MutableStateFlow(false)
    var dismissedUpdateVersion: Long? = null
        private set
    private var installation: AppUpdateCandidate? = null

    fun showAppUpdate() { updatePrompt.value = true }
    fun dismissAppUpdate() {
        dismissedUpdateVersion = container.appUpdates.state.value.candidate?.versionCode
        updatePrompt.value = false
    }
    fun prepareAppInstallation() {
        installation = container.appUpdates.state.value.takeIf { it.ready }?.candidate
    }
    fun installAppUpdate(launch: (Intent) -> Unit) = viewModelScope.launch {
        try {
            val candidate = installation ?: error("Download the app update before installing it.")
            launch(container.appUpdates.installationIntent(candidate))
            installation = null
            dismissAppUpdate()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { container.appUpdates.installationFailed(error.message ?: "Android could not open the app installer.") }
    }

    fun capture() { request.value = IncomingRequest.Link() }
    fun finishRequest() { request.value = null }
    fun open(item: MediaItem) { container.artwork.select(item); selected.value = item }
    fun closePlayer() { selected.value = null }
    fun folder(uri: String) = viewModelScope.launch {
        try { container.library.relocate(uri) } catch (error: Exception) { container.report(error) }
    }
    fun import(uris: List<Uri>) = viewModelScope.launch {
        try {
            val result = container.library.import(uris)
            if (result.errors.isNotEmpty()) container.error.value = result.errors.joinToString("\n").take(800)
        } catch (error: Exception) { container.report(error) }
    }
}

package com.maynkudu.yoinkd.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maynkudu.yoinkd.app.AppContainer
import com.maynkudu.yoinkd.core.model.AccentTheme
import com.maynkudu.yoinkd.core.model.Appearance
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val preferences = container.preferences.preferences
    val media = container.library.items
    val jobs = container.jobs.jobs
    val storage = container.storage.status
    val updates = container.updates.state
    val initialized = container.initialized
    val setup = container.runtimePackages.state
    val appUpdates = container.appUpdates.state
    val runtimeVersion = container.runtimePackages.manifest.version
    val canCancelUpdate: Boolean get() = container.canCancelExtractorUpdate

    fun appearance(value: Appearance) = viewModelScope.launch { container.preferences.update { it.copy(appearance = value) } }
    fun theme(value: AccentTheme) = viewModelScope.launch { container.preferences.update { it.copy(theme = value) } }
    fun folder(uri: String?) = viewModelScope.launch {
        try { container.library.relocate(uri) } catch (error: Exception) { container.report(error) }
    }
    fun checkUpdates() = viewModelScope.launch { container.updates.check() }
    fun update() { container.updateExtractor() }
    fun cancelUpdate() { container.cancelExtractorUpdate() }
    fun rollback() = viewModelScope.launch { container.updates.rollback() }
    fun retrySetup() { container.prepareMediaTools() }
    fun pauseSetup() { container.pauseMediaSetup() }
    fun checkAppUpdates() { container.checkAppUpdates() }
    fun downloadAppUpdate() { container.downloadAppUpdate() }
    fun pauseAppUpdate() { container.pauseAppUpdate() }
}

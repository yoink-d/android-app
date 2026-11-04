package com.maynkudu.yoinkd.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maynkudu.yoinkd.app.AppContainer
import kotlinx.coroutines.launch

class DownloadsViewModel(private val container: AppContainer) : ViewModel() {
    val jobs = container.jobs.jobs
    fun pause(id: String) = viewModelScope.launch { container.downloads.pause(id) }
    fun cancel(id: String) = viewModelScope.launch { container.downloads.pause(id, cancel = true) }
    fun resume(id: String) = viewModelScope.launch { container.downloads.resume(id) }
    fun pauseAll() = viewModelScope.launch { container.downloads.pauseAll() }
}

package com.maynkudu.yoinkd.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maynkudu.yoinkd.app.AppContainer
import com.maynkudu.yoinkd.core.model.DisplayMode
import com.maynkudu.yoinkd.core.model.MediaFilter
import com.maynkudu.yoinkd.core.model.MediaSort
import kotlinx.coroutines.launch

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    val jobs = container.jobs.jobs
    val library = container.library.items
    val preferences = container.preferences.preferences
    fun sort(value: MediaSort) = viewModelScope.launch { container.preferences.update { it.copy(historySort = value) } }
    fun display(value: DisplayMode) = viewModelScope.launch { container.preferences.update { it.copy(historyDisplay = value) } }
    fun filter(value: MediaFilter) = viewModelScope.launch { container.preferences.update { it.copy(historyFilter = value) } }
    fun retry(id: String) = viewModelScope.launch { container.downloads.resume(id) }
    fun remove(id: String) = viewModelScope.launch { container.downloads.removeHistory(id) }
    fun clear() = viewModelScope.launch { container.downloads.clearHistory() }
}

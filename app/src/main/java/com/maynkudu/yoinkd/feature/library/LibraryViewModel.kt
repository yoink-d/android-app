package com.maynkudu.yoinkd.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maynkudu.yoinkd.app.AppContainer
import com.maynkudu.yoinkd.core.model.DisplayMode
import com.maynkudu.yoinkd.core.model.MediaFilter
import com.maynkudu.yoinkd.core.model.MediaItem
import com.maynkudu.yoinkd.core.model.MediaSort
import kotlinx.coroutines.launch

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    val items = container.library.items
    val preferences = container.preferences.preferences
    val storage = container.storage.status
    fun sort(value: MediaSort) = viewModelScope.launch { container.preferences.update { it.copy(librarySort = value) } }
    fun display(value: DisplayMode) = viewModelScope.launch { container.preferences.update { it.copy(libraryDisplay = value) } }
    fun filter(value: MediaFilter) = viewModelScope.launch { container.preferences.update { it.copy(libraryFilter = value) } }
    fun rename(item: MediaItem, title: String) = viewModelScope.launch {
        try { container.library.rename(item, title) } catch (error: Exception) { container.report(error) }
    }
    fun delete(item: MediaItem) = viewModelScope.launch {
        try { container.library.delete(item); container.artwork.remove(item.id) }
        catch (error: Exception) { container.report(error) }
    }
}

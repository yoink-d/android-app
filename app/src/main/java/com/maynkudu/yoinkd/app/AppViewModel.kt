package com.maynkudu.yoinkd.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maynkudu.yoinkd.core.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AppViewModel(val container: AppContainer) : ViewModel() {
    val request = MutableStateFlow<IncomingRequest?>(null)
    val selected = MutableStateFlow<MediaItem?>(null)

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

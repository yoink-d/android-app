package com.maynkudu.yoinkd.feature.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maynkudu.yoinkd.app.AppContainer
import com.maynkudu.yoinkd.app.IncomingRequest
import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.core.model.MediaLink
import com.maynkudu.yoinkd.core.model.OutputFormat
import com.maynkudu.yoinkd.core.model.OutputSupport
import com.maynkudu.yoinkd.core.model.SourceMedia
import com.maynkudu.yoinkd.core.model.SourceSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CaptureState(val input: String = "", val media: SourceMedia? = null,
    val checking: Boolean = false, val enqueuing: Boolean = false, val error: String? = null,
    val kind: MediaKind = MediaKind.VIDEO, val formatId: String? = null,
    val output: OutputFormat = OutputFormat.AUTOMATIC, val support: OutputSupport? = null)

class CaptureViewModel(private val container: AppContainer, request: IncomingRequest.Link) : ViewModel() {
    private val mutableState = MutableStateFlow(CaptureState(input = request.text, kind = request.kind ?: MediaKind.VIDEO))
    val state = mutableState.asStateFlow()
    private var inspection: Job? = null
    private var revision = 0L

    init {
        viewModelScope.launch {
            try { mutableState.value = mutableState.value.copy(support = container.runtime.outputSupport()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message) }
        }
    }

    fun input(value: String) {
        revision++
        inspection?.cancel()
        mutableState.value = mutableState.value.copy(input = value, checking = false, media = null,
            error = null, formatId = null, output = OutputFormat.AUTOMATIC)
        container.artwork.endPreview()
    }

    fun cancelInspection() {
        revision++
        inspection?.cancel()
        mutableState.value = mutableState.value.copy(checking = false)
    }

    fun inspect() {
        inspection?.cancel()
        val identity = ++revision
        val link = try { MediaLink.parse(state.value.input) }
        catch (error: Exception) { mutableState.value = state.value.copy(error = error.message); return }
        mutableState.value = state.value.copy(checking = true, error = null, media = null)
        inspection = viewModelScope.launch {
            try {
                val media = container.inspector.inspect(link)
                val support = state.value.support ?: container.runtime.outputSupport()
                if (identity != revision) return@launch
                val kind = if (media.choices(state.value.kind).isNotEmpty()) state.value.kind
                    else if (media.choices(MediaKind.VIDEO).isNotEmpty()) MediaKind.VIDEO else MediaKind.AUDIO
                mutableState.value = state.value.copy(checking = false, media = media, kind = kind,
                    formatId = null, output = OutputFormat.AUTOMATIC, support = support)
                container.artwork.preview(link.value, media.thumbnail)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (identity == revision) mutableState.value = state.value.copy(checking = false, error = error.message ?: "Could not check this link.")
            }
        }
    }

    fun kind(kind: MediaKind) { mutableState.value = state.value.copy(kind = kind, formatId = null, output = OutputFormat.AUTOMATIC) }
    fun quality(id: String?) { mutableState.value = state.value.copy(formatId = id) }
    fun output(format: OutputFormat) { mutableState.value = state.value.copy(output = format) }

    fun download(onQueued: () -> Unit) = viewModelScope.launch {
        val current = state.value
        val media = current.media ?: return@launch
        mutableState.value = current.copy(enqueuing = true, error = null)
        try {
            val quality = if (current.formatId == null) "Best available"
                else media.selected(current.kind, current.formatId)?.label(current.kind) ?: "Original"
            container.downloads.enqueue(media, SourceSelection(current.kind, current.formatId, current.output, quality))
            onQueued()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.value = state.value.copy(error = error.message) }
        finally { mutableState.value = state.value.copy(enqueuing = false) }
    }

    override fun onCleared() { container.artwork.endPreview(); super.onCleared() }
}

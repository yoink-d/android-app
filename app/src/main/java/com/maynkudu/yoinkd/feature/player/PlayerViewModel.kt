package com.maynkudu.yoinkd.feature.player

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.maynkudu.yoinkd.core.model.MediaItem
import com.maynkudu.yoinkd.core.model.MediaKind
import com.maynkudu.yoinkd.data.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaybackState(val player: ExoPlayer? = null, val loading: Boolean = true,
    val position: Long = 0, val duration: Long = 0, val playing: Boolean = false,
    val volume: Float = 0f, val error: String? = null)

class PlayerViewModel(context: Context, private val library: LibraryRepository) : ViewModel() {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val mutableState = MutableStateFlow(PlaybackState())
    val state = mutableState.asStateFlow()
    private var opening: Job? = null
    private var ticker: Job? = null
    private var identity = 0L
    private var itemId: String? = null

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun open(item: MediaItem) {
        if (itemId == item.id && state.value.player != null) return
        close()
        itemId = item.id
        val request = ++identity
        mutableState.value = PlaybackState(duration = (item.duration * 1_000).toLong(), volume = streamVolume())
        opening = viewModelScope.launch {
            try {
                val uri = library.playbackUri(item)
                if (request != identity) return@launch
                val player = ExoPlayer.Builder(appContext).setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(15_000).build()
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(if (item.kind == MediaKind.AUDIO) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                player.setHandleAudioBecomingNoisy(true)
                player.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { update() }
                    override fun onPlayerError(error: PlaybackException) {
                        mutableState.value = mutableState.value.copy(error = error.localizedMessage ?: "This file could not be played.", loading = false)
                    }
                })
                mutableState.value = mutableState.value.copy(player = player, loading = false)
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
                player.prepare()
                player.play()
                ticker = viewModelScope.launch { while (true) { update(); delay(250) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (request == identity) mutableState.value = state.value.copy(loading = false, error = error.message) }
        }
    }

    private fun update() {
        val player = state.value.player ?: return
        mutableState.value = state.value.copy(position = player.currentPosition.coerceAtLeast(0),
            duration = player.duration.takeIf { it > 0 } ?: state.value.duration,
            playing = player.isPlaying, volume = streamVolume())
    }

    fun toggle() {
        val player = state.value.player ?: return
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        update()
    }

    fun seek(position: Long) { state.value.player?.seekTo(position.coerceIn(0, state.value.duration)); update() }
    fun skip(milliseconds: Long) { seek(state.value.position + milliseconds) }
    fun volume(value: Float) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
            (value.coerceIn(0f, 1f) * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0)
        mutableState.value = state.value.copy(volume = streamVolume())
    }

    private fun streamVolume(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private fun close() {
        identity++
        opening?.cancel(); opening = null
        ticker?.cancel(); ticker = null
        state.value.player?.release()
        mutableState.value = PlaybackState(loading = false)
        itemId = null
    }

    override fun onCleared() { close(); super.onCleared() }
}

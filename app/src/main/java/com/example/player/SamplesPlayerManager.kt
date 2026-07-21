package com.example.player

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks play/pause + buffering state for whichever ExoPlayer is currently
 * the "active" page in the Samples (Shorts-style) feed.
 *
 * Each page in SamplesScreen owns its own short-lived ExoPlayer instance
 * (see SampleVideoPage) - this class doesn't create or hold a player of its
 * own, it just exposes whichever one is currently active as StateFlows so
 * SamplesScreen/SamplesViewModel can drive UI (play/pause icon, buffering
 * spinner, the "pause when leaving the tab" behavior) without reaching into
 * Compose-owned player instances directly.
 */
class SamplesPlayerManager {

    /** The ExoPlayer for whichever feed page is currently on-screen, if any. */
    var activePlayer: ExoPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /** Called by the active page's Player.Listener/position-poll to publish its latest state. */
    fun reportActiveState(isPlaying: Boolean, isBuffering: Boolean, positionMs: Long, durationMs: Long) {
        _isPlaying.value = isPlaying
        _isBuffering.value = isBuffering
        _positionMs.value = positionMs
        _durationMs.value = durationMs
    }

    /** Tap-to-toggle on the active page. */
    fun togglePlayPause() {
        activePlayer?.let { it.playWhenReady = !it.playWhenReady }
    }

    /** Pauses the active player - called when navigating away from the Samples tab entirely. */
    fun pause() {
        activePlayer?.playWhenReady = false
        _isPlaying.value = false
    }
}

package com.example.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class AudioPlaybackState(
    val activeSongId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null
)

class AudioPreviewManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private var exoPlayer: ExoPlayer? = null
    private var progressTrackingJob: Job? = null

    private val _playbackState = MutableStateFlow(AudioPlaybackState())
    val playbackState: StateFlow<AudioPlaybackState> = _playbackState.asStateFlow()

    private fun getOrCreatePlayer(): ExoPlayer {
        return exoPlayer ?: ExoPlayer.Builder(context).build().also { player ->
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playbackState.update { it.copy(isPlaying = isPlaying) }
                    if (isPlaying) {
                        startProgressTracker()
                    } else {
                        stopProgressTracker()
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            _playbackState.update { it.copy(isBuffering = true) }
                        }
                        Player.STATE_READY -> {
                            val duration = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                            _playbackState.update {
                                it.copy(
                                    isBuffering = false,
                                    durationMs = duration
                                )
                            }
                        }
                        Player.STATE_ENDED -> {
                            _playbackState.update {
                                it.copy(
                                    isPlaying = false,
                                    currentPositionMs = 0L
                                )
                            }
                        }
                        Player.STATE_IDLE -> {
                            _playbackState.update { it.copy(isBuffering = false) }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _playbackState.update {
                        it.copy(
                            isPlaying = false,
                            isBuffering = false,
                            errorMessage = "Playback error: ${error.localizedMessage ?: "Unknown"}"
                        )
                    }
                }
            })
            exoPlayer = player
        }
    }

    /**
     * Plays a local audio file or toggles play/pause if already active.
     */
    fun togglePlaySong(song: SongItem, audioFile: File) {
        val player = getOrCreatePlayer()

        if (_playbackState.value.activeSongId == song.id) {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            return
        }

        // New song selected: stop previous and play
        if (!audioFile.exists() || audioFile.length() == 0L) {
            _playbackState.update {
                it.copy(
                    activeSongId = song.id,
                    isPlaying = false,
                    errorMessage = "Audio file not found or empty on device: ${audioFile.name}"
                )
            }
            return
        }

        try {
            player.stop()
            player.clearMediaItems()
            val mediaItem = MediaItem.fromUri(Uri.fromFile(audioFile))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            _playbackState.update {
                it.copy(
                    activeSongId = song.id,
                    isPlaying = true,
                    currentPositionMs = 0L,
                    durationMs = (song.durationSeconds * 1000L),
                    errorMessage = null
                )
            }
        } catch (e: Exception) {
            _playbackState.update {
                it.copy(
                    activeSongId = song.id,
                    isPlaying = false,
                    errorMessage = "Failed to play audio: ${e.localizedMessage}"
                )
            }
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun stop() {
        stopProgressTracker()
        exoPlayer?.stop()
        _playbackState.update {
            it.copy(
                activeSongId = null,
                isPlaying = false,
                currentPositionMs = 0L
            )
        }
    }

    fun release() {
        stopProgressTracker()
        exoPlayer?.release()
        exoPlayer = null
        _playbackState.update { AudioPlaybackState() }
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressTrackingJob = scope.launch {
            while (isActive) {
                val pos = exoPlayer?.currentPosition ?: 0L
                _playbackState.update { it.copy(currentPositionMs = pos) }
                delay(250)
            }
        }
    }

    private fun stopProgressTracker() {
        progressTrackingJob?.cancel()
        progressTrackingJob = null
    }
}

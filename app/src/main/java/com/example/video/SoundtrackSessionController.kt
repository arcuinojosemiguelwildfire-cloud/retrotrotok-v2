package com.example.video

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.local.storage.MusicStorageManager
import com.example.model.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Coordinates soundtrack playback through kiosk speakers during Dance Studio recording sessions.
 * Ensures audio playback starts synchronously with the recording start (not during countdown)
 * and re-prepares cleanly across Retake cycles without leaking or duplicating players.
 */
class SoundtrackSessionController(
    private val context: Context,
    private val storageManager: MusicStorageManager
) {
    companion object {
        private const val TAG = "SoundtrackSessionCtrl"
    }

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private var exoPlayer: ExoPlayer? = null
    private var preparedSong: SongItem? = null
    private var resolvedAudioFile: File? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isPrepared = MutableStateFlow(false)
    val isPrepared: StateFlow<Boolean> = _isPrepared.asStateFlow()

    /**
     * Prepares the soundtrack for a recording session.
     */
    fun prepareTrack(song: SongItem): Boolean {
        preparedSong = song
        val file = if (song.audioRelativePath.isNotBlank()) {
            storageManager.getAbsoluteFile(song.audioRelativePath)
        } else {
            null
        }
        resolvedAudioFile = file

        if (file == null || !file.exists() || file.length() == 0L) {
            Log.w(TAG, "Audio file for '${song.title}' does not exist on disk: ${song.audioRelativePath}")
            releasePlayer()
            _isPlaying.value = false
            _isPrepared.value = false
            return false
        }

        try {
            releasePlayer()
            val player = ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttributes, true)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    repeatMode = Player.REPEAT_MODE_OFF
                    volume = 1f
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            _isPlaying.value = playing
                        }
                    })
                    prepare()
                }
            exoPlayer = player
            _isPlaying.value = false
            _isPrepared.value = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare soundtrack player", e)
            releasePlayer()
            _isPlaying.value = false
            _isPrepared.value = false
            return false
        }
    }

    /**
     * Ensures the soundtrack player is prepared and ready for another recording (e.g. after Retake).
     * Reuses the existing ExoPlayer when healthy and stopped (STATE_IDLE -> prepare()) to avoid duplicates/leaks.
     */
    fun ensurePrepared(song: SongItem? = preparedSong): Boolean {
        val targetSong = song ?: preparedSong ?: return false
        val file = if (targetSong.audioRelativePath.isNotBlank()) {
            storageManager.getAbsoluteFile(targetSong.audioRelativePath)
        } else {
            null
        }
        if (file == null || !file.exists() || file.length() == 0L) {
            preparedSong = targetSong
            resolvedAudioFile = file
            releasePlayer()
            _isPlaying.value = false
            _isPrepared.value = false
            return false
        }

        val existing = exoPlayer
        if (existing != null &&
            preparedSong?.id == targetSong.id &&
            resolvedAudioFile?.absolutePath == file.absolutePath &&
            existing.playerError == null &&
            existing.mediaItemCount > 0
        ) {
            try {
                existing.volume = 1f
                if (existing.playbackState == Player.STATE_IDLE) {
                    existing.seekTo(0)
                    existing.prepare()
                } else {
                    existing.seekTo(0)
                }
                _isPrepared.value = true
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Re-preparing existing player failed, recreating instance: ${e.message}")
            }
        }

        return prepareTrack(targetSong)
    }

    /**
     * Starts speaker playback at recording start.
     * Must NOT be called during countdown.
     */
    fun startPlaybackAtRecordStart() {
        val needsPrepare = exoPlayer == null ||
            exoPlayer?.playbackState == Player.STATE_IDLE ||
            exoPlayer?.playerError != null ||
            !_isPrepared.value
        if (needsPrepare && !ensurePrepared(preparedSong)) {
            return
        }
        val player = exoPlayer ?: return
        try {
            player.volume = 1f
            player.seekTo(0)
            player.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start soundtrack playback", e)
        }
    }

    /**
     * Stops speaker playback upon recording stop or cancel.
     * Preserves song metadata and file reference so Retake can re-prepare cleanly.
     */
    fun stopPlayback() {
        try {
            exoPlayer?.let { player ->
                player.playWhenReady = false
                player.stop()
                player.seekTo(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping soundtrack playback", e)
        }
        _isPlaying.value = false
        _isPrepared.value = false
    }

    /**
     * Resolves the original local audio file for digital post-processing and muxing.
     */
    fun getResolvedAudioFile(): File? = resolvedAudioFile

    internal fun hasActivePlayerInstance(): Boolean = exoPlayer != null

    internal fun currentPlayerInstance(): ExoPlayer? = exoPlayer

    internal fun currentPlaybackState(): Int = exoPlayer?.playbackState ?: Player.STATE_IDLE

    internal fun isPlayWhenReadyRequested(): Boolean = exoPlayer?.playWhenReady == true

    internal fun currentPreparedSongId(): String? = preparedSong?.id

    internal fun currentVolume(): Float = exoPlayer?.volume ?: 0f

    /**
     * Releases player resources cleanly.
     */
    fun release() {
        releasePlayer()
        preparedSong = null
        resolvedAudioFile = null
        _isPrepared.value = false
        _isPlaying.value = false
    }

    private fun releasePlayer() {
        try {
            exoPlayer?.release()
            exoPlayer = null
        } catch (e: Exception) {
            // Ignore
        }
    }
}

package com.earlyspark.orbn.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.earlyspark.orbn.data.OrbnDatabase
import com.earlyspark.orbn.data.PlayEventEntity
import com.earlyspark.orbn.oura.Oura
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Background playback host: an ExoPlayer wrapped in a MediaSession, exposed as a
 * MediaSessionService so the OS gives us lock-screen / notification transport controls and
 * keeps audio alive when the UI is gone.
 *
 * Audiophile path (D11/F2): ExoPlayer opens the AudioTrack at the *decoded content's* sample
 * rate, so a 44.1 kHz track plays at 44.1 kHz and a 96 kHz file at 96 kHz — the framework mixer
 * does no sample-rate conversion (the dominant fidelity factor). Gapless playback is automatic
 * for gaplessly-encoded content.
 *
 * Play-history logging (D12, M5.2c) lives here — not in the UI — so background/screen-off plays
 * are captured. Each track that actually starts playing is logged PLAYED with the current
 * biometric snapshot; a manual skip logs the departed track SKIPPED. The recency penalty reads
 * the PLAYED rows.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track which item is current and whether we've already logged it as PLAYED (once it starts).
    private var currentId: String? = null
    private var loggedCurrent = false

    private val logListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A manual skip (seek to next/prev or tapping a track) departs the current track early.
            if (currentId != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                logEvent(currentId!!, TYPE_SKIPPED)
            }
            currentId = mediaItem?.mediaId
            loggedCurrent = false
            if (session?.player?.isPlaying == true) maybeLogPlayed()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) maybeLogPlayed()
        }
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            // Route as music + cooperate with system audio focus (pause on calls/other apps).
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            // Pause when headphones/DAC are unplugged instead of blasting the speaker.
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(logListener)
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /** Log the current track PLAYED once, the first time it actually starts playing. */
    private fun maybeLogPlayed() {
        val id = currentId
        if (id != null && !loggedCurrent) {
            loggedCurrent = true
            logEvent(id, TYPE_PLAYED)
        }
    }

    private fun logEvent(trackPath: String, type: String) {
        scope.launch {
            val state = runCatching { Oura.repository(this@PlaybackService).currentState() }.getOrNull()
            OrbnDatabase.get(this@PlaybackService).playEventDao().insert(
                PlayEventEntity(
                    id = UUID.randomUUID().toString(),
                    trackPath = trackPath,
                    type = type,
                    playedAt = System.currentTimeMillis(),
                    energyTarget = state?.energyCenter,
                    readiness = state?.diagnostics?.readinessScore,
                    arousal = state?.diagnostics?.arousal,
                    source = state?.source?.name,
                )
            )
        }
    }

    private companion object {
        const val TYPE_PLAYED = "PLAYED"
        const val TYPE_SKIPPED = "SKIPPED"
    }
}

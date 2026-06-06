package com.earlyspark.orbn.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background playback host: an ExoPlayer wrapped in a MediaSession, exposed as a
 * MediaSessionService so the OS gives us lock-screen / notification transport controls and
 * keeps audio alive when the UI is gone.
 *
 * Audiophile path (D11/F2): ExoPlayer opens the AudioTrack at the *decoded content's* sample
 * rate, so a 44.1 kHz track plays at 44.1 kHz and a 96 kHz file at 96 kHz — the framework mixer
 * does no sample-rate conversion (the dominant fidelity factor). Gapless playback is automatic
 * for gaplessly-encoded content. The CS43198 (USB_HEADSET) accepts up to 192 kHz / 32-bit; the
 * device does not expose a formal bit-perfect mixer attribute, so this native-rate path is the
 * realization of D11 without root. (Float output can be layered on once verified.)
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

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

        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

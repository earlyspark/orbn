package com.earlyspark.orbn.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.earlyspark.orbn.data.OrbnDatabase
import com.earlyspark.orbn.data.PlayEventEntity
import com.earlyspark.orbn.match.QueueBuilder
import com.earlyspark.orbn.oura.Oura
import com.earlyspark.orbn.visualizer.AudioTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    /**
     * Taps the decoded PCM as it flows to the audio device, downmixes to mono float, and hands it
     * to [AudioTap] for the visualizer (P3). Transparent — TeeAudioProcessor copies the audio
     * without altering it, so the native-rate/gapless output path (D11) is unaffected.
     */
    private val audioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        private var channelCount = 2
        private var encoding = C.ENCODING_PCM_16BIT

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            this.channelCount = channelCount.coerceAtLeast(1)
            this.encoding = encoding
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            val ch = channelCount
            val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
            // Only 16-bit and float PCM are handled; skip exotic encodings rather than misread them.
            if (encoding != C.ENCODING_PCM_FLOAT && encoding != C.ENCODING_PCM_16BIT) return
            val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val frames = src.remaining() / (bytesPerSample * ch)
            if (frames <= 0) return
            val mono = FloatArray(frames)
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until ch) {
                    sum += if (encoding == C.ENCODING_PCM_FLOAT) src.float else src.short / 32768f
                }
                mono[f] = sum / ch
            }
            AudioTap.submit(mono)
        }
    }

    private val logListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A manual skip (seek to next/prev or tapping a track) departs the current track early.
            if (currentId != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                logEvent(currentId!!, TYPE_SKIPPED)
            }
            currentId = mediaItem?.mediaId
            loggedCurrent = false
            if (session?.player?.isPlaying == true) maybeLogPlayed()
            // Keep the biometric cache fresh across track boundaries even when the UI is gone
            // (screen off / backgrounded): MainActivity's equivalent refresh dies with its
            // controller in onStop, but playback — and the data a re-match or play-history
            // snapshot reads — continues out here. Staleness-gated (5 min) and deduped inside
            // the repository, so overlap with the UI's refresh is a no-op; failures (e.g. wifi
            // dozing with the screen off) are swallowed and retried at the next boundary.
            scope.launch {
                runCatching { Oura.repository(applicationContext).refreshIfStale() }
            }
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

        // Renderers factory that inserts the visualizer audio tap into the sink's processor chain.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(audioBufferSink)))
                .build()
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            // Route as music + cooperate with system audio focus (pause on calls/other apps).
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            // Pause when headphones/DAC are unplugged instead of blasting the speaker.
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(logListener)
        session = MediaSession.Builder(this, player).build()

        // Auto-re-steer (SPEC §6): whenever a refresh lands fresh Oura data, check whether the
        // live target has drifted past the threshold from what the current queue was built for —
        // if so, silently rebuild the UPCOMING tracks (the playing one is never touched). Lives
        // here, not in the UI, so the queue follows the body during screen-off listening too.
        // Player access must happen on the main thread.
        scope.launch {
            Oura.repository(applicationContext).refreshCompletedAt.drop(1).collect {
                withContext(Dispatchers.Main) {
                    val p = session?.player ?: return@withContext
                    runCatching { QueueBuilder(this@PlaybackService).reSteerIfDrifted(p) }
                }
            }
        }
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

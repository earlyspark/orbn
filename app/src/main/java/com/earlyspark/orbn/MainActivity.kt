package com.earlyspark.orbn

import android.content.ComponentName
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.earlyspark.orbn.library.LibraryRepository
import com.earlyspark.orbn.library.TaggingWorker
import com.earlyspark.orbn.match.FeedbackBias
import com.earlyspark.orbn.match.Matcher
import com.earlyspark.orbn.match.Mood
import com.earlyspark.orbn.match.QueueBuilder
import com.earlyspark.orbn.model.BiometricState
import com.earlyspark.orbn.model.HistoryEntry
import com.earlyspark.orbn.model.biometricReadout
import com.earlyspark.orbn.model.energyWord
import com.earlyspark.orbn.oura.Oura
import com.earlyspark.orbn.oura.OuraAuthManager
import com.earlyspark.orbn.oura.OuraRepository
import com.earlyspark.orbn.playback.AudioCapabilities
import com.earlyspark.orbn.playback.PlaybackService
import com.earlyspark.orbn.model.WhyThisTrack
import com.earlyspark.orbn.ui.HistorySheet
import com.earlyspark.orbn.ui.MoodSheet
import com.earlyspark.orbn.ui.RefreshBanner
import com.earlyspark.orbn.ui.WhyThisTrackSheet
import com.earlyspark.orbn.visualizer.VisualizerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

private val OrbnBg = Color(0xFF0A0A0F)

class MainActivity : ComponentActivity() {

    private lateinit var repository: LibraryRepository
    private val queueBuilder by lazy { QueueBuilder(applicationContext) }
    private lateinit var audioManager: AudioManager
    private var audioCallback: AudioDeviceCallback? = null

    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    // UI state driven by the player.
    private val nowPlaying = MutableStateFlow<String?>(null)
    private val nowPlayingArtist = MutableStateFlow<String?>(null)
    private val isPlaying = MutableStateFlow(false)
    // Whether the current track has actually started (vs. queued at 0:00) — "paused" vs "tap to play".
    private val startedCurrent = MutableStateFlow(false)
    // One-shot orb "burst" trigger: a counter bumped on deliberate re-picks (rematch / mood / 👎-skip).
    private val orbBurst = MutableStateFlow(0)

    // Biometric readout (plain language) + the numeric Oura energy that drives the reactive orb.
    private val ouraLine = MutableStateFlow("")
    private val ouraEnergy = MutableStateFlow<Float?>(null)

    // Manual mood override (D17): null = Default (follow Oura); else a pinned valence×energy mood. Persisted.
    private val manualMood = MutableStateFlow<Mood?>(null)

    // Overlay state.
    private val banner = MutableStateFlow<String?>(null)
    private val showOverride = MutableStateFlow(false)
    private val whyThisTrack = MutableStateFlow<WhyThisTrack?>(null)
    private val showHistory = MutableStateFlow(false)
    private val historyEntries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    private var bannerJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying.value = playing
            if (playing) startedCurrent.value = true // playback began → a later pause is a real "paused"
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            nowPlaying.value = mediaItem?.mediaMetadata?.title?.toString()
            nowPlayingArtist.value = mediaItem?.mediaMetadata?.artist?.toString()
            startedCurrent.value = false // a fresh track at 0:00 is "tap to play", not "paused"
            // Keep the biometric target warm around track boundaries (gated — usually a no-op).
            refreshOuraStatus(forceNetwork = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = LibraryRepository(applicationContext)
        manualMood.value = queueBuilder.manualMood()

        // M3 spike: log output-device capabilities now, and again whenever a device is
        // plugged/unplugged — so attaching the CS43198 dock prints the bit-perfect verdict live.
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        AudioCapabilities.report(audioManager)
        audioCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                AudioCapabilities.report(audioManager)
            }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                AudioCapabilities.report(audioManager)
            }
        }
        audioManager.registerAudioDeviceCallback(audioCallback, null)

        // Scan the library, then kick off background tagging for anything new.
        rescanAndTag()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = OrbnBg)) {
                val bannerMsg by banner.collectAsState(initial = null)
                val overrideVisible by showOverride.collectAsState(initial = false)
                val why by whyThisTrack.collectAsState(initial = null)
                val mood by manualMood.collectAsState(initial = null)
                val historyVisible by showHistory.collectAsState(initial = false)
                val history by historyEntries.collectAsState(initial = emptyList())

                Box(modifier = Modifier.fillMaxSize()) {
                    OrbnHome(
                        totalCount = repository.totalCount,
                        analyzedCount = repository.analyzedCount,
                        nowPlaying = nowPlaying,
                        nowPlayingArtist = nowPlayingArtist,
                        isPlaying = isPlaying,
                        startedCurrent = startedCurrent,
                        ouraLine = ouraLine,
                        manualMood = manualMood,
                        ouraEnergy = ouraEnergy,
                        orbBurst = orbBurst,
                        onTap = ::onOrbTap,
                        onLongPress = { startActivity(Intent(this@MainActivity, VisualizerActivity::class.java)) },
                        onSwipeUp = ::openWhyThisTrack,
                        onSwipeDown = ::reMatch,
                        onSwipeLeft = { showOverride.value = true },
                        onSwipeRight = ::openHistory,
                        onOuraTap = ::onOuraTap,
                    )
                    RefreshBanner(message = bannerMsg)
                    MoodSheet(
                        visible = overrideVisible,
                        current = mood,
                        onApply = ::pickMood,
                        onDismiss = { showOverride.value = false },
                    )
                    WhyThisTrackSheet(
                        info = why,
                        onThumbsUp = ::thumbsUp,
                        onThumbsDown = ::thumbsDown,
                        onDismiss = { whyThisTrack.value = null },
                    )
                    HistorySheet(
                        visible = historyVisible,
                        entries = history,
                        onRate = ::onRateHistory,
                        onDismiss = { showHistory.value = false },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Connect to the playback service; load the library once the controller is ready.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            // If onStop released this future before it connected (quick start→stop, e.g. unplug →
            // screen sleep), the listener still fires on a cancelled future and get() throws.
            if (future.isCancelled) return@addListener
            val c = try {
                future.get()
            } catch (e: Exception) {
                return@addListener
            }
            controller = c
            c.addListener(playerListener)
            isPlaying.value = c.isPlaying
            nowPlaying.value = c.currentMediaItem?.mediaMetadata?.title?.toString()
            nowPlayingArtist.value = c.currentMediaItem?.mediaMetadata?.artist?.toString()
            startedCurrent.value = c.isPlaying || c.currentPosition > 0 // reconnect: paused-mid-song vs fresh
            // Build a ready queue but DO NOT play — playback is always tap-to-play (D23).
            if (c.mediaItemCount == 0) lifecycleScope.launch { buildMatchedQueueInto(c, autoPlay = false) }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        // Reflect connection state and the cached biometric target whenever we return to the
        // foreground (e.g. back from the OAuth browser tab). No network here — cache only.
        refreshOuraStatus(forceNetwork = false)
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        audioCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        super.onDestroy()
    }

    /** Tap = play/pause. If nothing is queued yet, build a biometric-matched queue and start. */
    private fun onOrbTap() {
        val c = controller ?: return
        when {
            c.mediaItemCount == 0 -> lifecycleScope.launch { buildMatchedQueueInto(c, autoPlay = true) }
            c.isPlaying -> c.pause()
            else -> c.play()
        }
    }

    /** Build the matched queue via the shared [QueueBuilder] and set it on the player. */
    private suspend fun buildMatchedQueueInto(c: MediaController, autoPlay: Boolean) {
        queueBuilder.applyTo(c, autoPlay)
    }

    /**
     * Swipe-down (D24): pull the latest Oura data, rebuild the matched queue from it, and surface a
     * top banner — the deliberate "tune to how I am right now" action. Preserves the play/pause state
     * (no surprise autoplay — D23). Unlike resume/next, this re-reads Oura.
     */
    private fun reMatch() {
        val c = controller ?: return
        lifecycleScope.launch {
            showBanner("Re-tuning to how you are now…")
            runCatching { Oura.repository(applicationContext).refresh() } // pull latest; no-op if not connected
            val wasPlaying = c.isPlaying
            buildMatchedQueueInto(c, autoPlay = wasPlaying)
            triggerBurst() // deliberate re-pick → orb flourish
            refreshOuraStatus(forceNetwork = false) // refresh the readout line from the new cache
            showBanner("Re-matched · feeling ${energyWord(queueBuilder.currentTarget().energyCenter)}")
        }
    }

    /** Bump the one-shot orb-burst trigger (a deliberate re-pick swapped the song in). */
    private fun triggerBurst() {
        orbBurst.value = orbBurst.value + 1
    }

    /**
     * Pick a manual mood (D17) or clear it (null = Default → follow Oura): persist, rebuild the queue,
     * keep the play/pause state. The orb + readout follow the chosen mood while it's set.
     */
    private fun pickMood(mood: Mood?) {
        manualMood.value = mood
        queueBuilder.setManualMood(mood)
        showOverride.value = false
        if (mood == null) refreshOuraStatus(forceNetwork = false)
        rebuildQueuePreserving(if (mood != null) "Mood · ${mood.label}" else "Following Oura")
    }

    /** Rebuild the queue from the current target without changing whether audio is playing. */
    private fun rebuildQueuePreserving(message: String) {
        val c = controller ?: return
        lifecycleScope.launch {
            val wasPlaying = c.isPlaying
            buildMatchedQueueInto(c, autoPlay = wasPlaying)
            triggerBurst() // deliberate re-pick → orb flourish
            showBanner(message)
        }
    }

    /** Assemble + show the "why this track" detail for the now-playing item (D24 swipe-up). */
    private fun openWhyThisTrack() {
        val c = controller ?: return
        val path = c.currentMediaItem?.mediaId
        val title = c.currentMediaItem?.mediaMetadata?.title?.toString() ?: "This track"
        val artist = c.currentMediaItem?.mediaMetadata?.artist?.toString()
        if (path == null) {
            showBanner("Nothing playing yet")
            return
        }
        lifecycleScope.launch { whyThisTrack.value = queueBuilder.whyThisTrack(path, title, artist) }
    }

    /** Swipe-right: load + show the recent-play history for retroactive feedback. */
    private fun openHistory() {
        lifecycleScope.launch {
            historyEntries.value = queueBuilder.recentHistory()
            showHistory.value = true
        }
    }

    /** Set/clear a track's rating from History (persists; reflects on all of that track's rows). */
    private fun onRateHistory(entry: HistoryEntry, rating: Int) {
        lifecycleScope.launch { queueBuilder.setHistoryRating(entry.trackPath, rating, entry.energyValue) }
        historyEntries.value = historyEntries.value.map {
            if (it.trackPath == entry.trackPath) it.copy(rating = rating) else it
        }
    }

    /** 👍 (D12 feedback): reinforce this pick, keep playing, close the sheet. */
    private fun thumbsUp() {
        val path = whyThisTrack.value?.trackPath ?: return
        lifecycleScope.launch { queueBuilder.recordFeedback(path, rating = +1) }
        whyThisTrack.value = null
        showBanner("Noted — more like this")
    }

    /** 👎 (D12 feedback): record it was wrong for this state, skip to the next track (orb burst). */
    private fun thumbsDown() {
        val path = whyThisTrack.value?.trackPath ?: return
        lifecycleScope.launch { queueBuilder.recordFeedback(path, rating = -1) }
        whyThisTrack.value = null
        controller?.let { c ->
            if (c.hasNextMediaItem()) c.seekToNextMediaItem()
            triggerBurst() // a new song was picked → orb flourish
        }
        showBanner("Skipped — noted for next time")
    }

    /** Reconcile the folder with the DB, then enqueue the (resumable) tagging job. */
    private fun rescanAndTag() {
        lifecycleScope.launch {
            repository.scan()
            val request = OneTimeWorkRequestBuilder<TaggingWorker>().build()
            // KEEP: if a tagging run is already queued/running, don't duplicate it.
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(TaggingWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    /**
     * Oura line tap: connect if not yet authorized, otherwise pull a fresh sync. When orbn has no
     * credentials at all, just say so (rather than launch a broken flow).
     */
    private fun onOuraTap() {
        val repo = Oura.repository(applicationContext)
        when {
            !OuraAuthManager.isConfigured ->
                ouraLine.value = "Oura: add credentials to local.properties"
            !repo.isConnected -> OuraAuthManager.startAuthorization(this)
            else -> refreshOuraStatus(forceNetwork = true)
        }
    }

    /** Update [ouraLine] from cache, or pull a fresh sync first when [forceNetwork]. */
    private fun refreshOuraStatus(forceNetwork: Boolean) {
        val repo = Oura.repository(applicationContext)
        when {
            !OuraAuthManager.isConfigured -> {
                ouraLine.value = "Oura: add credentials to local.properties"
                ouraEnergy.value = null
                return
            }
            !repo.isConnected -> {
                ouraLine.value = "tap to connect Oura"
                ouraEnergy.value = null
                return
            }
        }
        lifecycleScope.launch {
            if (forceNetwork) ouraLine.value = "syncing Oura…"
            // Manual tap forces a fetch; auto triggers (open/song change) are freshness-gated.
            val result = if (forceNetwork) repo.refresh() else repo.refreshIfStale()
            val state: BiometricState? = when (result) {
                is OuraRepository.RefreshResult.Success -> result.state
                OuraRepository.RefreshResult.NotConfigured -> {
                    ouraLine.value = "Oura: add credentials to local.properties"; return@launch
                }
                OuraRepository.RefreshResult.NotConnected -> {
                    ouraLine.value = "tap to connect Oura"; return@launch
                }
                is OuraRepository.RefreshResult.Error -> {
                    // Manual tap surfaces the failure; an auto refresh keeps the cached readout.
                    if (forceNetwork) { ouraLine.value = "Oura sync failed"; return@launch }
                    repo.currentState()
                }
                null -> repo.currentState() // gated skip (still fresh / in flight) → show cache
            }
            ouraLine.value = biometricReadout(state)
            ouraEnergy.value = state?.energyCenter
        }
    }

    /** Show a top banner message, auto-clearing after a beat. */
    private fun showBanner(message: String) {
        banner.value = message
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch {
            delay(2200)
            banner.value = null
        }
    }

}

/**
 * Home screen: a biometric-reactive breathing orb plus a status line. Gestures (D24): tap anywhere =
 * play/pause, long-press anywhere = visualizer, swipe-up = why-this-track, swipe-down = rematch,
 * swipe-left = energy override. The orb's palette + pulse rate follow the effective energy (manual
 * override if set, else Oura).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OrbnHome(
    totalCount: Flow<Int>,
    analyzedCount: Flow<Int>,
    nowPlaying: Flow<String?>,
    nowPlayingArtist: Flow<String?>,
    isPlaying: Flow<Boolean>,
    startedCurrent: Flow<Boolean>,
    ouraLine: Flow<String>,
    manualMood: Flow<Mood?>,
    ouraEnergy: Flow<Float?>,
    orbBurst: Flow<Int>,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onOuraTap: () -> Unit,
) {
    val total by totalCount.collectAsState(initial = 0)
    val analyzed by analyzedCount.collectAsState(initial = 0)
    val playing by isPlaying.collectAsState(initial = false)
    val started by startedCurrent.collectAsState(initial = false)
    val track by nowPlaying.collectAsState(initial = null)
    val artist by nowPlayingArtist.collectAsState(initial = null)
    val oura by ouraLine.collectAsState(initial = "")
    val mood by manualMood.collectAsState(initial = null)
    val ouraE by ouraEnergy.collectAsState(initial = null)
    val burstTick by orbBurst.collectAsState(initial = 0)

    // Effective energy drives the orb: a chosen mood's energy wins, else Oura, else a neutral middle.
    val energy = (mood?.energyCenter ?: ouraE ?: 0.5f).coerceIn(0f, 1f)

    // Stay cool/blue through calm & moderate energy; only warm at genuinely high arousal (≥0.6,
    // full warm by ~0.9) so a relaxed state never reads "red". Pulse still speeds up with energy.
    val warm = ((energy - 0.6f) / 0.3f).coerceIn(0f, 1f)
    val core = lerp(Color(0xFFBFD4FF), Color(0xFFFFC9A8), warm)
    val mid = lerp(Color(0xFF4F86E8), Color(0xFFE8624F), warm)
    val pulseDuration = (3200 - 1600 * energy).toInt()

    val transition = rememberInfiniteTransition(label = "breathing")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = pulseDuration),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // One-shot orb "transition" on a deliberate re-pick: a gentle swell + slow color *shift* (not a
    // bright flash — flashing risks photosensitivity). Ramps up, then eases back over ~1.8s.
    val burst = remember { Animatable(0f) }
    LaunchedEffect(burstTick) {
        if (burstTick > 0) {
            burst.animateTo(1f, tween(durationMillis = 500, easing = LinearOutSlowInEasing))
            burst.animateTo(0f, tween(durationMillis = 1300, easing = FastOutSlowInEasing))
        }
    }
    val bv = burst.value

    // The now-playing line (♪ + artist – title) is built in the Text below when a track is loaded;
    // these are the no-track-loaded states.
    val status = when {
        analyzed < total -> "tagging your library…  $analyzed / $total"
        total > 0 -> "tap to play · $total tracks"
        else -> "drop music in the orbn folder"
    }
    // The readout always shows your body state (feeling / readiness / synced), even when a manual
    // mood is set — the mood drives the orb + queue, but never rewrites this line.
    val bioLine = oura
    // Only the connect prompt is tappable; the readout is informational so tap-anywhere = play/pause.
    val ouraTappable = bioLine.startsWith("tap to connect")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrbnBg)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            }
            .pointerInput(Unit) {
                var dx = 0f
                var dy = 0f
                val threshold = 80.dp.toPx()
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDrag = { change, drag -> dx += drag.x; dy += drag.y; change.consume() },
                    onDragEnd = {
                        if (abs(dx) > abs(dy)) {
                            if (dx <= -threshold) onSwipeLeft()
                            else if (dx >= threshold) onSwipeRight()
                        } else {
                            if (dy <= -threshold) onSwipeUp()
                            else if (dy >= threshold) onSwipeDown()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Play-state cue, floated over the orb (out of the text flow → no layout jump). Hidden
            // while actually playing.
            val stateWord = when {
                track == null -> null
                playing -> null
                started -> "paused"
                else -> "tap to play"
            }
            Box(
                modifier = Modifier
                    .size((180 * pulse * (1f + 0.15f * bv)).dp)
                    .drawBehind {
                        val r = size.minDimension / 2f
                        // Burst eases the orb toward a violet, then back — a color shift, not a flash.
                        val burstCore = lerp(core, Color(0xFFC9A9FF), 0.7f * bv)
                        val burstMid = lerp(mid, Color(0xFF7A52E0), 0.7f * bv)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    burstCore,
                                    burstMid,
                                    burstMid.copy(alpha = 0.2f),
                                    Color(0x00000000)
                                ),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = r
                            ),
                            radius = r,
                            center = Offset(size.width / 2f, size.height / 2f)
                        )
                    }
            )
            // CTA where the "orbn" title used to be — tucked close under the orb. Height is reserved
            // so the lines below don't jump when it shows (paused / tap to play) or hides (playing).
            Box(
                modifier = Modifier.padding(top = 18.dp).height(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                stateWord?.let {
                    Text(
                        text = it,
                        color = Color(0xFFAEB6C7),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            val t = track
            val a = artist
            // Now-playing line: a persistent ♪ (brighter while actually playing) + "artist – title".
            // Capped at 90% screen width; marquees if it overflows.
            Text(
                text = if (t != null) buildAnnotatedString {
                    val noteColor = if (playing) Color(0xFFB4C4E8) else Color(0xFF8A93A6)
                    withStyle(SpanStyle(fontSize = 18.sp, color = noteColor)) { append("♪") }
                    append("  ")
                    append(if (!a.isNullOrBlank()) "$a – $t" else t)
                } else AnnotatedString(status),
                color = Color(0xFF7C8499),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = (LocalConfiguration.current.screenWidthDp * 0.9f).dp)
                    .basicMarquee(),
            )
            if (bioLine.isNotBlank()) {
                val ouraModifier = Modifier
                    .padding(top = 12.dp, start = 32.dp, end = 32.dp)
                    .let { if (ouraTappable) it.clickable { onOuraTap() } else it }
                Text(
                    text = bioLine,
                    color = Color(0xFF5B8DEF),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = ouraModifier
                )
            }
        }
    }
}

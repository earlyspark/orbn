package com.earlyspark.orbn

import android.content.ComponentName
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.earlyspark.orbn.data.OrbnDatabase
import com.earlyspark.orbn.library.LibraryRepository
import com.earlyspark.orbn.library.TaggingWorker
import com.earlyspark.orbn.match.AffectFold
import com.earlyspark.orbn.match.MatchTarget
import com.earlyspark.orbn.match.Matcher
import com.earlyspark.orbn.match.Mood
import com.earlyspark.orbn.match.QueueSequencer
import com.earlyspark.orbn.match.RecencyPenalty
import com.earlyspark.orbn.match.toFeaturesOrNull
import com.earlyspark.orbn.match.toTarget
import com.earlyspark.orbn.model.BiometricState
import com.earlyspark.orbn.model.TrackFeatures
import com.earlyspark.orbn.model.biometricReadout
import com.earlyspark.orbn.model.energyWord
import com.earlyspark.orbn.model.valenceWord
import com.earlyspark.orbn.oura.Oura
import com.earlyspark.orbn.oura.OuraAuthManager
import com.earlyspark.orbn.oura.OuraRepository
import com.earlyspark.orbn.playback.AudioCapabilities
import com.earlyspark.orbn.playback.PlaybackService
import com.earlyspark.orbn.ui.MoodSheet
import com.earlyspark.orbn.ui.RefreshBanner
import com.earlyspark.orbn.ui.WhyThisTrack
import com.earlyspark.orbn.ui.WhyThisTrackSheet
import com.earlyspark.orbn.visualizer.VisualizerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

private val OrbnBg = Color(0xFF0A0A0F)

/** How many tracks the matcher samples into a queue per build. */
private const val QUEUE_SIZE = 30

/** Recently-played tracks are down-weighted, recovering over this window (≈ a full small-library cycle). */
private const val RECENCY_WINDOW_MS = 2 * 60 * 60 * 1000L
private const val RECENCY_FLOOR = 0.1f

/** Best-effort artist from an "Artist - Title.ext" filename; null if the pattern doesn't match. */
private fun artistOf(path: String): String? =
    path.substringAfterLast('/').substringBeforeLast('.')
        .substringBefore(" - ", missingDelimiterValue = "").trim().ifBlank { null }

class MainActivity : ComponentActivity() {

    private lateinit var repository: LibraryRepository
    private lateinit var audioManager: AudioManager
    private var audioCallback: AudioDeviceCallback? = null

    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    // UI state driven by the player.
    private val nowPlaying = MutableStateFlow<String?>(null)
    private val isPlaying = MutableStateFlow(false)

    // Biometric readout (plain language) + the numeric Oura energy that drives the reactive orb.
    private val ouraLine = MutableStateFlow("")
    private val ouraEnergy = MutableStateFlow<Float?>(null)

    // Manual mood override (D17): null = Default (follow Oura); else a pinned valence×energy mood. Persisted.
    private val manualMood = MutableStateFlow<Mood?>(null)

    // Overlay state.
    private val banner = MutableStateFlow<String?>(null)
    private val showOverride = MutableStateFlow(false)
    private val whyThisTrack = MutableStateFlow<WhyThisTrack?>(null)

    private var bannerJob: Job? = null
    private val uiPrefs by lazy { getSharedPreferences("orbn_ui", MODE_PRIVATE) }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying.value = playing
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            nowPlaying.value = mediaItem?.mediaMetadata?.title?.toString()
            // Keep the biometric target warm around track boundaries (gated — usually a no-op).
            refreshOuraStatus(forceNetwork = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = LibraryRepository(applicationContext)
        manualMood.value = loadManualMood()

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

                Box(modifier = Modifier.fillMaxSize()) {
                    OrbnHome(
                        totalCount = repository.totalCount,
                        analyzedCount = repository.analyzedCount,
                        nowPlaying = nowPlaying,
                        isPlaying = isPlaying,
                        ouraLine = ouraLine,
                        manualMood = manualMood,
                        ouraEnergy = ouraEnergy,
                        onTap = ::onOrbTap,
                        onLongPress = { startActivity(Intent(this@MainActivity, VisualizerActivity::class.java)) },
                        onSwipeUp = ::openWhyThisTrack,
                        onSwipeDown = ::reMatch,
                        onSwipeLeft = { showOverride.value = true },
                        onOuraTap = ::onOuraTap,
                    )
                    RefreshBanner(message = bannerMsg)
                    MoodSheet(
                        visible = overrideVisible,
                        current = mood,
                        onPick = ::pickMood,
                        onDismiss = { showOverride.value = false },
                    )
                    WhyThisTrackSheet(info = why, onDismiss = { whyThisTrack.value = null })
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

    /**
     * Resolve the active matching target: a manual override (D17) wins; else the cached Oura state;
     * else a neutral fallback (D17). Reads only cached state (no network).
     */
    private suspend fun currentTarget(): MatchTarget {
        val mood = manualMood.value
        return mood?.toTarget()
            ?: Oura.repository(applicationContext).currentState()?.toTarget()
            ?: MatchTarget.neutral()
    }

    /**
     * Build a biometric-matched play queue (M5.2): score the analyzed library through [AffectFold],
     * sample it against the current target (manual override → Oura → neutral) and set it on the
     * player. Falls back to a plain folder listing if nothing is analyzed yet (e.g. mid-tagging).
     */
    private suspend fun buildMatchedQueueInto(c: MediaController, autoPlay: Boolean) {
        val db = OrbnDatabase.get(applicationContext)
        val tracks = db.trackDao().analyzed()
        val byPath = tracks.associateBy { it.path }
        val candidates = tracks.mapNotNull { t ->
            val f = t.toFeaturesOrNull() ?: return@mapNotNull null
            // Prefer the embedded artist tag; fall back to the filename guess.
            Matcher.Candidate(t.path, AffectFold.fold(f), f.instrumental, artist = t.artist ?: artistOf(t.path))
        }
        if (candidates.isEmpty()) {
            // Nothing analyzed yet — keep playback working with the raw folder.
            loadLibraryInto(c)
            if (autoPlay) c.play()
            return
        }
        val target = currentTarget()

        // Recency penalty (D16): down-weight recently played tracks, from the D12 play-history log.
        val now = System.currentTimeMillis()
        val lastPlayed = db.playEventDao().lastPlayedSince(now - RECENCY_WINDOW_MS)
            .associate { it.trackPath to it.lastPlayed }
        val recency = RecencyPenalty.multipliers(lastPlayed, now, RECENCY_WINDOW_MS, RECENCY_FLOOR)

        val queue = Matcher.buildQueue(candidates, target, count = QUEUE_SIZE, recency = recency)
        // Reorder for a smooth energy contour + artist spread (selection unchanged).
        val sequenced = QueueSequencer.sequence(queue)
        android.util.Log.i(
            "OrbnMatch",
            "target e=%.2f±%.2f val=%s → %d/%d queued (%d recent); seq energies=%s".format(
                target.energyCenter, target.energyBand,
                target.valenceCenter?.let { "%.2f".format(it) } ?: "free",
                sequenced.size, candidates.size, lastPlayed.size,
                sequenced.take(10).joinToString(",") { "%.2f".format(it.point.energy) },
            ),
        )
        val items = sequenced.map { cand ->
            val f = java.io.File(cand.id)
            val entity = byPath[cand.id]
            MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(f))
                .setMediaId(cand.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(entity?.title ?: f.nameWithoutExtension)
                        .setArtist(entity?.artist)
                        .build()
                )
                .build()
        }
        c.setMediaItems(items)
        c.prepare()
        if (autoPlay) c.play()
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
            refreshOuraStatus(forceNetwork = false) // refresh the readout line from the new cache
            showBanner("Re-matched · feeling ${energyWord(currentTarget().energyCenter)}")
        }
    }

    /**
     * Pick a manual mood (D17) or clear it (null = Default → follow Oura): persist, rebuild the queue,
     * keep the play/pause state. The orb + readout follow the chosen mood while it's set.
     */
    private fun pickMood(mood: Mood?) {
        manualMood.value = mood
        saveManualMood(mood)
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
        lifecycleScope.launch {
            val entity = OrbnDatabase.get(applicationContext).trackDao().byPath(path)
            val features = entity?.toFeaturesOrNull()
            if (features == null) {
                whyThisTrack.value = WhyThisTrack(
                    title = title, artist = artist, energyLabel = "—", energyValue = 0f,
                    valenceLabel = "—", topMood = null,
                    reason = "Still analyzing this track — check back once tagging finishes.",
                )
                return@launch
            }
            val point = AffectFold.fold(features)
            val target = currentTarget()
            whyThisTrack.value = WhyThisTrack(
                title = title,
                artist = artist,
                energyLabel = energyWord(point.energy),
                energyValue = point.energy,
                valenceLabel = valenceWord(point.valence),
                topMood = features.topMoodOrNull(),
                reason = reasonFor(point.energy, target.energyCenter, manualMood.value),
            )
        }
    }

    /** Build the play queue from the app-owned Music folder (audio files only). */
    private fun loadLibraryInto(c: MediaController) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return
        val files = dir.listFiles { f ->
            f.isFile && f.extension.lowercase() in setOf("mp3", "flac", "m4a", "ogg", "wav", "aac")
        }?.sortedBy { it.name } ?: return
        if (files.isEmpty()) return
        val items = files.map { f ->
            MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(f))
                .setMediaId(f.absolutePath)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(f.nameWithoutExtension).build())
                .build()
        }
        c.setMediaItems(items)
        c.prepare()
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

    // --- Manual-override persistence (SharedPreferences "orbn_ui") --------------------------------

    /** Load the saved manual mood by name, or null (= Default / follow Oura). */
    private fun loadManualMood(): Mood? = Mood.byName(uiPrefs.getString("manual_mood", null))

    private fun saveManualMood(mood: Mood?) {
        uiPrefs.edit().putString("manual_mood", mood?.name).apply()
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

    /** Dominant mood head over a confidence floor, or null. */
    private fun TrackFeatures.topMoodOrNull(): String? {
        val top = listOf(
            "happy" to happy, "sad" to sad, "aggressive" to aggressive, "relaxed" to relaxed,
        ).maxByOrNull { it.second } ?: return null
        return if (top.second >= 0.4f) top.first else null
    }

    /** One plain-language reason a track fits (or pleasantly deviates from) the target. */
    private fun reasonFor(trackEnergy: Float, targetEnergy: Float, mood: Mood?): String {
        val src = if (mood != null) "your ${mood.label} mood" else "your current state"
        val d = trackEnergy - targetEnergy
        return when {
            abs(d) < 0.12f -> "A close match for $src (${energyWord(targetEnergy)})."
            d > 0f -> "A little livelier than $src, mixed in for variety."
            else -> "A little calmer than $src, mixed in for variety."
        }
    }
}

/**
 * Home screen: a biometric-reactive breathing orb plus a status line. Gestures (D24): tap anywhere =
 * play/pause, long-press anywhere = visualizer, swipe-up = why-this-track, swipe-down = rematch,
 * swipe-left = energy override. The orb's palette + pulse rate follow the effective energy (manual
 * override if set, else Oura).
 */
@Composable
fun OrbnHome(
    totalCount: Flow<Int>,
    analyzedCount: Flow<Int>,
    nowPlaying: Flow<String?>,
    isPlaying: Flow<Boolean>,
    ouraLine: Flow<String>,
    manualMood: Flow<Mood?>,
    ouraEnergy: Flow<Float?>,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeLeft: () -> Unit,
    onOuraTap: () -> Unit,
) {
    val total by totalCount.collectAsState(initial = 0)
    val analyzed by analyzedCount.collectAsState(initial = 0)
    val playing by isPlaying.collectAsState(initial = false)
    val track by nowPlaying.collectAsState(initial = null)
    val oura by ouraLine.collectAsState(initial = "")
    val mood by manualMood.collectAsState(initial = null)
    val ouraE by ouraEnergy.collectAsState(initial = null)

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

    val status = when {
        playing && track != null -> "♪  $track"
        track != null -> "paused · $track"
        analyzed < total -> "tagging your library…  $analyzed / $total"
        total > 0 -> "tap to play · $total tracks"
        else -> "drop music in the orbn folder"
    }
    // When a mood is chosen it replaces the body readout (so orb + line agree); else show Oura.
    val bioLine = mood?.let { "mood · ${it.label}" } ?: oura
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
            Box(
                modifier = Modifier
                    .size((180 * pulse).dp)
                    .drawBehind {
                        val r = size.minDimension / 2f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    core,
                                    mid,
                                    mid.copy(alpha = 0.2f),
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
            Text(
                text = "orbn",
                color = Color(0xFFE8ECF5),
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 48.dp)
            )
            Text(
                text = status,
                color = Color(0xFF7C8499),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp, start = 32.dp, end = 32.dp)
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

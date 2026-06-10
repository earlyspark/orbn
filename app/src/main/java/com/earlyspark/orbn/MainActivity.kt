package com.earlyspark.orbn

import android.content.ComponentName
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
import androidx.work.WorkManager
import com.earlyspark.orbn.library.LibraryRepository
import com.earlyspark.orbn.library.TaggingService
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
import com.earlyspark.orbn.ui.MoodChip
import com.earlyspark.orbn.ui.MoodSheet
import com.earlyspark.orbn.ui.Orbn
import com.earlyspark.orbn.ui.RefreshBanner
import com.earlyspark.orbn.ui.WhyThisTrackSheet
import com.earlyspark.orbn.visualizer.VisualizerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

private val OrbnBg = Color(0xFF0A0A0F)

/**
 * Side of the centered square where a tap toggles play/pause, as a fraction of screen width.
 * Generous around the mascot but well clear of the edges, where accidental contacts
 * (palm grazes, pocket touches, taps on a dimmed screen) overwhelmingly land.
 */
private const val CENTER_TAP_ZONE_FRACTION = 0.55f

class MainActivity : ComponentActivity() {

    private lateinit var repository: LibraryRepository
    private val queueBuilder by lazy { QueueBuilder(applicationContext) }
    private lateinit var audioManager: AudioManager
    private var audioCallback: AudioDeviceCallback? = null

    // SAF "add music" picker: the user selects audio from anywhere on the device (Downloads, internal
    // storage, SD, cloud) and orbn COPIES it into its own Music folder — no storage permission needed.
    private val importMusic = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) importMusicFiles(uris) }

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
    // One-shot "nudge the add-music CTA" trigger: bumped when the orb is tapped with no library yet.
    private val addMusicNudge = MutableStateFlow(0)
    // Latest library size, cached from the count Flow so onOrbTap can branch without a suspend read.
    @Volatile private var libraryTotal = 0

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
        // Keep the cached library size current for onOrbTap's "no music yet" branch.
        lifecycleScope.launch { repository.totalCount.collect { libraryTotal = it } }

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
                        addMusicNudge = addMusicNudge,
                        onTap = ::onOrbTap,
                        onLongPress = { startActivity(Intent(this@MainActivity, VisualizerActivity::class.java)) },
                        onSwipeUp = ::openWhyThisTrack,
                        onSwipeDown = ::reMatch,
                        onSwipeLeft = { showOverride.value = true },
                        onSwipeRight = ::openHistory,
                        onOuraTap = ::onOuraTap,
                        onAddMusic = ::launchAddMusic,
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
        // Reconcile the music folder on every return-to-foreground (not just a cold start), so files
        // added while the app was backgrounded — a USB drop or the in-app import — get detected and
        // tagged. scan() is a cheap folder-walk + DB reconcile; unchanged files are a no-op lookup.
        rescanAndTag()
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
            // No library yet → there's nothing to play; pulse the "add music" CTA instead of
            // silently building an empty queue.
            c.mediaItemCount == 0 && libraryTotal == 0 -> addMusicNudge.value++
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
        if (libraryTotal == 0) return // no music → the refresh gesture does nothing
        lifecycleScope.launch {
            val wasPlaying = c.isPlaying
            // Optimistic "working on it" banner only when we'll actually re-read Oura over the network.
            if (Oura.repository(applicationContext).isConnected) showBanner("Re-tuning to how you are now…")
            when (queueBuilder.reMatch(c, autoPlay = wasPlaying)) {
                QueueBuilder.ReMatch.NONE -> return@launch // nothing playable → leave it be
                QueueBuilder.ReMatch.BIOMETRIC -> {
                    refreshOuraStatus(forceNetwork = false) // refresh the readout from the new cache
                    showBanner("Re-matched · feeling ${energyWord(queueBuilder.currentTarget().energyCenter)}")
                }
                QueueBuilder.ReMatch.MOOD -> queueBuilder.manualMood()?.let { m ->
                    showBanner("Mood: ${m.label} · ${energyWord(m.energyCenter)} picks")
                }
                QueueBuilder.ReMatch.RANDOM -> showBanner("Finding a random song")
            }
            triggerBurst() // deliberate re-pick → orb flourish
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
        rebuildQueuePreserving(if (mood != null) "Mood · ${mood.label}" else "Updating mood")
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
        lifecycleScope.launch { queueBuilder.setHistoryRating(entry.trackPath, rating, entry.energyValue ?: 0.5f) }
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

    /** Open the system file picker for audio (SAF — no storage permission). Result → [importMusicFiles]. */
    private fun launchAddMusic() {
        runCatching { importMusic.launch(arrayOf("audio/*")) }
            .onFailure { showBanner("No file picker available") }
    }

    /**
     * Copy each picked file into orbn's Music folder (on IO), then rescan + tag. The originals are left
     * untouched; orbn owns its copies (they're removed if the app is uninstalled). Banner shows progress.
     */
    private fun importMusicFiles(uris: List<Uri>) {
        lifecycleScope.launch {
            showBanner("Importing ${uris.size} ${if (uris.size == 1) "song" else "songs"}…")
            val added = withContext(Dispatchers.IO) { copyIntoMusicFolder(uris) }
            if (added > 0) {
                rescanAndTag() // register the new files + kick off background analysis
                showBanner("Added $added · analyzing in the background")
            } else {
                showBanner("Couldn't import those files")
            }
        }
    }

    /** Stream each content URI into a uniquely-named file in the Music folder. Returns the count copied. */
    private suspend fun copyIntoMusicFolder(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val dir = repository.musicDir()?.apply { mkdirs() } ?: return@withContext 0
        var added = 0
        for (uri in uris) {
            val name = displayName(uri) ?: continue
            val target = uniqueFile(dir, name)
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("no input stream")
                added++
            }.onFailure {
                Log.w("OrbnImport", "import failed for $name: ${it.message}")
                target.delete() // don't leave a half-written file behind
            }
        }
        added
    }

    /** The picked file's display name (e.g. "song.mp3"), or the URI's last path segment as a fallback. */
    private fun displayName(uri: Uri): String? {
        val raw = run {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { return@run it }
            }
            uri.lastPathSegment
        } ?: return null
        // Reduce to a bare filename: never let a provider-supplied name with path separators ("../…")
        // escape the Music folder when used in File(dir, name).
        return raw.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { null }
    }

    /** Avoid clobbering an existing file: "song.mp3" → "song (1).mp3" if taken. */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    /** Reconcile the folder with the DB, then start the (resumable) tagging service. */
    private fun rescanAndTag() {
        lifecycleScope.launch {
            repository.scan()
            // Retire any leftover WorkManager tagger from a prior version (its backoff
            // could otherwise keep a stale, stalled job parked in the scheduler).
            WorkManager.getInstance(applicationContext).cancelUniqueWork(TaggingService.LEGACY_WORK_NAME)
            TaggingService.start(applicationContext)
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
 * Home screen: the biometric-reactive mascot ([Orbn]) plus a status line. Gestures (D24): tap
 * anywhere = play/pause, long-press anywhere = visualizer, swipe-up = why-this-track, swipe-down =
 * rematch, swipe-left = energy override. The mascot's head hue follows the effective energy (manual
 * override if set, else Oura); it wakes/dozes with playback.
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
    addMusicNudge: Flow<Int>,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onOuraTap: () -> Unit,
    onAddMusic: () -> Unit,
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

    // Effective energy drives the mascot's head hue: a chosen mood's energy wins, else Oura, else a
    // neutral middle.
    val energy = (mood?.energyCenter ?: ouraE ?: 0.5f).coerceIn(0f, 1f)
    // Valence only comes from an emotional mood; Oura leaves it free (D15), so default to neutral 0.5.
    val valence = (mood?.valenceCenter ?: 0.5f).coerceIn(0f, 1f)

    // One-shot "nudge" on the add-music CTA: a gentle scale + brighten when the orb is tapped with no
    // library yet. Single soft pulse (no strobe — photosensitivity).
    val nudgeTick by addMusicNudge.collectAsState(initial = 0)
    val nudge = remember { Animatable(0f) }
    LaunchedEffect(nudgeTick) {
        if (nudgeTick > 0) {
            nudge.animateTo(1f, tween(durationMillis = 160, easing = LinearOutSlowInEasing))
            nudge.animateTo(0f, tween(durationMillis = 520, easing = FastOutSlowInEasing))
        }
    }

    // The now-playing line (♪ + artist – title) is built below when a track is loaded. With no track,
    // an empty library shows the "add music" CTA; otherwise this "tap to play" status. (Tagging progress
    // is its own line pinned at the bottom, below the readout.)
    val status = "tap to play · $total tracks"
    // The readout always shows your body state (feeling / readiness / synced), even when a manual
    // mood is set — the mood drives the orb + queue, but never rewrites this line.
    val bioLine = oura
    // Only the connect prompt is tappable; the readout is informational.
    val ouraTappable = bioLine.startsWith("tap to connect")

    // Tap-to-play only fires in a generous zone around the mascot (a square,
    // CENTER_TAP_ZONE_FRACTION of screen width, centered on the orb's measured position — the
    // orb sits above screen center, so a screen-centered square would clip its head). Stray
    // contacts — palm/pocket grazes, taps on a dimmed screen — land near the edges and must not
    // toggle audio; they get a small orb wobble instead, so the screen never feels dead and the
    // wobble points at where the button is. Long-press (visualizer) and swipes stay full-screen:
    // a graze can't hold still for 500 ms, and none of those gestures start audio.
    var orbCenter by remember { mutableStateOf<Offset?>(null) }
    var missedTapTick by remember { mutableIntStateOf(0) }
    val wobble = remember { Animatable(0f) }
    LaunchedEffect(missedTapTick) {
        if (missedTapTick > 0) {
            // A few quick decaying side-to-side nudges — motion only, no brightness change
            // (photosensitivity: nothing here flashes).
            wobble.animateTo(1f, tween(durationMillis = 90))
            wobble.animateTo(-0.7f, tween(durationMillis = 120))
            wobble.animateTo(0.4f, tween(durationMillis = 110))
            wobble.animateTo(0f, tween(durationMillis = 140, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Claim this surface back from Android's system gestures so edge/diagonal
            // swipes drive orbn (mood/history/why/rematch) instead of triggering the
            // system back-swipe. The platform caps back exclusion at 200dp/edge and
            // keeps the bottom home-swipe reserved, so this curbs — not eliminates — it.
            .systemGestureExclusion()
            .background(OrbnBg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        // The root Box fills the window at (0,0), so root coords == tap coords.
                        val center = orbCenter ?: Offset(size.width / 2f, size.height / 2f)
                        val half = size.width * CENTER_TAP_ZONE_FRACTION / 2f
                        val inZone = abs(pos.x - center.x) <= half &&
                            abs(pos.y - center.y) <= half
                        if (inZone) onTap() else missedTapTick++
                    },
                    onLongPress = { onLongPress() },
                )
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
            Orbn(
                valence = valence,
                energy = energy,
                playing = playing,
                burstTick = burstTick,
                nudgeTick = nudgeTick,
                modifier = Modifier
                    .offset(x = (wobble.value * 6).dp)
                    .size(150.dp)
                    .onGloballyPositioned { orbCenter = it.boundsInRoot().center },
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
            when {
                // Now-playing line: a persistent ♪ (brighter while actually playing) + "artist – title".
                // Capped at 90% screen width; marquees if it overflows.
                t != null -> Text(
                    text = buildAnnotatedString {
                        val noteColor = if (playing) Color(0xFFB4C4E8) else Color(0xFF8A93A6)
                        withStyle(SpanStyle(fontSize = 18.sp, color = noteColor)) { append("♪") }
                        append("  ")
                        append(if (!a.isNullOrBlank()) "$a – $t" else t)
                    },
                    color = Color(0xFF7C8499),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .widthIn(max = (LocalConfiguration.current.screenWidthDp * 0.9f).dp)
                        .basicMarquee(iterations = Int.MAX_VALUE), // loop forever, don't freeze clipped
                )
                // Empty library → tappable CTA that opens the SAF picker to import music. (A persistent
                // "add more" entry point lives in M10 settings; this is the onboarding affordance.)
                // Styled like the "tap to play" status line, just clickable.
                total == 0 -> {
                    val nv = nudge.value
                    Text(
                        text = "add music",
                        // Match the "tap to play" play-state cue (brighter + Medium), not the dim
                        // song-info line; the nudge brightens it further toward white.
                        color = lerp(Color(0xFFAEB6C7), Color(0xFFE8ECF5), nv),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .scale(1f + 0.18f * nv)
                            .clickable { onAddMusic() },
                    )
                }
                // Tagging in progress, or library ready to play.
                else -> Text(
                    text = status,
                    color = Color(0xFF7C8499),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
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
            // A manual mood overrides the queue's energy while it's set, so the body line above can read
            // "mellow" while picks are "charged". This chip names the override so the two never look
            // contradictory; the body readout itself is never rewritten.
            mood?.let { m ->
                MoodChip(text = "Mood: ${m.label}", modifier = Modifier.padding(top = 8.dp))
            }
            // Library analysis progress — pinned at the bottom, below the readout, while tagging runs
            // (home only; the viz never shows it). Hidden once everything's analyzed.
            if (analyzed < total) {
                Text(
                    text = "tagging your library…  $analyzed / $total",
                    color = Color(0xFF5A6173),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

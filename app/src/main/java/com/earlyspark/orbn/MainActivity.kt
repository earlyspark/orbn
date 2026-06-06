package com.earlyspark.orbn

import android.content.ComponentName
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Environment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
import androidx.compose.foundation.layout.Arrangement
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
import com.earlyspark.orbn.match.QueueSequencer
import com.earlyspark.orbn.match.RecencyPenalty
import com.earlyspark.orbn.match.toFeaturesOrNull
import com.earlyspark.orbn.match.toTarget
import com.earlyspark.orbn.model.BiometricState
import com.earlyspark.orbn.oura.Oura
import com.earlyspark.orbn.oura.OuraAuthManager
import com.earlyspark.orbn.oura.OuraRepository
import com.earlyspark.orbn.playback.AudioCapabilities
import com.earlyspark.orbn.playback.PlaybackService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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

    // Minimal M4 readout: connection prompt / biometric summary. Full UX (swipe-to-state,
    // "why this track") is M7 — this is just enough to drive and verify the Oura flow.
    private val ouraLine = MutableStateFlow("")

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying.value = playing
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            nowPlaying.value = mediaItem?.mediaMetadata?.title?.toString()
            // Keep the biometric target warm around track boundaries (gated — usually a no-op).
            // In M5 this is where a fresh sync will drive next-track selection.
            refreshOuraStatus(forceNetwork = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = LibraryRepository(applicationContext)

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
                OrbnHome(
                    totalCount = repository.totalCount,
                    analyzedCount = repository.analyzedCount,
                    nowPlaying = nowPlaying,
                    isPlaying = isPlaying,
                    ouraLine = ouraLine,
                    onTap = ::onOrbTap,
                    onOuraTap = ::onOuraTap,
                )
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
            val c = future.get()
            controller = c
            c.addListener(playerListener)
            isPlaying.value = c.isPlaying
            nowPlaying.value = c.currentMediaItem?.mediaMetadata?.title?.toString()
            if (c.mediaItemCount == 0) buildMatchedQueueInto(c, autoPlay = false)
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

    /** Orb tap = play/pause. If nothing is queued yet, build a biometric-matched queue and start. */
    private fun onOrbTap() {
        val c = controller ?: return
        when {
            c.mediaItemCount == 0 -> buildMatchedQueueInto(c, autoPlay = true)
            c.isPlaying -> c.pause()
            else -> c.play()
        }
    }

    /**
     * Build a biometric-matched play queue (M5.2): score the analyzed library through [AffectFold],
     * sample it against the current target (Oura → [MatchTarget], else neutral), and set it on the
     * player. Falls back to a plain folder listing if nothing is analyzed yet (e.g. mid-tagging).
     */
    private fun buildMatchedQueueInto(c: MediaController, autoPlay: Boolean) {
        lifecycleScope.launch {
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
                return@launch
            }
            val target = Oura.repository(applicationContext).currentState()?.toTarget()
                ?: MatchTarget.neutral()

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
                return
            }
            !repo.isConnected -> {
                ouraLine.value = "tap to connect Oura"
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
            ouraLine.value = formatOura(state)
        }
    }

    private fun formatOura(state: BiometricState?): String {
        if (state == null) return "Oura connected · tap to sync"
        val energy = "energy %.2f".format(state.energyCenter)
        val readiness = state.diagnostics.readinessScore?.let { "readiness $it" }
        val synced = state.syncedAt?.let { "synced ${syncedAtLabel(it)}" }
        return listOfNotNull(energy, readiness, synced).joinToString("  ·  ")
    }

    /**
     * Absolute local-clock time of the freshest Oura datum (its real timestamp, not orbn's fetch
     * time — see OuraRepository). Shown as a wall-clock time so it never goes stale on screen; the
     * date is added only when the data isn't from today, so it can't be misread as recent.
     */
    private fun syncedAtLabel(ts: Long): String {
        val zone = ZoneId.systemDefault()
        val dt = Instant.ofEpochMilli(ts).atZone(zone)
        val time = dt.format(DateTimeFormatter.ofPattern("h:mm a"))
        return if (dt.toLocalDate() == LocalDate.now(zone)) time
        else dt.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
    }
}

/**
 * Home screen: the breathing orb plus a status line. Tapping the orb plays/pauses the library.
 */
@Composable
fun OrbnHome(
    totalCount: Flow<Int>,
    analyzedCount: Flow<Int>,
    nowPlaying: Flow<String?>,
    isPlaying: Flow<Boolean>,
    ouraLine: Flow<String>,
    onTap: () -> Unit,
    onOuraTap: () -> Unit,
) {
    val total by totalCount.collectAsState(initial = 0)
    val analyzed by analyzedCount.collectAsState(initial = 0)
    val playing by isPlaying.collectAsState(initial = false)
    val track by nowPlaying.collectAsState(initial = null)
    val oura by ouraLine.collectAsState(initial = "")

    val transition = rememberInfiniteTransition(label = "breathing")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600),
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrbnBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size((180 * pulse).dp)
                    .clickable { onTap() }
                    .drawBehind {
                        val r = size.minDimension / 2f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFBFD4FF),
                                    Color(0xFF4F86E8),
                                    Color(0x335B8DEF),
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
            if (oura.isNotBlank()) {
                Text(
                    text = oura,
                    color = Color(0xFF5B8DEF),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 12.dp, start = 32.dp, end = 32.dp)
                        .clickable { onOuraTap() }
                )
            }
        }
    }
}

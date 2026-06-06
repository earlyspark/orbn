package com.earlyspark.orbn

import android.content.ComponentName
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
import com.earlyspark.orbn.library.LibraryRepository
import com.earlyspark.orbn.library.TaggingWorker
import com.earlyspark.orbn.playback.AudioCapabilities
import com.earlyspark.orbn.playback.PlaybackService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val OrbnBg = Color(0xFF0A0A0F)

class MainActivity : ComponentActivity() {

    private lateinit var repository: LibraryRepository
    private lateinit var audioManager: AudioManager
    private var audioCallback: AudioDeviceCallback? = null

    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    // UI state driven by the player.
    private val nowPlaying = MutableStateFlow<String?>(null)
    private val isPlaying = MutableStateFlow(false)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying.value = playing
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            nowPlaying.value = mediaItem?.mediaMetadata?.title?.toString()
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
                    onTap = ::onOrbTap,
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
            if (c.mediaItemCount == 0) loadLibraryInto(c)
        }, ContextCompat.getMainExecutor(this))
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

    /** Orb tap = play/pause. If nothing is queued yet, (re)load the library and start. */
    private fun onOrbTap() {
        val c = controller ?: return
        when {
            c.mediaItemCount == 0 -> {
                loadLibraryInto(c)
                c.play()
            }
            c.isPlaying -> c.pause()
            else -> c.play()
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
    onTap: () -> Unit,
) {
    val total by totalCount.collectAsState(initial = 0)
    val analyzed by analyzedCount.collectAsState(initial = 0)
    val playing by isPlaying.collectAsState(initial = false)
    val track by nowPlaying.collectAsState(initial = null)

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
        }
    }
}

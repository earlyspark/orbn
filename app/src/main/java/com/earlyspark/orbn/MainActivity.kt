package com.earlyspark.orbn

import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.earlyspark.orbn.library.LibraryRepository
import com.earlyspark.orbn.library.TaggingWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private val OrbnBg = Color(0xFF0A0A0F)

class MainActivity : ComponentActivity() {

    private lateinit var repository: LibraryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = LibraryRepository(applicationContext)

        // Scan the library, then kick off background tagging for anything new.
        rescanAndTag()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = OrbnBg)) {
                OrbnHome(
                    totalCount = repository.totalCount,
                    analyzedCount = repository.analyzedCount,
                    onTap = ::rescanAndTag,
                )
            }
        }
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
 * Home screen: the breathing orb plus a live library-tagging progress line.
 * Tapping the orb re-scans the folder (handy after adding music) and resumes tagging.
 */
@Composable
fun OrbnHome(
    totalCount: Flow<Int>,
    analyzedCount: Flow<Int>,
    onTap: () -> Unit,
) {
    val total by totalCount.collectAsState(initial = 0)
    val analyzed by analyzedCount.collectAsState(initial = 0)

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
        total == 0 -> "drop music in the orbn folder, then tap"
        analyzed < total -> "tagging your library…  $analyzed / $total"
        else -> "library tagged · $total tracks"
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
            // Tap to re-scan the folder + resume tagging.
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
                text = "music that matches you",
                color = Color(0xFF7C8499),
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = status,
                color = Color(0xFF555A6E),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp, start = 32.dp, end = 32.dp)
            )
        }
    }
}

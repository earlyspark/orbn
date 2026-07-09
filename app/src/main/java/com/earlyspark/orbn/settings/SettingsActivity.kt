package com.earlyspark.orbn.settings

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.earlyspark.orbn.library.LibraryRepository
import com.earlyspark.orbn.library.TaggingService
import com.earlyspark.orbn.ui.RefreshBanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// Palette matching the home screen and the sheets.
private val OrbnBg = Color(0xFF0A0A0F)
private val TextPrimary = Color(0xFFE8ECF5)
private val TextDim = Color(0xFF7C8499)
private val IconDim = Color(0xFF5A6173)
private val Accent = Color(0xFF5B8DEF)

/**
 * Settings screen, opened from the gear icon on the home screen. Toggles write through to
 * [Settings] immediately (no Apply button); callers pick the values up in their onResume.
 */
class SettingsActivity : ComponentActivity() {

    private val repository by lazy { LibraryRepository(applicationContext) }

    // Same SAF "add music" import as the home CTA: pick audio from anywhere on the device;
    // orbn copies it into its own Music folder — no storage permission needed.
    private val importMusic = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) importMusicFiles(uris) }

    private val banner = MutableStateFlow<String?>(null)
    private var bannerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = OrbnBg)) {
                Box(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(onBack = { finish() })
                    val bannerMsg by banner.collectAsState()
                    RefreshBanner(message = bannerMsg)
                }
            }
        }
    }

    /** Open the system file picker for audio (SAF). Result → [importMusicFiles]. */
    private fun launchAddMusic() {
        runCatching { importMusic.launch(arrayOf("audio/*")) }
            .onFailure { showBanner("No file picker available") }
    }

    /** Copy the picked files into orbn's Music folder, then register + tag them (shared repo path). */
    private fun importMusicFiles(uris: List<Uri>) {
        lifecycleScope.launch {
            showBanner("Importing ${uris.size} ${if (uris.size == 1) "song" else "songs"}…")
            val added = repository.importFiles(uris)
            if (added > 0) {
                repository.scan()
                TaggingService.start(applicationContext)
                showBanner("Added $added · analyzing in the background")
            } else {
                showBanner("Couldn't import those files")
            }
        }
    }

    /** Top banner message, auto-clearing after a beat (same as home). */
    private fun showBanner(message: String) {
        banner.value = message
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch {
            delay(2200)
            banner.value = null
        }
    }

    @Composable
    private fun SettingsScreen(onBack: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(OrbnBg)
                .systemBarsPadding()
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = IconDim,
                    )
                }
                Text(
                    text = "settings",
                    color = TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            var reduceMotion by remember { mutableStateOf(Settings.reduceMotion(this@SettingsActivity)) }
            var suppressDrawers by remember { mutableStateOf(Settings.suppressVizDrawers(this@SettingsActivity)) }

            SettingRow(
                title = "Don't animate the homepage",
                subtitle = "The mascot holds still — no blinking, bobbing, or wobble reactions.",
                checked = reduceMotion,
                onCheckedChange = {
                    reduceMotion = it
                    Settings.setReduceMotion(this@SettingsActivity, it)
                },
            )
            SettingRow(
                title = "No drawers on the visualizer",
                subtitle = "Left, right, and up swipes open nothing there. Swipe-down still re-matches.",
                checked = suppressDrawers,
                onCheckedChange = {
                    suppressDrawers = it
                    Settings.setSuppressVizDrawers(this@SettingsActivity, it)
                },
            )
            ActionRow(
                title = "Add music",
                subtitle = "Import songs from anywhere on this phone — orbn keeps its own copy.",
                onClick = ::launchAddMusic,
            )
        }
    }

    /** A tappable settings entry (no switch) — a title/subtitle row that fires an action. */
    @Composable
    private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp)
                .clickable(onClick = onClick),
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    color = TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Icon(Icons.Filled.Add, contentDescription = null, tint = IconDim)
        }
    }

    @Composable
    private fun SettingRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp),
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    color = TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Accent),
            )
        }
    }
}

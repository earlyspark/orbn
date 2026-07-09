package com.earlyspark.orbn.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = OrbnBg)) {
                SettingsScreen(onBack = { finish() })
            }
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

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OrbnBg = Color(0xFF0A0A0F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = OrbnBg)) {
                OrbnHome()
            }
        }
    }
}

/**
 * M0 home screen: a slowly "breathing" glowing orb on near-black — a first taste of the
 * eventual visualizer aesthetic. Proves the Gradle build -> APK -> device loop and Compose
 * rendering/animation on the MindOne.
 */
@Composable
fun OrbnHome() {
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
            // The orb: a radial-gradient circle that gently scales (breathes).
            Box(
                modifier = Modifier
                    .size((180 * pulse).dp)
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
        }
    }
}

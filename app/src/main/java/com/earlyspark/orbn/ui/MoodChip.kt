package com.earlyspark.orbn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A manual mood overrides the matching target while it's set, so the body readout ("feeling …") and
// the picks can legitimately diverge. This small pill makes the override legible — "Mood: Excited" —
// without ever rewriting the body readout. Shared so the home screen and the "why this track" sheet
// render the exact same chip (the visualizer mirrors this look with a TextView, since its readout is
// a classic View, not Compose).
private val ChipBg = Color(0xFF1B2233)     // dark slate, reads over both the home bg and the divider
private val ChipText = Color(0xFF7FB0FF)   // soft blue, the same accent as the biometric readout

@Composable
fun MoodChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = ChipText,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(ChipBg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

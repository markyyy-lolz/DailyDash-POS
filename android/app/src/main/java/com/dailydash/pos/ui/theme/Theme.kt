package com.dailydash.pos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DailyDashColors = lightColorScheme(
    primary = Color(0xFF075FCB),
    onPrimary = Color.White,
    secondary = Color(0xFFFFA000),
    background = Color(0xFFF6F8FC),
    surface = Color.White,
    onSurface = Color(0xFF172033),
    outline = Color(0xFFE2E7F0)
)

@Composable
fun DailyDashTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DailyDashColors, content = content)
}

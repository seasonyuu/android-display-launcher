package me.seasonyuu.displaylauncher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF245D73), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEDF3), onPrimaryContainer = Color(0xFF123846),
    background = Color(0xFFF8FAFB), surface = Color(0xFFF8FAFB),
    surfaceContainer = Color(0xFFEDF2F4), surfaceContainerLow = Color(0xFFF2F6F7),
    surfaceContainerHighest = Color(0xFFE2EAED)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9CCFE1), onPrimary = Color(0xFF073544),
    primaryContainer = Color(0xFF244B5C), onPrimaryContainer = Color(0xFFDDEDF3),
    background = Color(0xFF10171B), surface = Color(0xFF10171B),
    surfaceContainer = Color(0xFF1B252A), surfaceContainerLow = Color(0xFF172126),
    surfaceContainerHighest = Color(0xFF303C42)
)
@Composable
fun DisplayLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Typography, content = content)
}

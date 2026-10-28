package dev.fieldnotes.feature

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightPalette = lightColorScheme(
    primary = Color(0xFF265A45), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBDD), onPrimaryContainer = Color(0xFF183F30),
    secondary = Color(0xFF7A6035), secondaryContainer = Color(0xFFF0E4CE),
    background = Color(0xFFF8F7F2), onBackground = Color(0xFF202B25),
    surface = Color(0xFFFBFAF6), onSurface = Color(0xFF202B25),
    surfaceVariant = Color(0xFFE7EAE1), onSurfaceVariant = Color(0xFF536057),
    surfaceContainerLow = Color(0xFFF1F1EA), surfaceContainer = Color(0xFFECEEE6),
    outlineVariant = Color(0xFFCDD4C9),
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFFA4D4B8), onPrimary = Color(0xFF123827),
    primaryContainer = Color(0xFF254D3B), onPrimaryContainer = Color(0xFFCCECD6),
    secondary = Color(0xFFDFC69B), background = Color(0xFF111B16),
    surface = Color(0xFF15201A), surfaceContainerLow = Color(0xFF1A271F),
    surfaceContainer = Color(0xFF213026), onSurface = Color(0xFFE1E9DF),
    onBackground = Color(0xFFE1E9DF), onSurfaceVariant = Color(0xFFB5C4B8),
)

@Composable
fun FieldNotesTheme(content: @Composable () -> Unit) {
    val typography = Typography(
        headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 42.sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
        titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 29.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    )
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkPalette else LightPalette,
        typography = typography, content = content)
}

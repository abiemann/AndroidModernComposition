package dev.fieldnotes.feature

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared spacing for page gutters, groups, cards, and controls. */
internal object FieldNotesSpacing {
    val Small = 8.dp
    val Medium = 16.dp
    val Large = 24.dp
    val Section = 32.dp
    val IconGap = Small
}

// Define paired foreground/container roles in both themes. Unspecified roles
// otherwise fall back to Material's default palette, including purple selections.
private val LightPalette = lightColorScheme(
    primary = Color(0xFF265A45), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBDD), onPrimaryContainer = Color(0xFF183F30),
    inversePrimary = Color(0xFFA4D4B8),
    secondary = Color(0xFF725B32), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E4CE), onSecondaryContainer = Color(0xFF4F3E20),
    tertiary = Color(0xFF526344), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2EBD5), onTertiaryContainer = Color(0xFF24331C),
    background = Color(0xFFF8F7F2), onBackground = Color(0xFF202B25),
    surface = Color(0xFFFBFAF6), onSurface = Color(0xFF202B25),
    surfaceVariant = Color(0xFFE7EAE1), onSurfaceVariant = Color(0xFF536057),
    surfaceTint = Color(0xFF265A45),
    inverseSurface = Color(0xFF26332B), inverseOnSurface = Color(0xFFF1F4EA),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF707B6F), outlineVariant = Color(0xFFCDD4C9), scrim = Color.Black,
    surfaceBright = Color(0xFFFBFAF6), surfaceDim = Color(0xFFDADDD4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F1EA), surfaceContainer = Color(0xFFECEEE6),
    surfaceContainerHigh = Color(0xFFE6E9DF), surfaceContainerHighest = Color(0xFFE1E5DB),
    primaryFixed = Color(0xFFDDEBDD), primaryFixedDim = Color(0xFFA4D4B8),
    onPrimaryFixed = Color(0xFF0E2C1E), onPrimaryFixedVariant = Color(0xFF265A45),
    secondaryFixed = Color(0xFFF0E4CE), secondaryFixedDim = Color(0xFFDFC69B),
    onSecondaryFixed = Color(0xFF2B1B04), onSecondaryFixedVariant = Color(0xFF574524),
    tertiaryFixed = Color(0xFFE2EBD5), tertiaryFixedDim = Color(0xFFBFCEAC),
    onTertiaryFixed = Color(0xFF12210B), onTertiaryFixedVariant = Color(0xFF394B2E),
)

// Fixed roles intentionally keep the same colors across light and dark themes.
private val DarkPalette = LightPalette.copy(
    primary = Color(0xFFA4D4B8), onPrimary = Color(0xFF123827),
    primaryContainer = Color(0xFF254D3B), onPrimaryContainer = Color(0xFFCCECD6),
    inversePrimary = Color(0xFF265A45),
    secondary = Color(0xFFDFC69B), onSecondary = Color(0xFF3F2E10),
    secondaryContainer = Color(0xFF574524), onSecondaryContainer = Color(0xFFF5E2BC),
    tertiary = Color(0xFFBFCEAC), onTertiary = Color(0xFF2A361F),
    tertiaryContainer = Color(0xFF394B2E), onTertiaryContainer = Color(0xFFDCE8CB),
    background = Color(0xFF111B16),
    surface = Color(0xFF15201A), surfaceContainerLow = Color(0xFF1A271F),
    surfaceContainer = Color(0xFF213026), onSurface = Color(0xFFE1E9DF),
    onBackground = Color(0xFFE1E9DF), onSurfaceVariant = Color(0xFFB5C4B8),
    surfaceVariant = Color(0xFF3C4A40), surfaceTint = Color(0xFFA4D4B8),
    inverseSurface = Color(0xFFE1E9DF), inverseOnSurface = Color(0xFF24322A),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8C9C8F), outlineVariant = Color(0xFF3C4A40), scrim = Color.Black,
    surfaceBright = Color(0xFF36453B), surfaceDim = Color(0xFF111B16),
    surfaceContainerLowest = Color(0xFF0C150F),
    surfaceContainerHigh = Color(0xFF2B3A30), surfaceContainerHighest = Color(0xFF36453B),
)

private val FieldNotesTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun FieldNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkPalette else LightPalette,
        typography = FieldNotesTypography, content = content)
}

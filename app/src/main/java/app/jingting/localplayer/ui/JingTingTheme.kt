package app.jingting.localplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Every component, including dialogs and menus, uses the same quiet night palette. */
object NightPalette {
    val Ink = Color(0xFF100E0C)
    val Panel = Color(0xFF1C1916)
    val Accent = Color(0xFFC6A477)
    val Text = Color(0xFFD8D0C4)
    val Muted = Color(0xFFB6AEA2)
    val AccentPanel = Color(0xFF3A3025)
    val Outline = Color(0xFF8C8173)
    val Error = Color(0xFFD7A193)
}

// Explicitly assign all roles available in Material 3 1.3.2. Partial overrides leave
// purple defaults in AlertDialog, DropdownMenu, text fields and elevated containers.
val NightColorScheme = darkColorScheme(
    primary = NightPalette.Accent,
    onPrimary = NightPalette.Ink,
    primaryContainer = NightPalette.AccentPanel,
    onPrimaryContainer = Color(0xFFE1C39D),
    inversePrimary = Color(0xFF6D5030),
    secondary = Color(0xFFBEAD96),
    onSecondary = NightPalette.Ink,
    secondaryContainer = Color(0xFF342B22),
    onSecondaryContainer = Color(0xFFE0CBB0),
    tertiary = Color(0xFFBCAE98),
    onTertiary = NightPalette.Ink,
    tertiaryContainer = Color(0xFF342F27),
    onTertiaryContainer = Color(0xFFDDCFB9),
    background = NightPalette.Ink,
    onBackground = NightPalette.Text,
    surface = NightPalette.Ink,
    onSurface = NightPalette.Text,
    surfaceVariant = NightPalette.Panel,
    onSurfaceVariant = NightPalette.Muted,
    surfaceTint = NightPalette.Accent,
    inverseSurface = Color(0xFFD8D0C4),
    inverseOnSurface = Color(0xFF27211A),
    error = NightPalette.Error,
    onError = Color(0xFF291813),
    errorContainer = Color(0xFF4C2D25),
    onErrorContainer = Color(0xFFE6BAB0),
    outline = NightPalette.Outline,
    outlineVariant = Color(0xFF4A4137),
    scrim = Color.Black,
    surfaceBright = Color(0xFF38322B),
    surfaceDim = NightPalette.Ink,
    surfaceContainerLowest = Color(0xFF0C0A09),
    surfaceContainerLow = Color(0xFF171411),
    surfaceContainer = NightPalette.Panel,
    surfaceContainerHigh = Color(0xFF26211D),
    surfaceContainerHighest = Color(0xFF302A24),
)

@Composable
fun NightTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightColorScheme, content = content)
}

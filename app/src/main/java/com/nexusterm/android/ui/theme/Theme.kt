package com.nexusterm.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette (matches NexusTerm/icon-design/icon.svg and the terminal accent).
val NexusGreen = Color(0xFF1E8467)
val NexusMint = Color(0xFF5DCAA5)
val NexusDeep = Color(0xFF0F2A23)

private val LightColors = lightColorScheme(
    primary = NexusGreen,
    onPrimary = Color.White,
    primaryContainer = NexusMint,
    onPrimaryContainer = NexusDeep,
    inversePrimary = NexusMint,
    secondary = Color(0xFF3A6B5C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEBDF),
    onSecondaryContainer = NexusDeep,
    tertiary = Color(0xFF2B6A7A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC4E9F2),
    onTertiaryContainer = Color(0xFF052A33),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF181C1B),
    surface = Color(0xFFF7FAF8),
    onSurface = Color(0xFF181C1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceTint = NexusGreen,
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C4),
    // Material3 1.2+ introduced the surfaceContainer* elevation ramp and uses it
    // for every raised container — AlertDialog takes surfaceContainerHigh, menus
    // and bottom sheets take surfaceContainer, etc. These roles are NOT derived
    // from `surface`: unspecified they fall back to the baseline *purple* tonal
    // palette, which is why 操作说明 rendered lavender (#F1EBF4) in an otherwise
    // green app. Spelling out the neutral-green ramp here fixes every such
    // container at once rather than patching containerColor per dialog.
    surfaceBright = Color(0xFFF7FAF8),
    surfaceDim = Color(0xFFD7E0DC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F6F3),
    surfaceContainer = Color(0xFFEBF1EE),
    surfaceContainerHigh = Color(0xFFE6ECE9),
    surfaceContainerHighest = Color(0xFFE3EAE6),
    inverseSurface = Color(0xFF2C3230),
    inverseOnSurface = Color(0xFFEFF4F1),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = NexusMint,
    onPrimary = NexusDeep,
    primaryContainer = Color(0xFF00513E),
    onPrimaryContainer = Color(0xFFA6F2D3),
    inversePrimary = NexusGreen,
    secondary = Color(0xFFB2CCC1),
    onSecondary = Color(0xFF1E352D),
    secondaryContainer = Color(0xFF344C43),
    onSecondaryContainer = Color(0xFFCDEBDF),
    tertiary = Color(0xFFA8CDDA),
    onTertiary = Color(0xFF0E3540),
    tertiaryContainer = Color(0xFF264C57),
    onTertiaryContainer = Color(0xFFC4E9F2),
    background = Color(0xFF101413),
    onBackground = Color(0xFFDFE4E1),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFDFE4E1),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceTint = NexusMint,
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4945),
    // Dark half of the neutral-green container ramp — see the note in LightColors.
    surfaceBright = Color(0xFF353B39),
    surfaceDim = Color(0xFF101413),
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = Color(0xFF181C1B),
    surfaceContainer = Color(0xFF1C2120),
    surfaceContainerHigh = Color(0xFF262B2A),
    surfaceContainerHighest = Color(0xFF313634),
    inverseSurface = Color(0xFFDFE4E1),
    inverseOnSurface = Color(0xFF2C3230),
    scrim = Color(0xFF000000),
)

@Composable
fun NexusTermTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

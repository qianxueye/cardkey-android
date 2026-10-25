package cc.cardkey.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PlexoLightColors = lightColorScheme(
    primary = Color(0xFFD97706),
    onPrimary = Color(0xFF1B1408),
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF92400E),
    secondary = Color(0xFF626268),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3F0EC),
    onSecondaryContainer = Color(0xFF245448),
    tertiary = Color(0xFF0E6B54),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1F3ED),
    onTertiaryContainer = Color(0xFF0E6B54),
    background = Color.White,
    onBackground = Color(0xFF1D1D1F),
    surface = Color.White,
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF626268),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF202325),
    inverseOnSurface = Color(0xFFEAE7E2),
    inversePrimary = Color(0xFFD8A44C),
    outline = Color(0xFF76767C),
    outlineVariant = Color(0xFFE0E0E2),
    scrim = Color.Black,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE0E0E2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF4F5F7),
    surfaceContainerHigh = Color(0xFFF0F0F0),
    surfaceContainerHighest = Color(0xFFE8E8EA),
)

private val PlexoDarkColors = darkColorScheme(
    primary = Color(0xFFD8A44C),
    onPrimary = Color(0xFF1B1408),
    primaryContainer = Color(0xFF33291A),
    onPrimaryContainer = Color(0xFFE8BB6D),
    secondary = Color(0xFFB5B9BF),
    onSecondary = Color(0xFF1B1E20),
    secondaryContainer = Color(0xFF173C33),
    onSecondaryContainer = Color(0xFF9AD6C5),
    tertiary = Color(0xFF7FC9BB),
    onTertiary = Color(0xFF123A31),
    tertiaryContainer = Color(0xFF173C33),
    onTertiaryContainer = Color(0xFF7FC9BB),
    background = Color(0xFF1B1E20),
    onBackground = Color(0xFFEAE7E2),
    surface = Color(0xFF1B1E20),
    onSurface = Color(0xFFEAE7E2),
    surfaceVariant = Color(0xFF242729),
    onSurfaceVariant = Color(0xFFB5B9BF),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFEAE7E2),
    inverseOnSurface = Color(0xFF1D1D1F),
    inversePrimary = Color(0xFF92400E),
    outline = Color(0xFF858B91),
    outlineVariant = Color(0xFF33383C),
    scrim = Color.Black,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceBright = Color(0xFF33383C),
    surfaceDim = Color(0xFF1B1E20),
    surfaceContainerLowest = Color(0xFF16181A),
    surfaceContainerLow = Color(0xFF1B1E20),
    surfaceContainer = Color(0xFF202325),
    surfaceContainerHigh = Color(0xFF242729),
    surfaceContainerHighest = Color(0xFF2B2F33),
)

private val PlexoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

private val PlexoTypography = Typography().let { native ->
    native.copy(
        bodySmall = native.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = native.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = native.labelSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    )
}

@Composable
fun CardKeyTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 48.dp) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) PlexoDarkColors else PlexoLightColors,
            typography = PlexoTypography,
            shapes = PlexoShapes,
            content = content,
        )
    }
}

@Composable
internal fun managerColorScheme() = MaterialTheme.colorScheme.let { base ->
    if (isSystemInDarkTheme()) base.copy(
        primary = Color(0xFFADC6FF), onPrimary = Color(0xFF002E69),
        primaryContainer = Color(0xFF164681), onPrimaryContainer = Color(0xFFD8E2FF),
        secondaryContainer = Color(0xFF3B3215), onSecondaryContainer = Color(0xFFF4DF9A),
        tertiary = Color(0xFFE8C45A), onTertiary = Color(0xFF3A3000),
    ) else base.copy(
        primary = Color(0xFF1757A6), onPrimary = Color.White,
        primaryContainer = Color(0xFFE8F0FF), onPrimaryContainer = Color(0xFF103867),
        secondaryContainer = Color(0xFFFFF2C9), onSecondaryContainer = Color(0xFF574500),
        tertiary = Color(0xFF8A6800), onTertiary = Color.White,
    )
}

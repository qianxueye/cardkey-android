package cc.cardkey.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF454A51),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E3FD),
    onSecondaryContainer = Color(0xFF041E49),
    tertiary = Color(0xFF18692C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCEFE0),
    onTertiaryContainer = Color(0xFF0B3A18),
    background = Color(0xFFECEEF1),
    onBackground = Color(0xFF1B1D21),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF1B1D21),
    surfaceVariant = Color(0xFFE2E5E9),
    onSurfaceVariant = Color(0xFF454A51),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2B2F34),
    inverseOnSurface = Color(0xFFE3E5E8),
    inversePrimary = Color(0xFFA8C7FA),
    outline = Color(0xFF737880),
    outlineVariant = Color(0xFFC7CBD1),
    scrim = Color(0xFF000000),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    surfaceBright = Color(0xFFF7F8FA),
    surfaceDim = Color(0xFFD8DBE0),
    surfaceContainerLowest = Color(0xFFF7F8FA),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFE2E5E9),
    surfaceContainerHigh = Color(0xFFDDE0E5),
    surfaceContainerHighest = Color(0xFFD8DBE0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFC2C6CC),
    onSecondary = Color(0xFF2B2F34),
    secondaryContainer = Color(0xFF0842A0),
    onSecondaryContainer = Color(0xFFD3E3FD),
    tertiary = Color(0xFF30D158),
    onTertiary = Color(0xFF00390F),
    tertiaryContainer = Color(0xFF12401E),
    onTertiaryContainer = Color(0xFFBFF0CA),
    background = Color(0xFF16181B),
    onBackground = Color(0xFFE3E5E8),
    surface = Color(0xFF202327),
    onSurface = Color(0xFFE3E5E8),
    surfaceVariant = Color(0xFF2B2F34),
    onSurfaceVariant = Color(0xFFC2C6CC),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE3E5E8),
    inverseOnSurface = Color(0xFF1B1D21),
    inversePrimary = Color(0xFF0B57D0),
    outline = Color(0xFF8A8F97),
    outlineVariant = Color(0xFF41454C),
    scrim = Color(0xFF000000),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceBright = Color(0xFF383C41),
    surfaceDim = Color(0xFF16181B),
    surfaceContainerLowest = Color(0xFF1B1D21),
    surfaceContainerLow = Color(0xFF202327),
    surfaceContainer = Color(0xFF2B2F34),
    surfaceContainerHigh = Color(0xFF31353A),
    surfaceContainerHighest = Color(0xFF383C41),
)

private val CardKeyShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(3.dp),
    extraLarge = RoundedCornerShape(3.dp),
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
            colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
            typography = PlexoTypography,
            shapes = CardKeyShapes,
            content = content,
        )
    }
}

internal val ColorScheme.isDark: Boolean get() = surface.luminance() < 0.5f

internal val ColorScheme.success: Color get() = if (isDark) Color(0xFF30D158) else Color(0xFF18692C)

internal val ColorScheme.warning: Color get() = if (isDark) Color(0xFFFFD60A) else Color(0xFF7A5100)

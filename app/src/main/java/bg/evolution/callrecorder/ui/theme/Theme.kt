package bg.evolution.callrecorder.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bg.evolution.callrecorder.core.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4CF0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF170065),
    inversePrimary = Color(0xFFC6BFFF),
    secondary = Color(0xFF5E5B7D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4DFF9),
    onSecondaryContainer = Color(0xFF1B1836),
    tertiary = Color(0xFF00796F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA6F2E8),
    onTertiaryContainer = Color(0xFF00201D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFE5E0F0),
    onSurfaceVariant = Color(0xFF48454F),
    surfaceTint = Color(0xFF5B4CF0),
    inverseSurface = Color(0xFF313037),
    inverseOnSurface = Color(0xFFF3EFF9),
    outline = Color(0xFF79757F),
    outlineVariant = Color(0xFFC9C4D0),
    surfaceBright = Color(0xFFFCF8FF),
    surfaceDim = Color(0xFFDDD8E3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2FC),
    surfaceContainer = Color(0xFFF0ECF6),
    surfaceContainerHigh = Color(0xFFEAE6F1),
    surfaceContainerHighest = Color(0xFFE5E1EB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF2A119C),
    primaryContainer = Color(0xFF4130C9),
    onPrimaryContainer = Color(0xFFE4DFFF),
    inversePrimary = Color(0xFF5B4CF0),
    secondary = Color(0xFFC8C3DC),
    onSecondary = Color(0xFF302D4C),
    secondaryContainer = Color(0xFF47445F),
    onSecondaryContainer = Color(0xFFE4DFF9),
    tertiary = Color(0xFF89D6CC),
    onTertiary = Color(0xFF003732),
    tertiaryContainer = Color(0xFF005049),
    onTertiaryContainer = Color(0xFFA6F2E8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131219),
    onBackground = Color(0xFFE5E1EB),
    surface = Color(0xFF131219),
    onSurface = Color(0xFFE5E1EB),
    surfaceVariant = Color(0xFF48454F),
    onSurfaceVariant = Color(0xFFC9C4D0),
    surfaceTint = Color(0xFFC6BFFF),
    inverseSurface = Color(0xFFE5E1EB),
    inverseOnSurface = Color(0xFF313037),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF48454F),
    surfaceBright = Color(0xFF3A3840),
    surfaceDim = Color(0xFF131219),
    surfaceContainerLowest = Color(0xFF0E0D14),
    surfaceContainerLow = Color(0xFF1C1B22),
    surfaceContainer = Color(0xFF201F26),
    surfaceContainerHigh = Color(0xFF2A2931),
    surfaceContainerHighest = Color(0xFF35343C),
)

/** Colours that Material doesn't define but the app needs everywhere. */
@Immutable
data class ExtraColors(
    val record: Color,
    val onRecord: Color,
    val incoming: Color,
    val outgoing: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
)

val LocalExtraColors = staticCompositionLocalOf {
    ExtraColors(Color.Red, Color.White, Color.Green, Color.Blue, Color.Magenta, Color.Blue, Color.White)
}

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun CallRecorderTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val extra = if (dark) {
        ExtraColors(
            record = Color(0xFFFF8FA3), onRecord = Color(0xFF4A0016),
            incoming = Color(0xFF7FD8A4), outgoing = Color(0xFF9CC3FF),
            heroStart = scheme.primaryContainer, heroEnd = scheme.tertiaryContainer,
            onHero = scheme.onPrimaryContainer,
        )
    } else {
        ExtraColors(
            record = Color(0xFFE5294F), onRecord = Color.White,
            incoming = Color(0xFF1E8E52), outgoing = Color(0xFF2F6FDB),
            heroStart = scheme.primary, heroEnd = scheme.tertiary,
            onHero = scheme.onPrimary,
        )
    }
    CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

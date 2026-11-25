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
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bg.evolution.callrecorder.core.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF00856F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA0F2DD),
    onPrimaryContainer = Color(0xFF002019),
    inversePrimary = Color(0xFF84D6C1),
    secondary = Color(0xFF4A6360),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF0E6A85),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBEE9FF),
    onTertiaryContainer = Color(0xFF001F2A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FBF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5FBF8),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceTint = Color(0xFF00856F),
    inverseSurface = Color(0xFF2B3230),
    inverseOnSurface = Color(0xFFECF2EF),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    surfaceBright = Color(0xFFF5FBF8),
    surfaceDim = Color(0xFFD5DBD8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE6),
    surfaceContainerHighest = Color(0xFFDEE4E1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF84D6C1),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005144),
    onPrimaryContainer = Color(0xFFA0F2DD),
    inversePrimary = Color(0xFF006B5A),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFF8DCFEE),
    onTertiary = Color(0xFF003546),
    tertiaryContainer = Color(0xFF004D64),
    onTertiaryContainer = Color(0xFFBEE9FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1514),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0F1514),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    surfaceTint = Color(0xFF84D6C1),
    inverseSurface = Color(0xFFDEE4E1),
    inverseOnSurface = Color(0xFF2B3230),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    surfaceBright = Color(0xFF353B39),
    surfaceDim = Color(0xFF0F1514),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303634),
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

// Headings and short texts break into lines of equal length ("balanced") instead of leaving a
// single word on the last line; body text uses the high-quality paragraph breaker.
private val AppTypography = Typography().run {
    val heading = LineBreak.Heading
    val body = LineBreak.Paragraph
    copy(
        displayLarge = displayLarge.copy(lineBreak = heading),
        displayMedium = displayMedium.copy(lineBreak = heading),
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold, lineBreak = heading),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold, lineBreak = heading),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, lineBreak = heading),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, lineBreak = heading),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, lineBreak = heading),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, lineBreak = heading),
        titleSmall = titleSmall.copy(lineBreak = heading),
        bodyLarge = bodyLarge.copy(lineBreak = body),
        bodyMedium = bodyMedium.copy(lineBreak = body),
        bodySmall = bodySmall.copy(lineBreak = body),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp, lineBreak = heading),
        labelMedium = labelMedium.copy(lineBreak = heading),
        labelSmall = labelSmall.copy(lineBreak = heading),
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
    val dynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
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
            heroStart = if (dynamic) scheme.primaryContainer else Color(0xFF0B6E5E),
            heroEnd = if (dynamic) scheme.tertiaryContainer else Color(0xFF0B4A63),
            onHero = if (dynamic) scheme.onPrimaryContainer else Color(0xFFE6FFF8),
        )
    } else {
        ExtraColors(
            record = Color(0xFFE5294F), onRecord = Color.White,
            incoming = Color(0xFF1E8E52), outgoing = Color(0xFF2F6FDB),
            heroStart = if (dynamic) scheme.primary else Color(0xFF17A78C),
            heroEnd = if (dynamic) scheme.tertiary else Color(0xFF0E5F7A),
            onHero = if (dynamic) scheme.onPrimary else Color.White,
        )
    }
    CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

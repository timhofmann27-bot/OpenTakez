package io.github.timhofmann27.opentakez.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The mark's lavender is the dark theme accent. Light mode uses a deeper shade for contrast.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA182FD),
    onPrimary = Color(0xFF211338),
    primaryContainer = Color(0xFF373047),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCAB8FA),
    onSecondary = Color(0xFF211338),
    secondaryContainer = Color(0xFF373047),
    onSecondaryContainer = Color(0xFFEADDFF),
    background = Color(0xFF19181A),
    onBackground = Color(0xFFF5F2F8),
    surface = Color(0xFF232225),
    onSurface = Color(0xFFF5F2F8),
    surfaceVariant = Color(0xFF302D35),
    onSurfaceVariant = Color(0xFFBEB9C7),
    surfaceContainerLowest = Color(0xFF141315),
    surfaceContainerLow = Color(0xFF201F22),
    surfaceContainer = Color(0xFF232225),
    surfaceContainerHigh = Color(0xFF2B292F),
    surfaceContainerHighest = Color(0xFF35313C),
    surfaceTint = Color(0xFFA182FD),
    inverseSurface = Color(0xFFE9E4EF),
    inverseOnSurface = Color(0xFF29252F),
    inversePrimary = Color(0xFF6940BD),
    outline = Color(0xFF827B8D),
    outlineVariant = Color(0xFF3C3843),
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0A0A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6940BD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF2E0A6B),
    secondary = Color(0xFF6940BD),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE4FF),
    onSecondaryContainer = Color(0xFF34214F),
    background = Color(0xFFF7F5FB),
    onBackground = Color(0xFF18181B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFECECF0),
    onSurfaceVariant = Color(0xFF5D5D69),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFECECF0),
    surfaceContainerHighest = Color(0xFFECECF0),
    outline = Color(0xFF80808C),
    outlineVariant = Color(0xFFDCDCE3),
    surfaceTint = Color(0xFF6940BD),
    error = Color(0xFFB91C1C),
    onError = Color.White,
)

private val AppTypography = Typography(
    displayMedium = TextStyle(fontSize = 42.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** The PWA's success green (done states); Material has no such role. Always paired with text. */
@Composable
fun successColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF4ADE80) else Color(0xFF15803D)

@Composable
fun OpenTakezTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}

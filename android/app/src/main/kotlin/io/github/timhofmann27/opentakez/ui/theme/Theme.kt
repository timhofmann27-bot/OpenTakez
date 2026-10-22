package io.github.timhofmann27.opentakez.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

// Design tokens from web/src/index.css, so the app looks like the PWA when dynamic color is off.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B1F72),
    onPrimaryContainer = Color(0xFFEADDFF),
    background = Color(0xFF0C0C0F),
    onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF17171C),
    onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF222229),
    onSurfaceVariant = Color(0xFFA1A1AD),
    surfaceContainerLowest = Color(0xFF0C0C0F),
    surfaceContainerLow = Color(0xFF17171C),
    surfaceContainer = Color(0xFF17171C),
    surfaceContainerHigh = Color(0xFF222229),
    surfaceContainerHighest = Color(0xFF222229),
    outline = Color(0xFF6B6B78),
    outlineVariant = Color(0xFF2E2E37),
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0A0A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6D28D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF2E0A6B),
    background = Color(0xFFF6F6F8),
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
    error = Color(0xFFB91C1C),
    onError = Color.White,
)

/** The PWA's success green (done states); Material has no such role. Always paired with text. */
@Composable
fun successColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF4ADE80) else Color(0xFF15803D)

@Composable
fun OpenTakezTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
    MaterialTheme(colorScheme = scheme, content = content)
}

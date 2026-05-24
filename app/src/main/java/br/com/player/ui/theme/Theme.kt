package br.com.player.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = CinemaBlue80,
    onPrimary = Color(0xFF002B6B),
    primaryContainer = CinemaBlueContainer80,
    onPrimaryContainer = CinemaBlue80,
    secondary = CinemaIndigo80,
    onSecondary = Color(0xFF1F2A5E),
    secondaryContainer = CinemaIndigoContainer80,
    onSecondaryContainer = CinemaIndigo80,
    tertiary = CinemaAmber80,
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFDEA0),
    error = CinemaError80,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = CinemaBlack,
    onBackground = Color(0xFFE3E2EC),
    surface = CinemaSurface,
    onSurface = Color(0xFFE3E2EC),
    surfaceVariant = CinemaSurfaceHigh,
    onSurfaceVariant = Color(0xFFC5C4D0),
    surfaceContainerLowest = CinemaDeepSurface,
    surfaceContainerLow = CinemaSurfaceMid,
    surfaceContainer = CinemaSurface,
    surfaceContainerHigh = CinemaSurfaceHigh,
    outline = Color(0xFF8F8FA0),
    outlineVariant = Color(0xFF45454F)
)

private val LightColorScheme = lightColorScheme(
    primary = CinemaBlue40,
    onPrimary = Color.White,
    primaryContainer = CinemaBlueContainer40,
    onPrimaryContainer = Color(0xFF001A45),
    secondary = CinemaIndigo40,
    onSecondary = Color.White,
    secondaryContainer = CinemaIndigoContainer40,
    onSecondaryContainer = Color(0xFF0C1446),
    tertiary = CinemaAmber40,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA0),
    onTertiaryContainer = Color(0xFF271900),
    error = CinemaError40,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B23),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B23),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F2FF),
    surfaceContainer = Color(0xFFEFECF9),
    surfaceContainerHigh = Color(0xFFE9E6F3),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC8C5D0)
)

private val PlayerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun PlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PlayerTypography,
        shapes = PlayerShapes,
        content = content
    )
}

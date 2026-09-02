package com.mediabackup.utility.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF67DF9E),
    onPrimary = Color(0xFF00381E),
    primaryContainer = Color(0xFF00522E),
    onPrimaryContainer = Color(0xFF85FCB8),
    secondary = Color(0xFF8CD4B3),
    onSecondary = Color(0xFF003823),
    secondaryContainer = Color(0xFF165038),
    onSecondaryContainer = Color(0xFFA8F1CE),
    background = Color(0xFF0F1512),
    surface = Color(0xFF171E1A),
    surfaceVariant = Color(0xFF26332C),
    onSurface = Color(0xFFE1E3DF),
    onSurfaceVariant = Color(0xFFC0C9C2),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006C3E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF85FCB8),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF2D6A4F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA8F1CE),
    onSecondaryContainer = Color(0xFF002113),
    background = Color(0xFFF7FBF7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2EBE5),
    onSurface = Color(0xFF191C1A),
    onSurfaceVariant = Color(0xFF404943),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

@Composable
fun MediaBackupTheme(
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
        typography = Typography(),
        content = content
    )
}

package com.example.clauderemote.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = DPrimary, onPrimary = DOnPrimary,
    primaryContainer = DPrimaryContainer, onPrimaryContainer = DOnPrimaryContainer,
    secondary = DSecondary, onSecondary = DOnSecondary,
    secondaryContainer = DSecondaryContainer, onSecondaryContainer = DOnSecondaryContainer,
    tertiary = DTertiary, onTertiary = DOnTertiary,
    tertiaryContainer = DTertiaryContainer, onTertiaryContainer = DOnTertiaryContainer,
    background = DBackground, onBackground = DOnBackground,
    surface = DSurface, onSurface = DOnSurface,
    surfaceVariant = DSurfaceVariant, onSurfaceVariant = DOnSurfaceVariant,
    outline = DOutline, outlineVariant = DOutlineVariant,
    error = DError, onError = DOnError,
)

private val LightColors = lightColorScheme(
    primary = LPrimary, onPrimary = LOnPrimary,
    primaryContainer = LPrimaryContainer, onPrimaryContainer = LOnPrimaryContainer,
    secondary = LSecondary, onSecondary = LOnSecondary,
    secondaryContainer = LSecondaryContainer, onSecondaryContainer = LOnSecondaryContainer,
    tertiary = LTertiary, onTertiary = LOnTertiary,
    tertiaryContainer = LTertiaryContainer, onTertiaryContainer = LOnTertiaryContainer,
    background = LBackground, onBackground = LOnBackground,
    surface = LSurface, onSurface = LOnSurface,
    surfaceVariant = LSurfaceVariant, onSurfaceVariant = LOnSurfaceVariant,
    outline = LOutline, outlineVariant = LOutlineVariant,
    error = LError, onError = LOnError,
)

@Composable
fun ClaudeRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 默认关闭动态取色，让品牌色生效；需要 Material You 时传 true。
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

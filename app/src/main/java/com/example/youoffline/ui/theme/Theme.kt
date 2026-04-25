package com.example.youoffline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary        = LightPrimary,
    onPrimary      = LightOnPrimary,
    secondary      = LightSecondary,
    onSecondary    = LightOnSecondary,
    tertiary       = LightTertiary,
    onTertiary     = LightOnTertiary,
    background     = LightBackground,
    onBackground   = LightOnBackground,
    surface        = LightSurface,
    onSurface      = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    outline        = LightOutline
)

private val DarkColorScheme = darkColorScheme(
    primary            = DarkPrimary,
    onPrimary          = DarkOnPrimary,
    primaryContainer   = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary          = DarkSecondary,
    onSecondary        = DarkOnSecondary,
    tertiary           = DarkTertiary,
    onTertiary         = DarkOnTertiary,
    background         = DarkBackground,
    onBackground       = DarkOnBackground,
    surface            = DarkSurface,
    onSurface          = DarkOnSurface,
    surfaceVariant     = DarkSurfaceVariant,
    outline            = DarkOutline
)

@Composable
fun YouOfflineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
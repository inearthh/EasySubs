package com.example.easysubs.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BlackColorScheme = darkColorScheme(
    primary = EasySubsAccent,
    onPrimary = EasySubsButtonText,
    primaryContainer = EasySubsAccentLight,
    onPrimaryContainer = EasySubsAccentText,
    secondary = EasySubsTextMuted,
    tertiary = EasySubsAccentLight,
    background = EasySubsBg,
    surface = EasySubsCardBg,
    onBackground = EasySubsTextPrimary,
    onSurface = EasySubsTextPrimary
)

@Composable
fun EasySubsTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BlackColorScheme,
        typography = Typography,
        content = content
    )
}

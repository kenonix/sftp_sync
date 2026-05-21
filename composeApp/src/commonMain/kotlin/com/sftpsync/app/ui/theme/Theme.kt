package com.sftpsync.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanGlow,
    secondary = BlueGlow,
    tertiary = VioletGlow,
    background = Slate900,
    surface = Slate800,
    onPrimary = TextWhite,
    onSecondary = TextWhite,
    onBackground = TextWhite,
    onSurface = TextLight,
    error = ErrorRed
)

@Composable
fun SftpSyncTheme(
    darkTheme: Boolean = true, // Default to gorgeous dark mode!
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

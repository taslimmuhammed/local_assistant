package com.local.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A deliberately single-mode, white theme.
 *
 * [isSystemInDarkTheme] is intentionally not consulted: the app commits to the
 * light look rather than inheriting whatever the system is doing. A dark mode,
 * if it ever lands, should be an explicit setting the user opts into.
 */
object Palette {
    val White = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFFFFFF)

    /** Assistant message background and other recessed surfaces. */
    val SurfaceMuted = Color(0xFFF7F7F8)

    /** Hairlines, dividers, input outline. */
    val Border = Color(0xFFE6E6E8)

    val TextPrimary = Color(0xFF1A1A1A)
    val TextSecondary = Color(0xFF6B6B70)

    /** The single accent. Used for the send button, user bubble tint, meters. */
    val Accent = Color(0xFF1F6FEB)
    val AccentMuted = Color(0xFFEAF1FE)

    val Danger = Color(0xFFC0392B)
    val Warn = Color(0xFFB7791F)
}

private val LightColors = lightColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.White,
    primaryContainer = Palette.AccentMuted,
    onPrimaryContainer = Palette.TextPrimary,
    secondary = Palette.TextSecondary,
    onSecondary = Palette.White,
    background = Palette.White,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceMuted,
    onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Border,
    outlineVariant = Palette.Border,
    error = Palette.Danger,
    onError = Palette.White,
)

@Composable
fun LocalAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content,
    )
}

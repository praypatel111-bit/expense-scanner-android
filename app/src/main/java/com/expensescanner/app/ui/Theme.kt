package com.expensescanner.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Ink on paper with one highlighter-yellow accent, the same look as the web app. */
data class Palette(
  val bg: Color,
  val paper: Color,
  val field: Color,
  val ink: Color,
  val muted: Color,
  val line: Color,
  val hi: Color,
  val onHi: Color,
  val warn: Color,
  val bad: Color,
  val good: Color,
)

private val Light = Palette(
  bg = Color(0xFFE8EBE7), paper = Color(0xFFFFFFFF), field = Color(0xFFF3F5F2), ink = Color(0xFF15191C),
  muted = Color(0xFF5D656B), line = Color(0xFFD3D8D2), hi = Color(0xFFFFE33A), onHi = Color(0xFF15191C),
  warn = Color(0xFF965500), bad = Color(0xFFB3301D), good = Color(0xFF1B7748),
)
private val Dark = Palette(
  bg = Color(0xFF0E1113), paper = Color(0xFF181C1F), field = Color(0xFF1F2427), ink = Color(0xFFE8EBE7),
  muted = Color(0xFF9AA3A9), line = Color(0xFF2B3135), hi = Color(0xFFF4D50B), onHi = Color(0xFF15191C),
  warn = Color(0xFFE7A33E), bad = Color(0xFFF2735F), good = Color(0xFF4CC28A),
)

val LocalPalette = staticCompositionLocalOf { Light }
val Mono: FontFamily = FontFamily.Monospace

@Composable
fun ExpenseTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val p = if (dark) Dark else Light
  val scheme = if (dark) {
    darkColorScheme(
      primary = p.ink, onPrimary = p.paper, primaryContainer = p.hi, onPrimaryContainer = p.onHi,
      secondary = p.ink, onSecondary = p.paper, secondaryContainer = p.hi, onSecondaryContainer = p.onHi,
      tertiary = p.hi, onTertiary = p.onHi, background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink,
      surfaceVariant = p.field, onSurfaceVariant = p.muted, outline = p.line, outlineVariant = p.line,
      surfaceTint = Color.Transparent, error = p.bad, onError = p.paper,
      surfaceContainerLowest = p.paper, surfaceContainerLow = p.paper, surfaceContainer = p.paper,
      surfaceContainerHigh = p.paper, surfaceContainerHighest = p.field, surfaceBright = p.paper, surfaceDim = p.bg,
    )
  } else {
    lightColorScheme(
      primary = p.ink, onPrimary = p.paper, primaryContainer = p.hi, onPrimaryContainer = p.onHi,
      secondary = p.ink, onSecondary = p.paper, secondaryContainer = p.hi, onSecondaryContainer = p.onHi,
      tertiary = p.hi, onTertiary = p.onHi, background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink,
      surfaceVariant = p.field, onSurfaceVariant = p.muted, outline = p.line, outlineVariant = p.line,
      surfaceTint = Color.Transparent, error = p.bad, onError = p.paper,
      surfaceContainerLowest = p.paper, surfaceContainerLow = p.paper, surfaceContainer = p.paper,
      surfaceContainerHigh = p.paper, surfaceContainerHighest = p.field, surfaceBright = p.paper, surfaceDim = p.bg,
    )
  }
  val base = Typography()
  val type = base.copy(
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.sp),
  )
  CompositionLocalProvider(LocalPalette provides p) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
  }
}

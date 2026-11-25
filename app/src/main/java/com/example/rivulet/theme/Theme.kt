package com.example.rivulet.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BlackBlueColorScheme = darkColorScheme(
  primary = RivuletBlue,
  onPrimary = Color(0xFF061529),
  primaryContainer = RivuletBlueContainer,
  onPrimaryContainer = Color(0xFFD8E7FF),
  secondary = RivuletSlate,
  onSecondary = Color(0xFF0F172A),
  secondaryContainer = Color(0xFF1E293B),
  onSecondaryContainer = Color(0xFFD8E3F2),
  background = RivuletDarkBackground,
  onBackground = Color(0xFFEDF2FA),
  surface = RivuletDarkSurface,
  onSurface = Color(0xFFEDF2FA),
  surfaceVariant = RivuletDarkSurfaceVariant,
  onSurfaceVariant = Color(0xFF99A6BA),
  surfaceContainerLowest = Color(0xFF090D14),
  surfaceContainerLow = Color(0xFF131926),
  surfaceContainer = Color(0xFF182030),
  surfaceContainerHigh = Color(0xFF1E273A),
  surfaceContainerHighest = Color(0xFF253046),
  outline = Color(0xFF3B4861),
  outlineVariant = Color(0xFF232D40),
  error = Color(0xFFF87171),
  onError = Color(0xFF3B0909),
)

private val KindlePaperColorScheme = lightColorScheme(
  primary = Color(0xFF7A4E24),
  onPrimary = Color(0xFFFFFDF9),
  primaryContainer = Color(0xFFE5D2B4),
  onPrimaryContainer = Color(0xFF361E0A),
  secondary = Color(0xFF66584B),
  onSecondary = Color(0xFFFFFDF9),
  secondaryContainer = Color(0xFFDED2BE),
  onSecondaryContainer = Color(0xFF2B2219),
  background = Color(0xFFF3ECE0),
  onBackground = Color(0xFF1E1812),
  surface = Color(0xFFEDE4D5),
  onSurface = Color(0xFF1E1812),
  surfaceVariant = Color(0xFFE5DAC7),
  onSurfaceVariant = Color(0xFF5B4F43),
  surfaceContainerLowest = Color(0xFFFAF6EE),
  surfaceContainerLow = Color(0xFFF1E9DA),
  surfaceContainer = Color(0xFFEBE2D2),
  surfaceContainerHigh = Color(0xFFE3D8C5),
  surfaceContainerHighest = Color(0xFFDACDB8),
  outline = Color(0xFF9A8B78),
  outlineVariant = Color(0xFFD2C5B0),
  error = Color(0xFF9E2A2B),
  onError = Color(0xFFFFF9F8),
)

private val WarmSepiaColorScheme = lightColorScheme(
  primary = Color(0xFF6E3E14),
  onPrimary = Color(0xFFFDF6EB),
  primaryContainer = Color(0xFFD2B383),
  onPrimaryContainer = Color(0xFF2A1403),
  secondary = Color(0xFF68543E),
  onSecondary = Color(0xFFFDF6EB),
  secondaryContainer = Color(0xFFD4C09E),
  onSecondaryContainer = Color(0xFF2B1F12),
  background = Color(0xFFE9DBC3),
  onBackground = Color(0xFF23170B),
  surface = Color(0xFFE3D3B7),
  onSurface = Color(0xFF23170B),
  surfaceVariant = Color(0xFFDAC6A4),
  onSurfaceVariant = Color(0xFF5B4834),
  surfaceContainerLowest = Color(0xFFF0E4CE),
  surfaceContainerLow = Color(0xFFE6D7BC),
  surfaceContainer = Color(0xFFDFCEAD),
  surfaceContainerHigh = Color(0xFFD6C29C),
  surfaceContainerHighest = Color(0xFFCCB68D),
  outline = Color(0xFF8A745A),
  outlineVariant = Color(0xFFC4AF8A),
  error = Color(0xFF962825),
  onError = Color(0xFFFDF6EB),
)

private val SoftSageColorScheme = lightColorScheme(
  primary = Color(0xFF225B34),
  onPrimary = Color(0xFFF5FAF4),
  primaryContainer = Color(0xFFBAD5BB),
  onPrimaryContainer = Color(0xFF0B2D16),
  secondary = Color(0xFF4A5D4F),
  onSecondary = Color(0xFFF5FAF4),
  secondaryContainer = Color(0xFFC6D8C5),
  onSecondaryContainer = Color(0xFF1A291E),
  background = Color(0xFFE2ECE0),
  onBackground = Color(0xFF131F16),
  surface = Color(0xFFDAE6D7),
  onSurface = Color(0xFF131F16),
  surfaceVariant = Color(0xFFCEDCCB),
  onSurfaceVariant = Color(0xFF485A4D),
  surfaceContainerLowest = Color(0xFFEDF4EB),
  surfaceContainerLow = Color(0xFFE0EAD8),
  surfaceContainer = Color(0xFFD6E3D2),
  surfaceContainerHigh = Color(0xFFCADAC6),
  surfaceContainerHighest = Color(0xFFBFD1BA),
  outline = Color(0xFF778C7B),
  outlineVariant = Color(0xFFB6C9B6),
  error = Color(0xFF9E2A2B),
  onError = Color(0xFFFFF9F8),
)

private val OledMidnightColorScheme = darkColorScheme(
  primary = Color(0xFF7AA2F7),
  onPrimary = Color(0xFF081124),
  primaryContainer = Color(0xFF192642),
  onPrimaryContainer = Color(0xFFD9E4FF),
  secondary = Color(0xFF8C93A1),
  onSecondary = Color(0xFF0C1018),
  secondaryContainer = Color(0xFF161B26),
  onSecondaryContainer = Color(0xFFCFD6E3),
  background = Color(0xFF000000),
  onBackground = Color(0xFFE6E1D6),
  surface = Color(0xFF05070A),
  onSurface = Color(0xFFE6E1D6),
  surfaceVariant = Color(0xFF11151E),
  onSurfaceVariant = Color(0xFF8D94A1),
  surfaceContainerLowest = Color(0xFF000000),
  surfaceContainerLow = Color(0xFF090C11),
  surfaceContainer = Color(0xFF0F131B),
  surfaceContainerHigh = Color(0xFF161B25),
  surfaceContainerHighest = Color(0xFF1E2430),
  outline = Color(0xFF2F3646),
  outlineVariant = Color(0xFF1A1F2B),
  error = Color(0xFFF87171),
  onError = Color(0xFF2B0808),
)

fun appColorSchemeFor(preset: ReaderThemePreset): ColorScheme = when (preset) {
  ReaderThemePreset.BLACK_BLUE -> BlackBlueColorScheme
  ReaderThemePreset.KINDLE_PAPER -> KindlePaperColorScheme
  ReaderThemePreset.WARM_SEPIA -> WarmSepiaColorScheme
  ReaderThemePreset.SOFT_SAGE -> SoftSageColorScheme
  ReaderThemePreset.OLED_MIDNIGHT -> OledMidnightColorScheme
}

@Composable
fun RivuletTheme(
  preset: ReaderThemePreset = ReaderThemePreset.BLACK_BLUE,
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = appColorSchemeFor(preset),
    typography = Typography,
    content = content,
  )
}

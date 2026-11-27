package com.example.audiotorrent.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ReaderThemePreset(val label: String, val tagline: String, val isLight: Boolean) {
  BLACK_BLUE("Black & Blue", "Original dark contrast", isLight = false),
  KINDLE_PAPER("Kindle Paper", "Warm matte paper", isLight = true),
  WARM_SEPIA("Warm Sepia", "Low-strain evening tone", isLight = true),
  SOFT_SAGE("Soft Sage", "Calm matte green", isLight = true),
  OLED_MIDNIGHT("OLED Midnight", "True black, gentle text", isLight = false),
}

enum class ReaderFontFamily(val label: String) {
  SANS("Clean Sans"),
  SERIF("Book Serif"),
}

enum class ReaderLineSpacing(val label: String, val extraSp: Int) {
  COMPACT("Compact", 4),
  COMFORTABLE("Standard", 8),
  RELAXED("Relaxed", 12),
}

data class ReaderPalette(
  val background: Color,
  val headerBackground: Color,
  val bodyText: Color,
  val activeText: Color,
  val untimedText: Color,
  val activeHighlightFill: Color,
  val selectedHighlightFill: Color,
  val selectedBorder: Color,
  val accent: Color,
  val mutedText: Color,
  val chipContainer: Color,
  val isLight: Boolean,
)

@Composable
fun readerPaletteFor(preset: ReaderThemePreset, scheme: ColorScheme): ReaderPalette = when (preset) {
  ReaderThemePreset.BLACK_BLUE -> ReaderPalette(
    background = Color(0xFF131926),
    headerBackground = Color.Transparent,
    bodyText = Color(0xFFAEBACD),
    activeText = Color(0xFFF1F5FB),
    untimedText = Color(0xFF8895AA),
    activeHighlightFill = Color(0xFF1C3C6E),
    selectedHighlightFill = Color(0xFF254D8C),
    selectedBorder = Color(0xFF60A5FA),
    accent = Color(0xFF60A5FA),
    mutedText = Color(0xFF7D8B9F),
    chipContainer = Color(0xFF1B2436),
    isLight = false,
  )

  ReaderThemePreset.KINDLE_PAPER -> ReaderPalette(
    background = Color(0xFFFAF6EE),
    headerBackground = Color(0xFFF1E9DA),
    bodyText = Color(0xFF3A3129),
    activeText = Color(0xFF16110C),
    untimedText = Color(0xFF6E6053),
    activeHighlightFill = Color(0xFFE7D8BC),
    selectedHighlightFill = Color(0xFFD8C197),
    selectedBorder = Color(0xFF7A4E24),
    accent = Color(0xFF7A4E24),
    mutedText = Color(0xFF6B5D50),
    chipContainer = Color(0xFFECE3D3),
    isLight = true,
  )

  ReaderThemePreset.WARM_SEPIA -> ReaderPalette(
    background = Color(0xFFF0E4CE),
    headerBackground = Color(0xFFE6D7BC),
    bodyText = Color(0xFF473725),
    activeText = Color(0xFF201307),
    untimedText = Color(0xFF745F47),
    activeHighlightFill = Color(0xFFDDC396),
    selectedHighlightFill = Color(0xFFCFA96F),
    selectedBorder = Color(0xFF6E3E14),
    accent = Color(0xFF6E3E14),
    mutedText = Color(0xFF6C5740),
    chipContainer = Color(0xFFE3D3B4),
    isLight = true,
  )

  ReaderThemePreset.SOFT_SAGE -> ReaderPalette(
    background = Color(0xFFEDF4EB),
    headerBackground = Color(0xFFE0EAD8),
    bodyText = Color(0xFF2A392F),
    activeText = Color(0xFF0F1B13),
    untimedText = Color(0xFF546859),
    activeHighlightFill = Color(0xFFC3DBC1),
    selectedHighlightFill = Color(0xFFA7CBA6),
    selectedBorder = Color(0xFF225B34),
    accent = Color(0xFF225B34),
    mutedText = Color(0xFF4E6254),
    chipContainer = Color(0xFFD8E6D5),
    isLight = true,
  )

  ReaderThemePreset.OLED_MIDNIGHT -> ReaderPalette(
    background = Color(0xFF000000),
    headerBackground = Color(0xFF080A0F),
    bodyText = Color(0xFF949BA6),
    activeText = Color(0xFFEAE5DB),
    untimedText = Color(0xFF6E747E),
    activeHighlightFill = Color(0xFF192236),
    selectedHighlightFill = Color(0xFF23304D),
    selectedBorder = Color(0xFF7AA2F7),
    accent = Color(0xFF7AA2F7),
    mutedText = Color(0xFF787E8A),
    chipContainer = Color(0xFF121620),
    isLight = false,
  )
}

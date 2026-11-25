package com.example.rivulet.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ReaderThemePreset(val label: String, val tagline: String) {
  BLACK_BLUE("Black & Blue", "Original dark contrast"),
  KINDLE_PAPER("Kindle Paper", "Warm matte paper"),
  WARM_SEPIA("Warm Sepia", "Low-strain evening tone"),
  SOFT_SAGE("Soft Sage", "Calm matte green"),
  OLED_MIDNIGHT("OLED Midnight", "True black, gentle text"),
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
    background = scheme.surfaceVariant.copy(alpha = 0.35f),
    headerBackground = Color.Transparent,
    bodyText = scheme.onSurfaceVariant,
    activeText = scheme.onSurface,
    untimedText = scheme.onSurfaceVariant.copy(alpha = 0.78f),
    activeHighlightFill = scheme.primaryContainer.copy(alpha = 0.45f),
    selectedHighlightFill = scheme.primaryContainer.copy(alpha = 0.85f),
    selectedBorder = scheme.primary,
    accent = scheme.primary,
    mutedText = scheme.onSurfaceVariant.copy(alpha = 0.65f),
    chipContainer = scheme.surfaceVariant.copy(alpha = 0.6f),
    isLight = false,
  )

  ReaderThemePreset.KINDLE_PAPER -> ReaderPalette(
    background = Color(0xFFF6F1E7),
    headerBackground = Color(0xFFEDE5D5),
    bodyText = Color(0xFF3E352D),
    activeText = Color(0xFF17120D),
    untimedText = Color(0xFF6B5E52),
    activeHighlightFill = Color(0xFFE5D7BC),
    selectedHighlightFill = Color(0xFFD8C29B),
    selectedBorder = Color(0xFF8B5E34),
    accent = Color(0xFF7A4E24),
    mutedText = Color(0xFF6D6054),
    chipContainer = Color(0xFFE7DEC9),
    isLight = true,
  )

  ReaderThemePreset.WARM_SEPIA -> ReaderPalette(
    background = Color(0xFFEDE0C8),
    headerBackground = Color(0xFFE3D3B4),
    bodyText = Color(0xFF4A3A28),
    activeText = Color(0xFF23160A),
    untimedText = Color(0xFF735E47),
    activeHighlightFill = Color(0xFFDCC398),
    selectedHighlightFill = Color(0xFFCFAD75),
    selectedBorder = Color(0xFF7C4D1E),
    accent = Color(0xFF734517),
    mutedText = Color(0xFF6E5942),
    chipContainer = Color(0xFFE0CFAF),
    isLight = true,
  )

  ReaderThemePreset.SOFT_SAGE -> ReaderPalette(
    background = Color(0xFFE8EFE6),
    headerBackground = Color(0xFFDAE5D7),
    bodyText = Color(0xFF2D3B32),
    activeText = Color(0xFF111C15),
    untimedText = Color(0xFF56685B),
    activeHighlightFill = Color(0xFFC5DCC4),
    selectedHighlightFill = Color(0xFFA9CCA8),
    selectedBorder = Color(0xFF2E6F40),
    accent = Color(0xFF276238),
    mutedText = Color(0xFF536558),
    chipContainer = Color(0xFFD4E2D1),
    isLight = true,
  )

  ReaderThemePreset.OLED_MIDNIGHT -> ReaderPalette(
    background = Color(0xFF000000),
    headerBackground = Color(0xFF0B0D12),
    bodyText = Color(0xFF9399A2),
    activeText = Color(0xFFE8E3D9),
    untimedText = Color(0xFF6F757E),
    activeHighlightFill = Color(0xFF1C2333),
    selectedHighlightFill = Color(0xFF25324D),
    selectedBorder = Color(0xFF7AA2F7),
    accent = Color(0xFF7AA2F7),
    mutedText = Color(0xFF787E88),
    chipContainer = Color(0xFF141821),
    isLight = false,
  )
}

package com.example.rivulet.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
  primary = RivuletBlue,
  onPrimary = Color(0xFF061529),
  primaryContainer = RivuletBlueContainer,
  onPrimaryContainer = Color(0xFFD6E6FF),
  secondary = RivuletSlate,
  background = RivuletDarkBackground,
  surface = RivuletDarkSurface,
  surfaceVariant = RivuletDarkSurfaceVariant,
  onBackground = Color(0xFFEAF0F8),
  onSurface = Color(0xFFEAF0F8),
  onSurfaceVariant = Color(0xFFA7B2C3),
)

private val LightColorScheme = lightColorScheme(
  primary = Color(0xFF1D4ED8),
  onPrimary = Color.White,
  primaryContainer = Color(0xFFDBEAFE),
  onPrimaryContainer = Color(0xFF0F295C),
  secondary = Color(0xFF475569),
  background = Color(0xFFF8FAFC),
  surface = Color(0xFFFFFFFF),
  surfaceVariant = Color(0xFFE2E8F0),
)

@Composable
fun RivuletTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

package com.example.rivulet.data.lyrics

import android.content.Context
import androidx.core.content.edit
import com.example.rivulet.theme.ReaderFontFamily
import com.example.rivulet.theme.ReaderLineSpacing
import com.example.rivulet.theme.ReaderThemePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SyncMode(
  val label: String,
  val summary: String,
  val fixedWindows: Int?,
  val lookaheadCooldownMs: Long,
) {
  PLAYHEAD_ONLY(
    label = "Playhead",
    summary = "Syncs only the current passage (~40s) for minimal battery and heat.",
    fixedWindows = 2,
    lookaheadCooldownMs = 350L,
  ),
  BALANCED(
    label = "Balanced (+3 min)",
    summary = "Pre-syncs 3 minutes ahead while playing, then rests at 0% CPU.",
    fixedWindows = 9,
    lookaheadCooldownMs = 1_800L,
  ),
  EXTENDED(
    label = "Extended (+15 min)",
    summary = "Pre-syncs 15 minutes ahead while playing with gentle cooldowns.",
    fixedWindows = 45,
    lookaheadCooldownMs = 2_400L,
  ),
  FULL_TRACK(
    label = "Full track",
    summary = "Pre-syncs ahead through the entire active track (no charger needed).",
    fixedWindows = null,
    lookaheadCooldownMs = 3_000L,
  );

  fun horizonWindows(baseMs: Long, durationMs: Long): Int {
    if (durationMs <= baseMs) return 0
    val remaining = ((durationMs - baseMs - 1L) / 20_000L + 1).toInt().coerceAtLeast(1)
    return fixedWindows?.coerceAtMost(remaining) ?: remaining
  }

  companion object {
    fun fromPersisted(raw: String?): SyncMode = when (raw) {
      "CHARGING_FULL" -> FULL_TRACK
      else -> entries.firstOrNull { it.name == raw } ?: BALANCED
    }
  }
}

/** App-wide reading & sync preferences; Compose still applies the system's font scale. */
class ReaderPreferences(context: Context) {
  private val preferences = context.applicationContext.getSharedPreferences("readalong_reader", Context.MODE_PRIVATE)
  private val size = MutableStateFlow(normalize(preferences.getInt("text_size_sp", DEFAULT_SIZE)))
  val textSize = size.asStateFlow()

  private val themeState = MutableStateFlow(enumOrDefault(preferences.getString("reader_theme", null), ReaderThemePreset.BLACK_BLUE))
  val readerTheme = themeState.asStateFlow()

  private val fontState = MutableStateFlow(enumOrDefault(preferences.getString("reader_font", null), ReaderFontFamily.SANS))
  val fontFamily = fontState.asStateFlow()

  private val spacingState = MutableStateFlow(enumOrDefault(preferences.getString("reader_spacing", null), ReaderLineSpacing.COMFORTABLE))
  val lineSpacing = spacingState.asStateFlow()

  private val keepScreenState = MutableStateFlow(preferences.getBoolean("keep_screen_on", false))
  val keepScreenOn = keepScreenState.asStateFlow()

  private val syncModeState = MutableStateFlow(SyncMode.fromPersisted(preferences.getString("sync_mode", null)))
  val syncMode = syncModeState.asStateFlow()

  fun setTextSize(value: Int) {
    val next = normalize(value)
    size.value = next
    preferences.edit { putInt("text_size_sp", next) }
  }

  fun setReaderTheme(preset: ReaderThemePreset) {
    themeState.value = preset
    preferences.edit { putString("reader_theme", preset.name) }
  }

  fun setFontFamily(family: ReaderFontFamily) {
    fontState.value = family
    preferences.edit { putString("reader_font", family.name) }
  }

  fun setLineSpacing(spacing: ReaderLineSpacing) {
    spacingState.value = spacing
    preferences.edit { putString("reader_spacing", spacing.name) }
  }

  fun setKeepScreenOn(enabled: Boolean) {
    keepScreenState.value = enabled
    preferences.edit { putBoolean("keep_screen_on", enabled) }
  }

  fun setSyncMode(mode: SyncMode) {
    syncModeState.value = mode
    preferences.edit { putString("sync_mode", mode.name) }
  }

  private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
    raw?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

  companion object {
    const val DEFAULT_SIZE = 18
    fun normalize(value: Int): Int = (value.coerceIn(14, 26) / 2) * 2
  }
}

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
  val targetAheadMs: Long?,
  val resumeAheadMs: Long?,
  val fixedWindows: Int?,
  val lookaheadCooldownMs: Long,
) {
  PLAYHEAD_ONLY(
    label = "Playhead (+1 min)",
    summary = "Syncs 1 min ahead while playing; waits until 40s remains before refilling. Rests when paused.",
    targetAheadMs = 60_000L,
    resumeAheadMs = 40_000L,
    fixedWindows = 3,
    lookaheadCooldownMs = 350L,
  ),
  BALANCED(
    label = "Balanced (5m / 3m)",
    summary = "Syncs 5 min ahead while reading or paused, then rests at 0% CPU until 3 min remain.",
    targetAheadMs = 300_000L,
    resumeAheadMs = 180_000L,
    fixedWindows = 15,
    lookaheadCooldownMs = 1_800L,
  ),
  EXTENDED(
    label = "Extended (15m / 10m)",
    summary = "Syncs 15 min ahead while reading or paused, then rests at 0% CPU until 10 min remain.",
    targetAheadMs = 900_000L,
    resumeAheadMs = 600_000L,
    fixedWindows = 45,
    lookaheadCooldownMs = 2_400L,
  ),
  FULL_TRACK(
    label = "Full track",
    summary = "Pre-syncs the entire active track even while paused or on Cover.",
    targetAheadMs = null,
    resumeAheadMs = null,
    fixedWindows = null,
    lookaheadCooldownMs = 3_000L,
  );

  fun horizonWindows(baseMs: Long, durationMs: Long): Int {
    if (durationMs <= baseMs) return 0
    val remaining = ((durationMs - baseMs - 1L) / 20_000L + 1).toInt().coerceAtLeast(1)
    return fixedWindows?.coerceAtMost(remaining) ?: remaining
  }

  fun horizonEndMs(positionMs: Long, durationMs: Long): Long {
    if (durationMs <= 0L) return 0L
    val cap = targetAheadMs ?: return durationMs
    val rawEnd = positionMs.coerceAtLeast(0L) + cap
    val aligned = ((rawEnd + 15_999L) / 20_000L) * 20_000L
    return minOf(durationMs, aligned)
  }

  companion object {
    fun fromPersisted(raw: String?): SyncMode = when (raw) {
      "CHARGING_FULL" -> FULL_TRACK
      else -> entries.firstOrNull { it.name == raw } ?: BALANCED
    }
  }
}

enum class SpeechModelTier(
  val label: String,
  val modelTag: String,
  val fileName: String,
  val approximateMb: Int,
  val exactBytes: Long,
  val sha256: String,
  val url: String,
  val summary: String,
) {
  FAST(
    label = "Fast (Real-Time)",
    modelTag = "tiny.en-q8_0",
    fileName = "ggml-tiny.en-q8_0.bin",
    approximateMb = 44,
    exactBytes = 43_550_795L,
    sha256 = "5bc2b3860aa151a4c6e7bb095e1fcce7cf12c7b020ca08dcec0c6d018bb7dd94",
    url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q8_0.bin",
    summary = "Recommended for mobile (~2.5x faster than base.en; stays ahead of 1.5x–2.0x playback).",
  ),
  ACCURATE(
    label = "Accurate (Higher Accuracy)",
    modelTag = "base.en-q8_0",
    fileName = "ggml-base.en-q8_0.bin",
    approximateMb = 82,
    exactBytes = 81_781_811L,
    sha256 = "a4d4a0768075e13cfd7e19df3ae2dbc4a68d37d36a7dad45e8410c9a34f8c87e",
    url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q8_0.bin",
    summary = "Higher punctuation and rare-word accuracy on faster phones (~82 MB).",
  );

  companion object {
    fun fromPersisted(raw: String?): SpeechModelTier = entries.firstOrNull { it.name == raw } ?: FAST
  }
}

/** App-wide reading & sync preferences; Compose still applies the system's font scale. */
class ReaderPreferences(context: Context) {
  private val preferences = context.applicationContext.getSharedPreferences("readalong_reader", Context.MODE_PRIVATE)
  private val size = MutableStateFlow(normalize(preferences.getInt("text_size_sp", DEFAULT_SIZE)))
  val textSize = size.asStateFlow()

  private val themeState = MutableStateFlow(enumOrDefault(preferences.getString("reader_theme", null), ReaderThemePreset.SOFT_SAGE))
  val readerTheme = themeState.asStateFlow()

  private val fontState = MutableStateFlow(enumOrDefault(preferences.getString("reader_font", null), ReaderFontFamily.SANS))
  val fontFamily = fontState.asStateFlow()

  private val spacingState = MutableStateFlow(enumOrDefault(preferences.getString("reader_spacing", null), ReaderLineSpacing.COMFORTABLE))
  val lineSpacing = spacingState.asStateFlow()

  private val keepScreenState = MutableStateFlow(preferences.getBoolean("keep_screen_on", false))
  val keepScreenOn = keepScreenState.asStateFlow()

  private val syncModeState = MutableStateFlow(SyncMode.fromPersisted(preferences.getString("sync_mode", null)))
  val syncMode = syncModeState.asStateFlow()

  private val autoGenerateScriptState = MutableStateFlow(preferences.getBoolean("auto_generate_script", false))
  val autoGenerateScript = autoGenerateScriptState.asStateFlow()

  private val speechModelTierState = MutableStateFlow(SpeechModelTier.fromPersisted(preferences.getString("speech_model_tier", null)))
  val speechModelTier = speechModelTierState.asStateFlow()

  private val annasSecretKeyState = MutableStateFlow(preferences.getString(KEY_ANNAS_SECRET, "").orEmpty())
  val annasSecretKey = annasSecretKeyState.asStateFlow()

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

  fun setAutoGenerateScript(enabled: Boolean) {
    autoGenerateScriptState.value = enabled
    preferences.edit { putBoolean("auto_generate_script", enabled) }
  }

  fun setSpeechModelTier(tier: SpeechModelTier) {
    speechModelTierState.value = tier
    preferences.edit { putString("speech_model_tier", tier.name) }
  }

  fun setAnnasSecretKey(key: String) {
    val clean = key.trim()
    annasSecretKeyState.value = clean
    preferences.edit {
      if (clean.isEmpty()) remove(KEY_ANNAS_SECRET) else putString(KEY_ANNAS_SECRET, clean)
    }
  }

  private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
    raw?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

  companion object {
    const val DEFAULT_SIZE = 18
    private const val KEY_ANNAS_SECRET = "annas_secret_key"
    fun normalize(value: Int): Int = (value.coerceIn(14, 26) / 2) * 2
    fun readAnnasSecretKey(context: Context): String? =
      context.applicationContext.getSharedPreferences("readalong_reader", Context.MODE_PRIVATE)
        .getString(KEY_ANNAS_SECRET, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
  }
}

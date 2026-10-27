package com.example.rivulet.data.lyrics

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide reading size in sp; Compose still applies the system's font scale. */
class ReaderPreferences(context: Context) {
  private val preferences = context.applicationContext.getSharedPreferences("readalong_reader", Context.MODE_PRIVATE)
  private val size = MutableStateFlow(normalize(preferences.getInt("text_size_sp", DEFAULT_SIZE)))
  val textSize = size.asStateFlow()
  fun setTextSize(value: Int) {
    val next = normalize(value)
    size.value = next
    preferences.edit { putInt("text_size_sp", next) }
  }
  companion object {
    const val DEFAULT_SIZE = 18
    fun normalize(value: Int): Int = (value.coerceIn(14, 26) / 2) * 2
  }
}

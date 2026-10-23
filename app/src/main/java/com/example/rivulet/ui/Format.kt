package com.example.rivulet.ui

import java.util.Locale

fun formatBytes(bytes: Long): String {
  if (bytes < 1024L) return "$bytes B"
  val units = arrayOf("KB", "MB", "GB", "TB")
  var value = bytes.toDouble() / 1024.0
  var index = 0
  while (value >= 1024.0 && index < units.lastIndex) {
    value /= 1024.0
    index++
  }
  val pattern = if (value >= 100.0) "%.0f %s" else "%.1f %s"
  return String.format(Locale.US, pattern, value, units[index])
}

fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

fun formatDuration(milliseconds: Long): String {
  val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
  val hours = totalSeconds / 3600L
  val minutes = (totalSeconds % 3600L) / 60L
  val seconds = totalSeconds % 60L
  return if (hours > 0L) {
    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.US, "%d:%02d", minutes, seconds)
  }
}

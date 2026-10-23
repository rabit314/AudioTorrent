package com.example.rivulet.data.lyrics

import java.util.regex.Pattern

object LrcParser {

  private val LRC_TIMESTAMP_REGEX = Pattern.compile("""\[(\d{1,3}):(\d{2})(?:\.(\d{2,3}))?]""")
  private val SRT_TIMESTAMP_REGEX =
    Pattern.compile("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")

  fun parseLrc(content: String): List<TimedLine> {
    val entries = ArrayList<Pair<Long, String>>()

    for (rawLine in content.lines()) {
      val trimmed = rawLine.trim()
      if (trimmed.isBlank() || trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") ||
        trimmed.startsWith("[al:") || trimmed.startsWith("[by:") || trimmed.startsWith("[offset:")
      ) {
        continue
      }

      val matcher = LRC_TIMESTAMP_REGEX.matcher(trimmed)
      val timestamps = ArrayList<Long>()
      var lastMatchEnd = 0

      while (matcher.find()) {
        val minutes = matcher.group(1)?.toLongOrNull() ?: 0L
        val seconds = matcher.group(2)?.toLongOrNull() ?: 0L
        val fractionStr = matcher.group(3) ?: "0"
        val millis = when (fractionStr.length) {
          1 -> fractionStr.toLong() * 100
          2 -> fractionStr.toLong() * 10
          else -> fractionStr.take(3).toLong()
        }
        val totalMs = minutes * 60_000L + seconds * 1000L + millis
        timestamps.add(totalMs)
        lastMatchEnd = matcher.end()
      }

      if (timestamps.isNotEmpty()) {
        val text = trimmed.substring(lastMatchEnd).trim()
        if (text.isNotBlank()) {
          for (ts in timestamps) {
            entries.add(ts to text)
          }
        }
      }
    }

    // Sort by timestamp
    entries.sortBy { it.first }

    val result = ArrayList<TimedLine>()
    for (i in entries.indices) {
      val (startMs, text) = entries[i]
      val nextStart = if (i + 1 < entries.size) entries[i + 1].first else startMs + 4000L
      val endMs = maxOf(startMs + 500L, minOf(nextStart, startMs + 10_000L))
      result.add(
        TimedLine(
          id = i.toLong(),
          startTimeMs = startMs,
          endTimeMs = endMs,
          text = text,
          isParagraphStart = false,
        )
      )
    }

    return result
  }

  fun parseSrt(content: String): List<TimedLine> {
    val result = ArrayList<TimedLine>()
    val lines = content.lines()
    var i = 0
    var idCounter = 0L

    while (i < lines.size) {
      val line = lines[i].trim()
      val matcher = SRT_TIMESTAMP_REGEX.matcher(line)
      if (matcher.find()) {
        val startH = matcher.group(1)?.toLongOrNull() ?: 0L
        val startM = matcher.group(2)?.toLongOrNull() ?: 0L
        val startS = matcher.group(3)?.toLongOrNull() ?: 0L
        val startMs = matcher.group(4)?.toLongOrNull() ?: 0L
        val totalStart = startH * 3600_000L + startM * 60_000L + startS * 1000L + startMs

        val endH = matcher.group(5)?.toLongOrNull() ?: 0L
        val endM = matcher.group(6)?.toLongOrNull() ?: 0L
        val endS = matcher.group(7)?.toLongOrNull() ?: 0L
        val endMs = matcher.group(8)?.toLongOrNull() ?: 0L
        val totalEnd = endH * 3600_000L + endM * 60_000L + endS * 1000L + endMs

        val textBuilder = StringBuilder()
        i++
        while (i < lines.size && lines[i].isNotBlank()) {
          if (textBuilder.isNotEmpty()) textBuilder.append(" ")
          textBuilder.append(lines[i].trim())
          i++
        }

        val text = textBuilder.toString()
        if (text.isNotBlank()) {
          result.add(
            TimedLine(
              id = idCounter++,
              startTimeMs = totalStart,
              endTimeMs = totalEnd,
              text = text,
              isParagraphStart = false,
            )
          )
        }
      }
      i++
    }

    return result
  }

  fun detectAndParse(content: String): List<TimedLine> {
    if (content.contains("-->") && content.contains(":")) {
      val srt = parseSrt(content)
      if (srt.isNotEmpty()) return srt
    }
    return parseLrc(content)
  }
}

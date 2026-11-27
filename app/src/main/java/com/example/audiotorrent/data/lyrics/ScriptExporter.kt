package com.example.audiotorrent.data.lyrics

import java.util.Locale

/** Formats generated or timed read-along scripts as plain-text prose or SubRip (.srt) cues. */
object ScriptExporter {
  private val FORBIDDEN_FILENAME_REGEX = Regex("""[\\/:*?"<>|\r\n]+""")
  private val MULTI_SPACE_REGEX = Regex("""\s+""")

  fun suggestedFileName(bookTitle: String, chapterTitle: String?, extension: String): String {
    val cleanBook = sanitizePart(bookTitle)
    val cleanChapter = chapterTitle?.let(::sanitizePart).orEmpty()
    val base = when {
      cleanBook.isNotBlank() && cleanChapter.isNotBlank() && !cleanBook.equals(cleanChapter, ignoreCase = true) ->
        "$cleanBook - $cleanChapter"
      cleanBook.isNotBlank() -> cleanBook
      cleanChapter.isNotBlank() -> cleanChapter
      else -> "audiobook-script"
    }.take(80).trim().ifBlank { "audiobook-script" }
    val ext = extension.trimStart('.')
    return "$base.$ext"
  }

  fun toPlainText(title: String, lines: List<TimedLine>): String {
    val paragraphs = formatParagraphs(lines)
    if (title.isBlank()) return paragraphs
    return buildString {
      append(title.trim())
      append('\n')
      append("=".repeat(title.trim().length.coerceIn(3, 48)))
      if (paragraphs.isNotEmpty()) {
        append("\n\n")
        append(paragraphs)
      }
    }
  }

  fun toBookPlainText(bookTitle: String, chapters: List<Pair<String, List<TimedLine>>>): String {
    val nonEmpty = chapters.filter { (_, lines) -> lines.any { it.text.isNotBlank() } }
    if (nonEmpty.isEmpty()) return ""
    return buildString {
      val heading = bookTitle.trim().ifBlank { "Audiobook Script" }
      append(heading)
      append('\n')
      append("=".repeat(heading.length.coerceIn(3, 48)))
      nonEmpty.forEachIndexed { idx, (chapterTitle, lines) ->
        append("\n\n## ")
        append(chapterTitle.trim().ifBlank { "Part ${idx + 1}" })
        val body = formatParagraphs(lines)
        if (body.isNotEmpty()) {
          append("\n\n")
          append(body)
        }
      }
    }
  }

  fun toSrt(lines: List<TimedLine>): String {
    val timed = lines.filter { it.isTimed && it.text.isNotBlank() }.sortedBy { it.startTimeMs }
    if (timed.isEmpty()) return ""
    return buildString {
      timed.forEachIndexed { index, line ->
        if (index > 0) append("\n\n")
        append(index + 1)
        append('\n')
        append(formatSrtTime(line.startTimeMs))
        append(" --> ")
        append(formatSrtTime(maxOf(line.endTimeMs, line.startTimeMs + 80L)))
        append('\n')
        append(line.text.trim())
      }
      append('\n')
    }
  }

  private fun formatParagraphs(lines: List<TimedLine>): String {
    val valid = lines.filter { it.text.isNotBlank() }.sortedBy { it.startTimeMs }
    if (valid.isEmpty()) return ""
    val structured = TranscriptAssembler.applyParagraphBreaks(valid)
    val paragraphs = ArrayList<String>()
    val current = StringBuilder()
    for (line in structured) {
      if (line.isParagraphStart && current.isNotEmpty()) {
        paragraphs += current.toString()
        current.clear()
      }
      if (current.isNotEmpty()) current.append(' ')
      current.append(line.text.trim())
    }
    if (current.isNotEmpty()) paragraphs += current.toString()
    return paragraphs.joinToString("\n\n")
  }

  private fun formatSrtTime(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val hours = clamped / 3_600_000L
    val minutes = (clamped % 3_600_000L) / 60_000L
    val seconds = (clamped % 60_000L) / 1_000L
    val millis = clamped % 1_000L
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
  }

  private fun sanitizePart(raw: String): String =
    raw.replace(FORBIDDEN_FILENAME_REGEX, " ").replace(MULTI_SPACE_REGEX, " ").trim()
}

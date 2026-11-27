package com.example.audiotorrent.ui.player

import com.example.audiotorrent.data.lyrics.TextSyncSource
import com.example.audiotorrent.data.lyrics.TimedLine

private val PARAGRAPH_SPLIT_REGEX = Regex("\\r?\\n[\\t \\r]*\\n+")
private val WHITESPACE_REGEX = Regex("\\s+")

/** Display ranges point back to immutable narration/correction IDs, never new timing units. */
data class ReaderSentenceRange(val sentenceId: Long, val start: Int, val end: Int)
data class ReaderParagraph(val key: String, val text: String, val ranges: List<ReaderSentenceRange>) {
  fun sentenceAt(offset: Int): Long? = ranges.firstOrNull { offset >= it.start && offset < it.end }?.sentenceId
}

fun readerParagraphs(lines: List<TimedLine>, source: TextSyncSource?): List<ReaderParagraph> {
  val paragraphs = ArrayList<ReaderParagraph>()
  val text = StringBuilder()
  val ranges = ArrayList<ReaderSentenceRange>()
  val cues = source == TextSyncSource.LRC || source == TextSyncSource.SRT
  fun flush() {
    if (ranges.isNotEmpty()) paragraphs += ReaderParagraph("${ranges.first().sentenceId}:${paragraphs.size}", text.toString(), ranges.toList())
    text.clear(); ranges.clear()
  }
  lines.forEach { line ->
    if (cues || line.isParagraphStart) flush()
    // A source paragraph can fall inside an unchanged timing unit. Preserve both boundaries and ID.
    line.text.split(PARAGRAPH_SPLIT_REGEX).forEachIndexed { index, part ->
      if (index > 0) flush()
      val words = part.replace(WHITESPACE_REGEX, " ").trim()
      if (words.isNotEmpty()) {
        if (text.isNotEmpty()) text.append(' ')
        val start = text.length
        text.append(words)
        ranges += ReaderSentenceRange(line.id, start, text.length)
      }
    }
  }
  flush()
  return paragraphs
}

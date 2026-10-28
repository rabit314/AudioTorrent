package com.example.rivulet

import com.example.rivulet.data.lyrics.*
import com.example.rivulet.ui.player.readerParagraphs
import org.junit.Assert.*
import org.junit.Test
import java.text.BreakIterator
import java.util.Locale

class ReaderParagraphTest {
  private fun line(id: Long, text: String, paragraph: Boolean = false) = TimedLine(id, id * 1000, id * 1000 + 900, text, paragraph)

  @Test fun fragmentsFlowAndHitRangesRetainOriginalTimingUnits() {
    val lines = listOf(line(1, "The door pressure"), line(2, "pad."), line(3, "Doors shut with grinding"), line(4, "hydraulics."))
    val block = readerParagraphs(lines, TextSyncSource.NARRATION).single()
    assertEquals("The door pressure pad. Doors shut with grinding hydraulics.", block.text)
    for (source in lines) {
      val range = block.ranges.single { it.sentenceId == source.id }
      assertEquals(source.text, block.text.substring(range.start, range.end))
      assertEquals(source.id, block.sentenceAt(range.start))
      assertEquals(source.id, block.sentenceAt(range.end - 1))
      assertNull(block.sentenceAt(range.end))
    }
    assertNull(block.sentenceAt(-1))
  }

  @Test fun paragraphChapterAndCueBoundariesArePreservedWithoutCards() {
    val lines = listOf(line(1, "First\nwrapped line.", true), line(2, "Continues."), line(3, "Next paragraph.", true))
    assertEquals(listOf("First wrapped line. Continues.", "Next paragraph."), readerParagraphs(lines, TextSyncSource.NARRATION).map { it.text })
    assertEquals(3, readerParagraphs(lines, TextSyncSource.LRC).size)
    assertEquals(3, readerParagraphs(lines, TextSyncSource.SRT).size)
    val inside = readerParagraphs(listOf(line(1, "First paragraph.\n\nSecond paragraph.")), TextSyncSource.NARRATION)
    assertEquals(2, inside.size)
    assertEquals(listOf(1L, 1L), inside.flatMap { it.ranges }.map { it.sentenceId })
  }

  @Test fun displayMetadataKeepsLegacySentenceIdsTextAndSavedTimings() {
    val raw = "First sentence. Another sentence.\n\nNext paragraph.\nSoft wrap continues."
    val chapter = EpubChapter(1, "one", "one.xhtml", "One", raw, 10)
    val book = EpubBook("Fixture", "Author", listOf(chapter, chapter.copy(index = 2)))
    val actual = ReadAlongText.sentences(book, "book")
    val legacy = book.chapters.flatMap { c ->
      val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH); iterator.setText(c.text)
      buildList {
        var start = iterator.first(); var end = iterator.next()
        while (end != BreakIterator.DONE) {
          val text = c.text.substring(start, end).trim()
          if (text.isNotBlank()) add(ReadAlongText.digest("book:${c.index}:$start".toByteArray()).take(15).toLong(16) to text)
          start = end; end = iterator.next()
        }
      }
    }
    assertEquals(legacy, actual.map { it.id to it.text })
    assertTrue(actual.first().isParagraphStart)
    assertFalse(actual[1].isParagraphStart)
    assertTrue(actual.first { it.text.startsWith("Next") }.isParagraphStart)
    assertTrue(actual.first { it.chapterIndex == 2 }.isParagraphStart)
    val sentence = actual.first()
    val cached = TimedLine(sentence.id, 12_000, 15_000, sentence.text)
    assertEquals(cached.copy(isParagraphStart = true), sentence.withTiming(cached))
    assertEquals(-1L, sentence.withTiming(null).startTimeMs)
  }
}

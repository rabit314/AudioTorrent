package com.example.rivulet

import com.example.rivulet.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test

class ScriptExporterTest {
  @Test fun exportsParagraphProseAndMultiChapterBookCleanly() {
    val lines = listOf(
      TimedLine(1, 500, 2000, "The ship set sail at dawn."),
      TimedLine(2, 2100, 4000, "Waves broke softly against the hull."),
      TimedLine(3, 6500, 9000, "Hours later the island appeared."),
    )
    val txt = ScriptExporter.toPlainText("Treasure Island", lines)
    assertTrue(txt.startsWith("Treasure Island\n==============="))
    assertTrue(txt.contains("The ship set sail at dawn. Waves broke softly against the hull.\n\nHours later the island appeared."))

    val bookTxt = ScriptExporter.toBookPlainText(
      "Treasure Island",
      listOf(
        "Chapter 1" to lines.take(2),
        "Chapter 2" to listOf(lines[2]),
      ),
    )
    assertTrue(bookTxt.contains("## Chapter 1"))
    assertTrue(bookTxt.contains("## Chapter 2"))
  }

  @Test fun exportsValidSrtThatRoundTripsWithLrcParser() {
    val lines = listOf(
      TimedLine(1, 320, 4500, "And so my fellow Americans."),
      TimedLine(2, 5100, 9800, "Ask what you can do for your country."),
    )
    val srt = ScriptExporter.toSrt(lines)
    assertTrue(srt.contains("00:00:00,320 --> 00:00:04,500"))
    val reparsed = LrcParser.parseSrt(srt)
    assertEquals(2, reparsed.size)
    assertEquals("And so my fellow Americans.", reparsed[0].text)
    assertEquals(320L, reparsed[0].startTimeMs)
    assertEquals(9800L, reparsed[1].endTimeMs)
  }

  @Test fun sanitizesSuggestedExportFileNames() {
    assertEquals(
      "Book Name - Chapter 1.txt",
      ScriptExporter.suggestedFileName("Book: Name?", "Chapter 1", "txt"),
    )
    assertEquals(
      "Same Title.srt",
      ScriptExporter.suggestedFileName("Same Title", "Same Title", ".srt"),
    )
  }
}

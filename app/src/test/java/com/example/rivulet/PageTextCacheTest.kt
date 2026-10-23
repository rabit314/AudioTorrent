package com.example.rivulet

import com.example.rivulet.data.lyrics.LrcParser
import com.example.rivulet.data.lyrics.PacedTimingEngine
import com.example.rivulet.data.lyrics.PageTextManager
import com.example.rivulet.data.lyrics.TimedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextCacheTest {

  @Test
  fun testLrcParser() {
    val sampleLrc = """
      [ti:Soul Hunter]
      [ar:Aaron Dembski-Bowden]
      [00:05.50]In the midnight darkness of the warp,
      [00:12.30]The Night Lords fleet drifted in silence.
      [00:25.00]Talos looked into the void and saw doom.
    """.trimIndent()

    val lines = LrcParser.parseLrc(sampleLrc)
    assertEquals(3, lines.size)

    assertEquals(5500L, lines[0].startTimeMs)
    assertEquals("In the midnight darkness of the warp,", lines[0].text)

    assertEquals(12300L, lines[1].startTimeMs)
    assertEquals("The Night Lords fleet drifted in silence.", lines[1].text)

    assertEquals(25000L, lines[2].startTimeMs)
    assertEquals("Talos looked into the void and saw doom.", lines[2].text)
  }

  @Test
  fun testSrtParser() {
    val sampleSrt = """
      1
      00:00:04,500 --> 00:00:08,200
      Welcome to the galaxy of Warhammer 40k.

      2
      00:00:09,000 --> 00:00:15,000
      There is only war.
    """.trimIndent()

    val lines = LrcParser.parseSrt(sampleSrt)
    assertEquals(2, lines.size)
    assertEquals(4500L, lines[0].startTimeMs)
    assertEquals(8200L, lines[0].endTimeMs)
    assertEquals("Welcome to the galaxy of Warhammer 40k.", lines[0].text)

    assertEquals(9000L, lines[1].startTimeMs)
    assertEquals(15000L, lines[1].endTimeMs)
    assertEquals("There is only war.", lines[1].text)
  }

  @Test
  fun testPacedTimingEngine() {
    val sampleText = """
      Chapter One. The city was asleep under the heavy rain.
      
      A lone figure walked along the empty boulevard. The night was young and filled with secrets.
    """.trimIndent()

    val totalDurationMs = 60_000L // 1 minute
    val lines = PacedTimingEngine.paceText(sampleText, totalDurationMs, leadSilenceMs = 1000L)

    assertTrue("Should segment into multiple sentences", lines.size >= 3)
    assertEquals("First sentence should start at lead silence", 1000L, lines[0].startTimeMs)
    assertTrue("Start times should strictly advance", lines[1].startTimeMs > lines[0].startTimeMs)
    assertTrue("Last sentence end time should not exceed total duration", lines.last().endTimeMs <= totalDurationMs)
  }

  @Test
  fun testPageTextSerializationAndDeserialization() {
    val original = listOf(
      TimedLine(id = 0L, startTimeMs = 1000L, endTimeMs = 5000L, text = "Line one of chapter.", isParagraphStart = true),
      TimedLine(id = 1L, startTimeMs = 5000L, endTimeMs = 12000L, text = "Line two continues here.", isParagraphStart = false),
    )

    val serialized = PageTextManager.serializeLines(original)
    val restored = PageTextManager.deserializeLines(serialized)

    assertEquals(original.size, restored.size)
    assertEquals(original[0].id, restored[0].id)
    assertEquals(original[0].startTimeMs, restored[0].startTimeMs)
    assertEquals(original[0].endTimeMs, restored[0].endTimeMs)
    assertEquals(original[0].text, restored[0].text)
    assertEquals(original[0].isParagraphStart, restored[0].isParagraphStart)

    assertEquals(original[1].id, restored[1].id)
    assertEquals(original[1].text, restored[1].text)
  }
}

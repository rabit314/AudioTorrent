package com.example.rivulet

import com.example.rivulet.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test

class TranscriptAssemblerTest {
  @Test fun filtersContextMarginsStripsTagsAndDeduplicatesBoundarySeam() {
    val words = listOf(
      SpeechWord(" margin", 18_500, 19_000),
      SpeechWord(" [BLANK_AUDIO]", 20_050, 20_300),
      SpeechWord(" water.", 20_100, 20_400),
      SpeechWord(" (music)", 20_450, 20_600),
      SpeechWord(" ♪", 20_650, 20_800),
      SpeechWord(" Then", 20_900, 21_200),
      SpeechWord(" she", 21_250, 21_500),
      SpeechWord(" paused.", 21_550, 22_000),
      SpeechWord(" next", 40_500, 41_000),
    )
    val lines = TranscriptAssembler.assembleWindow(
      words = words,
      bookId = "script:hash:0",
      windowStartMs = 20_000L,
      windowEndMs = 40_000L,
      previousTailToken = "water",
      previousTailEndMs = 19_950L,
    )
    assertEquals(1, lines.size)
    assertEquals("Then she paused.", lines.single().text)
    assertEquals(20_900L, lines.single().startTimeMs)
    assertEquals(22_000L, lines.single().endTimeMs)
  }

  @Test fun preservesAbbreviationsAndDialogueQuotesWhileSplittingSentencesAndPauses() {
    val words = listOf(
      SpeechWord(" \"Good", 100, 350),
      SpeechWord(" morning,", 360, 700),
      SpeechWord(" Dr.", 720, 950),
      SpeechWord(" Watson,\"", 960, 1300),
      SpeechWord(" he", 1310, 1450),
      SpeechWord(" said.", 1460, 1800),
      SpeechWord(" another", 3200, 3600), // >= 1200ms pause capitalizes & starts sentence
      SpeechWord(" thought", 3620, 4000),
      SpeechWord(" followed.", 4020, 4400),
    )
    val lines = TranscriptAssembler.assembleWindow(words, "b", 0L, 20_000L)
    assertEquals(2, lines.size)
    assertEquals("\"Good morning, Dr. Watson,\" he said.", lines[0].text)
    assertEquals("Another thought followed.", lines[1].text)
  }

  @Test fun collapsesHallucinationLoopsAndOutroArtifacts() {
    val loopWords = buildList {
      add(SpeechWord(" Hello", 100, 300))
      repeat(6) { idx ->
        add(SpeechWord(" the", 400L + idx * 200, 500L + idx * 200))
      }
      add(SpeechWord(" world.", 1800, 2100))
    }
    val assembled = TranscriptAssembler.assembleWindow(loopWords, "b", 0L, 20_000L)
    assertEquals("Hello the world.", assembled.single().text)

    val outroOnly = listOf(
      SpeechWord(" Thank", 100, 300),
      SpeechWord(" you", 310, 500),
      SpeechWord(" for", 510, 700),
      SpeechWord(" watching.", 710, 1000),
    )
    assertTrue(TranscriptAssembler.assembleWindow(outroOnly, "b", 0L, 20_000L).isEmpty())
  }

  @Test fun breaksOverlongRunOnSpeechAndComputesParagraphsAndSections() {
    val runOn = (1..32).map { i ->
      val suffix = if (i == 12) "," else ""
      SpeechWord(" word$i$suffix", i * 300L, i * 300L + 220L)
    }
    val lines = TranscriptAssembler.assembleWindow(runOn, "b", 0L, 20_000L)
    assertEquals(2, lines.size)
    assertTrue(lines[0].text.endsWith("word12,"))
    assertTrue(lines[1].text.startsWith("word13"))

    val spaced = listOf(
      TimedLine(1, 0, 2000, "First sentence."),
      TimedLine(2, 2200, 4000, "Second sentence."),
      TimedLine(3, 6200, 8000, "New paragraph after pause."),
      TimedLine(4, 305_000, 308_000, "Five minutes in."),
    )
    val paragraphed = TranscriptAssembler.applyParagraphBreaks(spaced)
    assertEquals(listOf(true, false, true, true), paragraphed.map { it.isParagraphStart })

    val sections = TranscriptAssembler.buildTimeSections(paragraphed)
    assertEquals(2, sections.size)
    assertEquals("00:00 – 00:08", sections[0].label)
    assertEquals("05:00 – 05:08", sections[1].label)
    assertEquals(1L, sections[0].firstSentenceId)
    assertEquals(4L, sections[1].firstSentenceId)
  }

  @Test fun detectsSilentPcmAccurately() {
    assertTrue(TranscriptAssembler.isSilent(FloatArray(0)))
    assertTrue(TranscriptAssembler.isSilent(FloatArray(1600)))
    assertFalse(TranscriptAssembler.isSilent(FloatArray(1600) { if (it % 2 == 0) 0.05f else -0.05f }))
  }
}

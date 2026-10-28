package com.example.rivulet

import com.example.rivulet.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test

class NarrationCoverageTest {
  private fun sentence(id: Long, text: String) = BookSentence(id, 1, "Fixture", text)
  private fun words(text: String) = ReadAlongText.tokens(text).mapIndexed { i, word -> SpeechWord(word, i * 200L, i * 200L + 180) }

  @Test fun separateSpokenIslandsSurviveLongNarratorOnlyGaps() {
    val first = "The little boat moved slowly across the quiet water."
    val second = "A bright moon appeared above the distant hills."
    val omitted = "This printed heading is never read aloud in the recording."
    val recognized = words(first + " " + (1..45).joinToString(" ") { "unrelated" } + " " + second)
    val matched = NarrationMatcher(listOf(sentence(1, first), sentence(2, omitted), sentence(3, second))).align(recognized)
    assertEquals(listOf(1L, 3L), matched.map { it.id })
    assertTrue(matched.first().endTimeMs < matched.last().startTimeMs)
  }

  @Test fun shortFragmentsNeedExactRecognitionAndAnAdjacentConfidentAnchor() {
    val preceding = "He pressed against the door pressure"
    val short = "pad."
    val next = "Then the enormous doors slammed shut behind them."
    val matcher = NarrationMatcher(listOf(sentence(1, preceding), sentence(2, short), sentence(3, next)))
    assertEquals(listOf(1L, 2L, 3L), matcher.align(words("$preceding $short $next")).map { it.id })
    assertEquals(listOf(1L, 3L), matcher.align(words("$preceding $next")).map { it.id })
    assertTrue(NarrationMatcher(listOf(sentence(2, short))).align(words("pad")).isEmpty())
  }

  @Test fun printedOnlySentencesAndMissingBoundariesNeverReceiveGuessedTimings() {
    val lines = listOf(sentence(1, "The little boat moved slowly across the quiet water."),
      sentence(2, "A printed paragraph omitted from the recording."), sentence(3, "A bright moon appeared above the distant hills."))
    assertEquals(listOf(1L, 3L), NarrationMatcher(lines).align(words(lines[0].text + " " + lines[2].text)).map { it.id })
    assertTrue(NarrationMatcher(listOf(lines[0])).align(words("moved slowly across the quiet water")).isEmpty())
  }
}

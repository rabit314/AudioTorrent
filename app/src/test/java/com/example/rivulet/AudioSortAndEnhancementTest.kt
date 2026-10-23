package com.example.rivulet

import com.example.rivulet.data.ListeningHistoryItem
import com.example.rivulet.data.NATURAL_NAME_COMPARATOR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSortAndEnhancementTest {

  @Test
  fun testNaturalAlphanumericSortChapters() {
    val input = listOf(
      "Chapter 10.mp3",
      "Chapter 1.mp3",
      "Chapter 20.mp3",
      "Chapter 2.mp3",
      "Chapter 3.mp3",
    )
    val sorted = input.sortedWith(NATURAL_NAME_COMPARATOR)
    val expected = listOf(
      "Chapter 1.mp3",
      "Chapter 2.mp3",
      "Chapter 3.mp3",
      "Chapter 10.mp3",
      "Chapter 20.mp3",
    )
    assertEquals(expected, sorted)
  }

  @Test
  fun testNaturalSortMultiPartTitles() {
    val input = listOf(
      "The Way of Kings - Part 12 - Words of Radiance.m4b",
      "The Way of Kings - Part 1 - Prelude.m4b",
      "The Way of Kings - Part 2 - Chapter 1.m4b",
      "The Way of Kings - Part 9 - Interlude.m4b",
    )
    val sorted = input.sortedWith(NATURAL_NAME_COMPARATOR)
    assertEquals("The Way of Kings - Part 1 - Prelude.m4b", sorted[0])
    assertEquals("The Way of Kings - Part 2 - Chapter 1.m4b", sorted[1])
    assertEquals("The Way of Kings - Part 9 - Interlude.m4b", sorted[2])
    assertEquals("The Way of Kings - Part 12 - Words of Radiance.m4b", sorted[3])
  }

  @Test
  fun testListeningHistoryMetrics() {
    val item = ListeningHistoryItem(
      infoHash = "abcdef0123456789",
      bookTitle = "Dune",
      coverUrl = "https://example.com/cover.jpg",
      fileIndex = 0,
      positionMs = 300_000L,
      durationMs = 1_000_000L,
      lastPlayedAt = System.currentTimeMillis(),
    )

    assertEquals(0.3f, item.progress, 0.001f)
    assertEquals(700_000L, item.remainingMs)
  }

  @Test
  fun testSeekOffsetMath() {
    val curPos = 50_000L
    val duration = 120_000L

    // Seek back -10s
    val rewindPos = (curPos - 10_000L).coerceIn(0L, duration)
    assertEquals(40_000L, rewindPos)

    // Seek forward +30s
    val forwardPos = (curPos + 30_000L).coerceIn(0L, duration)
    assertEquals(80_000L, forwardPos)

    // Underflow protection
    val underflowPos = (5_000L - 10_000L).coerceIn(0L, duration)
    assertEquals(0L, underflowPos)

    // Overflow protection
    val overflowPos = (110_000L + 30_000L).coerceIn(0L, duration)
    assertEquals(120_000L, overflowPos)
  }
}

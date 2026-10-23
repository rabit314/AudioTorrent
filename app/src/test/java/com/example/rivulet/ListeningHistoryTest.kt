package com.example.rivulet

import com.example.rivulet.data.HistoryDateGroup
import com.example.rivulet.data.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningHistoryTest {

  @Test
  fun testHistoryEntryProgressCalculations() {
    val entryHalfway = HistoryEntry(
      id = 1L,
      infoHash = "abcdef",
      bookTitle = "Soul Hunter",
      coverUrl = "http://example.com/cover.jpg",
      fileIndex = 0,
      fileName = "01.m4b",
      positionMs = 30_000L,
      durationMs = 60_000L,
      playedAt = System.currentTimeMillis(),
    )

    assertEquals(0.5f, entryHalfway.progress, 0.001f)
    assertEquals(30_000L, entryHalfway.remainingMs)

    val entryFinished = entryHalfway.copy(positionMs = 60_000L)
    assertEquals(1.0f, entryFinished.progress, 0.001f)
    assertEquals(0L, entryFinished.remainingMs)
  }

  @Test
  fun testHistoryDateGroupHeaders() {
    assertEquals("Today", HistoryDateGroup.TODAY.title)
    assertEquals("Yesterday", HistoryDateGroup.YESTERDAY.title)
    assertEquals("Earlier This Week", HistoryDateGroup.THIS_WEEK.title)
    assertEquals("Past Month & Earlier", HistoryDateGroup.EARLIER.title)
  }
}

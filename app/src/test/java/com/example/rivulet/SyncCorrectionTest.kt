package com.example.rivulet

import com.example.rivulet.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test

class SyncCorrectionTest {
  private val state = ReadAlongState(track = ReadAlongTrack("a", 2), bookId = "book", source = TextSyncSource.NARRATION,
    lines = listOf(TimedLine(7, -1, -1, "A sentence without timings.")))

  @Test fun targetUsesStableIdentityAndIncludesUntimedSentences() {
    val target = state.syncTarget(7)!!
    assertTrue(state.accepts(target))
    assertNull(state.syncTarget(8))
    assertFalse(state.copy(track = ReadAlongTrack("a", 3)).accepts(target))
    assertFalse(state.copy(bookId = "replacement").accepts(target))
    assertFalse(state.copy(source = TextSyncSource.EPUB_PACED).accepts(target))
    assertFalse(state.copy(lines = emptyList()).accepts(target))
  }

  @Test fun recognitionWindowIsBoundedAroundCapturedAudioPosition() {
    val target = state.syncTarget(7)!!
    val beginning = SyncReference(target, 1000, 100_000)
    assertEquals(0L, beginning.windowStartMs); assertEquals(30_000L, beginning.windowDurationMs)
    val middle = SyncReference(target, 60_000, 100_000)
    assertEquals(55_000L, middle.windowStartMs); assertEquals(30_000L, middle.windowDurationMs)
    val end = SyncReference(target, 99_000, 100_000)
    assertEquals(94_000L, end.windowStartMs); assertEquals(6000L, end.windowDurationMs)
  }

  @Test fun correctionStatusSharesTheExistingProgressArea() {
    val matching = state.copy(correction = SyncCorrectionState(SyncCorrectionPhase.MATCHING))
    assertEquals("Matching the selected sentence… Audio can keep playing.", matching.progressLabel)
    assertTrue(matching.correction.isEditing)
    assertFalse(matching.copy(correction = SyncCorrectionState(SyncCorrectionPhase.APPLIED)).correction.isEditing)
    assertEquals("Downloading speech model… 20%", matching.copy(modelProgress = 20).progressLabel)
  }
}

package com.example.rivulet

import com.example.rivulet.data.lyrics.*
import com.example.rivulet.ui.player.PlayerSeekGesture
import com.example.rivulet.ui.player.PlayerTrackKey
import org.junit.Assert.*
import org.junit.Test

class PlayerReadAlongRegressionTest {
  private fun card(image: String) = """<z-bookcard href="/book/1/example" title="Example" extension="epub">$image</z-bookcard>"""

  @Test fun normalAndRenderedCardsResolveLazyAndNormalCoverUrls() {
    assertEquals("https://covers.example/book.jpg", ZLibraryClient.parseResults(card("<img data-src='//covers.example/book.jpg' src='placeholder.jpg'>"), "https://zlib.li/s/query").single().coverUrl)
    assertEquals("https://zlib.li/covers/book.jpg", ZLibraryClient.parseResults(card("<img src='../covers/book.jpg'>"), "https://zlib.li/s/query").single().coverUrl)
    assertEquals("https://zlib.li/book/cover.jpg", ZLibraryClient.parseResults(card("<img data-original='cover.jpg'>"), "https://zlib.li/book/1").single().coverUrl)
  }

  @Test fun missingOrInvalidCoverDoesNotLoseEditionAndNormalUrlIsFallback() {
    assertNull(ZLibraryClient.parseResults(card(""), ZLibraryClient.BASE).single().coverUrl)
    assertNull(ZLibraryClient.parseResults(card("<img src='data:image/png;base64,abc'>"), ZLibraryClient.BASE).single().coverUrl)
    assertEquals("https://zlib.li/real.jpg", ZLibraryClient.parseResults(card("<img data-src='javascript:alert(1)' src='/real.jpg'>"), ZLibraryClient.BASE).single().coverUrl)
  }

  @Test fun trustworthyTimingsAndTranscriptsNeverOfferEstimation() {
    val matching = ReadAlongState(bookId = "b", source = TextSyncSource.NARRATION, phase = ReadAlongPhase.NEEDS_MODEL)
    assertTrue(matching.canEstimate)
    assertFalse(matching.copy(matchedSentences = 1).canEstimate)
    assertFalse(matching.copy(source = TextSyncSource.LRC).canEstimate)
    assertFalse(matching.copy(source = TextSyncSource.SRT).canEstimate)
    assertFalse(matching.copy(phase = ReadAlongPhase.ERROR, recoveryLabel = "Choose another file").canEstimate)
    assertTrue(matching.copy(phase = ReadAlongPhase.ERROR, recoveryLabel = "Retry matching").canEstimate)
    assertFalse(matching.copy(phase = ReadAlongPhase.READY).canEstimate)
    assertTrue(matching.copy(phase = ReadAlongPhase.READY, alignmentAttempted = true).canEstimate)
  }

  @Test fun acquisitionAndSyncHaveOnePrioritizedLabel() {
    val both = ReadAlongState(phase = ReadAlongPhase.PREPARING, searching = true)
    assertEquals("Searching for book text…", both.progressLabel)
    assertEquals("Downloading Example…", both.copy(lookupMessage = "Downloading Example…").progressLabel)
    assertEquals("Downloading speech model… 25%", both.copy(modelProgress = 25).progressLabel)
    assertEquals("Preparing read-along…", both.copy(searching = false).progressLabel)
    assertNull(both.copy(searching = false, phase = ReadAlongPhase.READY).progressLabel)
  }

  @Test fun unfinishedSeekCannotJumpTheNextTrack() {
    val track = PlayerTrackKey("a", 2)
    val gesture = PlayerSeekGesture(track, 0.5f)
    assertEquals(5_000L, gesture.positionFor(track, 10_000))
    assertNull(gesture.positionFor(PlayerTrackKey("a", 3), 10_000))
    assertNull(gesture.positionFor(PlayerTrackKey("b", 2), 10_000))
    assertNull(gesture.positionFor(track, 0))
  }
}

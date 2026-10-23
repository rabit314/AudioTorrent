package com.example.rivulet

import com.example.rivulet.data.BookmarkEntry
import com.example.rivulet.data.WishlistBook
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentSearchesAndEnhancementsTest {

  @Test
  fun testCompletedTrackReplayLogic() {
    // If saved position is within 3 seconds of the end (or past it), it resets to 0L.
    fun computeResumePosition(savedPos: Long, durationMs: Long): Long {
      return if (durationMs > 0 && savedPos >= durationMs - 3000L) {
        0L
      } else {
        savedPos
      }
    }

    val trackDuration = 180_000L // 3 minutes

    // 1. Played completely to the end
    assertEquals(0L, computeResumePosition(180_000L, trackDuration))

    // 2. Played until 1 second before end
    assertEquals(0L, computeResumePosition(179_000L, trackDuration))

    // 3. Played until 2.9 seconds before end
    assertEquals(0L, computeResumePosition(177_100L, trackDuration))

    // 4. Played halfway (90 seconds in)
    assertEquals(90_000L, computeResumePosition(90_000L, trackDuration))

    // 5. Fresh start at 0
    assertEquals(0L, computeResumePosition(0L, trackDuration))

    // 6. Unknown duration (0 or negative)
    assertEquals(45_000L, computeResumePosition(45_000L, 0L))
  }

  @Test
  fun testSleepTimerVolumeFadeCalculation() {
    fun computeFadeVolume(remainingSeconds: Long): Float {
      return (remainingSeconds.coerceAtLeast(0) / 30.0f).coerceIn(0.0f, 1.0f)
    }

    // Full volume at or above 30 seconds
    assertEquals(1.0f, computeFadeVolume(45L), 0.001f)
    assertEquals(1.0f, computeFadeVolume(30L), 0.001f)

    // Linear fade during the final 30 seconds
    assertEquals(0.8f, computeFadeVolume(24L), 0.001f)
    assertEquals(0.5f, computeFadeVolume(15L), 0.001f)
    assertEquals(0.2f, computeFadeVolume(6L), 0.001f)
    assertEquals(0.0f, computeFadeVolume(0L), 0.001f)

    // Negative seconds safety clamp
    assertEquals(0.0f, computeFadeVolume(-5L), 0.001f)
  }

  @Test
  fun testPlaybackSpeedSteppingAndClamping() {
    fun stepSpeed(currentSpeed: Float, delta: Float): Float {
      val stepped = ((currentSpeed + delta) * 20).roundToInt() / 20f
      return stepped.coerceIn(0.5f, 3.0f)
    }

    // Step up
    assertEquals(1.1f, stepSpeed(1.0f, 0.1f), 0.001f)
    assertEquals(1.25f, stepSpeed(1.2f, 0.05f), 0.001f)

    // Step down
    assertEquals(0.9f, stepSpeed(1.0f, -0.1f), 0.001f)

    // Upper clamp at 3.0x
    assertEquals(3.0f, stepSpeed(2.95f, 0.2f), 0.001f)
    assertEquals(3.0f, stepSpeed(3.0f, 0.1f), 0.001f)

    // Lower clamp at 0.5x
    assertEquals(0.5f, stepSpeed(0.55f, -0.1f), 0.001f)
    assertEquals(0.5f, stepSpeed(0.5f, -0.1f), 0.001f)
  }

  @Test
  fun testRecentSearchesListDeduplicationAndOrder() {
    val searches = mutableListOf<String>()

    fun recordSearch(query: String) {
      val trimmed = query.trim()
      if (trimmed.isEmpty()) return
      searches.removeAll { it.equals(trimmed, ignoreCase = true) }
      searches.add(0, trimmed)
      while (searches.size > 20) {
        searches.removeAt(searches.lastIndex)
      }
    }

    recordSearch("Brandon Sanderson")
    recordSearch("Neil Gaiman")
    recordSearch("Stephen King")

    assertEquals(listOf("Stephen King", "Neil Gaiman", "Brandon Sanderson"), searches)

    // Re-searching brings item to the front without duplicates
    recordSearch("brandon sanderson")
    assertEquals(listOf("brandon sanderson", "Stephen King", "Neil Gaiman"), searches)

    // Individual removal
    searches.remove("Neil Gaiman")
    assertEquals(listOf("brandon sanderson", "Stephen King"), searches)

    // Clear all
    searches.clear()
    assertTrue(searches.isEmpty())
  }

  @Test
  fun testBookmarkEntryModel() {
    val bookmark = BookmarkEntry(
      id = 42L,
      infoHash = "0123456789abcdef",
      fileIndex = 1,
      chapterTitle = "Chapter 2: The Storm",
      positionMs = 125_000L,
      note = "Important quote about the heralds",
      createdAt = 1700000000000L,
    )

    assertEquals(42L, bookmark.id)
    assertEquals("0123456789abcdef", bookmark.infoHash)
    assertEquals(1, bookmark.fileIndex)
    assertEquals("Chapter 2: The Storm", bookmark.chapterTitle)
    assertEquals(125_000L, bookmark.positionMs)
    assertEquals("Important quote about the heralds", bookmark.note)
  }

  @Test
  fun testWishlistBookModel() {
    val book = WishlistBook(
      infoHash = "0123456789abcdef0123456789abcdef01234567",
      title = "Words of Radiance",
      author = "Brandon Sanderson",
      coverUrl = "https://example.com/cover.jpg",
      magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Words+of+Radiance",
      addedAt = 1700000000000L,
    )

    assertEquals("0123456789abcdef0123456789abcdef01234567", book.infoHash)
    assertEquals("Words of Radiance", book.title)
    assertEquals("Brandon Sanderson", book.author)
    assertEquals("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Words+of+Radiance", book.magnetUri)
  }
}

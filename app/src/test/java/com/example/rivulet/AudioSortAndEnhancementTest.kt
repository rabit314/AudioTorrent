package com.example.rivulet

import com.example.rivulet.data.AudioFile
import com.example.rivulet.data.ListeningHistoryItem
import com.example.rivulet.data.NATURAL_NAME_COMPARATOR
import com.example.rivulet.data.NATURAL_PATH_COMPARATOR
import com.example.rivulet.data.TorrentUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSortAndEnhancementTest {

  @Test
  fun testTorrentProviderOriginalOrder_PreservedInAudioFiles() {
    val f0 = AudioFile(index = 0, path = "Night Lords/[01] Soul Hunter/Soul Hunter.m4b", name = "Soul Hunter.m4b", size = 100L, downloadedBytes = 0L, isAudio = true)
    val f1 = AudioFile(index = 1, path = "Night Lords/[02] Blood Reaver/Blood Reaver.m4b", name = "Blood Reaver.m4b", size = 100L, downloadedBytes = 0L, isAudio = true)
    val f2 = AudioFile(index = 2, path = "Night Lords/[03] Void Stalker/Void Stalker.m4b", name = "Void Stalker.m4b", size = 100L, downloadedBytes = 0L, isAudio = true)

    val torrent = TorrentUi(
      infoHash = "40e9a79d7fe668daf733ca0ea01ce91d945f5ed3",
      name = "Night Lords",
      state = "Downloading",
      progress = 0f,
      downloadRate = 0L,
      uploadRate = 0L,
      peers = 2,
      seeds = 1,
      totalBytes = 300L,
      doneBytes = 0L,
      hasMetadata = true,
      files = listOf(f0, f1, f2),
    )

    // Verify torrent provider's original sequence (index 0, 1, 2) is preserved
    assertEquals(0, torrent.audioFiles[0].index)
    assertEquals("Soul Hunter.m4b", torrent.audioFiles[0].name)
    assertEquals(1, torrent.audioFiles[1].index)
    assertEquals("Blood Reaver.m4b", torrent.audioFiles[1].name)
    assertEquals(2, torrent.audioFiles[2].index)
    assertEquals("Void Stalker.m4b", torrent.audioFiles[2].name)
  }

  @Test
  fun testNaturalPathComparatorWithSubfolders() {
    val f0 = AudioFile(index = 0, path = "Night Lords/[01] Soul Hunter/Soul Hunter.m4b", name = "Soul Hunter.m4b", size = 100L, downloadedBytes = 0L, isAudio = true)
    val f1 = AudioFile(index = 1, path = "Night Lords/[02] Blood Reaver/Blood Reaver.m4b", name = "Blood Reaver.m4b", size = 100L, downloadedBytes = 0L, isAudio = true)
    val f2 = AudioFile(index = 2, path = "Night Lords/[03] Void Stalker/Void Stalker.m4b", name = "Void Stalker.m4b", size = 100L, downloadedBytes = 0L, isAudio = true)

    val scrambled = listOf(f2, f0, f1)
    val sorted = scrambled.sortedWith(NATURAL_PATH_COMPARATOR)

    assertEquals("[01] Soul Hunter", sorted[0].directory)
    assertEquals("[02] Blood Reaver", sorted[1].directory)
    assertEquals("[03] Void Stalker", sorted[2].directory)
  }

  @Test
  fun testAudioFileDirectoryExtraction() {
    val nested = AudioFile(index = 0, path = "Night Lords/[01] Soul Hunter/Soul Hunter.m4b", name = "Soul Hunter.m4b", size = 100L, downloadedBytes = 0L, isAudio = true)
    assertEquals("[01] Soul Hunter", nested.directory)
    assertEquals("[01] Soul Hunter / Soul Hunter.m4b", nested.displayTitle)

    val flat = AudioFile(index = 1, path = "Dune.mp3", name = "Dune.mp3", size = 100L, downloadedBytes = 0L, isAudio = true)
    assertEquals("", flat.directory)
    assertEquals("Dune.mp3", flat.displayTitle)
  }

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

package com.example.rivulet

import com.example.rivulet.data.AudioFile
import com.example.rivulet.data.TorrentUi
import com.example.rivulet.data.lyrics.PacedTimingEngine
import com.example.rivulet.playback.QueueTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilizationAndArchitectureTest {

  @Test
  fun testPacedTimingEngineShortTrackNoCrash() {
    val text = "Short track. Only two sentences here."
    // 2-second track with 500ms lead silence
    val lines = PacedTimingEngine.paceText(
      rawText = text,
      totalDurationMs = 2_000L,
      leadSilenceMs = 500L,
    )
    assertTrue("Should generate lines for short track", lines.isNotEmpty())
    for (line in lines) {
      assertTrue("startMs >= 0", line.startTimeMs >= 0L)
      assertTrue("startMs <= endMs", line.startTimeMs <= line.endTimeMs)
      assertTrue("endMs <= duration", line.endTimeMs <= 2_000L)
    }
  }

  @Test
  fun testPacedTimingEngineDenseTextNoCrash() {
    val sentences = (1..60).joinToString(" ") { "This is sentence number $it in a very dense chapter." }
    // 3-second track with 60 sentences!
    val lines = PacedTimingEngine.paceText(
      rawText = sentences,
      totalDurationMs = 3_000L,
      leadSilenceMs = 200L,
    )
    assertEquals(60, lines.size)
    for (line in lines) {
      assertTrue("startMs >= 0", line.startTimeMs >= 0L)
      assertTrue("startMs <= endMs", line.startTimeMs <= line.endTimeMs)
      assertTrue("endMs <= duration", line.endTimeMs <= 3_000L)
    }
  }

  @Test
  fun testPacedTimingEngineLargeOffsetsNoCrash() {
    val text = "Chapter 1. The beginning of the universe was quiet."
    // Large positive offset (+45 seconds on a 10-second track)
    val linesPositive = PacedTimingEngine.paceText(
      rawText = text,
      totalDurationMs = 10_000L,
      offsetMs = 45_000L,
    )
    assertTrue("Should handle large positive offset without crash", linesPositive.isNotEmpty())
    for (line in linesPositive) {
      assertTrue(line.startTimeMs <= line.endTimeMs)
      assertTrue(line.endTimeMs <= 10_000L)
    }

    // Large negative offset (-45 seconds)
    val linesNegative = PacedTimingEngine.paceText(
      rawText = text,
      totalDurationMs = 10_000L,
      offsetMs = -45_000L,
    )
    assertTrue("Should handle large negative offset without crash", linesNegative.isNotEmpty())
    for (line in linesNegative) {
      assertTrue(line.startTimeMs >= 0L)
      assertTrue(line.startTimeMs <= line.endTimeMs)
    }
  }

  @Test
  fun testPacedTimingEngineZeroDurationGraceful() {
    val text = "Testing zero duration audio stream."
    val lines = PacedTimingEngine.paceText(
      rawText = text,
      totalDurationMs = 0L,
    )
    assertTrue("0ms track safely returns empty list without throwing", lines.isEmpty())
  }

  @Test
  fun testTorrentIsFullyDownloadedVerification() {
    val file1 = AudioFile(0, "ch1.mp3", "Chapter 1", 10_000L, 10_000L, isAudio = true)
    val file2 = AudioFile(1, "ch2.mp3", "Chapter 2", 10_000L, 5_000L, isAudio = true)
    val file3 = AudioFile(2, "cover.jpg", "Cover", 1_000L, 1_000L, isAudio = false)

    // Partially downloaded: ch1 is done, but ch2 is only half done
    val partialTorrent = TorrentUi(
      infoHash = "abc1",
      name = "Book 1",
      state = "Downloading",
      progress = 1.0f, // Libtorrent progress might report 1.0f if only wanted file was finished
      downloadRate = 0L,
      uploadRate = 0L,
      peers = 5,
      seeds = 2,
      totalBytes = 21_000L,
      doneBytes = 16_000L,
      hasMetadata = true,
      files = listOf(file1, file2, file3),
    )
    assertFalse("Partially downloaded book must NOT be isFullyDownloaded", partialTorrent.isFullyDownloaded)

    // Fully downloaded: both ch1 and ch2 are done
    val completedFile2 = file2.copy(downloadedBytes = 10_000L)
    val completeTorrent = partialTorrent.copy(
      files = listOf(file1, completedFile2, file3),
      doneBytes = 21_000L,
    )
    assertTrue("Book with all audio files downloaded must be isFullyDownloaded", completeTorrent.isFullyDownloaded)
  }

  @Test
  fun testQueueTrackBoundsAndMapping() {
    val tracks = listOf(
      QueueTrack("hash1", 0, "Ch 1", "Book"),
      QueueTrack("hash1", 1, "Ch 2", "Book"),
      QueueTrack("hash1", 2, "Ch 3", "Book"),
    )
    val index0 = tracks.indexOfFirst { it.fileIndex == 0 }
    val index2 = tracks.indexOfFirst { it.fileIndex == 2 }
    val indexMissing = tracks.indexOfFirst { it.fileIndex == 99 }

    assertEquals(0, index0)
    assertEquals(2, index2)
    assertEquals(-1, indexMissing)
  }
}

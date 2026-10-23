package com.example.rivulet

import com.example.rivulet.data.AudioFile
import com.example.rivulet.data.isAudioFile
import com.example.rivulet.playback.QueueTrack
import com.example.rivulet.ui.formatBytes
import com.example.rivulet.ui.formatDuration
import com.example.rivulet.ui.formatRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioModelAndFormatTest {

  @Test
  fun isAudioFile_recognizesSupportedAudioExtensions() {
    assertTrue(isAudioFile("chapter1.mp3"))
    assertTrue(isAudioFile("audiobook.m4b"))
    assertTrue(isAudioFile("track01.FLAC"))
    assertTrue(isAudioFile("podcast.opus"))
    assertTrue(isAudioFile("song.ogg"))
    assertTrue(isAudioFile("recording.wav"))
    assertTrue(isAudioFile("audio.aac"))
    assertTrue(isAudioFile("file.m4a"))
  }

  @Test
  fun isAudioFile_rejectsNonAudioExtensions() {
    assertFalse(isAudioFile("cover.jpg"))
    assertFalse(isAudioFile("book.pdf"))
    assertFalse(isAudioFile("info.nfo"))
    assertFalse(isAudioFile("meta.json"))
    assertFalse(isAudioFile("archive.zip"))
    assertFalse(isAudioFile("video.mp4"))
    assertFalse(isAudioFile("noextension"))
  }

  @Test
  fun formatBytes_formatsProperly() {
    assertEquals("500 B", formatBytes(500L))
    assertEquals("1.0 KB", formatBytes(1024L))
    assertEquals("10.5 MB", formatBytes((10.5 * 1024 * 1024).toLong()))
    assertEquals("1.5 GB", formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
  }

  @Test
  fun formatRate_includesPerSecond() {
    assertEquals("1.0 MB/s", formatRate(1024 * 1024L))
  }

  @Test
  fun formatDuration_handlesZeroMinutesAndHours() {
    assertEquals("0:00", formatDuration(0L))
    assertEquals("0:45", formatDuration(45_000L))
    assertEquals("5:30", formatDuration(330_000L))
    // Multi-hour audiobook duration (e.g. 2 hours, 15 minutes, 4 seconds)
    assertEquals("2:15:04", formatDuration((2 * 3600 + 15 * 60 + 4) * 1000L))
  }

  @Test
  fun audioFile_calculatesProgressAndDownloaded() {
    val file = AudioFile(
      index = 0,
      path = "book/track1.mp3",
      name = "track1.mp3",
      size = 10_000_000L,
      downloadedBytes = 5_000_000L,
      isAudio = true,
      isWanted = true,
      savedPositionMs = 45_000L,
    )

    assertEquals(0.5f, file.progress, 0.001f)
    assertFalse(file.isDownloaded)

    val completedFile = file.copy(downloadedBytes = 10_000_000L)
    assertEquals(1.0f, completedFile.progress, 0.001f)
    assertTrue(completedFile.isDownloaded)
  }

  @Test
  fun queueTrack_navigationLogic() {
    val tracks = listOf(
      QueueTrack("hash1", 0, "Track 1", "Album"),
      QueueTrack("hash1", 1, "Track 2", "Album"),
      QueueTrack("hash1", 2, "Track 3", "Album"),
    )

    var currentIndex = 0
    fun hasNext() = currentIndex < tracks.size - 1
    fun hasPrevious() = currentIndex > 0

    assertTrue(hasNext())
    assertFalse(hasPrevious())

    currentIndex++
    assertTrue(hasNext())
    assertTrue(hasPrevious())

    currentIndex++
    assertFalse(hasNext())
    assertTrue(hasPrevious())
  }
}

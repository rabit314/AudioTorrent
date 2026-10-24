package com.example.rivulet.readalong

import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.rivulet.MainActivity
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import com.example.rivulet.ui.player.LyricsView
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ReadAlongUiTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test fun autoFollowPausesForDragAndResumesOnExplicitAction() {
    val activity = compose.activity
    val name = "readalong-ui-${UUID.randomUUID()}.db"
    val database = TorrentDatabase(activity, name)
    val repository = ReadAlongRepository(activity.applicationContext, ReadAlongStore(database), AudioWindowDecoder { error("Not used for LRC") })
    try {
      val lrc = (0..79).joinToString("\n") { i -> "[%02d:%02d.00]Public fixture sentence %d.".format(i/60, i%60, i) }
      compose.runOnUiThread {
        repository.activate(ReadAlongTrack("public-ui-fixture", 0))
        repository.importBytes(lrc.toByteArray(), "Read Along fixture.lrc", "text/plain", setOf(0))
      }
      compose.waitUntil(10_000) { repository.state.value.bookId != null }
      compose.runOnUiThread {
        repository.playback(20_100, 80_000)
        activity.setContent { androidx.compose.material3.MaterialTheme {
          LyricsView(repository, emptyList(), "Read Along fixture", onSeekToLine = { line -> repository.playback(line.startTimeMs, 80_000) })
        } }
      }
      compose.onNodeWithText("Public fixture sentence 20.").assertIsDisplayed()
      compose.onNodeWithText("Follow narration").assertDoesNotExist()
      compose.onNodeWithTag("readalong-lines").performTouchInput { swipeUp() }
      compose.onNodeWithText("Follow narration").assertIsDisplayed().performClick()
      compose.onNodeWithText("Public fixture sentence 20.").assertIsDisplayed()
      val file = File(activity.cacheDir, "readalong-ui.png")
      file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    } finally {
      compose.runOnUiThread { repository.close() }
      database.close(); activity.deleteDatabase(name)
    }
  }
}

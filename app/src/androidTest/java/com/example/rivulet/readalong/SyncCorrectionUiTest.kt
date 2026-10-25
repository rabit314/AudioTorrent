package com.example.rivulet.readalong

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import com.example.rivulet.playback.PlaybackUi
import com.example.rivulet.ui.player.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class SyncCorrectionUiTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val first = "First fixture sentence is being highlighted incorrectly."
  private val second = "Second fixture sentence is the one being narrated."

  private fun fixture(action: (ReadAlongRepository) -> Unit) {
    val name = "correction-ui-${UUID.randomUUID()}.db"
    val database = TorrentDatabase(compose.activity, name)
    val r = ReadAlongRepository(compose.activity, ReadAlongStore(database), AudioWindowDecoder { error("LRC needs no decoder") })
    try {
      compose.runOnUiThread {
        r.activate(ReadAlongTrack("sync-ui", 0))
        val extra = (8..40).joinToString("\n") { "[00:%02d.00]Additional fixture sentence %d.".format(it, it) }
        r.importBytes("[00:01.00]$first\n[00:04.00]$second\n[00:07.00]Third fixture sentence follows.\n$extra".toByteArray(), "sync.lrc", "text/plain", setOf(0, 1))
      }
      compose.waitUntil(10_000) { r.state.value.lines.isNotEmpty() }
      compose.runOnUiThread { r.playback(2500, 11_000) }
      action(r)
    } finally { compose.runOnUiThread { r.close() }; database.close(); compose.activity.deleteDatabase(name) }
  }

  @Test fun selectingThenSyncingDoesNotSeekAndSurvivesRotationAndViewSwitches() = fixture { r ->
    val seeks = mutableListOf<Long>()
    var playClicks = 0
    val restoration = StateRestorationTester(compose)
    restoration.setContent { MaterialTheme {
      PlayerLayout(PlaybackUi(infoHash = "sync-ui", fileIndex = 0, hasMedia = true, durationMs = 11_000, positionMs = 2500, speed = 1.5f),
        "Correction fixture", "Paused at 1.5×", null, {}, {}, {}, {}, {}, { playClicks++ }, {}, {}, { seeks += it }, {}, {}) { modifier ->
        LyricsView(r, emptyList(), "Fixture", { seeks += it.startTimeMs }, modifier,
          onSyncNow = { r.syncNow(SyncReference(it, 8750, 11_000)) })
      }
    } }
    compose.onNodeWithTag("player-text").performClick()
    compose.onNodeWithText("Correct sync").performClick()
    compose.onNodeWithText(second, substring = false).performClick()
    assertEquals(1L, r.state.value.correction.target!!.sentenceId)
    assertTrue(seeks.isEmpty()); assertEquals(0L, r.state.value.offsetMs)
    restoration.emulateSavedInstanceStateRestore()
    assertEquals(1L, r.state.value.correction.target!!.sentenceId)
    compose.onNodeWithTag("player-cover").performClick()
    compose.onNodeWithTag("player-text").performClick()
    compose.onNodeWithText("Sync now").assertIsEnabled()
    screenshot("sync-selected.png")
    compose.onNodeWithText("Sync now").performClick()
    compose.waitUntil(10_000) { r.state.value.canUndoCorrection }
    assertEquals(4750L, r.state.value.offsetMs)
    assertEquals(1, r.state.value.activeLineIndex)
    assertTrue(seeks.isEmpty()); assertEquals(0, playClicks)
    compose.onNodeWithTag("readalong-lines").performTouchInput { swipeUp() }
    compose.onNodeWithText("Follow narration").assertIsDisplayed()
    compose.onNodeWithTag("player-cover").performClick()
    compose.onNodeWithTag("player-text").performClick()
    compose.onNodeWithText("Follow narration").assertIsDisplayed().performClick()
    compose.onNodeWithText("Undo correction").performClick()
    assertEquals(0L, r.state.value.offsetMs)
    compose.onNodeWithText(second, substring = false).performClick()
    assertEquals(listOf(4000L), seeks)
  }

  @Test fun longPressOnlySelectsAndCancelKeepsPreviousCorrection() = fixture { r ->
    var seeks = 0
    compose.setContent { MaterialTheme { LyricsView(r, emptyList(), "Fixture", { seeks++ }) } }
    compose.onNodeWithText(second, substring = false).performTouchInput { longClick() }
    compose.onNodeWithText("Sync now").assertIsDisplayed()
    assertEquals(1L, r.state.value.correction.target!!.sentenceId)
    assertEquals(0L, r.state.value.offsetMs); assertEquals(0, seeks)
    compose.runOnUiThread { r.playback(7500, 11_000) }
    assertEquals(1L, r.state.value.correction.target!!.sentenceId)
    compose.onNodeWithText("Cancel").performClick()
    assertFalse(r.state.value.correction.isEditing); assertEquals(0L, r.state.value.offsetMs)
  }

  @Test fun correctionActionsAndTextFitNarrowLargeFontAndLandscapePanels() = fixture { r ->
    var width by mutableStateOf(320); var height by mutableStateOf(640); var font by mutableStateOf(1f)
    compose.setContent {
      val base = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(base.density * if (width > 400) 0.5f else 1f, font)) {
        MaterialTheme {
          PlayerLayout(PlaybackUi(hasMedia = true, durationMs = 11_000), "Correction fixture", "Chapter one", null,
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.requiredSize(width.dp, height.dp).consumeWindowInsets(WindowInsets.safeDrawing)) { modifier ->
            LyricsView(r, emptyList(), "Fixture", {}, modifier)
          }
        }
      }
    }
    compose.onNodeWithTag("player-text").performClick()
    compose.onNodeWithText("Correct sync").performClick()
    for ((w, h, scale) in listOf(Triple(320, 640, 1f), Triple(360, 740, 1.5f), Triple(720, 360, 1.3f))) {
      compose.runOnUiThread { width = w; height = h; font = scale }
      compose.onNodeWithText("Sync now").assertIsDisplayed()
      compose.onNodeWithText("Cancel").assertIsDisplayed()
      compose.onNodeWithText(first).assertIsDisplayed()
      compose.onNodeWithContentDescription("Play").assertIsDisplayed()
      screenshot("sync-layout-$w.png")
    }
  }

  private fun screenshot(name: String) {
    File(compose.activity.cacheDir, name).outputStream().use {
      compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}

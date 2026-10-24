package com.example.rivulet.readalong

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.Options
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import com.example.rivulet.playback.PlaybackUi
import com.example.rivulet.ui.player.*
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class IntegratedPlayerUiTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val playing = PlaybackUi(infoHash = "fixture", fileIndex = 0, hasMedia = true, hasNext = true, hasPrevious = true, durationMs = 80_000, positionMs = 20_100)

  @Composable private fun player(state: PlaybackUi = playing, modifier: Modifier = Modifier, onSeek: (Long) -> Unit = {}, text: @Composable (Modifier) -> Unit = {}) {
    PlayerLayout(state, "An audiobook with a deliberately long title that should wrap elegantly", "Chapter twenty · Fixture author", "Chapter 20 of 80",
      {}, {}, {}, {}, {}, {}, {}, {}, onSeek, {}, {}, modifier, text)
  }

  @Test fun transportFitsNarrowLargeFontAndLandscapeLayouts() {
    var width by mutableStateOf(320)
    var height by mutableStateOf(640)
    var fontScale by mutableStateOf(1f)
    compose.setContent {
      val base = LocalDensity.current
      val scale = if (width > 400) 0.5f else 1f
      CompositionLocalProvider(LocalDensity provides Density(base.density * scale, fontScale)) {
        // These are virtual content viewports; do not scale the emulator's physical system bars into them.
        MaterialTheme { player(modifier = Modifier.requiredSize(width.dp, height.dp).consumeWindowInsets(WindowInsets.safeDrawing).testTag("test-player")) }
      }
    }
    for ((w, h, font) in listOf(Triple(320, 640, 1f), Triple(360, 740, 1.5f), Triple(720, 360, 1.3f))) {
      compose.runOnUiThread { width = w; height = h; fontScale = font }
      compose.waitForIdle()
      saveScreenshot("player-layout-$w.png")
      val bounds = compose.onNodeWithTag("test-player").fetchSemanticsNode().boundsInRoot
      for (label in listOf("Previous chapter", "Back 10 seconds", "Play", "Forward 30 seconds", "Next chapter")) {
        val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("$label overflows $w dp", node.left >= bounds.left && node.right <= bounds.right)
      }
      compose.onNodeWithTag("player-seek").assertIsDisplayed()
      compose.onNodeWithText("Speed 1.0×").assertIsDisplayed()
      compose.onNodeWithText("Sleep").assertDoesNotExist()
      compose.onNodeWithText("Trim").assertDoesNotExist()
      if (w == 720) {
        val media = compose.onNodeWithTag("player-media").fetchSemanticsNode().boundsInRoot
        val seeker = compose.onNodeWithTag("player-seek").fetchSemanticsNode().boundsInRoot
        assertTrue("Landscape controls must be beside the panel", seeker.left >= media.right)
      }
    }
  }

  @Test fun coverTextPreservesManualPositionRestoresModeAndKeepsControlsStationary() {
    val activity = compose.activity
    val name = "integrated-ui-${UUID.randomUUID()}.db"
    val database = TorrentDatabase(activity, name)
    val repository = ReadAlongRepository(activity, ReadAlongStore(database), AudioWindowDecoder { error("LRC needs no decoder") })
    val restoration = StateRestorationTester(compose)
    var playback by mutableStateOf(playing)
    try {
      val lrc = (0..79).joinToString("\n") { "[00:%02d.00]Public fixture sentence %d.".format(it, it) }
      compose.runOnUiThread { repository.activate(ReadAlongTrack("fixture", 0)); repository.importBytes(lrc.toByteArray(), "a.lrc", "text/plain", setOf(0, 1)) }
      compose.waitUntil(10_000) { repository.state.value.bookId != null }
      compose.runOnUiThread { repository.playback(20_100, 80_000) }
      restoration.setContent { MaterialTheme { player(playback) { LyricsView(repository, emptyList(), "Fixture", { repository.playback(it.startTimeMs, 80_000) }, it) } } }
      compose.onNodeWithTag("player-text").performClick()
      compose.onNodeWithText("Public fixture sentence 20.").assertIsDisplayed()
      val controls = compose.onNodeWithTag("player-seek").fetchSemanticsNode().boundsInRoot
      compose.onNodeWithTag("readalong-lines").performTouchInput { swipeUp() }
      compose.onNodeWithText("Follow narration").assertIsDisplayed()
      val before = compose.onNodeWithTag("readalong-lines").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
      compose.onNodeWithTag("player-cover").performClick()
      compose.onNodeWithTag("readalong-lines").assertDoesNotExist()
      compose.onNodeWithTag("player-text").performClick()
      compose.onNodeWithText("Follow narration").assertIsDisplayed()
      val after = compose.onNodeWithTag("readalong-lines").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
      assertEquals(before, after, 1f)
      assertEquals(controls, compose.onNodeWithTag("player-seek").fetchSemanticsNode().boundsInRoot)
      restoration.emulateSavedInstanceStateRestore()
      compose.onNodeWithTag("readalong-lines").assertExists()
      compose.onNodeWithText("Follow narration").performClick()
      compose.onNodeWithText("Public fixture sentence 20.").assertIsDisplayed()
      compose.onNodeWithText("Public fixture sentence 20.").performClick()
      assertEquals(20_000L, repository.state.value.lines[repository.state.value.activeLineIndex].startTimeMs)
      compose.runOnUiThread { playback = playing.copy(fileIndex = 1); repository.activate(ReadAlongTrack("fixture", 1)) }
      compose.waitUntil(10_000) { repository.state.value.track?.fileIndex == 1 && repository.state.value.phase == ReadAlongPhase.READY }
      compose.onNodeWithTag("readalong-lines").assertExists()
      saveScreenshot("integrated-player.png")
    } finally { compose.runOnUiThread { repository.close() }; database.close(); activity.deleteDatabase(name) }
  }

  @Test fun singleProgressAreaAndSpecificRecoveryAction() {
    var imports = 0; var retries = 0
    compose.setContent { MaterialTheme {
      ReadAlongStatus(ReadAlongState(phase = ReadAlongPhase.PREPARING, searching = true, modelProgress = 20, recoveryLabel = "Choose another file", recovery = ReadAlongRecovery.PICK_FILE), { retries++ }, { imports++ }, {})
    } }
    compose.onAllNodesWithTag("readalong-progress").assertCountEquals(1)
    compose.onNodeWithText("Downloading speech model… 20%").assertIsDisplayed()
    compose.onNodeWithText("Choose another file").performClick()
    assertEquals(1, imports); assertEquals(0, retries)
  }

  @Test fun trackChangeCancelsAnUnfinishedSeekGesture() {
    var state by mutableStateOf(playing)
    val seeks = mutableListOf<Long>()
    compose.setContent { MaterialTheme { player(state, onSeek = { seeks += it }) } }
    compose.onNodeWithTag("player-seek").performTouchInput { down(centerLeft); moveTo(center) }
    compose.runOnUiThread { state = playing.copy(fileIndex = 1) }
    compose.onNodeWithTag("player-seek").performTouchInput { moveTo(centerRight); up() }
    assertTrue("A drag on the previous track must be cancelled", seeks.isEmpty())
    compose.onNodeWithTag("player-seek").performTouchInput { click(center) }
    assertEquals(1, seeks.size)
  }

  @Test fun coverRowsRemainSelectableWhileLoadingOrBrokenAndReuseImageCache() {
    val fetches = AtomicInteger(); val selections = AtomicInteger()
    val loader = ImageLoader.Builder(compose.activity).components {
      add(object : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
          if (data.scheme != "fixture") return null
          return Fetcher {
            fetches.incrementAndGet()
            if (data.toString().endsWith("slow")) delay(2_000)
            if (data.toString().endsWith("broken")) throw IOException("Broken fixture image")
            DrawableResult(BitmapDrawable(compose.activity.resources, Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)), false, DataSource.NETWORK)
          }
        }
      })
    }.build()
    var image by mutableStateOf<String?>("fixture:slow")
    var visible by mutableStateOf(true)
    compose.setContent { MaterialTheme { if (visible) BookEditionRow(
      BookEdition("A long ebook edition title which occupies several lines", "Author", "English", "EPUB", "9780306406157", "https://zlib.li/book/1", coverUrl = image), { selections.incrementAndGet() }, imageLoader = loader,
    ) } }
    try {
      val before = compose.onNodeWithTag("edition-cover", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
      compose.onNodeWithTag("edition-row").performClick()
      assertEquals(1, selections.get())
      compose.runOnUiThread { image = "fixture:broken" }
      compose.waitUntil(5_000) { fetches.get() >= 2 }
      compose.onNodeWithTag("edition-row").performClick()
      compose.runOnUiThread { image = null }
      compose.onNodeWithTag("edition-row").performClick()
      assertEquals(before, compose.onNodeWithTag("edition-cover", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
      assertEquals(3, selections.get())
      compose.runOnUiThread { image = "fixture:cached" }
      compose.waitUntil(5_000) { loader.memoryCache!!.size > 0 }
      val count = fetches.get()
      compose.runOnUiThread { visible = false }; compose.waitForIdle()
      compose.runOnUiThread { visible = true }; compose.waitForIdle()
      assertEquals("Cover should be served from Coil memory cache", count, fetches.get())
      saveScreenshot("ebook-cover-row.png")
    } finally { loader.shutdown() }
  }

  private fun saveScreenshot(name: String) {
    File(compose.activity.cacheDir, name).outputStream().use {
      compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}

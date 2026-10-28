package com.example.rivulet.readalong

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
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
import java.util.concurrent.atomic.AtomicInteger

class FlowingReaderUiTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val track = ReadAlongTrack("flow-public-fixture", 0)

  private fun fixture(text: String, action: (ReadAlongRepository) -> Unit) {
    val name = "flow-${UUID.randomUUID()}.db"; val db = TorrentDatabase(compose.activity, name)
    val store = ReadAlongStore(db)
    val book = StoredReadAlongBook("flow-book", EpubBook("Public Fixture", "Test Author",
      listOf(EpubChapter(1, "one", "one.xhtml", "One", text, text.split(' ').size))), "public.epub")
    store.attach(track, book, setOf(0))
    val sentences = ReadAlongText.sentences(book.book, book.id)
    store.saveWindow(track, book.id, 0, 200_000, sentences.mapIndexed { i, sentence -> TimedLine(sentence.id, i * 2000L + 1000, i * 2000L + 2900, sentence.text) })
    val r = ReadAlongRepository(compose.activity, store, ReadAlongAudioDecoder { _, _, _ -> error("Cached fixture must not decode") })
    val previous = r.readerPreferences.textSize.value
    try {
      compose.runOnUiThread { r.readerPreferences.setTextSize(18); r.activate(track) }
      compose.waitUntil(10_000) { r.state.value.lines.isNotEmpty() }
      compose.runOnUiThread { r.playback(1100, 200_000, prepared = false) }
      action(r)
    } finally { compose.runOnUiThread { r.readerPreferences.setTextSize(previous); r.close() }; db.close(); compose.activity.deleteDatabase(name) }
  }

  @Composable private fun FlowPlayer(r: ReadAlongRepository, modifier: Modifier = Modifier, onSeek: (TimedLine) -> Unit = {}) {
    PlayerLayout(PlaybackUi(infoHash = track.infoHash, fileIndex = 0, hasMedia = true, durationMs = 200_000, positionMs = 1100),
      "Public Fixture", "Chapter one", "Chapter 1 of 3", {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, modifier) { readerModifier, visible ->
      LyricsView(r, emptyList(), "Public Fixture", onSeek, readerModifier, isVisible = visible)
    }
  }

  private fun layout(text: String): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
    return results.single()
  }

  @Test fun inlineTapsLongPressAndAccessibilityKeepOriginalTargetsWithoutReflow() = fixture(
    "The door pressure\npad. The ramp withdrew with grinding\nhydraulics. Cyrian asked the next question.\n\nA new paragraph follows.") { r ->
    val seeks = mutableListOf<Long>()
    compose.setContent { MaterialTheme { LyricsView(r, emptyList(), "Fixture", { seeks += it.id }) } }
    val block = readerParagraphs(r.state.value.lines, TextSyncSource.NARRATION).first()
    assertTrue(block.text.contains("pressure pad.")); assertTrue(block.text.contains("grinding hydraulics."))
    val target = block.ranges.last()
    val before = layout(block.text)
    val point = before.getBoundingBox(target.start + 2).center
    compose.onNodeWithText(block.text).performTouchInput { click(point) }
    assertEquals(listOf(target.sentenceId), seeks)
    val timing = r.state.value.lines.first { it.id == target.sentenceId }
    compose.runOnUiThread { r.playback(timing.startTimeMs + 1, 200_000, prepared = false) }
    val after = layout(block.text)
    assertEquals(before.size, after.size)
    assertEquals(before.getBoundingBox(target.start), after.getBoundingBox(target.start))
    compose.onNodeWithText(block.text).performTouchInput { longClick(point) }
    assertEquals(target.sentenceId, r.state.value.correction.target!!.sentenceId)
    assertEquals(1, seeks.size)
    val actions = compose.onNodeWithText(block.text).fetchSemanticsNode().config[SemanticsActions.CustomActions]
    compose.runOnUiThread { assertTrue(actions.first { it.label.startsWith("Select:") }.action()) }
    assertEquals(block.ranges.first().sentenceId, r.state.value.correction.target!!.sentenceId)
    assertEquals(1, seeks.size)
  }

  @Test fun pagerPreloadingDoesNotAcquireUntilTextSettlesAndTabsTrackSwipes() {
    val dbName = "pager-${UUID.randomUUID()}.db"; val db = TorrentDatabase(compose.activity, dbName)
    val searches = AtomicInteger(); val seeks = AtomicInteger()
    val r = ReadAlongRepository(compose.activity, ReadAlongStore(db), ReadAlongAudioDecoder { _, _, _ -> error("No audio") }, library = object : BookCatalog {
      override fun search(query: String): List<BookEdition> { searches.incrementAndGet(); return emptyList() }
      override fun download(edition: BookEdition, renderedHtml: String?) = error("No download")
    })
    try {
      compose.runOnUiThread { r.activate(track) }
      compose.waitUntil(10_000) { r.state.value.phase == ReadAlongPhase.EMPTY }
      compose.setContent { MaterialTheme { FlowPlayer(r) { seeks.incrementAndGet() } } }
      compose.waitForIdle()
      compose.onNodeWithTag("reader-visible").assertExists().assertIsNotDisplayed()
      assertEquals(0, searches.get())
      compose.onNodeWithTag("player-pager").performTouchInput { swipeLeft() }
      compose.onNodeWithTag("player-text").assertIsSelected()
      compose.waitUntil(10_000) { searches.get() == 1 && !r.state.value.searching }
      compose.onNodeWithTag("player-pager").performTouchInput { swipeRight() }
      compose.onNodeWithTag("player-cover").assertIsSelected()
      compose.onNodeWithTag("player-text").performClick()
      compose.onNodeWithTag("player-text").assertIsSelected()
      assertEquals(1, searches.get()); assertEquals(0, seeks.get())
    } finally { compose.runOnUiThread { r.close() }; db.close(); compose.activity.deleteDatabase(dbName) }
  }

  @Test fun longParagraphFollowsItsActiveSentenceAndRetainsManualScrollAcrossSwipeAndRestore() = fixture(
    (0..79).joinToString(" ") { "Public fixture sentence $it continues across the page." }) { r ->
    val restoration = StateRestorationTester(compose)
    val seeks = AtomicInteger()
    var landscape by mutableStateOf(false)
    restoration.setContent {
      val base = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(base.density * if (landscape) 0.5f else 1f, 1f)) {
        MaterialTheme { FlowPlayer(r, Modifier.requiredSize(if (landscape) 720.dp else 360.dp, if (landscape) 360.dp else 740.dp)
          .consumeWindowInsets(WindowInsets.safeDrawing)) { seeks.incrementAndGet() } }
      }
    }
    compose.onNodeWithTag("player-text").performClick()
    val target = r.state.value.lines[60]
    compose.runOnUiThread { r.playback(target.startTimeMs + 1, 200_000, prepared = false) }
    compose.waitForIdle()
    fun scroll() = compose.onNodeWithTag("readalong-lines").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    assertTrue("Follow must scroll within the single paragraph", scroll() > 0)
    File(compose.activity.cacheDir, "plan017-flowing-text.png").outputStream().use {
      compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    compose.onNodeWithTag("readalong-lines").performTouchInput { swipeUp() }
    compose.onNodeWithText("Follow narration").assertIsDisplayed()
    val before = scroll()
    compose.onNodeWithTag("player-pager").performTouchInput { swipeRight() }
    compose.onNodeWithTag("player-pager").performTouchInput { swipeLeft() }
    compose.onNodeWithText("Follow narration").assertIsDisplayed()
    assertEquals(before, scroll(), 1f)
    val block = readerParagraphs(r.state.value.lines, TextSyncSource.NARRATION).single()
    fun firstVisibleCharacter(): Int {
      val measured = layout(block.text)
      val top = compose.onNodeWithTag("readalong-lines").getUnclippedBoundsInRoot().top - compose.onNodeWithText(block.text).getUnclippedBoundsInRoot().top
      return measured.getLineStart(measured.getLineForVerticalPosition(top.value * measured.layoutInput.density.density))
    }
    val character = firstVisibleCharacter()
    compose.runOnUiThread { landscape = true }
    compose.onNodeWithTag("player-text").assertIsSelected()
    File(compose.activity.cacheDir, "plan017-reader-landscape.png").outputStream().use {
      compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    compose.onNodeWithText("Follow narration").assertIsDisplayed()
    assertTrue("Rotation must retain the words being read", kotlin.math.abs(character - firstVisibleCharacter()) < 100)
    compose.runOnUiThread { landscape = false }
    compose.onNodeWithText("Follow narration").assertIsDisplayed()
    assertTrue("Returning to portrait must retain the reading anchor", kotlin.math.abs(character - firstVisibleCharacter()) < 100)
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithText("Follow narration").assertIsDisplayed()
    assertEquals(before, scroll(), 1f)
    assertEquals(0, seeks.get()); assertFalse(r.state.value.correction.isEditing)
    compose.onNodeWithText("Follow narration").performClick()
    assertTrue(scroll() > 0)
  }
}

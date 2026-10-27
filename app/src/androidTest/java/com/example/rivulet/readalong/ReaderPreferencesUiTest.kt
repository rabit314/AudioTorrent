package com.example.rivulet.readalong

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import com.example.rivulet.ui.player.LyricsView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ReaderPreferencesUiTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun sizePersistsThroughRestorationAndFontScalingWithoutChangingReaderPosition() {
    val activity = compose.activity; val name = "size-${UUID.randomUUID()}.db"; val db = TorrentDatabase(activity, name)
    val r = ReadAlongRepository(activity, ReadAlongStore(db), ReadAlongAudioDecoder { _, _, _ -> error("LRC") })
    val previous = r.readerPreferences.textSize.value
    var fontScale by mutableStateOf(1f)
    val restore = StateRestorationTester(compose)
    try {
      compose.runOnUiThread {
        r.readerPreferences.setTextSize(18)
        r.activate(ReadAlongTrack("size-public", 0))
        r.importBytes((0..79).joinToString("\n") { "[00:%02d.00]Public sentence number %d.".format(it, it) }.toByteArray(), "size.lrc", "text/plain", setOf(0))
      }
      compose.waitUntil(10_000) { r.state.value.bookId != null }
      compose.runOnUiThread { r.playback(20_100, 80_000) }
      restore.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
          MaterialTheme { LyricsView(r, emptyList(), "Size fixture", {}) }
        }
      }
      val layouts = mutableListOf<TextLayoutResult>()
      compose.onNodeWithText("Public sentence number 20.").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      assertEquals(20.sp, layouts.single().layoutInput.style.fontSize)
      compose.onNodeWithText("Text options").performClick(); compose.onNodeWithText("Text size", useUnmergedTree = true).performClick()
      compose.onNodeWithTag("reader-text-size").assertTextEquals("18 sp")
      compose.onNodeWithTag("reader-text-size-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(22f) }
      compose.onNodeWithTag("reader-text-size").assertTextEquals("22 sp")
      assertEquals(22, ReaderPreferences(activity).textSize.value)
      restore.emulateSavedInstanceStateRestore()
      compose.onNodeWithTag("reader-text-size").assertTextEquals("22 sp")
      compose.onNodeWithText("Done").performClick()
      compose.onNodeWithText("Public sentence number 20.").assertIsDisplayed()
      compose.runOnUiThread { fontScale = 1.3f }
      layouts.clear()
      compose.onNodeWithText("Public sentence number 20.").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      assertEquals(24.sp, layouts.single().layoutInput.style.fontSize)
      assertEquals(1.3f, layouts.single().layoutInput.density.fontScale)
      assertEquals(20, r.state.value.activeLineIndex)
      compose.onNodeWithText("Text options").performClick(); compose.onNodeWithText("Text size", useUnmergedTree = true).performClick()
      compose.onNodeWithText("Reset").performClick(); compose.onNodeWithTag("reader-text-size").assertTextEquals("18 sp")
    } finally { compose.runOnUiThread { r.readerPreferences.setTextSize(previous); r.close() }; db.close(); activity.deleteDatabase(name) }
  }

  @Test fun openingTextAutomaticallySearchesAndExposesSiteAccessRecovery() {
    val activity = compose.activity; val name = "auto-ui-${UUID.randomUUID()}.db"; val db = TorrentDatabase(activity, name)
    val searches = AtomicInteger()
    val r = ReadAlongRepository(activity, ReadAlongStore(db), ReadAlongAudioDecoder { _, _, _ -> error("Unused") }, library = object : BookCatalog {
      override fun search(query: String): List<BookEdition> { searches.incrementAndGet(); throw SiteAccessRequired("https://zlib.li/s/Public") }
      override fun download(edition: BookEdition, renderedHtml: String?) = error("Unused")
    })
    try {
      compose.runOnUiThread { r.activate(ReadAlongTrack("auto-ui-public", 0)) }
      compose.waitUntil(10_000) { r.state.value.phase == ReadAlongPhase.EMPTY }
      compose.setContent { MaterialTheme { LyricsView(r, emptyList(), "Public Fixture", {}, searchAuthor = "Test Author") } }
      compose.waitUntil(10_000) { r.state.value.accessUrl != null }
      compose.onNodeWithText("Open site access").assertIsDisplayed()
      assertEquals(1, searches.get())
    } finally { compose.runOnUiThread { r.close() }; db.close(); activity.deleteDatabase(name) }
  }
}

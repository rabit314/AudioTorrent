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
import com.example.rivulet.theme.*
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
      assertEquals(18.sp, layouts.single().layoutInput.style.fontSize)
      compose.onNodeWithTag("text-settings-button").performClick(); compose.onNodeWithText("Text size", useUnmergedTree = true).performClick()
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
      assertEquals(22.sp, layouts.single().layoutInput.style.fontSize)
      assertEquals(1.3f, layouts.single().layoutInput.density.fontScale)
      assertEquals(20, r.state.value.activeLineIndex)
      compose.onNodeWithTag("text-settings-button").performClick(); compose.onNodeWithText("Text size", useUnmergedTree = true).performClick()
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

  @Test fun readerThemeAndSyncModeDialogsPersistSelections() {
    val activity = compose.activity; val name = "theme-${UUID.randomUUID()}.db"; val db = TorrentDatabase(activity, name)
    val store = ReadAlongStore(db)
    val track = ReadAlongTrack("theme-public", 0)
    val book = StoredReadAlongBook(
      "theme-book",
      EpubBook("Theme Fixture", "Author", listOf(EpubChapter(1, "c1", "c1.xhtml", "Chapter One", "First quiet sentence. Second warm sentence.", 2))),
      "fixture.epub",
    )
    store.attach(track, book, setOf(0))
    val sentence = ReadAlongText.sentences(book.book, book.id).first()
    for (start in 0L..160_000L step 20_000L) {
      val lines = if (start == 0L) listOf(TimedLine(sentence.id, 500, 3_000, sentence.text)) else emptyList()
      store.saveWindow(track, book.id, start, start + 20_000L, lines)
    }
    val r = ReadAlongRepository(activity, store, ReadAlongAudioDecoder { _, _, _ -> error("Cached") })
    val prefs = r.readerPreferences
    val prevTheme = prefs.readerTheme.value
    val prevFont = prefs.fontFamily.value
    val prevSpacing = prefs.lineSpacing.value
    val prevSync = prefs.syncMode.value
    try {
      compose.runOnUiThread {
        prefs.setReaderTheme(ReaderThemePreset.BLACK_BLUE)
        prefs.setFontFamily(ReaderFontFamily.SANS)
        prefs.setLineSpacing(ReaderLineSpacing.COMFORTABLE)
        prefs.setSyncMode(SyncMode.BALANCED)
        r.activate(track)
      }
      compose.waitUntil(10_000) { r.state.value.bookId != null }
      compose.runOnUiThread { r.playback(1_000, 80_000, prepared = false) }
      compose.setContent { MaterialTheme { LyricsView(r, emptyList(), "Theme Fixture", {}) } }

      compose.onNodeWithContentDescription("Reading theme and style").performClick()
      compose.onNodeWithText("Reading appearance").assertIsDisplayed()
      compose.onNodeWithText(ReaderThemePreset.KINDLE_PAPER.label).performScrollTo().performClick()
      compose.onNodeWithText(ReaderFontFamily.SERIF.label).performScrollTo().performClick()
      compose.onNodeWithText(ReaderLineSpacing.RELAXED.label).performScrollTo().performClick()
      compose.onNodeWithText("Done").performClick()

      val reloaded = ReaderPreferences(activity)
      assertEquals(ReaderThemePreset.KINDLE_PAPER, reloaded.readerTheme.value)
      assertEquals(ReaderFontFamily.SERIF, reloaded.fontFamily.value)
      assertEquals(ReaderLineSpacing.RELAXED, reloaded.lineSpacing.value)

      compose.onNodeWithTag("sync-status-pill").performClick()
      compose.onNodeWithText("Synchronization details").assertIsDisplayed()
      compose.onNodeWithText(SyncMode.EXTENDED.label).performScrollTo().performClick()
      assertEquals(SyncMode.EXTENDED, ReaderPreferences(activity).syncMode.value)
      compose.onNodeWithText(SyncMode.FULL_TRACK.label).performScrollTo().performClick()
      assertEquals(SyncMode.FULL_TRACK, ReaderPreferences(activity).syncMode.value)
      compose.onNodeWithText(SyncMode.PLAYHEAD_ONLY.label).performScrollTo().performClick()
      compose.onNodeWithText("Done").performClick()
      assertEquals(SyncMode.PLAYHEAD_ONLY, ReaderPreferences(activity).syncMode.value)

      compose.onNodeWithTag("text-settings-button").performClick()
      compose.onNodeWithTag("menu-correct-sync").performClick()
      compose.onNodeWithText("Fine-tune & tools").performClick()
      compose.onNodeWithText("Correct sync & timing tools").assertIsDisplayed()
      compose.onNodeWithText("Done").performClick()
      compose.onNodeWithText("Cancel").performClick()
    } finally {
      compose.runOnUiThread {
        prefs.setReaderTheme(prevTheme)
        prefs.setFontFamily(prevFont)
        prefs.setLineSpacing(prevSpacing)
        prefs.setSyncMode(prevSync)
        r.close()
      }
      db.close()
      activity.deleteDatabase(name)
    }
  }
}

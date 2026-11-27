package com.example.audiotorrent.readalong

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.audiotorrent.data.TorrentDatabase
import com.example.audiotorrent.data.lyrics.*
import com.example.audiotorrent.playback.PlaybackUi
import com.example.audiotorrent.ui.player.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class BookLookupUiTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun textLookupWorksDuringBufferingAndMetadataRefreshPreservesEditedQuery() {
    val context = compose.activity
    val name = "buffer-lookup-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name)
    val observedQuery = AtomicReference<String>()
    val catalog = object : BookCatalog {
      override fun search(query: String): List<BookEdition> { observedQuery.set(query); return emptyList() }
      override fun download(edition: BookEdition, renderedHtml: String?) = error("Unused")
    }
    val repo = ReadAlongRepository(context, ReadAlongStore(db), AudioWindowDecoder { error("No analysis while audio is unavailable") }, library = catalog)
    var title by mutableStateOf("Simon Kurt Unsworth")
    try {
      compose.runOnUiThread { repo.activate(ReadAlongTrack("buffer", 2)) }
      compose.waitUntil(5000) { repo.state.value.phase == ReadAlongPhase.EMPTY }
      compose.setContent { MaterialTheme {
        PlayerLayout(PlaybackUi(infoHash = "buffer", fileIndex = 2, hasMedia = true, isBuffering = true), title, "", null,
          {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) { modifier, visible -> LyricsView(repo, emptyList(), title, {}, modifier, isVisible = visible) }
      } }
      compose.onNodeWithTag("player-pager").performTouchInput { swipeLeft() }
      compose.onNodeWithText("Buffering audio…").assertIsDisplayed()
      compose.onNodeWithText("Find book text").performClick()
      compose.waitUntil(5000) { observedQuery.get() == "Simon Kurt Unsworth" }
      compose.runOnUiThread { title = "The Devil's Detective" }
      compose.onNode(hasSetTextAction() and hasText("The Devil's Detective")).assertExists()
      compose.onNode(hasSetTextAction()).performTextReplacement("My chosen edition")
      compose.runOnUiThread { title = "New catalog label" }
      compose.onNodeWithText("My chosen edition").assertExists()
      compose.onNodeWithText("Search", useUnmergedTree = true).performClick()
      compose.waitUntil(5000) { observedQuery.get() == "My chosen edition" }
      assertEquals(2, repo.state.value.track!!.fileIndex)
      compose.onNodeWithText("Done").performClick()
      compose.onNodeWithText("Buffering audio…").assertIsDisplayed()
    } finally { compose.runOnUiThread { repo.close() }; db.close(); context.deleteDatabase(name) }
  }

  @Test fun hidesFailedLookupClutterUnderSyncedHeaderAndAllowsDismissWhenEmpty() {
    val context = compose.activity
    val name = "lookup-ui-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name)
    val store = ReadAlongStore(db)
    val failingCatalog = object : BookCatalog {
      override fun search(query: String) = emptyList<BookEdition>()
      override fun lookup(request: BookLookupRequest) = BookLookupResult(
        editions = emptyList(),
        complete = false,
        message = "Z-Library verification needed.",
        accessUrl = "https://zlib.li/",
      )
      override fun download(edition: BookEdition, renderedHtml: String?) = error("Unused")
    }
    val repo = ReadAlongRepository(
      context,
      store,
      AudioWindowDecoder { error("No audio") },
      library = failingCatalog,
    )
    try {
      // Case 1: No attached book yet -> compact lookup card appears and can be dismissed.
      val emptyTrack = ReadAlongTrack("empty-lookup", 0)
      compose.runOnUiThread { repo.activate(emptyTrack) }
      compose.waitUntil(5000) { repo.state.value.phase == ReadAlongPhase.EMPTY }
      compose.runOnUiThread { repo.searchBooks("Test Book", force = true) }
      compose.waitUntil(5000) { repo.state.value.lookupMessage != null }
      compose.setContent {
        MaterialTheme {
          LyricsView(repo, emptyList(), "Test Book", {}, isVisible = true)
        }
      }
      compose.onNodeWithText("Z-Library verification needed.").assertIsDisplayed()
      compose.onNodeWithText("Open site access").assertIsDisplayed()
      compose.onNodeWithText("Dismiss").performClick()
      compose.waitForIdle()
      compose.onAllNodesWithText("Z-Library verification needed.").assertCountEquals(0)
      compose.onAllNodesWithText("Open site access").assertCountEquals(0)

      // Case 2: Attached/loaded book -> closing Search leaves lookup clutter hidden under sync pill.
      val bookTrack = ReadAlongTrack("loaded-lookup", 0)
      val book = StoredReadAlongBook(
        "loaded-id",
        EpubBook("Loaded Book", "Author", listOf(EpubChapter(1, "c1", "c1.html", "Chapter 1", "Sentence one.", 2))),
        "loaded.epub",
      )
      store.attach(bookTrack, book, setOf(0))
      compose.runOnUiThread {
        repo.activate(bookTrack)
      }
      compose.waitUntil(5000) { repo.state.value.bookId == "loaded-id" }
      compose.onNodeWithTag("text-settings-button").performClick()
      compose.onNodeWithText("Replace edition").performClick()
      compose.waitUntil(5000) { repo.state.value.lookupMessage == "Z-Library verification needed." }
      compose.onNodeWithText("Done").performClick()
      compose.waitForIdle()
      compose.onAllNodesWithText("Z-Library verification needed.").assertCountEquals(0)
      compose.onAllNodesWithText("Open site access").assertCountEquals(0)
    } finally {
      compose.runOnUiThread { repo.close() }
      db.close()
      context.deleteDatabase(name)
    }
  }
}

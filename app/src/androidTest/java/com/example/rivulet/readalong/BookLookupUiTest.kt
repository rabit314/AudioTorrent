package com.example.rivulet.readalong

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import com.example.rivulet.playback.PlaybackUi
import com.example.rivulet.ui.player.*
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
          {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) { LyricsView(repo, emptyList(), title, {}, it) }
      } }
      compose.onNodeWithTag("player-text").performClick()
      compose.onNodeWithText("Buffering from peers…").assertIsDisplayed()
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
      compose.onNodeWithText("Buffering from peers…").assertIsDisplayed()
    } finally { compose.runOnUiThread { repo.close() }; db.close(); context.deleteDatabase(name) }
  }
}

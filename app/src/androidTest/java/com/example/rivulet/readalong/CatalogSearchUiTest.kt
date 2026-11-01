package com.example.rivulet.readalong

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.rivulet.RivuletApp
import com.example.rivulet.data.audiobookbay.*
import com.example.rivulet.data.catalog.*
import com.example.rivulet.ui.catalog.AudiobookSearchScreen
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class CatalogSearchUiTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun librivoxOnlyResultRemainsVisibleWhenBayFailsAndSourceFiltersWork() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val search = AudioCatalogSearch(scope) { source, query, _ ->
      if (source == AudioBookSource.AUDIOBOOKBAY) throw IOException("offline fixture")
      AudioCatalogPage(listOf(AudioBookItem(if (query.isBlank()) "Public LibriVox-only book" else "Found: $query", "Test Author", "https://librivox.org/fixture",
        source = source, providerId = "fixture")), false)
    }
    try {
      compose.setContent { MaterialTheme {
        AudiobookSearchScreen((compose.activity.application as RivuletApp).container, {}, {}, {}, search)
      } }
      compose.onNodeWithText("Search audiobooks").assertIsDisplayed()
      compose.waitUntil(10_000) { search.state.value.loading.isEmpty() && search.state.value.started }
      compose.onNodeWithText("Public LibriVox-only book").assertIsDisplayed()
      compose.onNodeWithText("AudiobookBay: offline fixture").assertIsDisplayed()
      compose.onNodeWithText("LibriVox", substring = false).performClick()
      compose.onNodeWithText("AudiobookBay: offline fixture").assertDoesNotExist()
      compose.onNodeWithTag("audiobook-query").performTextReplacement("another title")
      compose.onNodeWithText("Search", substring = false).performClick()
      compose.waitUntil(10_000) { search.state.value.query == "another title" && search.state.value.loading.isEmpty() }
      compose.onNodeWithText("Found: another title").assertIsDisplayed()
    } finally { scope.cancel() }
  }
}

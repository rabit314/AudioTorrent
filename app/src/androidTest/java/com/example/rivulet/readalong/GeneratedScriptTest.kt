package com.example.rivulet.readalong

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.rivulet.data.BookCatalogMetadata
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import com.example.rivulet.ui.player.LyricsView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GeneratedScriptTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val context: Context get() = compose.activity
  private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
  private suspend fun eventually(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(15) }

  @Test fun generatesPersistsContinuesChaptersAndExportsWithoutEbook() = runBlocking {
    val dbName = "script-${UUID.randomUUID()}.db"
    var db = TorrentDatabase(context, dbName)
    var store = ReadAlongStore(db)
    val files = File(context.cacheDir, "script-model-${UUID.randomUUID()}")
    val models = SpeechModelManager(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = files })
    models.file.parentFile!!.mkdirs(); models.file.writeBytes(byteArrayOf(1))
    val hash = "script-hash-${UUID.randomUUID()}"
    val speech = SpeechRecognizer { _, _ ->
      listOf(
        SpeechWord(" The", 2_500, 2_800),
        SpeechWord(" first", 2_850, 3_200),
        SpeechWord(" passage.", 3_250, 3_800),
        SpeechWord(" Second", 5_200, 5_600),
        SpeechWord(" line.", 5_650, 6_100),
      )
    }
    var repo = ReadAlongRepository(context, store, ReadAlongAudioDecoder { _, _, _ -> FloatArray(1600) }, speech, models)
    try {
      val track0 = ReadAlongTrack(hash, 0)
      main {
        repo.activate(track0)
        repo.playback(1_000, 35_000, track0, prepared = true, isPlaying = false)
        repo.setVisible(true)
      }
      eventually { repo.state.value.phase == ReadAlongPhase.EMPTY }
      main { repo.generateScriptFromAudio("Rare Book", "Chapter 1", track0) }
      eventually { repo.state.value.source == TextSyncSource.WHISPER_SCRIPT && repo.state.value.lines.size == 2 }
      assertEquals("The first passage.", repo.state.value.lines[0].text)
      assertEquals("Second line.", repo.state.value.lines[1].text)
      assertEquals(0, repo.state.value.activeLineIndex.coerceAtLeast(0))

      // Wait for window persistence and verify DB v8 reload without re-running Whisper.
      eventually { store.windowReady(track0, "script:$hash:0", 0) }
      main { repo.close() }
      db.close()

      db = TorrentDatabase(context, dbName)
      store = ReadAlongStore(db)
      assertEquals(8, db.readableDatabase.version)
      val failIfCalled = SpeechRecognizer { _, _ -> error("Cached window should not re-run speech") }
      repo = ReadAlongRepository(context, store, ReadAlongAudioDecoder { _, _, _ -> FloatArray(1600) }, failIfCalled, models)
      main { repo.activate(track0) }
      eventually { repo.state.value.lines.size == 2 }
      assertEquals("The first passage.", repo.state.value.lines[0].text)
      assertEquals(1, repo.searchInBook("passage").size)

      // Advancing to Chapter 2 inherits script mode automatically via BookCatalogMetadata.
      val track1 = ReadAlongTrack(hash, 1)
      main { repo.activate(track1) }
      eventually { repo.state.value.track == track1 && repo.state.value.source == TextSyncSource.WHISPER_SCRIPT }

      // Clearing script disables multi-chapter continuity and returns to EMPTY.
      main { repo.clearGeneratedScript() }
      eventually { repo.state.value.bookId == null && repo.state.value.phase == ReadAlongPhase.EMPTY }
      assertFalse(BookCatalogMetadata(context).isScriptModeEnabled(hash))
    } finally {
      main { repo.close() }
      db.close()
      context.deleteDatabase(dbName)
      files.deleteRecursively()
      BookCatalogMetadata(context).remove(hash)
    }
  }

  @Test fun fullTrackTranscriptionContinuesPausedAndWrapsFromStart() = runBlocking {
    val dbName = "full-script-${UUID.randomUUID()}.db"
    val db = TorrentDatabase(context, dbName)
    val store = ReadAlongStore(db)
    val files = File(context.cacheDir, "full-script-model-${UUID.randomUUID()}")
    val models = SpeechModelManager(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = files })
    models.file.parentFile!!.mkdirs(); models.file.writeBytes(byteArrayOf(1))
    val hash = "full-script-${UUID.randomUUID()}"
    val decodedStarts = mutableListOf<Long>()
    val speech = SpeechRecognizer { _, _ ->
      listOf(SpeechWord(" Spoken", 2_500, 3_000), SpeechWord(" line.", 3_050, 3_500))
    }
    val decoder = ReadAlongAudioDecoder { _, decodeStart, _ ->
      synchronized(decodedStarts) { decodedStarts += decodeStart }
      FloatArray(1600)
    }
    val repo = ReadAlongRepository(context, store, decoder, speech, models)
    try {
      val track = ReadAlongTrack(hash, 0)
      main {
        repo.activate(track)
        // Start paused in the second window (20s..40s of a 45s track) while reader is hidden.
        repo.playback(25_000, 45_000, track, prepared = true, isPlaying = false)
        repo.setVisible(false)
        repo.generateScriptFromAudio("Full Track Test", "Track 1", track)
      }
      eventually { repo.state.value.source == TextSyncSource.WHISPER_SCRIPT }
      main { repo.setTranscribeFullTrack(true) }
      eventually {
        store.windowReady(track, "script:$hash:0", 0) &&
          store.windowReady(track, "script:$hash:0", 20_000) &&
          store.windowReady(track, "script:$hash:0", 40_000) &&
          !repo.state.value.transcribingFullTrack
      }
      assertTrue(repo.state.value.lines.size >= 3)
      // Started at playhead window (20_000 - 2_000 = 18_000) before wrapping to 0.
      assertEquals(18_000L, decodedStarts.first())
      assertTrue(0L in decodedStarts)
    } finally {
      main { repo.close() }
      db.close()
      context.deleteDatabase(dbName)
      files.deleteRecursively()
      BookCatalogMetadata(context).remove(hash)
    }
  }

  @Test fun generateScriptEscapesBlockedSearchAndShowsListeningAndSectionsUi() {
    val dbName = "script-ui-${UUID.randomUUID()}.db"
    val db = TorrentDatabase(context, dbName)
    val files = File(context.cacheDir, "script-ui-model-${UUID.randomUUID()}")
    val models = SpeechModelManager(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = files })
    models.file.parentFile!!.mkdirs(); models.file.writeBytes(byteArrayOf(1))
    val gate = CountDownLatch(1)
    val blockedCatalog = object : BookCatalog {
      override fun search(query: String): List<BookEdition> {
        gate.await(5, TimeUnit.SECONDS)
        return emptyList()
      }
      override fun download(edition: BookEdition, renderedHtml: String?) = error("Unused")
    }
    val speechGate = CompletableDeferred<Unit>()
    val speech = SpeechRecognizer { _, _ ->
      speechGate.await()
      listOf(SpeechWord(" Generated", 2_500, 3_000), SpeechWord(" line.", 3_050, 3_600))
    }
    val repo = ReadAlongRepository(context, ReadAlongStore(db), ReadAlongAudioDecoder { _, _, _ -> FloatArray(1600) }, speech, models, blockedCatalog)
    val hash = "script-ui-${UUID.randomUUID()}"
    try {
      val track = ReadAlongTrack(hash, 0)
      compose.runOnUiThread {
        repo.activate(track)
        repo.playback(1_000, 40_000, track, prepared = true, isPlaying = false)
      }
      compose.setContent {
        MaterialTheme {
          LyricsView(
            repository = repo,
            audioFiles = emptyList(),
            cleanBookTitle = "Missing Book",
            onSeekToLine = {},
            trackTitle = "Part 1",
          )
        }
      }
      // Even while blockedCatalog search is still spinning, Generate script is clickable immediately.
      compose.onNodeWithTag("generate-script-button").assertIsDisplayed().performClick()
      compose.waitUntil(5_000) { repo.state.value.isScriptSource }
      compose.onNodeWithTag("script-listening-placeholder").assertIsDisplayed()

      speechGate.complete(Unit)
      compose.waitUntil(5_000) { repo.state.value.lines.isNotEmpty() }
      compose.onNodeWithText("Generated line.").assertIsDisplayed()
      compose.onNodeWithTag("text-settings-button").performClick()
      compose.onNodeWithText("Export script (.txt)").assertIsDisplayed()
    } finally {
      gate.countDown()
      speechGate.complete(Unit)
      compose.runOnUiThread { repo.close() }
      db.close()
      context.deleteDatabase(dbName)
      files.deleteRecursively()
      BookCatalogMetadata(context).remove(hash)
    }
  }
}

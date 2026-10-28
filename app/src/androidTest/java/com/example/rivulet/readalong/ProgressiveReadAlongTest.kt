package com.example.rivulet.readalong

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProgressiveReadAlongTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val text = "The little boat moved slowly across the quiet water."
  private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
  private suspend fun eventually(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }

  private suspend fun fixture(failAfterPartial: Boolean, action: suspend (ReadAlongRepository, ReadAlongStore, CompletableDeferred<Unit>) -> Unit) {
    val name = "progressive-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name); val store = ReadAlongStore(db)
    val files = File(context.cacheDir, "progressive-${UUID.randomUUID()}")
    val models = SpeechModelManager(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = files })
    models.file.parentFile!!.mkdirs(); models.file.writeBytes(byteArrayOf(1))
    val finish = CompletableDeferred<Unit>()
    val speech = object : SpeechRecognizer {
      override suspend fun transcribe(model: File, pcm: FloatArray): List<SpeechWord> = error("Must publish progressively")
      override suspend fun transcribeProgressive(model: File, pcm: FloatArray, focusMs: Long, onPartial: suspend (List<SpeechWord>) -> Unit): List<SpeechWord> {
        val words = ReadAlongText.tokens(text).mapIndexed { i, token -> SpeechWord(token, 1000L + i * 200, 1180L + i * 200) }
        onPartial(words)
        finish.await()
        if (failAfterPartial) error("Later batch unavailable")
        return words
      }
    }
    val r = ReadAlongRepository(context, store, ReadAlongAudioDecoder { _, _, _ -> FloatArray(1600) }, speech, models)
    val track = ReadAlongTrack("progressive-public", 0)
    val b = StoredReadAlongBook("progressive-book", EpubBook("Public Fixture", "Author", listOf(EpubChapter(1, "one", "one.xhtml", "One", text, 10))), "fixture.epub")
    try {
      store.attach(track, b, setOf(0))
      main { r.activate(track) }; eventually { r.state.value.bookId != null }
      main { r.playback(1500, 100_000); r.setVisible(true) }
      eventually { r.state.value.lines.any { it.isTimed } }
      action(r, store, finish)
    } finally { finish.complete(Unit); main { r.close() }; db.close(); context.deleteDatabase(name); files.deleteRecursively() }
  }

  @Test fun partialTimingIsUsableAndDurableBeforeTheWindowCompletes() = runBlocking {
    fixture(false) { r, store, finish ->
      val track = r.state.value.track!!; val book = r.state.value.bookId!!
      assertEquals(ReadAlongPhase.READY, r.state.value.phase)
      assertEquals(0, r.state.value.activeLineIndex)
      assertTrue(r.state.value.lines.single().isTimed)
      assertEquals(1000L, store.timings(track, book).single().startTimeMs)
      assertFalse(store.windowReady(track, book, 0))
      main { r.setVisible(false) }; finish.complete(Unit)
      eventually { store.windowReady(track, book, 0) }
      assertFalse(store.windowReady(track, book, 20_000))
    }
  }

  @Test fun aLaterBatchFailureKeepsAlreadyConfidentTextReady() = runBlocking {
    fixture(true) { r, store, finish ->
      finish.complete(Unit)
      eventually { r.state.value.recoveryLabel == "Retry matching" }
      assertEquals(ReadAlongPhase.READY, r.state.value.phase)
      assertTrue(r.state.value.lines.single().isTimed)
      assertFalse(store.windowReady(r.state.value.track!!, r.state.value.bookId!!, 0))
    }
  }
}

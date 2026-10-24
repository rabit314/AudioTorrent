package com.example.rivulet.readalong

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlayerRepositoryRegressionTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val track = ReadAlongTrack("public-regression", 2)
  private val edition = BookEdition("Example", "Author", "English", "EPUB", null, "https://zlib.li/book/1")
  private fun book() = EpubBook("Example", "Author", listOf(EpubChapter(1, "c", "c.xhtml", "Chapter", "The little boat moved slowly across the quiet water.", 10)))
  private suspend fun ready(repository: ReadAlongRepository) = withTimeout(10_000) { repository.state.first { (it.phase == ReadAlongPhase.READY && it.lines.isNotEmpty()) || it.phase == ReadAlongPhase.EMPTY } }

  @Test fun duplicateActiveAndSuccessfulSearchesAreReusedAndRetryTargetsFailedOperation() = runBlocking {
    val name = "lookup-${UUID.randomUUID()}.db"
    val database = TorrentDatabase(context, name)
    val gate = CountDownLatch(1); val calls = AtomicInteger(); val downloads = AtomicInteger()
    val catalog = object : BookCatalog {
      override fun search(query: String): List<BookEdition> {
        calls.incrementAndGet(); check(gate.await(10, TimeUnit.SECONDS))
        if (query == "fail" && calls.get() == 2) throw IOException("Search offline")
        return listOf(edition)
      }
      override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> {
        downloads.incrementAndGet(); throw IOException("Download offline")
      }
    }
    val repository = ReadAlongRepository(context, ReadAlongStore(database), AudioWindowDecoder { error("Unused") }, library = catalog)
    try {
      withContext(Dispatchers.Main) { repository.activate(track) }; ready(repository)
      withContext(Dispatchers.Main) { repository.searchBooks("Example"); repository.searchBooks("  EXAMPLE  ") }
      withTimeout(10_000) { while (calls.get() == 0) delay(10) }
      assertEquals(1, calls.get()); gate.countDown()
      withTimeout(10_000) { repository.state.first { it.editions.isNotEmpty() && !it.searching } }
      withContext(Dispatchers.Main) { repository.searchBooks("example") }
      assertEquals(1, calls.get())
      withContext(Dispatchers.Main) { repository.searchBooks("fail") }
      withTimeout(10_000) { repository.state.first { it.recoveryLabel == "Retry search" } }
      withContext(Dispatchers.Main) { repository.retry() }
      withTimeout(10_000) { repository.state.first { !it.searching && it.editions.isNotEmpty() } }
      assertEquals(3, calls.get())
      withContext(Dispatchers.Main) { repository.downloadEdition(edition, setOf(2)) }
      withTimeout(10_000) { repository.state.first { it.recoveryLabel == "Retry ebook download" } }
      withContext(Dispatchers.Main) { repository.retry() }
      withTimeout(10_000) { repository.state.first { it.recoveryLabel == "Retry ebook download" } }
      assertEquals(2, downloads.get()); assertEquals(3, calls.get())
      withContext(Dispatchers.Main) { repository.importBytes("<html>Sign in</html>".toByteArray(), "fake.epub", "application/epub+zip", setOf(2)) }
      withTimeout(10_000) { repository.state.first { it.recovery == ReadAlongRecovery.PICK_FILE } }
      assertEquals("Choose another file", repository.state.value.recoveryLabel)
      withContext(Dispatchers.Main) { repository.retry() }
      assertEquals(2, downloads.get()); assertEquals(3, calls.get())
      withContext(Dispatchers.Main) { repository.importBytes("A valid public fixture sentence.".toByteArray(), "local.txt", "text/plain", setOf(2)) }
      ready(repository)
      assertTrue(repository.state.value.editions.isEmpty())
      withContext(Dispatchers.Main) { repository.searchBooks("fail") }
      assertEquals(1, repository.state.value.editions.size); assertEquals(3, calls.get())
      withContext(Dispatchers.Main) {
        repository.acceptRenderedSearch("<z-bookcard title='Example' href='/book/1'><img src='/cover.jpg'></z-bookcard>", ZLibraryClient.BASE)
      }
      assertEquals("https://zlib.li/cover.jpg", repository.state.value.editions.single().coverUrl)
    } finally { gate.countDown(); withContext(Dispatchers.Main) { repository.close() }; database.close(); context.deleteDatabase(name) }
  }

  @Test fun oldSearchCannotReplaceNewerTrackResults() = runBlocking {
    val name = "stale-lookup-${UUID.randomUUID()}.db"; val database = TorrentDatabase(context, name)
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val catalog = object : BookCatalog {
      override fun search(query: String): List<BookEdition> { entered.countDown(); release.await(10, TimeUnit.SECONDS); return listOf(edition) }
      override fun download(edition: BookEdition, renderedHtml: String?) = error("Unused")
    }
    val repository = ReadAlongRepository(context, ReadAlongStore(database), AudioWindowDecoder { error("Unused") }, library = catalog)
    try {
      withContext(Dispatchers.Main) { repository.activate(track) }; ready(repository)
      withContext(Dispatchers.Main) { repository.searchBooks("Example") }
      assertTrue(entered.await(10, TimeUnit.SECONDS))
      withContext(Dispatchers.Main) { repository.activate(track.copy(fileIndex = 9)) }; ready(repository)
      release.countDown(); delay(200)
      assertEquals(9, repository.state.value.track!!.fileIndex)
      assertTrue(repository.state.value.editions.isEmpty()); assertFalse(repository.state.value.searching)
    } finally { release.countDown(); withContext(Dispatchers.Main) { repository.close() }; database.close(); context.deleteDatabase(name) }
  }

  @Test fun estimatedAdjustmentsAreTemporaryAndAccurateTimingsAreProtected() = runBlocking {
    val name = "estimate-${UUID.randomUUID()}.db"; val database = TorrentDatabase(context, name)
    val store = ReadAlongStore(database)
    val emptyModels = File(context.cacheDir, "empty-model-${UUID.randomUUID()}")
    val modelContext = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = emptyModels }
    val repository = ReadAlongRepository(context, store, AudioWindowDecoder { error("No model should decode") }, models = SpeechModelManager(modelContext))
    try {
      store.attach(track, StoredReadAlongBook("b", book(), "b.epub"), setOf(2))
      store.setOffset(track, 1500); store.setChapterHint(track, 1)
      withContext(Dispatchers.Main) { repository.activate(track) }; ready(repository)
      withContext(Dispatchers.Main) { repository.playback(0, 60_000); repository.setVisible(true) }
      withTimeout(10_000) { repository.state.first { it.phase == ReadAlongPhase.NEEDS_MODEL } }
      withContext(Dispatchers.Main) { repository.useEstimatedTiming(); repository.adjustOffset(5000) }
      assertEquals(TextSyncSource.EPUB_PACED, repository.state.value.source)
      assertEquals(6500L, repository.state.value.offsetMs); assertEquals(1500L, store.offset(track))
      withContext(Dispatchers.Main) { repository.setVisible(false); repository.useNarrationTiming() }
      ready(repository)
      assertEquals(TextSyncSource.NARRATION, repository.state.value.source)
      assertEquals(1500L, repository.state.value.offsetMs)
      val sentence = ReadAlongText.sentences(book(), "b").single()
      store.saveWindow(track, "b", 0, 20_000, listOf(TimedLine(sentence.id, 1000, 5000, sentence.text)))
      store.setTrackSelection(track, "b", setOf(2, 4))
      assertEquals(1, store.chapterHint(track)); assertEquals(1500L, store.offset(track)); assertEquals(1, store.timings(track, "b").size)
      withContext(Dispatchers.Main) { repository.activate(track.copy(fileIndex = 4)) }; ready(repository)
      withContext(Dispatchers.Main) { repository.activate(track) }; ready(repository)
      assertFalse(repository.state.value.canEstimate)
      val original = repository.state.value.lines
      withContext(Dispatchers.Main) { repository.useEstimatedTiming() }
      assertEquals(original, repository.state.value.lines); assertEquals(TextSyncSource.NARRATION, repository.state.value.source)
    } finally { withContext(Dispatchers.Main) { repository.close() }; database.close(); context.deleteDatabase(name) }
  }

  @Test fun chapterHintCannotEraseLrcOrSrtTimings() = runBlocking {
    val name = "transcript-${UUID.randomUUID()}.db"; val database = TorrentDatabase(context, name)
    val store = ReadAlongStore(database)
    val repository = ReadAlongRepository(context, store, AudioWindowDecoder { error("Transcript needs no decoder") })
    try {
      withContext(Dispatchers.Main) { repository.activate(track) }; ready(repository)
      for ((filename, text) in listOf("a.lrc" to "[00:01.00]A timestamped sentence.\n[00:03.00]Another sentence.", "a.srt" to "1\n00:00:01,000 --> 00:00:03,000\nA timestamped sentence.\n")) {
        withContext(Dispatchers.Main) { repository.importBytes(text.toByteArray(), filename, "text/plain", setOf(2)) }
        ready(repository)
        val before = repository.state.value
        assertFalse(before.canChooseChapter); assertTrue(before.lines.first().isTimed)
        withContext(Dispatchers.Main) { repository.chooseChapter(before.chapters.first()); repository.useEstimatedTiming() }
        assertEquals(before.lines, repository.state.value.lines); assertEquals(before.source, repository.state.value.source)
        assertNull(store.chapterHint(track))
      }
    } finally { withContext(Dispatchers.Main) { repository.close() }; database.close(); context.deleteDatabase(name) }
  }
}

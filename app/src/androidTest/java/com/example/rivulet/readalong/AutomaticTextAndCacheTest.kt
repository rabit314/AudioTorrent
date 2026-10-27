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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AutomaticTextAndCacheTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val track = ReadAlongTrack("plan016-public-fixture", 3)
  private val identity = BookIdentity("Public Fixture", "Test Author")
  private val edition = BookEdition(identity.title, identity.author, "English", "EPUB", null, "https://zlib.li/book/16")
  private val text = "The little boat moved slowly across the quiet water."
  private fun book() = StoredReadAlongBook("public-book", EpubBook(identity.title, identity.author,
    listOf(EpubChapter(1, "c", "c.xhtml", "One", text, 10))), "fixture.epub")
  private fun epub(): Triple<ByteArray, String, String> {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      fun entry(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
      entry("mimetype", "application/epub+zip")
      entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""")
      entry("book.opf", """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>Public Fixture</dc:title><dc:creator>Test Author</dc:creator></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""")
      entry("c.xhtml", "<html><body><p>$text</p></body></html>")
    }
    return Triple(bytes.toByteArray(), "fixture.epub", "application/epub+zip")
  }
  private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
  private suspend fun eventually(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(10) }
  private suspend fun loaded(r: ReadAlongRepository) = withTimeout(15_000) { r.state.first { it.phase == ReadAlongPhase.EMPTY || (it.bookId != null && it.phase == ReadAlongPhase.READY) } }
  private suspend fun fixture(catalog: BookCatalog, action: suspend (ReadAlongRepository, ReadAlongStore) -> Unit) {
    val name = "auto-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name)
    val r = ReadAlongRepository(context, ReadAlongStore(db), ReadAlongAudioDecoder { _, _, _ -> error("No audio required") }, library = catalog)
    try { main { r.activate(track); r.setVisible(true) }; loaded(r); action(r, ReadAlongStore(db)) }
    finally { main { r.close() }; db.close(); context.deleteDatabase(name) }
  }

  @Test fun clearMatchAutomaticallyDownloadsImportsAndAttachesOnlySelectedTracks() = runBlocking {
    val searches = AtomicInteger(); val downloads = AtomicInteger()
    val catalog = object : BookCatalog {
      override fun search(query: String): List<BookEdition> { searches.incrementAndGet(); assertEquals(identity.query, query); return listOf(edition) }
      override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> { downloads.incrementAndGet(); return epub() }
    }
    fixture(catalog) { r, store ->
      main { r.ensureBookText(identity, setOf(3, 4), track.copy(fileIndex = 9)) }
      assertEquals(0, searches.get())
      main { r.ensureBookText(identity, setOf(3, 4)); r.ensureBookText(identity, setOf(3, 4)) }
      eventually { r.state.value.bookId != null && r.state.value.lines.isNotEmpty() }
      assertEquals(identity.title, r.state.value.title); assertEquals(setOf(3, 4), store.associatedTracks(r.state.value.bookId!!))
      assertNull(store.book(track.copy(fileIndex = 9)))
      main { r.setVisible(false); r.setVisible(true); r.ensureBookText(identity, setOf(3, 4)) }
      assertEquals(1, searches.get()); assertEquals(1, downloads.get())
    }
  }

  @Test fun uncertainEditionRequiresChoiceAndDoesNotSearchAgainOnViewOrTrackReturn() = runBlocking {
    val searches = AtomicInteger(); val downloads = AtomicInteger()
    fixture(object : BookCatalog {
      override fun search(query: String): List<BookEdition> { searches.incrementAndGet(); return listOf(edition.copy(author = "Unknown")) }
      override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> { downloads.incrementAndGet(); return epub() }
    }) { r, _ ->
      main { r.ensureBookText(identity.copy(author = ""), setOf(3)) }
      eventually { r.state.value.choosingEdition }
      main { r.setVisible(false); r.setVisible(true); r.ensureBookText(identity.copy(author = ""), setOf(3)); r.activate(track.copy(fileIndex = 8)) }
      loaded(r); main { r.activate(track) }; loaded(r)
      main { r.ensureBookText(identity.copy(author = ""), setOf(3)) }
      assertTrue(r.state.value.choosingEdition); assertEquals(1, searches.get()); assertEquals(0, downloads.get())
    }
  }

  @Test fun browserAccessResumesSearchAndThenThePendingDownloadAcrossViewSwitch() = runBlocking {
    val searches = AtomicInteger(); val downloads = AtomicInteger()
    fixture(object : BookCatalog {
      override fun search(query: String): List<BookEdition> { searches.incrementAndGet(); throw SiteAccessRequired("https://zlib.li/s/Public") }
      override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> {
        if (downloads.incrementAndGet() == 1) throw SiteAccessRequired(edition.url)
        return epub()
      }
    }) { r, _ ->
      main { r.ensureBookText(identity, setOf(3)) }; eventually { r.state.value.accessUrl != null }
      main { r.resumeSiteAccess("""<z-bookcard href="/book/16" title="Public Fixture" author="Test Author" language="English" extension="epub"></z-bookcard>""", "https://zlib.li/s/Public", track) }
      eventually { r.state.value.accessUrl == edition.url }
      main { r.setVisible(false); r.setVisible(true); r.resumeSiteAccess("<html>Download</html>", edition.url, track) }
      eventually { r.state.value.bookId != null && r.state.value.lines.isNotEmpty() }
      assertEquals(1, searches.get()); assertEquals(2, downloads.get())
    }
  }

  @Test fun failedAutoDownloadHasTargetedRetryAndInvalidImportRecovery() = runBlocking {
    val downloads = AtomicInteger()
    fixture(object : BookCatalog {
      override fun search(query: String) = listOf(edition)
      override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> {
        if (downloads.incrementAndGet() == 1) throw IOException("Network unavailable")
        return Triple("<html>Not a book</html>".toByteArray(), "bad.epub", "application/epub+zip")
      }
    }) { r, _ ->
      main { r.ensureBookText(identity, setOf(3)) }; eventually { r.state.value.recoveryLabel == "Retry ebook download" }
      main { r.retry() }; eventually { r.state.value.recovery == ReadAlongRecovery.PICK_FILE }
      assertEquals(2, downloads.get()); assertNull(r.state.value.bookId)
    }
  }

  @Test fun manualImportAndTrackChangeRejectAnObsoleteAutomaticDownload() = runBlocking {
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    try {
      fixture(object : BookCatalog {
        override fun search(query: String) = listOf(edition)
        override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> {
          entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); return epub()
        }
      }) { r, store ->
        main { r.ensureBookText(identity, setOf(3)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        main { r.importBytes("A manually selected public book sentence.".toByteArray(), "manual.txt", "text/plain", setOf(3)) }
        eventually { r.state.value.bookId != null }
        val manual = r.state.value.bookId
        release.countDown(); delay(150)
        assertEquals(manual, store.book(track)!!.id)
        main { r.activate(track.copy(fileIndex = 9)); r.resumeSiteAccess("<html></html>", edition.url, track) }
        loaded(r); assertNull(r.state.value.bookId)
      }
    } finally { release.countDown() }
  }

  @Test fun cachedFarPositionSurvivesDatabaseReopenWithZeroDecodeOrRecognition() = runBlocking {
    val name = "cached-${UUID.randomUUID()}.db"; val calls = AtomicInteger()
    try {
      repeat(2) { pass ->
        val db = TorrentDatabase(context, name); val store = ReadAlongStore(db)
        if (pass == 0) {
          val b = book(); store.attach(track, b, setOf(3)); val sentence = ReadAlongText.sentences(b.book, b.id).single()
          store.saveWindow(track, b.id, 3_600_000, 3_620_000, listOf(TimedLine(sentence.id, 3_601_000, 3_605_000, text)))
          store.saveWindow(track, b.id, 3_620_000, 3_640_000, emptyList()); store.setOffset(track, 500)
        }
        val r = ReadAlongRepository(context, store, ReadAlongAudioDecoder { _, _, _ -> calls.incrementAndGet(); error("Must use cache") },
          speech = SpeechRecognizer { _, _ -> calls.incrementAndGet(); error("Must use cache") })
        try {
          main { r.activate(track, 3_602_000, 4_000_000); r.setVisible(true) }; loaded(r)
          main { r.playback(0, 0, track.copy(fileIndex = 9)) }
          assertEquals(0, r.state.value.activeLineIndex)
          main { r.playback(3_602_000, 4_000_000, track); r.setVisible(false); r.setVisible(true) }
          delay(200); assertEquals(0, calls.get()); assertEquals(ReadAlongPhase.READY, r.state.value.phase)
          assertNull(r.state.value.progressLabel); assertEquals(500L, r.state.value.offsetMs)
        } finally { main { r.close() }; db.close() }
      }
    } finally { context.deleteDatabase(name) }
  }

  @Test fun boundedCurrentWorkFinishesHiddenAndSeekStartsNearNewPosition() = runBlocking {
    val name = "scheduler-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name); val store = ReadAlongStore(db)
    val files = File(context.cacheDir, "scheduler-${UUID.randomUUID()}")
    val models = SpeechModelManager(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = files })
    models.file.parentFile!!.mkdirs(); models.file.writeBytes(byteArrayOf(1))
    val starts = CopyOnWriteArrayList<Long>(); val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
    val r = ReadAlongRepository(context, store, ReadAlongAudioDecoder { _, start, _ -> starts.add(start); FloatArray(16_000) },
      speech = SpeechRecognizer { _, _ -> if (!entered.isCompleted) { entered.complete(Unit); finish.await() }; emptyList() }, models = models)
    try {
      store.attach(track, book(), setOf(3))
      main { r.activate(track, 1_000_000, 2_000_000); r.setVisible(true) }; loaded(r)
      delay(100); assertTrue(starts.isEmpty()) // A restored position alone cannot start analysis.
      main { r.playback(1_000_000, 2_000_000, track) }; withTimeout(10_000) { entered.await() }
      main { r.setVisible(false) }; finish.complete(Unit)
      eventually { store.windowReady(track, "public-book", 1_000_000) }
      delay(150); assertEquals(listOf(995_000L), starts.toList())
      main { r.setVisible(true) }; eventually { store.windowReady(track, "public-book", 1_020_000) }
      delay(150); assertEquals(2, starts.size)
      main { r.playbackDiscontinuity(); r.playback(1_600_000, 2_000_000, track) }
      eventually { store.windowReady(track, "public-book", 1_620_000) }
      assertEquals(listOf(995_000L, 1_015_000L, 1_595_000L, 1_615_000L), starts.toList())
      val sentence = ReadAlongText.sentences(book().book, "public-book").single()
      store.saveWindow(track, "public-book", 1_600_000, 1_620_000, listOf(TimedLine(sentence.id, 1_601_000, 1_605_000, text)))
      store.setOffset(track, 1500)
      main { r.retryCurrentPassage() }
      eventually { starts.size == 5 && store.windowReady(track, "public-book", 1_600_000) }
      assertEquals(1_595_000L, starts.last())
      assertEquals(1_601_000L, store.timings(track, "public-book").single().startTimeMs)
      assertEquals(1500L, store.offset(track))
    } finally { finish.complete(Unit); main { r.close() }; db.close(); context.deleteDatabase(name); files.deleteRecursively() }
  }

  @Test fun rapidSeeksCancelObsoleteWindowsAndOnlySaveTheLatestPassage() = runBlocking {
    val name = "seek-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name); val store = ReadAlongStore(db)
    val files = File(context.cacheDir, "seek-${UUID.randomUUID()}")
    val models = SpeechModelManager(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = files })
    models.file.parentFile!!.mkdirs(); models.file.writeBytes(byteArrayOf(1))
    val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); val calls = AtomicInteger()
    val r = ReadAlongRepository(context, store, ReadAlongAudioDecoder { _, _, _ -> FloatArray(1600) },
      speech = SpeechRecognizer { _, _ ->
        if (calls.incrementAndGet() == 1) {
          entered.complete(Unit)
          try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        emptyList()
      }, models = models)
    try {
      store.attach(track, book(), setOf(3))
      main { r.activate(track) }; loaded(r)
      main { r.playback(1_000_000, 2_000_000, track); r.setVisible(true) }
      withTimeout(10_000) { entered.await() }
      main {
        r.playbackDiscontinuity(); r.playback(1_400_000, 2_000_000, track)
        r.playbackDiscontinuity(); r.playback(1_600_000, 2_000_000, track)
      }
      withTimeout(10_000) { cancelled.await() }
      eventually { store.windowReady(track, "public-book", 1_620_000) }
      assertFalse(store.windowReady(track, "public-book", 1_000_000))
      assertFalse(store.windowReady(track, "public-book", 1_400_000))
      assertTrue(store.windowReady(track, "public-book", 1_600_000))
    } finally { main { r.close() }; db.close(); context.deleteDatabase(name); files.deleteRecursively() }
  }
}

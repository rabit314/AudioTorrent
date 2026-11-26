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
    val prefs = ReaderPreferences(context); val previousMode = prefs.syncMode.value
    prefs.setSyncMode(SyncMode.BALANCED)
    try {
      repeat(2) { pass ->
        val db = TorrentDatabase(context, name); val store = ReadAlongStore(db)
        if (pass == 0) {
          val b = book(); store.attach(track, b, setOf(3)); val sentence = ReadAlongText.sentences(b.book, b.id).single()
          for (windowStart in 3_600_000L..3_760_000L step 20_000L) {
            val lines = if (windowStart == 3_600_000L) listOf(TimedLine(sentence.id, 3_601_000, 3_605_000, text)) else emptyList()
            store.saveWindow(track, b.id, windowStart, windowStart + 20_000L, lines)
          }
          store.setOffset(track, 500)
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
    } finally { prefs.setSyncMode(previousMode); context.deleteDatabase(name) }
  }

  @Test fun boundedCurrentWorkFinishesHiddenAndSeekStartsNearNewPosition() = runBlocking {
    val name = "scheduler-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name); val store = ReadAlongStore(db)
    val files = File(context.cacheDir, "scheduler-${UUID.randomUUID()}")
    val models = SpeechModelManager(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = files })
    models.file.parentFile!!.mkdirs(); models.file.writeBytes(byteArrayOf(1))
    val prefs = ReaderPreferences(context); val previousMode = prefs.syncMode.value
    prefs.setSyncMode(SyncMode.PLAYHEAD_ONLY)
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
      delay(150); assertEquals(listOf(998_500L), starts.toList())
      main { r.setVisible(true) }; eventually { store.windowReady(track, "public-book", 1_040_000) }
      delay(150); assertEquals(3, starts.size)
      main { r.playbackDiscontinuity(); r.playback(1_600_000, 2_000_000, track) }
      eventually { store.windowReady(track, "public-book", 1_640_000) }
      assertEquals(listOf(998_500L, 1_018_500L, 1_038_500L, 1_598_500L, 1_618_500L, 1_638_500L), starts.toList())
      val sentence = ReadAlongText.sentences(book().book, "public-book").single()
      store.saveWindow(track, "public-book", 1_600_000, 1_620_000, listOf(TimedLine(sentence.id, 1_601_000, 1_605_000, text)))
      store.setOffset(track, 1500)
      main { r.retryCurrentPassage() }
      eventually { starts.size == 7 && store.windowReady(track, "public-book", 1_600_000) }
      assertEquals(1_598_500L, starts.last())
      assertEquals(1_601_000L, store.timings(track, "public-book").single().startTimeMs)
      assertEquals(1500L, store.offset(track))
    } finally { prefs.setSyncMode(previousMode); finish.complete(Unit); main { r.close() }; db.close(); context.deleteDatabase(name); files.deleteRecursively() }
  }

  @Test fun lookaheadPrefetchesNextWindowConcurrentlyAndHonorsLegacyModels() = runBlocking {
    val name = "prefetch-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name); val store = ReadAlongStore(db)
    val files = File(context.cacheDir, "prefetch-${UUID.randomUUID()}")
    val models = SpeechModelManager(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = files })
    // Verify legacy base.en-q5_1 fallback is preserved until Fast is downloaded.
    models.legacyFile.parentFile!!.mkdirs(); models.legacyFile.writeBytes(byteArrayOf(2))
    assertTrue(models.isInstalled(SpeechModelTier.FAST))
    assertEquals(models.legacyFile, models.activeFile(SpeechModelTier.FAST))
    assertTrue(models.isUsingLegacyFallback(SpeechModelTier.FAST))
    models.fastFile.writeBytes(byteArrayOf(1))
    assertEquals(models.fastFile, models.activeFile(SpeechModelTier.FAST))
    assertFalse(models.isUsingLegacyFallback(SpeechModelTier.FAST))

    val prefs = ReaderPreferences(context); val previousMode = prefs.syncMode.value
    prefs.setSyncMode(SyncMode.BALANCED)
    val prefetchedConcurrent = CompletableDeferred<Unit>()
    val lookaheadEntered = CompletableDeferred<Unit>()
    val observedThreads = CopyOnWriteArrayList<Int>()
    val decodedStarts = CopyOnWriteArrayList<Long>()
    val r = ReadAlongRepository(
      context,
      store,
      ReadAlongAudioDecoder { _, start, _ ->
        decodedStarts.add(start)
        if (start == 38_500L) {
          withTimeout(5_000) { lookaheadEntered.await() }
          prefetchedConcurrent.complete(Unit)
        }
        FloatArray(16_000)
      },
      speech = object : SpeechRecognizer {
        override suspend fun transcribe(model: File, pcm: FloatArray): List<SpeechWord> = emptyList()
        override suspend fun transcribe(model: File, pcm: FloatArray, nThreads: Int): List<SpeechWord> {
          observedThreads.add(nThreads)
          if (observedThreads.size == 2) {
            lookaheadEntered.complete(Unit)
            withTimeout(5_000) { prefetchedConcurrent.await() }
          }
          return emptyList()
        }
        override suspend fun transcribeProgressive(model: File, pcm: FloatArray, focusMs: Long, nThreads: Int, onPartial: suspend (List<SpeechWord>) -> Unit): List<SpeechWord> {
          observedThreads.add(nThreads)
          onPartial(emptyList())
          return emptyList()
        }
      },
      models = models,
    )
    try {
      store.attach(track, book(), setOf(3))
      main { r.activate(track, 0, 120_000); r.setVisible(true) }; loaded(r)
      main { r.playback(0, 120_000, track, prepared = true, isPlaying = true, speed = 2.0f) }
      withTimeout(10_000) { prefetchedConcurrent.await() }
      assertTrue(observedThreads.isNotEmpty() && observedThreads.all { it == 4 })
      assertTrue(decodedStarts.contains(38_500L))
    } finally { prefs.setSyncMode(previousMode); main { r.close() }; db.close(); context.deleteDatabase(name); files.deleteRecursively() }
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

  @Test fun pausedLookaheadHonorsSyncModesAcrossTiersAndFullTrackWorksInBackground() = runBlocking {
    val name = "paused-tiers-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name); val store = ReadAlongStore(db)
    val files = File(context.cacheDir, "paused-tiers-${UUID.randomUUID()}")
    val models = SpeechModelManager(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = files })
    models.fastFile.parentFile!!.mkdirs(); models.fastFile.writeBytes(byteArrayOf(1))
    models.accurateFile.writeBytes(byteArrayOf(2))
    val prefs = ReaderPreferences(context); val previousMode = prefs.syncMode.value; val previousTier = prefs.speechModelTier.value
    val observedModels = CopyOnWriteArrayList<String>()
    val r = ReadAlongRepository(
      context,
      store,
      ReadAlongAudioDecoder { _, _, _ -> FloatArray(1600) },
      speech = object : SpeechRecognizer {
        override suspend fun transcribe(model: File, pcm: FloatArray) = emptyList<SpeechWord>()
        override suspend fun transcribe(model: File, pcm: FloatArray, nThreads: Int): List<SpeechWord> {
          observedModels.add(model.name)
          return emptyList()
        }
        override suspend fun transcribeProgressive(model: File, pcm: FloatArray, focusMs: Long, nThreads: Int, onPartial: suspend (List<SpeechWord>) -> Unit): List<SpeechWord> {
          observedModels.add(model.name)
          onPartial(emptyList())
          return emptyList()
        }
      },
      models = models,
    )
    try {
      store.attach(track, book(), setOf(3))
      main {
        r.setSpeechModelTier(SpeechModelTier.FAST)
        r.setSyncMode(SyncMode.PLAYHEAD_ONLY)
        r.activate(track, 0, 80_000)
        r.setVisible(true)
      }
      loaded(r)
      // Paused in PLAYHEAD_ONLY syncs only window 0 and goes idle.
      main { r.playback(0, 80_000, track, prepared = true, isPlaying = false) }
      eventually { store.windowReady(track, "public-book", 0) && r.state.value.syncCoverage?.isSyncingNow == false }
      delay(120)
      assertFalse(store.windowReady(track, "public-book", 20_000))

      // Switching to BALANCED while still paused syncs the ahead windows cleanly using ACCURATE tier too.
      main { r.setSpeechModelTier(SpeechModelTier.ACCURATE); r.setSyncMode(SyncMode.BALANCED) }
      eventually {
        store.windowReady(track, "public-book", 20_000) &&
          store.windowReady(track, "public-book", 40_000) &&
          store.windowReady(track, "public-book", 60_000)
      }
      assertTrue(observedModels.contains(SpeechModelTier.FAST.fileName))
      assertTrue(observedModels.contains(SpeechModelTier.ACCURATE.fileName))

      // Switching to FULL_TRACK while hidden (Cover tab) and paused completes remaining windows.
      main { r.setVisible(false); r.playback(0, 100_000, track, prepared = true, isPlaying = false); r.setSyncMode(SyncMode.FULL_TRACK) }
      eventually { store.windowReady(track, "public-book", 80_000) }
    } finally {
      prefs.setSyncMode(previousMode)
      prefs.setSpeechModelTier(previousTier)
      main { r.close() }; db.close(); context.deleteDatabase(name); files.deleteRecursively()
    }
  }

  @Test fun playheadHysteresisSyncsOneMinuteRestsUntilFortySecondsThenRefills() = runBlocking {
    val name = "hysteresis-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name); val store = ReadAlongStore(db)
    val files = File(context.cacheDir, "hysteresis-${UUID.randomUUID()}")
    val models = SpeechModelManager(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = files })
    models.fastFile.parentFile!!.mkdirs(); models.fastFile.writeBytes(byteArrayOf(1))
    val prefs = ReaderPreferences(context); val previousMode = prefs.syncMode.value
    val decodedCount = AtomicInteger()
    val r = ReadAlongRepository(
      context,
      store,
      ReadAlongAudioDecoder { _, _, _ -> decodedCount.incrementAndGet(); FloatArray(1600) },
      speech = SpeechRecognizer { _, _ -> emptyList() },
      models = models,
    )
    try {
      store.attach(track, book(), setOf(3))
      main {
        r.setSyncMode(SyncMode.PLAYHEAD_ONLY)
        r.activate(track, 0, 600_000)
        r.setVisible(true)
        r.playback(0, 600_000, track, prepared = true, isPlaying = true)
      }
      loaded(r)
      // Syncs 3 windows up to 60_000L (1 min ahead) and goes idle.
      eventually {
        store.windowReady(track, "public-book", 0) &&
          store.windowReady(track, "public-book", 20_000) &&
          store.windowReady(track, "public-book", 40_000) &&
          r.state.value.syncCoverage?.isSyncingNow == false
      }
      val callsAtOneMin = decodedCount.get()
      assertEquals(3, callsAtOneMin)
      assertFalse(store.windowReady(track, "public-book", 60_000))

      // Advances to 15s (45s ahead remaining > 40s threshold): stays completely idle.
      main { r.playback(15_000, 600_000, track, prepared = true, isPlaying = true) }
      delay(150)
      assertEquals(callsAtOneMin, decodedCount.get())
      assertFalse(store.windowReady(track, "public-book", 60_000))

      // Advances to 20s (40s ahead remaining): wakes up and refills back to 1 min (60_000..80_000).
      main { r.playback(20_000, 600_000, track, prepared = true, isPlaying = true) }
      eventually { store.windowReady(track, "public-book", 60_000) && r.state.value.syncCoverage?.isSyncingNow == false }
      assertEquals(4, decodedCount.get())
    } finally {
      prefs.setSyncMode(previousMode)
      main { r.close() }; db.close(); context.deleteDatabase(name); files.deleteRecursively()
    }
  }
}

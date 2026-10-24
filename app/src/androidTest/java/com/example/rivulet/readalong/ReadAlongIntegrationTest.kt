package com.example.rivulet.readalong

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class ReadAlongIntegrationTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private fun book(title: String) = EpubBook(title, "Test author", listOf(
    EpubChapter(1, "c1", "c1.xhtml", "One", "The little boat moved slowly across the quiet water.", 10),
    EpubChapter(2, "c2", "c2.xhtml", "Two", "A bright moon appeared above the distant hills.", 9),
  ))

  @Test fun scopesCorrectionsCachedWindowsAndReplacementSurviveReopen() {
    val name = "readalong-test-${UUID.randomUUID()}.db"
    var database = TorrentDatabase(context, name)
    val a = ReadAlongTrack("fixture", 2); val b = ReadAlongTrack("fixture", 8)
    try {
      var store = ReadAlongStore(database)
      store.attach(a, StoredReadAlongBook("a", book("First"), "a.epub"), setOf(2, 5))
      store.attach(b, StoredReadAlongBook("b", book("Second"), "b.epub"), setOf(8))
      store.setOffset(a, 5000)
      store.saveWindow(a, "a", 0, 20_000, listOf(TimedLine(1, 500, 1500, "A line")))
      database.close(); database = TorrentDatabase(context, name); store = ReadAlongStore(database)
      assertEquals("First", store.book(a)!!.book.title)
      assertEquals("Second", store.book(b)!!.book.title)
      assertEquals(2, store.book(a)!!.book.chapters.size)
      assertEquals(5000L, store.offset(a))
      assertTrue(store.windowReady(a, "a", 1000))
      store.attach(a, StoredReadAlongBook("replacement", book("Replacement"), "new.epub"), setOf(2))
      store.saveWindow(a, "a", 0, 20_000, listOf(TimedLine(1, 500, 1500, "Stale line")))
      assertTrue(store.timings(a, "replacement").isEmpty())
      assertFalse(store.windowReady(a, "replacement", 1000))
      assertEquals(0L, store.offset(a))
      assertEquals("First", store.book(ReadAlongTrack("fixture", 5))!!.book.title)
      assertEquals("Second", store.book(b)!!.book.title)
    } finally { database.close(); context.deleteDatabase(name) }
  }

  @Test fun v7MigrationPreservesLegacyBookAndAllChapters() {
    val name = "readalong-migration-${UUID.randomUUID()}.db"
    val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
    old.execSQL("CREATE TABLE companion_books(info_hash TEXT PRIMARY KEY,title TEXT,author TEXT,isbn TEXT,file_name TEXT)")
    old.execSQL("CREATE TABLE companion_chapters(info_hash TEXT,chapter_index INTEGER,title TEXT,content TEXT,word_count INTEGER,mapped_file_index INTEGER)")
    old.execSQL("CREATE TABLE playback_history(info_hash TEXT,file_index INTEGER)")
    old.execSQL("INSERT INTO companion_books VALUES('hash','Legacy book','Author',NULL,'legacy.epub')")
    old.execSQL("INSERT INTO companion_chapters VALUES('hash',1,'First','First chapter.',2,4)")
    old.execSQL("INSERT INTO companion_chapters VALUES('hash',2,'Second','Second chapter.',2,NULL)")
    old.version = 7; old.close()
    val database = TorrentDatabase(context, name)
    try {
      val saved = ReadAlongStore(database).book(ReadAlongTrack("hash", 4))!!
      assertEquals("Legacy book", saved.book.title)
      assertEquals(2, saved.book.chapters.size)
      assertEquals(8, database.readableDatabase.version)
    } finally { database.close(); context.deleteDatabase(name) }
  }

  @Test fun opaqueProviderImportUsesDisplayNameAndTrackSwitchRejectsStaleState() = runBlocking {
    val name = "readalong-provider-${UUID.randomUUID()}.db"
    val database = TorrentDatabase(context, name)
    val repository = ReadAlongRepository(context, ReadAlongStore(database), AudioWindowDecoder { error("Not used") })
    try {
      withContext(Dispatchers.Main) {
        repository.activate(ReadAlongTrack("provider", 3))
        repository.importDocument(Uri.parse("content://com.example.rivulet.test.documents/opaque/837"), setOf(3))
      }
      val loaded = try { withTimeout(20_000) { repository.state.first { it.bookId != null || it.phase == ReadAlongPhase.ERROR } } }
      catch (error: Exception) { throw AssertionError("Import stalled in ${repository.state.value}", error) }
      assertEquals(loaded.message, ReadAlongPhase.READY, loaded.phase)
      assertEquals("A real book", loaded.title)
      assertTrue(loaded.lines.isNotEmpty())
      withContext(Dispatchers.Main) {
        repository.importBytes("New text with a different sentence.".toByteArray(), "new.txt", "text/plain", setOf(3))
        repository.activate(ReadAlongTrack("provider", 9))
      }
      val next = try { withTimeout(20_000) { repository.state.first { it.track?.fileIndex == 9 && it.phase == ReadAlongPhase.EMPTY } } }
      catch (error: Exception) { throw AssertionError("Track switch stalled in ${repository.state.value}", error) }
      assertNull(next.bookId)
      assertTrue(next.lines.isEmpty())
    } finally { withContext(Dispatchers.Main) { repository.close() }; database.close(); context.deleteDatabase(name) }
  }

  @Test fun realPcmDecodingAndNativeSpeechMatchReviewedSentenceStart() = runBlocking {
    val fixture = File(context.cacheDir, "readalong-jfk.wav")
    InstrumentationRegistry.getInstrumentation().context.assets.open("readalong/jfk.wav").use { input -> fixture.outputStream().use { input.copyTo(it) } }
    val decoder = AudioWindowDecoder {
      object : MediaDataSource() {
        val file = RandomAccessFile(fixture, "r")
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
          if (position >= file.length()) return -1
          file.seek(position); return file.read(buffer, offset, minOf(size.toLong(), file.length() - position).toInt())
        }
        override fun getSize() = file.length()
        override fun close() = file.close()
      }
    }
    try {
      val model = SpeechModelManager(context).file
      assertTrue("Install the checksum-pinned model before this integration test", model.isFile)
      val began = SystemClock.elapsedRealtime()
      val pcm = decoder.decode(ReadAlongTrack("fixture", 0), 0, 11_000)
      assertTrue(pcm.size in 160_000..176_000)
      val words = WhisperEngine().transcribe(model, pcm)
      val transcript = "And so my fellow Americans ask not what your country can do for you ask what you can do for your country."
      val sentences = listOf(BookSentence(1, 1, "JFK inaugural speech", transcript))
      val lines = NarrationMatcher(sentences).align(words)
      Log.i("ReadAlongFixture", "audioMs=11000 elapsedMs=${SystemClock.elapsedRealtime()-began} pssKb=${Debug.getPss()} recognized=${words.size} matched=${lines.size} startMs=${lines.firstOrNull()?.startTimeMs}")
      assertEquals("Public-domain JFK sentence should match", 1, lines.size)
      // Reference onset from the upstream sample's published word-timestamp example: about 0.32s.
      assertTrue("Sentence start must be within one second", kotlin.math.abs(lines.first().startTimeMs - 320) <= 1000)
      val unrelated = NarrationMatcher(listOf(BookSentence(2, 1, "Other", "A bright moon appeared above the distant hills.")))
      assertTrue(unrelated.align(words).isEmpty())
    } finally { fixture.delete() }
  }
}

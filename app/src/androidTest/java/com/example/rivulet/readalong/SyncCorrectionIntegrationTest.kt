package com.example.rivulet.readalong

import android.content.Context
import android.content.ContextWrapper
import android.media.MediaDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SyncCorrectionIntegrationTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val track = ReadAlongTrack("correction-fixture", 0)
  private val sentence = "The little boat moved slowly across the quiet water."
  private fun book() = EpubBook("Correction fixture", "Author", listOf(
    EpubChapter(1, "a", "a", "First", sentence, 10), EpubChapter(2, "b", "b", "Second", sentence, 10)))
  private fun words() = ReadAlongText.tokens(sentence).mapIndexed { i, word -> SpeechWord(word, 1000 + i * 250L, 1250 + i * 250L) }
  private suspend fun ready(r: ReadAlongRepository) = withTimeout(10_000) { r.state.first { it.phase == ReadAlongPhase.READY && it.lines.isNotEmpty() } }
  private suspend fun finished(r: ReadAlongRepository, timeoutMs: Long = 15_000) = withTimeout(timeoutMs) { r.state.first { it.correction.phase in setOf(SyncCorrectionPhase.APPLIED, SyncCorrectionPhase.ERROR) } }
  private suspend fun eventually(check: () -> Boolean) = withTimeout(10_000) { while (!check()) delay(10) }

  private suspend fun fixture(installed: Boolean = true, speech: SpeechRecognizer = SpeechRecognizer { _, _ -> words() }, realModel: Boolean = false,
    action: suspend (ReadAlongRepository, ReadAlongStore) -> Unit) {
    val name = "sync-${UUID.randomUUID()}.db"
    val database = TorrentDatabase(context, name)
    val store = ReadAlongStore(database)
    val files = File(context.cacheDir, "sync-${UUID.randomUUID()}").apply { mkdirs() }
    val modelContext = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = files }
    val models = SpeechModelManager(if (realModel) context else modelContext)
    if (installed && !realModel) { models.file.parentFile!!.mkdirs(); models.file.writeBytes(byteArrayOf(1)) }
    val wav = File(files, "fixture.wav")
    InstrumentationRegistry.getInstrumentation().context.assets.open("readalong/jfk.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
    val decoder = AudioWindowDecoder {
      object : MediaDataSource() {
        val file = RandomAccessFile(wav, "r")
        override fun getSize() = file.length()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
          if (position >= file.length()) return -1
          file.seek(position); return file.read(buffer, offset, minOf(size.toLong(), file.length() - position).toInt())
        }
        override fun close() = file.close()
      }
    }
    val r = ReadAlongRepository(context, store, decoder, speech, models)
    try { action(r, store) }
    finally { withContext(Dispatchers.Main) { r.close() }; delay(100); database.close(); context.deleteDatabase(name); files.deleteRecursively() }
  }

  @Test fun timedCorrectionUsesFreshReferenceSupportsUndoResetAndRestart() = runBlocking { fixture { r, store ->
    withContext(Dispatchers.Main) {
      r.activate(track)
      r.importBytes("[00:01.00]First sentence.\n[00:04.00]Second sentence.".toByteArray(), "fixture.lrc", "text/plain", setOf(0))
    }
    ready(r)
    withContext(Dispatchers.Main) {
      r.playback(900, 11_000); r.beginCorrection(); r.selectCorrectionSentence(1)
      r.syncNow(SyncReference(r.state.value.correction.target!!, 8250, 11_000))
    }
    val applied = finished(r)
    assertEquals(SyncCorrectionPhase.APPLIED, applied.correction.phase)
    assertEquals(4250L, applied.offsetMs); assertEquals(1, applied.activeLineIndex)
    eventually { store.offset(track) == 4250L }
    assertEquals(4000L, applied.lines[1].startTimeMs)
    withContext(Dispatchers.Main) { r.undoSyncCorrection() }
    eventually { store.offset(track) == 0L }
    withContext(Dispatchers.Main) {
      r.beginCorrection(1); r.syncNow(SyncReference(r.state.value.correction.target!!, 1500, 11_000))
    }
    finished(r); eventually { store.offset(track) == -2500L }
    withContext(Dispatchers.Main) { r.activate(track.copy(fileIndex = 9)); r.activate(track) }
    ready(r)
    assertEquals(-2500L, r.state.value.offsetMs)
    withContext(Dispatchers.Main) { r.resetSyncCorrection() }
    eventually { store.offset(track) == 0L }
    assertEquals(0L, r.state.value.offsetMs)
  } }

  @Test fun guidedRematchUsesSelectedOccurrenceAndPreservesConfidentCacheAndHintUndo() = runBlocking { fixture { r, store ->
    val stored = StoredReadAlongBook("book", book(), "fixture.epub")
    val lines = ReadAlongText.sentences(stored.book, stored.id)
    store.attach(track, stored, setOf(0)); store.setChapterHint(track, 1)
    store.saveWindow(track, "book", 0, 20_000, listOf(TimedLine(lines[0].id, 9000, 10_000, sentence)))
    withContext(Dispatchers.Main) { r.activate(track) }; ready(r)
    withContext(Dispatchers.Main) { r.beginCorrection(lines[1].id); r.syncNow(SyncReference(r.state.value.correction.target!!, 5000, 11_000)) }
    val applied = finished(r)
    assertEquals(applied.correction.message, SyncCorrectionPhase.APPLIED, applied.correction.phase)
    assertEquals(4000L, applied.offsetMs)
    eventually { store.offset(track) == 4000L && store.chapterHint(track) == 2 }
    assertEquals(9000L, store.timings(track, "book").first { it.id == lines[0].id }.startTimeMs)
    assertEquals(1000L, store.timings(track, "book").first { it.id == lines[1].id }.startTimeMs)
    withContext(Dispatchers.Main) { r.undoSyncCorrection() }
    eventually { store.offset(track) == 0L && store.chapterHint(track) == 1 }
  } }

  @Test fun failedMatchKeepsCorrectionAndRetryUsesOriginalReference() = runBlocking {
    val calls = AtomicInteger()
    fixture(speech = SpeechRecognizer { _, _ -> if (calls.incrementAndGet() == 1) throw IOException("Pieces temporarily unavailable") else words() }) { r, store ->
      store.attach(track, StoredReadAlongBook("book", book(), "fixture.epub"), setOf(0)); store.setOffset(track, 1750)
      withContext(Dispatchers.Main) { r.activate(track) }; ready(r)
      withContext(Dispatchers.Main) { r.beginCorrection(r.state.value.lines.last().id); r.syncNow(SyncReference(r.state.value.correction.target!!, 5000, 11_000)) }
      val failure = finished(r)
      assertEquals(SyncCorrectionPhase.ERROR, failure.correction.phase); assertTrue(failure.correction.canRetry)
      assertEquals(1750L, failure.offsetMs); assertTrue(store.timings(track, "book").isEmpty())
      withContext(Dispatchers.Main) { r.playback(9000, 11_000); r.retryCorrection() }
      val success = finished(r)
      assertEquals(success.correction.message, SyncCorrectionPhase.APPLIED, success.correction.phase)
      assertEquals(4000L, success.offsetMs); eventually { store.offset(track) == 4000L }
    }
  }

  @Test fun seekAndTrackChangesRejectLateMatchesAndDuplicateSubmissions() = runBlocking {
    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val calls = AtomicInteger()
    fixture(speech = SpeechRecognizer { _, _ -> calls.incrementAndGet(); entered.complete(Unit); withContext(NonCancellable) { release.await() }; words() }) { r, store ->
      store.attach(track, StoredReadAlongBook("book", book(), "fixture.epub"), setOf(0))
      withContext(Dispatchers.Main) { r.activate(track) }; ready(r)
      withContext(Dispatchers.Main) {
        r.beginCorrection(r.state.value.lines.last().id)
        val ref = SyncReference(r.state.value.correction.target!!, 5000, 11_000)
        r.syncNow(ref); r.syncNow(ref)
      }
      try {
        withTimeout(10_000) { entered.await() }
        assertEquals(1, calls.get())
        withContext(Dispatchers.Main) { r.playbackDiscontinuity() }
        assertEquals(SyncCorrectionPhase.SELECTING, r.state.value.correction.phase)
        withContext(Dispatchers.Main) { r.activate(track.copy(fileIndex = 9)) }
      } finally { release.complete(Unit) }
      delay(200)
      assertEquals(0L, store.offset(track)); assertTrue(store.timings(track, "book").isEmpty())
      assertEquals(9, r.state.value.track!!.fileIndex); assertFalse(r.state.value.canUndoCorrection)
    }
  }

  @Test fun missingModelRequiresFreshSyncAndNeverInventsTimings() = runBlocking {
    val calls = AtomicInteger()
    fixture(installed = false, speech = SpeechRecognizer { _, _ -> calls.incrementAndGet(); words() }) { r, store ->
      store.attach(track, StoredReadAlongBook("book", book(), "fixture.epub"), setOf(0))
      withContext(Dispatchers.Main) { r.activate(track) }; ready(r)
      withContext(Dispatchers.Main) { r.beginCorrection(r.state.value.lines.last().id); r.syncNow(SyncReference(r.state.value.correction.target!!, 5000, 11_000)) }
      val failed = finished(r)
      assertTrue(failed.correction.needsModel); assertFalse(failed.correction.canRetry)
      withContext(Dispatchers.Main) { r.retryCorrection() }
      assertEquals(0, calls.get()); assertTrue(store.timings(track, "book").isEmpty()); assertEquals(0L, store.offset(track))
    }
  }

  @Test fun unrelatedRecognizedPassageCannotApplyACorrection() = runBlocking {
    fixture(speech = SpeechRecognizer { _, _ -> ReadAlongText.tokens("Please visit our website for a complete list of recordings and information.").mapIndexed { i, word -> SpeechWord(word, i * 250L, (i + 1) * 250L) } }) { r, store ->
      store.attach(track, StoredReadAlongBook("book", book(), "fixture.epub"), setOf(0)); store.setOffset(track, 1250)
      withContext(Dispatchers.Main) { r.activate(track) }; ready(r)
      withContext(Dispatchers.Main) { r.beginCorrection(r.state.value.lines.last().id); r.syncNow(SyncReference(r.state.value.correction.target!!, 5000, 11_000)) }
      val failed = finished(r)
      assertEquals(SyncCorrectionPhase.ERROR, failed.correction.phase)
      assertEquals(1250L, failed.offsetMs); assertEquals(1250L, store.offset(track)); assertTrue(store.timings(track, "book").isEmpty())
    }
  }

  @Test fun automaticOverlappingWindowKeepsExistingBaseTimestamp() = runBlocking { fixture { r, store ->
    val stored = StoredReadAlongBook("book", book(), "fixture.epub")
    val id = ReadAlongText.sentences(stored.book, stored.id).first().id
    store.attach(track, stored, setOf(0)); store.setChapterHint(track, 1)
    store.saveWindow(track, "book", 5000, 25_000, listOf(TimedLine(id, 9000, 10_000, sentence)))
    withContext(Dispatchers.Main) { r.activate(track) }; ready(r)
    withContext(Dispatchers.Main) { r.playback(1000, 11_000); r.setVisible(true) }
    withTimeout(15_000) { r.state.first { it.alignmentAttempted } }
    withContext(Dispatchers.Main) { r.setVisible(false) }
    assertEquals(9000L, r.state.value.lines.first().startTimeMs)
    assertEquals(9000L, store.timings(track, "book").first().startTimeMs)
  } }

  @Test fun successfulCorrectionRecoversAutomaticMatchingAfterAnEarlierFailure() = runBlocking {
    val calls = AtomicInteger()
    fixture(speech = SpeechRecognizer { _, _ -> if (calls.incrementAndGet() == 1) throw IOException("Temporary read failure") else words() }) { r, store ->
      val stored = StoredReadAlongBook("book", book(), "fixture.epub")
      val id = ReadAlongText.sentences(stored.book, stored.id).first().id
      store.attach(track, stored, setOf(0)); store.setChapterHint(track, 1)
      store.saveWindow(track, "book", 5000, 25_000, listOf(TimedLine(id, 9000, 10_000, sentence)))
      withContext(Dispatchers.Main) { r.activate(track) }; ready(r)
      withContext(Dispatchers.Main) { r.playback(1000, 11_000); r.setVisible(true) }
      withTimeout(15_000) { r.state.first { it.recoveryLabel == "Retry matching" } }
      withContext(Dispatchers.Main) { r.beginCorrection(id); r.syncNow(SyncReference(r.state.value.correction.target!!, 9000, 11_000)) }
      finished(r)
      withTimeout(15_000) { r.state.first { it.alignmentAttempted && it.phase == ReadAlongPhase.READY } }
      assertTrue(calls.get() >= 2); assertNull(r.state.value.recoveryLabel)
      assertEquals(SyncCorrectionPhase.APPLIED, r.state.value.correction.phase)
      withContext(Dispatchers.Main) { r.setVisible(false) }
    }
  }

  @Test fun estimatedManualCorrectionDoesNotChangeSavedNarrationOffset() = runBlocking { fixture(installed = false) { r, store ->
    store.attach(track, StoredReadAlongBook("book", book(), "fixture.epub"), setOf(0)); store.setOffset(track, 1500)
    withContext(Dispatchers.Main) { r.activate(track) }; ready(r)
    withContext(Dispatchers.Main) { r.playback(1000, 11_000); r.setVisible(true) }
    withTimeout(10_000) { r.state.first { it.canEstimate } }
    withContext(Dispatchers.Main) {
      r.useEstimatedTiming(); r.beginCorrection(r.state.value.lines.last().id)
      r.syncNow(SyncReference(r.state.value.correction.target!!, 5000, 11_000))
    }
    finished(r)
    assertTrue(r.state.value.canUndoCorrection); assertEquals(1500L, store.offset(track))
    withContext(Dispatchers.Main) { r.setVisible(false); r.useNarrationTiming() }; ready(r)
    assertEquals(1500L, r.state.value.offsetMs); assertFalse(r.state.value.canUndoCorrection)
  } }

  @Test fun realSpeechGuidesAnUntimedSentenceWithoutChangingAudioPosition() = runBlocking {
    fixture(speech = WhisperEngine(), realModel = true) { r, store ->
      val text = "And so my fellow Americans ask not what your country can do for you ask what you can do for your country."
      val book = EpubBook("Public-domain JFK speech", "John F. Kennedy", listOf(EpubChapter(1, "jfk", "jfk", "Inaugural speech", text, 26)))
      store.attach(track, StoredReadAlongBook("jfk", book, "public.txt"), setOf(0))
      withContext(Dispatchers.Main) { r.activate(track) }; ready(r)
      withContext(Dispatchers.Main) { r.beginCorrection(r.state.value.lines.single().id); r.syncNow(SyncReference(r.state.value.correction.target!!, 320, 11_000)) }
      val result = finished(r, 60_000)
      assertEquals(result.correction.message, SyncCorrectionPhase.APPLIED, result.correction.phase)
      assertTrue("Native onset should be within one second of the reviewed 320 ms onset", kotlin.math.abs(result.offsetMs) <= 1000)
      assertEquals(0, result.activeLineIndex)
    }
  }
}

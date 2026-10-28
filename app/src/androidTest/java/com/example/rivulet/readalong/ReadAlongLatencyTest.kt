package com.example.rivulet.readalong

import android.database.sqlite.SQLiteDatabase
import android.media.MediaDataSource
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rivulet.data.lyrics.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import com.example.rivulet.RivuletApp
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Defaults to the public-domain fixture. An explicit argument enables local, read-only source QA. */
class ReadAlongLatencyTest {
  @Test fun measuresDecodeRecognitionAndConfidentCoverage() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val args = InstrumentationRegistry.getArguments()
    val title = args.getString("perfBookTitle")
    val source: File
    val sentences: List<BookSentence>
    if (title == null) {
      source = File(context.cacheDir, "latency-jfk.wav")
      instrumentation.context.assets.open("readalong/jfk.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
      sentences = listOf(BookSentence(1, 1, "Public fixture", "And so my fellow Americans ask not what your country can do for you ask what you can do for your country."))
    } else {
      source = File(context.filesDir, "torrents").walkTopDown().first { it.isFile && it.extension == "m4b" && it.name.contains(title, true) }
      val db = SQLiteDatabase.openDatabase(context.getDatabasePath("rivulet.db").path, null, SQLiteDatabase.OPEN_READONLY)
      try {
        val id = db.rawQuery("SELECT id FROM readalong_books WHERE title=?", arrayOf(title)).use { check(it.moveToFirst()); it.getString(0) }
        val chapters = db.rawQuery("SELECT chapter_index,chapter_id,href,title,content,word_count FROM readalong_chapters WHERE book_id=? ORDER BY chapter_index", arrayOf(id)).use { c ->
          buildList { while (c.moveToNext()) add(EpubChapter(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5))) }
        }
        sentences = ReadAlongText.sentences(EpubBook(title, "", chapters), id)
      } finally { db.close() }
    }
    val decoder = AudioWindowDecoder {
      object : MediaDataSource() {
        val file = RandomAccessFile(source, "r")
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
          if (position >= file.length()) return -1
          file.seek(position); return file.read(buffer, offset, minOf(size.toLong(), file.length() - position).toInt())
        }
        override fun getSize() = file.length()
        override fun close() = file.close()
      }
    }
    val start = args.getString("perfStartMs")?.toLong() ?: 0L
    val duration = args.getString("perfDurationMs")?.toLong() ?: 11_000L
    val label = args.getString("perfLabel") ?: "public"
    // Production schedules analysis after source/matcher restoration, so benchmark the same state.
    val live = (context.applicationContext as RivuletApp).container.playback.readAlong
    withTimeout(20_000) { while (live.state.value.phase == ReadAlongPhase.LOADING) delay(20) }
    val matcher = NarrationMatcher(sentences)
    val began = SystemClock.elapsedRealtime()
    val pcm = decoder.decode(ReadAlongTrack("latency", 0), start, duration)
    val decoded = SystemClock.elapsedRealtime()
    val engine = WhisperEngine(adaptiveContext = args.getString("perfFullContext") != "true")
    val found = LinkedHashMap<Long, TimedLine>()
    var firstUsable = -1L
    val partial: suspend (List<SpeechWord>) -> Unit = { partial ->
      val lines = matcher.align(partial.map { it.copy(startMs = it.startMs + start, endMs = it.endMs + start) })
      lines.forEach { found.putIfAbsent(it.id, it) }
      if (lines.isNotEmpty() && firstUsable < 0) firstUsable = SystemClock.elapsedRealtime() - began
    }
    val words = try {
      if (args.getString("perfLegacy") == "true") engine.transcribe(SpeechModelManager(context).file, pcm).also { partial(it) }
      else engine.transcribeProgressive(SpeechModelManager(context).file, pcm, args.getString("perfFocusMs")?.toLong() ?: 0, partial)
    } finally { engine.close() }
    val recognized = SystemClock.elapsedRealtime()
    val lines = found.values.sortedBy { it.startTimeMs }
    val report = "label=$label decodeMs=${decoded-began} recognitionMs=${recognized-decoded} firstUsableMs=$firstUsable totalMs=${recognized-began} samples=${pcm.size} words=${words.size} matched=${lines.size}"
    Log.i("ReadAlongLatency", report)
    File(context.cacheDir, "task076-$label.txt").writeText(report + "\n" + lines.joinToString("\n") { "${it.startTimeMs}\t${it.endTimeMs}\t${it.id}" })
    val bytes = ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    pcm.forEach { bytes.putFloat(it) }
    File(context.cacheDir, "task076-$label.f32").writeBytes(bytes.array())
    assertTrue("Audio must produce confident public/source sentence matches", lines.isNotEmpty())
    if (title == null) assertTrue(kotlin.math.abs(lines.single().startTimeMs - 320) <= 1000)
  }
}

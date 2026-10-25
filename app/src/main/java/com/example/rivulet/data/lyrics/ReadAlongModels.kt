package com.example.rivulet.data.lyrics

import com.example.rivulet.data.AudioFile
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale

enum class ReadAlongPhase { EMPTY, LOADING, READY, PREPARING, NEEDS_MODEL, ERROR }
enum class ReadAlongRecovery { RETRY, PICK_FILE }

data class ReadAlongTrack(val infoHash: String, val fileIndex: Int)

data class BookSentence(val id: Long, val chapterIndex: Int, val chapterTitle: String, val text: String)

data class ReadAlongState(
  val track: ReadAlongTrack? = null,
  val phase: ReadAlongPhase = ReadAlongPhase.EMPTY,
  val bookId: String? = null,
  val title: String? = null,
  val isbn: String? = null,
  val chapters: List<EpubChapter> = emptyList(),
  val lines: List<TimedLine> = emptyList(),
  val activeLineIndex: Int = -1,
  val offsetMs: Long = 0,
  val source: TextSyncSource? = null,
  val message: String? = null,
  val modelProgress: Int? = null,
  val matchedSentences: Int = 0,
  val analysisMillis: Long? = null,
  val searching: Boolean = false,
  val editions: List<BookEdition> = emptyList(),
  val accessUrl: String? = null,
  val lookupMessage: String? = null,
  val trackIndices: Set<Int> = emptySet(),
  val recoveryLabel: String? = null,
  val recovery: ReadAlongRecovery? = null,
  val alignmentAttempted: Boolean = false,
  val correction: SyncCorrectionState = SyncCorrectionState(),
  val canUndoCorrection: Boolean = false,
) {
  val canEstimate: Boolean get() = bookId != null && source == TextSyncSource.NARRATION && matchedSentences == 0 &&
    (phase == ReadAlongPhase.NEEDS_MODEL ||
      (phase == ReadAlongPhase.ERROR && recoveryLabel in setOf("Retry matching", "Retry model download")) ||
      (phase == ReadAlongPhase.READY && alignmentAttempted))
  val canChooseChapter: Boolean get() = source == TextSyncSource.NARRATION && chapters.isNotEmpty()
  val progressLabel: String? get() = when {
    modelProgress != null -> "Downloading speech model… $modelProgress%"
    searching -> lookupMessage?.takeIf { it.startsWith("Downloading ") } ?: "Searching for book text…"
    correction.phase == SyncCorrectionPhase.MATCHING -> "Matching the selected sentence… Audio can keep playing."
    phase == ReadAlongPhase.LOADING -> "Loading book text…"
    phase == ReadAlongPhase.PREPARING -> "Preparing read-along…"
    else -> null
  }
}

data class StoredReadAlongBook(val id: String, val book: EpubBook, val fileName: String, val source: TextSyncSource = TextSyncSource.NARRATION)
data class SpeechWord(val text: String, val startMs: Long, val endMs: Long)

object ReadAlongText {
  fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

  fun sentences(book: EpubBook, identity: String): List<BookSentence> = book.chapters.flatMap { chapter ->
    val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH)
    iterator.setText(chapter.text)
    val result = ArrayList<BookSentence>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
      val text = chapter.text.substring(start, end).trim()
      if (text.isNotBlank()) {
        val hash = digest("$identity:${chapter.index}:$start".toByteArray())
        result += BookSentence(hash.take(15).toLong(16), chapter.index, chapter.title, text)
      }
      start = end
      end = iterator.next()
    }
    result
  }

  fun tokens(text: String): List<String> = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?")
    .findAll(text.lowercase(Locale.ENGLISH).replace('’', '\'')).map { it.value }.toList()

  fun defaultTracks(files: List<AudioFile>, currentIndex: Int): Set<Int> {
    val current = files.firstOrNull { it.index == currentIndex } ?: return setOf(currentIndex)
    // Named files in an omnibus are separate books. Generic numbered chapters share their folder.
    if (!BookTitleCleaner.isGenericTrackName(BookTitleCleaner.cleanFileNameOrTrack(current.name))) return setOf(currentIndex)
    return files.filter { it.directory == current.directory }.map { it.index }.toSet()
  }
}

object ReadAlongTimeline {
  fun activeIndex(lines: List<TimedLine>, positionMs: Long, offsetMs: Long): Int =
    lines.indexOfFirst { it.isTimed && positionMs - offsetMs >= it.startTimeMs && positionMs - offsetMs < it.endTimeMs }

  fun seekPosition(line: TimedLine, offsetMs: Long): Long = (line.startTimeMs + offsetMs).coerceAtLeast(0)
  fun calibration(line: TimedLine, positionMs: Long): Long = positionMs - line.startTimeMs
}

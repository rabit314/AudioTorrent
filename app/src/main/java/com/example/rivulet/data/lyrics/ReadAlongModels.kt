package com.example.rivulet.data.lyrics

import com.example.rivulet.data.AudioFile
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale

enum class ReadAlongPhase { EMPTY, LOADING, READY, PREPARING, NEEDS_MODEL, ERROR }
enum class ReadAlongRecovery { RETRY, PICK_FILE }

data class ReadAlongTrack(val infoHash: String, val fileIndex: Int)

data class BookSentence(
  val id: Long, val chapterIndex: Int, val chapterTitle: String, val text: String,
  val isParagraphStart: Boolean = false,
) {
  /** Rebuilt from source on load; display metadata never changes a saved timing or identity. */
  fun withTiming(timing: TimedLine?): TimedLine =
    TimedLine(
      id = id,
      startTimeMs = timing?.startTimeMs ?: -1,
      endTimeMs = timing?.endTimeMs ?: -1,
      text = text,
      isParagraphStart = isParagraphStart || timing?.isParagraphStart == true
    )
}

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
  val choosingEdition: Boolean = false,
  val preparingAhead: Boolean = false,
  val syncProgress: Float? = null,
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
    phase == ReadAlongPhase.LOADING -> message?.takeIf { it.startsWith("Importing ") } ?: "Loading book text…"
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
    var previousTextEnd = 0
    while (end != BreakIterator.DONE) {
      val raw = chapter.text.substring(start, end)
      val text = raw.trim()
      if (text.isNotBlank()) {
        val hash = digest("$identity:${chapter.index}:$start".toByteArray())
        val contentStart = start + raw.indexOfFirst { !it.isWhitespace() }
        val paragraphStart = result.isEmpty() || Regex("\\r?\\n[\\t \\r]*\\n").containsMatchIn(chapter.text.substring(previousTextEnd, contentStart))
        result += BookSentence(hash.take(15).toLong(16), chapter.index, chapter.title, text, paragraphStart)
        previousTextEnd = start + raw.indexOfLast { !it.isWhitespace() } + 1
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
  fun activeIndex(lines: List<TimedLine>, positionMs: Long, offsetMs: Long): Int {
    if (lines.isEmpty()) return -1
    val targetMs = positionMs - offsetMs
    if (targetMs < 0) return -1

    // Fast binary search across timed lines
    var low = 0
    var high = lines.size - 1
    var candidate = -1

    while (low <= high) {
      val mid = (low + high).ushr(1)
      val line = lines[mid]
      if (line.isTimed) {
        if (line.startTimeMs <= targetMs) {
          candidate = mid
          low = mid + 1
        } else {
          high = mid - 1
        }
      } else {
        // Find nearest timed line on left to maintain binary search invariant
        var leftTimed = -1
        for (k in mid downTo low) {
          if (lines[k].isTimed) { leftTimed = k; break }
        }
        if (leftTimed != -1) {
          if (lines[leftTimed].startTimeMs <= targetMs) {
            candidate = maxOf(candidate, leftTimed)
            low = mid + 1
          } else {
            high = leftTimed - 1
          }
        } else {
          low = mid + 1
        }
      }
    }

    if (candidate != -1) {
      val line = lines[candidate]
      // Directly within the active spoken duration
      if (targetMs < line.endTimeMs) {
        return candidate
      }
      // Sticky tracking: remain active during pause until the next timed line starts
      var nextTimedStart = Long.MAX_VALUE
      for (j in (candidate + 1) until lines.size) {
        if (lines[j].isTimed) {
          nextTimedStart = lines[j].startTimeMs
          break
        }
      }
      if (nextTimedStart != Long.MAX_VALUE) {
        if (targetMs < nextTimedStart) {
          return candidate
        }
      } else {
        // Last timed sentence: stay sticky for up to 3 seconds of trailing silence
        if (targetMs <= line.endTimeMs + 3_000L) {
          return candidate
        }
      }
    }
    return -1
  }

  fun seekPosition(line: TimedLine, offsetMs: Long): Long = (line.startTimeMs + offsetMs).coerceAtLeast(0)
  fun calibration(line: TimedLine, positionMs: Long): Long = positionMs - line.startTimeMs
}

package com.example.rivulet.data.lyrics

import com.example.rivulet.data.AudioFile

data class AlignmentResult(
  val trackToChapterMap: Map<Int, EpubChapter>, // audio fileIndex -> EpubChapter
  val confidence: Float,                         // 0.0 to 1.0
  val strategy: AlignmentStrategy,
)

enum class AlignmentStrategy(val displayName: String) {
  EXACT_TITLE_MATCH("Title & Number Match"),
  SEQUENTIAL_1_TO_1("Sequential 1:1 Match"),
  PROPORTIONAL("Proportional Distribution"),
  SINGLE_FILE_MULTI_CHAPTER("Single File Multi-Chapter"),
  FALLBACK("Fallback Chapter 1"),
}

object AudioBookAligner {

  fun align(audioFiles: List<AudioFile>, book: EpubBook): AlignmentResult {
    if (audioFiles.isEmpty() || book.chapters.isEmpty()) {
      return AlignmentResult(emptyMap(), 0f, AlignmentStrategy.FALLBACK)
    }

    val chapters = book.chapters
    val audioCount = audioFiles.size
    val chapterCount = chapters.size

    // Scenario 1: Single Audio File (e.g. 1 massive M4B/MP3)
    if (audioCount == 1) {
      val map = HashMap<Int, EpubChapter>()
      // For single file, associate with the first chapter by default
      map[audioFiles[0].index] = chapters.first()
      return AlignmentResult(map, 0.95f, AlignmentStrategy.SINGLE_FILE_MULTI_CHAPTER)
    }

    // Scenario 2: Numbered matching (e.g. "Track 01" / "Chapter 1")
    val numberMatchedMap = HashMap<Int, EpubChapter>()
    var numberMatchesCount = 0

    val chapterNumberMap = HashMap<Int, EpubChapter>()
    for (ch in chapters) {
      val num = extractLeadingOrIsolatedNumber(ch.title) ?: ch.index
      chapterNumberMap[num] = ch
    }

    for (audio in audioFiles) {
      val audioNum = extractLeadingOrIsolatedNumber(audio.name)
      if (audioNum != null && chapterNumberMap.containsKey(audioNum)) {
        numberMatchedMap[audio.index] = chapterNumberMap[audioNum]!!
        numberMatchesCount++
      }
    }

    if (numberMatchesCount >= (audioCount * 0.6).toInt().coerceAtLeast(2)) {
      // High-confidence title & number match
      // Fill in any gaps sequentially
      fillGapsSequentially(audioFiles, chapters, numberMatchedMap)
      val conf = (numberMatchesCount.toFloat() / audioCount).coerceIn(0.7f, 1.0f)
      return AlignmentResult(numberMatchedMap, conf, AlignmentStrategy.EXACT_TITLE_MATCH)
    }

    // Scenario 3: Sequential 1:1 Match (when track count is close to chapter count)
    if (Math.abs(audioCount - chapterCount) <= 2) {
      val map = HashMap<Int, EpubChapter>()
      for (i in 0 until minOf(audioCount, chapterCount)) {
        map[audioFiles[i].index] = chapters[i]
      }
      return AlignmentResult(map, 0.85f, AlignmentStrategy.SEQUENTIAL_1_TO_1)
    }

    // Scenario 4: Proportional distribution
    val map = HashMap<Int, EpubChapter>()
    for (i in audioFiles.indices) {
      val targetChapterIndex = ((i.toDouble() / audioCount) * chapterCount).toInt().coerceIn(0, chapterCount - 1)
      map[audioFiles[i].index] = chapters[targetChapterIndex]
    }

    return AlignmentResult(map, 0.60f, AlignmentStrategy.PROPORTIONAL)
  }

  private fun fillGapsSequentially(
    audioFiles: List<AudioFile>,
    chapters: List<EpubChapter>,
    map: HashMap<Int, EpubChapter>,
  ) {
    for (i in audioFiles.indices) {
      val fileIndex = audioFiles[i].index
      if (!map.containsKey(fileIndex)) {
        val chapterIdx = i.coerceIn(0, chapters.size - 1)
        map[fileIndex] = chapters[chapterIdx]
      }
    }
  }

  fun extractLeadingOrIsolatedNumber(text: String): Int? {
    // Looks for patterns like "01", "Chapter 1", "Ch. 02", "Track_05"
    val regex = Regex("(?:chapter|ch|track|part|cd)?\\s*0*(\\d+)", RegexOption.IGNORE_CASE)
    val match = regex.find(text)
    return match?.groupValues?.get(1)?.toIntOrNull()
  }
}

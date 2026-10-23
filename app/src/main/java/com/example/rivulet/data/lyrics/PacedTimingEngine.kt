package com.example.rivulet.data.lyrics

object PacedTimingEngine {

  /**
   * Splits raw chapter text into paragraphs and sentences, then distributes [totalDurationMs]
   * proportionally according to word count and natural punctuation pause weights.
   */
  fun paceChapter(
    chapterText: String,
    totalDurationMs: Long,
    leadSilenceMs: Long = 800L,
    offsetMs: Long = 0L,
  ): List<TimedLine> {
    return paceText(chapterText, totalDurationMs, leadSilenceMs, offsetMs)
  }

  /**
   * Splits raw text into paragraphs and sentences, then distributes [totalDurationMs]
   * proportionally according to word count.
   */
  fun paceText(
    rawText: String,
    totalDurationMs: Long,
    leadSilenceMs: Long = 800L,
    offsetMs: Long = 0L,
  ): List<TimedLine> {
    if (rawText.isBlank() || totalDurationMs <= 0L) return emptyList()

    val paragraphs = rawText.split(Regex("\n\n+|\r\n\r\n+"))
      .map { it.trim() }
      .filter { it.isNotBlank() }

    val sentences = ArrayList<Pair<String, Boolean>>() // text to isParagraphStart

    for (p in paragraphs) {
      // Split paragraph into sentences on punctuation (. ! ?)
      val sList = p.split(Regex("(?<=[.!?])\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

      sList.forEachIndexed { idx, s ->
        sentences.add(s to (idx == 0))
      }
    }

    if (sentences.isEmpty()) return emptyList()

    // Punctuation & word weighted distribution
    val wordCounts = sentences.map { (text, isParagraphStart) ->
      val words = text.split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
      val pauseBonus = if (isParagraphStart) 4 else 1 // paragraph pause weight
      words + pauseBonus
    }
    val totalWords = wordCounts.sum().coerceAtLeast(1)

    // Effective speech duration excluding lead silence
    val effectiveTotalDuration = totalDurationMs.coerceAtLeast(1000L)
    val effectiveSpeechDuration = (effectiveTotalDuration - leadSilenceMs).coerceAtLeast(500L)

    val result = ArrayList<TimedLine>()
    var accumulatedWords = 0L
    var idCounter = 0L

    for (i in sentences.indices) {
      val (text, isParagraphStart) = sentences[i]
      val count = wordCounts[i]

      val rawStart = leadSilenceMs + (accumulatedWords.toDouble() / totalWords * effectiveSpeechDuration).toLong() + offsetMs
      accumulatedWords += count
      val rawEnd = leadSilenceMs + (accumulatedWords.toDouble() / totalWords * effectiveSpeechDuration).toLong() + offsetMs

      val startMs = rawStart.coerceIn(0L, (effectiveTotalDuration - 100L).coerceAtLeast(0L))
      val endMs = rawEnd.coerceIn(startMs + 50L, effectiveTotalDuration)

      result.add(
        TimedLine(
          id = idCounter++,
          startTimeMs = startMs,
          endTimeMs = endMs,
          text = text,
          isParagraphStart = isParagraphStart,
        )
      )
    }

    return result
  }

  /**
   * Computes normalized token overlap similarity between two strings (0.0 to 1.0).
   */
  fun calculateSimilarity(textA: String, textB: String): Float {
    if (textA.isBlank() || textB.isBlank()) return 0f
    val tokensA = textA.lowercase().replace(Regex("[^a-z0-9 ]"), "").split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
    val tokensB = textB.lowercase().replace(Regex("[^a-z0-9 ]"), "").split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
    if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f

    val intersection = tokensA.intersect(tokensB).size
    val union = tokensA.union(tokensB).size
    return if (union == 0) 0f else intersection.toFloat() / union
  }

  /**
   * Finds the best matching line index for a probe phrase.
   */
  fun findBestMatchingLineIndex(lines: List<TimedLine>, probeText: String): Int? {
    if (lines.isEmpty() || probeText.isBlank()) return null
    var bestIndex: Int? = null
    var bestScore = 0.25f // minimum threshold

    for (i in lines.indices) {
      val sim = calculateSimilarity(lines[i].text, probeText)
      if (sim > bestScore) {
        bestScore = sim
        bestIndex = i
      }
    }
    return bestIndex
  }
}

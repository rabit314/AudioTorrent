package com.example.rivulet.data.lyrics

object PacedTimingEngine {
  private val PARAGRAPH_SPLIT_REGEX = Regex("\n\n+|\r\n\r\n+")
  private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")
  private val WHITESPACE_REGEX = Regex("\\s+")

  /**
   * Splits raw text into paragraphs and sentences, then distributes [totalDurationMs]
   * proportionally according to word count and natural punctuation pause weights.
   */
  fun paceText(
    rawText: String,
    totalDurationMs: Long,
    leadSilenceMs: Long = 800L,
    offsetMs: Long = 0L,
  ): List<TimedLine> {
    if (rawText.isBlank() || totalDurationMs <= 0L) return emptyList()

    val paragraphs = rawText.split(PARAGRAPH_SPLIT_REGEX)
      .map { it.trim() }
      .filter { it.isNotBlank() }

    val sentences = ArrayList<Pair<String, Boolean>>() // text to isParagraphStart

    for (p in paragraphs) {
      val sList = p.split(SENTENCE_SPLIT_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }

      sList.forEachIndexed { idx, s ->
        sentences.add(s to (idx == 0))
      }
    }

    if (sentences.isEmpty()) return emptyList()

    // Punctuation & word weighted distribution
    val wordCounts = sentences.map { (text, isParagraphStart) ->
      val words = text.split(WHITESPACE_REGEX).count { it.isNotBlank() }.coerceAtLeast(1)
      val pauseBonus = if (isParagraphStart) 4 else 1
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
}

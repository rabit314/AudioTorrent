package com.example.rivulet.data.lyrics

object PacedTimingEngine {

  /**
   * Splits raw text into paragraphs and sentences, then distributes [totalDurationMs]
   * proportionally according to word count.
   */
  fun paceText(
    rawText: String,
    totalDurationMs: Long,
    leadSilenceMs: Long = 1000L,
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

    val wordCounts = sentences.map { (text, _) ->
      maxOf(1, text.split(Regex("\\s+")).count { it.isNotBlank() })
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

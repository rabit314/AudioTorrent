package com.example.rivulet.data.lyrics

internal data class SpeechBatch(val startSample: Int, val endSample: Int) {
  val startMs: Long get() = startSample / 16L
  val endMs: Long get() = endSample / 16L
}

/** Batch planner and seam merger for bounded Whisper windows. */
internal object SpeechBatches {
  fun plan(samples: Int, focusMs: Long): List<SpeechBatch> {
    if (samples <= 0) return emptyList()
    val size = 12 * 16_000
    val starts = buildList {
      var at = 0
      while (at < samples) {
        add(SpeechBatch(at, minOf(at + size, samples)))
        if (at + size >= samples) break
        at += 9 * 16_000
      }
    }
    return starts.sortedBy { kotlin.math.abs((it.startMs + it.endMs) / 2 - focusMs) }
  }

  /**
   * Splits a full decode window (e.g. 24s) into chronological chunks <= 13s so each call stays within
   * Whisper's fast `audio_ctx=768` envelope while preserving 2s acoustic overlap across the midpoint.
   */
  fun planBalanced(samples: Int, maxBatchSeconds: Int = 13, overlapSeconds: Int = 2): List<SpeechBatch> {
    if (samples <= 0) return emptyList()
    val maxSamples = maxBatchSeconds * 16_000
    if (samples <= maxSamples) return listOf(SpeechBatch(0, samples))
    val overlapSamples = overlapSeconds * 16_000
    val twoChunkLimit = (maxBatchSeconds * 2 - overlapSeconds) * 16_000
    if (samples <= twoChunkLimit) {
      val mid = samples / 2
      val halfOverlap = overlapSamples / 2
      return listOf(
        SpeechBatch(0, minOf(samples, mid + halfOverlap)),
        SpeechBatch(maxOf(0, mid - halfOverlap), samples),
      )
    }
    val step = maxOf(16_000, maxSamples - overlapSamples)
    return buildList {
      var at = 0
      while (at < samples) {
        add(SpeechBatch(at, minOf(at + maxSamples, samples)))
        if (at + maxSamples >= samples) break
        at += step
      }
    }
  }

  fun merge(completed: Map<SpeechBatch, List<SpeechWord>>): List<SpeechWord> {
    val ordered = completed.keys.sortedBy { it.startSample }
    val merged = ArrayList<SpeechWord>()
    ordered.forEachIndexed { index, batch ->
      val from = ordered.getOrNull(index - 1)?.let { (it.endMs + batch.startMs) / 2 } ?: Long.MIN_VALUE
      val until = ordered.getOrNull(index + 1)?.let { (batch.endMs + it.startMs) / 2 } ?: Long.MAX_VALUE
      val kept = completed.getValue(batch)
        .filter { word ->
          val middle = (word.startMs + word.endMs) / 2
          middle >= from && middle < until && word.endMs > word.startMs
        }
        .sortedBy { it.startMs }
      if (merged.isNotEmpty() && kept.isNotEmpty() && index > 0) {
        val tailNorm = TranscriptAssembler.normalizedToken(merged.last().text)
        val firstNorm = TranscriptAssembler.normalizedToken(kept.first().text)
        val seamGap = kept.first().startMs - merged.last().endMs
        if (tailNorm.isNotEmpty() && tailNorm == firstNorm && seamGap <= 450L) {
          merged.addAll(kept.drop(1))
          return@forEachIndexed
        }
      }
      merged.addAll(kept)
    }
    return merged.sortedBy { it.startMs }
  }
}

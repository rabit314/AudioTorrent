package com.example.rivulet.data.lyrics

internal data class SpeechBatch(val startSample: Int, val endSample: Int) {
  val startMs: Long get() = startSample / 16L
  val endMs: Long get() = endSample / 16L
}

/** Twelve-second recognition with three-second overlap; first publish targets the current playhead. */
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

  fun merge(completed: Map<SpeechBatch, List<SpeechWord>>): List<SpeechWord> {
    val ordered = completed.keys.sortedBy { it.startSample }
    return ordered.flatMapIndexed { index, batch ->
      val from = ordered.getOrNull(index - 1)?.let { (it.endMs + batch.startMs) / 2 } ?: Long.MIN_VALUE
      val until = ordered.getOrNull(index + 1)?.let { (batch.endMs + it.startMs) / 2 } ?: Long.MAX_VALUE
      completed.getValue(batch).filter { word ->
        val middle = (word.startMs + word.endMs) / 2
        middle >= from && middle < until && word.endMs > word.startMs
      }
    }.sortedBy { it.startMs }
  }
}

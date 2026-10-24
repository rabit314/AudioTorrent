package com.example.rivulet.data.lyrics

/** Local sequence alignment of recognized words to the original book, never generated book text. */
class NarrationMatcher(private val sentences: List<BookSentence>) {
  private data class Token(val value: String, val sentence: Int, val within: Int)
  private val tokens = sentences.flatMapIndexed { index, sentence -> ReadAlongText.tokens(sentence.text).mapIndexed { within, word -> Token(word, index, within) } }
  private val trigrams = HashMap<String, MutableList<Int>>().apply {
    for (i in 0 until tokens.size - 2) getOrPut(key(tokens[i].value, tokens[i + 1].value, tokens[i + 2].value)) { ArrayList() }.add(i)
  }

  fun align(recognized: List<SpeechWord>, hintSentence: Int? = null): List<TimedLine> {
    val words = recognized.flatMap { word ->
      val parts = ReadAlongText.tokens(word.text)
      parts.mapIndexed { i, text -> SpeechWord(text, word.startMs + (word.endMs - word.startMs) * i / parts.size, word.startMs + (word.endMs - word.startMs) * (i + 1) / parts.size) }
    }.filter { it.endMs > it.startMs }.take(400)
    if (words.size < 6 || tokens.isEmpty()) return emptyList()
    // Repeated boilerplate alone cannot locate a passage without a chapter/previous-line anchor.
    if (hintSentence == null && (0 until words.size - 2).none { i -> trigrams[key(words[i].text, words[i + 1].text, words[i + 2].text)]?.size == 1 }) return emptyList()
    val votes = HashMap<Int, Int>()
    for (i in 0 until words.size - 2) {
      trigrams[key(words[i].text, words[i + 1].text, words[i + 2].text)]?.takeIf { it.size <= 30 }?.forEach { at ->
        val bucket = Math.floorDiv(at - i, 20)
        votes[bucket] = (votes[bucket] ?: 0) + 1
      }
    }
    val candidates = votes.entries.sortedByDescending { it.value }.take(4).map { it.key * 20 }.toMutableList()
    hintSentence?.let { hint -> tokens.indexOfFirst { it.sentence == hint }.takeIf { it >= 0 }?.let { candidates.add(0, it) } }
    if (candidates.isEmpty()) return emptyList()
    val preferred = hintSentence?.let { hint -> tokens.indexOfFirst { it.sentence == hint }.takeIf { it >= 0 } }
    val best = candidates.map { center -> alignWindow(words, (center - 80).coerceAtLeast(0), (center + words.size + 120).coerceAtMost(tokens.size), preferred) }
      .maxByOrNull { matches -> matches.size * 10_000 - (hintSentence?.let { hint -> matches.firstOrNull()?.let { kotlin.math.abs(tokens[it.second].sentence - hint) } } ?: 0) } ?: return emptyList()
    if (best.size < 6) return emptyList()
    return best.groupBy { tokens[it.second].sentence }.mapNotNull { (sentenceIndex, pairs) ->
      val sentenceTokens = ReadAlongText.tokens(sentences[sentenceIndex].text)
      val covered = pairs.map { tokens[it.second].within }.toSet()
      // Require the first word and most of the sentence; partial boundaries stay untimed.
      if (sentenceTokens.size < 3 || 0 !in covered || covered.size.toDouble() / sentenceTokens.size < 0.72 || sentenceTokens.lastIndex !in covered) return@mapNotNull null
      val first = words[pairs.first().first]
      val last = words[pairs.last().first]
      if (last.endMs <= first.startMs) return@mapNotNull null
      TimedLine(sentences[sentenceIndex].id, first.startMs, last.endMs, sentences[sentenceIndex].text)
    }.sortedBy { it.startTimeMs }
  }

  private fun alignWindow(words: List<SpeechWord>, from: Int, to: Int, preferred: Int?): List<Pair<Int, Int>> {
    if (from >= to) return emptyList()
    val width = to - from + 1
    val scores = Array(words.size + 1) { IntArray(width) }
    var best = 0; var bi = 0; var bj = 0
    for (i in 1..words.size) for (j in 1 until width) {
      val score = maxOf(0, scores[i - 1][j - 1] + if (words[i - 1].text == tokens[from + j - 1].value) 3 else -3,
        scores[i - 1][j] - 2, scores[i][j - 1] - 2)
      scores[i][j] = score
      if (score > best || (score == best && preferred != null && kotlin.math.abs(from + j - i - preferred) < kotlin.math.abs(from + bj - bi - preferred))) { best = score; bi = i; bj = j }
    }
    val pairs = ArrayList<Pair<Int, Int>>()
    var i = bi; var j = bj
    while (i > 0 && j > 0 && scores[i][j] > 0) {
      val same = words[i - 1].text == tokens[from + j - 1].value
      when (scores[i][j]) {
        scores[i - 1][j - 1] + if (same) 3 else -3 -> { if (same) pairs += (i - 1) to (from + j - 1); i--; j-- }
        scores[i - 1][j] - 2 -> i--
        else -> j--
      }
    }
    return pairs.asReversed()
  }

  private fun key(a: String, b: String, c: String) = "$a\u0001$b\u0001$c"
}

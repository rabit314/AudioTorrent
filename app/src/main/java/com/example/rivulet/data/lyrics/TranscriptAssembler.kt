package com.example.rivulet.data.lyrics

import java.util.Locale
import kotlin.math.sqrt

data class ScriptSection(
  val index: Int,
  val startMs: Long,
  val endMs: Long,
  val label: String,
  val previewText: String,
  val wordCount: Int,
  val firstSentenceId: Long,
)

/** Converts raw Whisper word tokens into clean, deduplicated, paragraph-structured script lines. */
object TranscriptAssembler {
  private val BRACKET_TAG_REGEX = Regex("""^\[[^\]]*]$""")
  private val NON_SPEECH_PAREN_REGEX = Regex(
    """^\((?:music|applause|laughter|laughing|sighs?|groans?|coughs?|clears throat|silence|inaudible|background noise|footsteps|door [^)]*)\)$""",
    RegexOption.IGNORE_CASE,
  )
  private val MUSICAL_REGEX = Regex("""^[♪♫♬*#]+$""")
  private val MULTI_SPACE_REGEX = Regex("""\s+""")
  private val ATTACHED_PUNCT_REGEX = Regex("""^[,.;:!?…]+['"”’)\]]*$""")
  private val TRAILING_CLOSER_REGEX = Regex("""['"”’)\]]+$""")
  private val NON_LETTER_DIGIT_EDGE_REGEX = Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$""")
  private val ABBREVIATIONS = setOf(
    "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "st.", "rev.", "gen.",
    "col.", "lt.", "sgt.", "capt.", "etc.", "vs.", "e.g.", "i.e.", "u.s.", "u.k.",
  )
  private val OUTRO_HALLUCINATIONS = setOf(
    "thank you for watching",
    "thanks for watching",
    "please subscribe",
    "subtitles by",
    "amara org",
  )

  fun isSilent(pcm: FloatArray, thresholdRms: Float = 0.002f): Boolean {
    if (pcm.isEmpty()) return true
    var sumSq = 0.0
    for (sample in pcm) sumSq += sample * sample
    return sqrt(sumSq / pcm.size) < thresholdRms
  }

  fun normalizedToken(text: String): String =
    text.trim().lowercase(Locale.ROOT).replace(NON_LETTER_DIGIT_EDGE_REGEX, "")

  fun filterWindowWords(
    words: List<SpeechWord>,
    windowStartMs: Long,
    windowEndMs: Long,
    previousTailToken: String? = null,
    previousTailEndMs: Long = Long.MIN_VALUE,
    previousTailTokens: List<String> = emptyList(),
  ): List<SpeechWord> {
    if (words.isEmpty() || windowEndMs <= windowStartMs) return emptyList()
    val filtered = ArrayList<SpeechWord>(words.size)
    for (word in words) {
      if (word.endMs < word.startMs) continue
      val mid = (word.startMs + word.endMs) / 2L
      if (mid < windowStartMs || mid >= windowEndMs) continue
      val cleaned = cleanToken(word.text)
      if (cleaned.isBlank()) continue
      val effectiveEnd = maxOf(word.endMs, word.startMs + 40L)
      filtered += SpeechWord(cleaned, word.startMs.coerceAtLeast(0L), effectiveEnd)
    }
    if (filtered.isEmpty()) return emptyList()

    // Deduplicate overlap phrases (1..8 tokens) if Whisper re-emitted the tail of the previous window.
    val tailNorms = if (previousTailTokens.isNotEmpty()) {
      previousTailTokens.map(::normalizedToken).filter { it.isNotEmpty() }
    } else {
      listOfNotNull(previousTailToken?.let(::normalizedToken)?.takeIf { it.isNotEmpty() })
    }
    if (tailNorms.isNotEmpty()) {
      val maxK = minOf(8, tailNorms.size, filtered.size)
      for (k in maxK downTo 1) {
        val gapMs = filtered.first().startMs - previousTailEndMs
        val maxGap = if (k >= 2) 1_500L else 600L
        if (gapMs > maxGap) continue
        val tailSlice = tailNorms.subList(tailNorms.size - k, tailNorms.size)
        val matches = (0 until k).all { idx -> normalizedToken(filtered[idx].text) == tailSlice[idx] }
        if (matches) {
          repeat(k) { filtered.removeAt(0) }
          break
        }
      }
    }
    if (filtered.isEmpty()) return emptyList()

    val deduped = collapseHallucinationLoops(filtered)
    if (deduped.size <= 6) {
      val joined = deduped.joinToString(" ") { normalizedToken(it.text) }.replace(MULTI_SPACE_REGEX, " ").trim()
      if (OUTRO_HALLUCINATIONS.any { joined == it || joined.startsWith("$it ") }) return emptyList()
    }
    return deduped
  }

  fun assembleWindow(
    words: List<SpeechWord>,
    bookId: String,
    windowStartMs: Long,
    windowEndMs: Long,
    previousTailToken: String? = null,
    previousTailEndMs: Long = Long.MIN_VALUE,
    idNamespace: String = "script",
    previousTailTokens: List<String> = emptyList(),
    previousEndedSentence: Boolean = true,
  ): List<TimedLine> {
    val cleanWords = filterWindowWords(
      words = words,
      windowStartMs = windowStartMs,
      windowEndMs = windowEndMs,
      previousTailToken = previousTailToken,
      previousTailEndMs = previousTailEndMs,
      previousTailTokens = previousTailTokens,
    )
    if (cleanWords.isEmpty()) return emptyList()

    val result = ArrayList<TimedLine>()
    val buffer = ArrayList<SpeechWord>()
    var charEstimate = 0
    val entryGapMs = if (previousTailEndMs > Long.MIN_VALUE) cleanWords.first().startMs - previousTailEndMs else Long.MAX_VALUE
    var capitalizeNext = previousEndedSentence || entryGapMs >= 1_800L

    fun flushBuffer(nextStartsSentence: Boolean) {
      if (buffer.isEmpty()) return
      val text = joinTokens(buffer, capitalizeFirst = capitalizeNext)
      if (text.isNotBlank()) {
        val startMs = buffer.first().startMs.coerceAtLeast(0L)
        val endMs = maxOf(buffer.last().endMs, startMs + 80L)
        val id = sentenceId(idNamespace, bookId, windowStartMs, result.size)
        result += TimedLine(id = id, startTimeMs = startMs, endTimeMs = endMs, text = text)
        capitalizeNext = nextStartsSentence || endsSentence(text)
      }
      buffer.clear()
      charEstimate = 0
    }

    for (word in cleanWords) {
      if (buffer.isNotEmpty()) {
        val prev = buffer.last()
        val gapMs = word.startMs - prev.endMs
        val sentenceEnded = endsSentence(prev.text)
        val pauseBreak = gapMs >= 2_000L || (gapMs >= 1_200L && startsWithUpperOrDigit(word.text))
        if (sentenceEnded || pauseBreak) {
          flushBuffer(nextStartsSentence = sentenceEnded || pauseBreak)
        } else if (buffer.size >= 28 || charEstimate >= 180) {
          val clauseIdx = (buffer.size - 1 downTo 10).firstOrNull { isClauseBreak(buffer[it].text) }
          if (clauseIdx != null && clauseIdx < buffer.lastIndex) {
            val remainder = buffer.subList(clauseIdx + 1, buffer.size).toList()
            buffer.subList(clauseIdx + 1, buffer.size).clear()
            flushBuffer(nextStartsSentence = false)
            remainder.forEach { rem ->
              buffer += rem
              charEstimate += rem.text.length + 1
            }
          } else {
            flushBuffer(nextStartsSentence = false)
          }
        }
      }
      buffer += word
      charEstimate += word.text.length + 1
    }
    flushBuffer(nextStartsSentence = false)
    return result
  }

  fun applyParagraphBreaks(lines: List<TimedLine>): List<TimedLine> {
    if (lines.isEmpty()) return emptyList()
    val result = ArrayList<TimedLine>(lines.size)
    var sentencesInParagraph = 0
    for (i in lines.indices) {
      val line = lines[i]
      val prev = lines.getOrNull(i - 1)
      val startParagraph = when {
        i == 0 -> true
        prev != null && line.startTimeMs - prev.endTimeMs >= 1_800L -> true
        prev != null && sentencesInParagraph >= 4 && endsSentence(prev.text) -> true
        else -> false
      }
      if (startParagraph) sentencesInParagraph = 1 else sentencesInParagraph++
      result += if (line.isParagraphStart == startParagraph) line else line.copy(isParagraphStart = startParagraph)
    }
    return result
  }

  fun buildTimeSections(lines: List<TimedLine>, intervalMs: Long = 300_000L): List<ScriptSection> {
    if (lines.isEmpty() || intervalMs <= 0L) return emptyList()
    val grouped = lines.filter { it.text.isNotBlank() && it.startTimeMs >= 0L }
      .groupBy { it.startTimeMs / intervalMs }
      .toSortedMap()
    return grouped.entries.mapIndexed { idx, (bucket, group) ->
      val startMs = bucket * intervalMs
      val endMs = maxOf(group.last().endTimeMs, startMs)
      val preview = group.take(2).joinToString(" ") { it.text.trim() }.take(120)
      val words = group.sumOf { ReadAlongText.tokens(it.text).size }
      ScriptSection(
        index = idx + 1,
        startMs = startMs,
        endMs = endMs,
        label = "${formatClock(startMs)} – ${formatClock(endMs)}",
        previewText = preview,
        wordCount = words,
        firstSentenceId = group.first().id,
      )
    }
  }

  private fun cleanToken(raw: String): String {
    val trimmed = raw.trim().replace(MULTI_SPACE_REGEX, " ")
    if (trimmed.isBlank()) return ""
    if (BRACKET_TAG_REGEX.matches(trimmed)) return ""
    if (NON_SPEECH_PAREN_REGEX.matches(trimmed)) return ""
    if (MUSICAL_REGEX.matches(trimmed)) return ""
    return trimmed
  }

  private fun collapseHallucinationLoops(words: List<SpeechWord>): List<SpeechWord> {
    if (words.size < 2) return words
    val norms = words.map { normalizedToken(it.text) }
    val out = ArrayList<SpeechWord>(words.size)
    var i = 0
    while (i < words.size) {
      var skipped = false
      for (n in 1..3) {
        if (i + n * 4 > words.size) continue
        val pattern = norms.subList(i, i + n)
        if (pattern.any { it.isBlank() }) continue
        var repeats = 1
        while (i + (repeats + 1) * n <= words.size &&
          norms.subList(i + repeats * n, i + (repeats + 1) * n) == pattern
        ) {
          repeats++
        }
        if (repeats >= 4) {
          for (k in 0 until n) out += words[i + k]
          i += repeats * n
          skipped = true
          break
        }
      }
      if (!skipped && i + 1 < words.size && norms[i].isNotEmpty() && norms[i] == norms[i + 1]) {
        val first = words[i]
        val second = words[i + 1]
        val gapMs = second.startMs - first.endMs
        val noSentenceOrClause = !endsSentence(first.text) && !isClauseBreak(first.text)
        val quoteMismatch = (first.text.contains('"') != second.text.contains('"')) ||
          (first.text.contains('“') != second.text.contains('“'))
        if (noSentenceOrClause && gapMs <= 200L && (norms[i].length >= 4 || quoteMismatch || gapMs <= 0L)) {
          val keepSecond = (endsSentence(second.text) || isClauseBreak(second.text)) && !quoteMismatch
          out += if (keepSecond) second else first
          i += 2
          skipped = true
        }
      }
      if (!skipped) {
        out += words[i]
        i++
      }
    }
    return out
  }

  private fun joinTokens(words: List<SpeechWord>, capitalizeFirst: Boolean): String {
    val sb = StringBuilder()
    for (w in words) {
      val token = w.text.trim()
      if (token.isEmpty()) continue
      if (sb.isNotEmpty() && !ATTACHED_PUNCT_REGEX.matches(token)) sb.append(' ')
      sb.append(token)
    }
    val collapsed = sb.toString().replace(MULTI_SPACE_REGEX, " ").trim()
    if (!capitalizeFirst || collapsed.isEmpty()) return collapsed
    val idx = collapsed.indexOfFirst { it.isLetterOrDigit() }
    if (idx < 0 || !collapsed[idx].isLowerCase()) return collapsed
    return collapsed.substring(0, idx) + collapsed[idx].uppercaseChar() + collapsed.substring(idx + 1)
  }

  fun endsSentence(rawToken: String): Boolean {
    val stripped = rawToken.trim().replace(TRAILING_CLOSER_REGEX, "")
    if (stripped.isEmpty()) return false
    val last = stripped.last()
    if (last !in charArrayOf('.', '?', '!', '…')) return false
    if (last == '.') {
      val lower = stripped.lowercase(Locale.ROOT)
      if (lower in ABBREVIATIONS) return false
      if (lower.length == 2 && lower[0].isLetter()) return false
    }
    return true
  }

  private fun isClauseBreak(rawToken: String): Boolean {
    val stripped = rawToken.trim().replace(TRAILING_CLOSER_REGEX, "")
    return stripped.isNotEmpty() && stripped.last() in charArrayOf(',', ';', ':', '—', '-')
  }

  private fun startsWithUpperOrDigit(rawToken: String): Boolean {
    val ch = rawToken.trimStart().firstOrNull { it.isLetterOrDigit() } ?: return false
    return ch.isUpperCase() || ch.isDigit()
  }

  private fun sentenceId(namespace: String, bookId: String, windowStartMs: Long, index: Int): Long =
    ReadAlongText.digest("$namespace:$bookId:$windowStartMs:$index".toByteArray()).take(15).toLong(16)

  private fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
  }
}

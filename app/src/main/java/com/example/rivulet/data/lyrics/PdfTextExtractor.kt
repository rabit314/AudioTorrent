package com.example.rivulet.data.lyrics

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream

data class PdfExtraction(
  val title: String?,
  val author: String?,
  val pages: List<String>,
)

fun interface PdfPageTextReader {
  fun readPages(bytes: ByteArray): PdfExtraction
}

class AndroidPdfPageReader(private val appContext: Context) : PdfPageTextReader {
  override fun readPages(bytes: ByteArray): PdfExtraction {
    ensureInitialized(appContext)
    return try {
      PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
        val info = document.documentInformation
        val title = info?.title?.trim()?.takeIf { it.isNotBlank() }
        val author = info?.author?.trim()?.takeIf { it.isNotBlank() }
        val pageCount = document.numberOfPages.coerceAtMost(MAX_PDF_PAGES)
        val stripper = PDFTextStripper().apply {
          sortByPosition = true
          addMoreFormatting = false
        }
        val pages = ArrayList<String>(pageCount.coerceAtLeast(1))
        var totalChars = 0
        for (page in 1..pageCount) {
          stripper.startPage = page
          stripper.endPage = page
          val raw = stripper.getText(document).orEmpty()
          pages += raw
          totalChars += raw.length
          if (totalChars >= MAX_EXTRACTED_CHARS) break
        }
        PdfExtraction(title, author, pages)
      }
    } catch (error: InvalidPasswordException) {
      throw IllegalStateException("This PDF is password-protected and cannot be read.", error)
    }
  }

  companion object {
    const val MAX_PDF_PAGES = 2_500
    const val MAX_EXTRACTED_CHARS = 12_000_000
    @Volatile private var initialized = false

    private fun ensureInitialized(context: Context) {
      if (initialized) return
      synchronized(this) {
        if (!initialized) {
          PDFBoxResourceLoader.init(context.applicationContext)
          initialized = true
        }
      }
    }
  }
}

object PdfTextCleanup {
  private val CRLF_REGEX = Regex("\\r\\n?")
  private val SOFT_HYPHEN_REGEX = Regex("\\u00AD")
  private val HYPHEN_BREAK_REGEX = Regex("([\\p{L}])[-\u2010]\\n([\\p{L}])")
  private val PAGE_NUMBER_ONLY_REGEX = Regex("^\\s*(?:page\\s+)?\\d{1,5}\\s*$", RegexOption.IGNORE_CASE)
  private val DIGIT_NORM_REGEX = Regex("\\d+")
  private val HORIZONTAL_WS_REGEX = Regex("[ \\t]+")
  private val LINE_WRAP_REGEX = Regex("(?<![.!?:;\"”’\\]\\n])\\n(?=[\\p{Ll}0-9(\"'“‘])")
  private val MULTI_NL_REGEX = Regex("\\n{3,}")
  private const val MIN_READABLE_TOKENS = 20
  private const val PAGES_PER_SECTION = 15

  fun toBook(extraction: PdfExtraction, fallbackFileName: String): EpubBook {
    val cleanedPages = cleanPages(extraction.pages)
    val nonBlankPages = cleanedPages.mapIndexedNotNull { idx, text ->
      text.takeIf { it.isNotBlank() }?.let { (idx + 1) to it }
    }
    val combined = nonBlankPages.joinToString("\n\n") { it.second }
    val totalTokens = ReadAlongText.tokens(combined).size
    check(totalTokens >= MIN_READABLE_TOKENS) {
      "This PDF contains scanned images with no selectable text. Use 'Generate script from audio' instead."
    }

    val title = extraction.title?.takeIf { it.isNotBlank() }
      ?: fallbackFileName.substringBeforeLast('.').ifBlank { "Imported PDF" }
    val author = extraction.author?.takeIf { it.isNotBlank() } ?: "Unknown Author"

    val headingChapters = EpubExtractor.parseTextIntoChapters(combined)
    val chapters = if (headingChapters.size >= 2) {
      headingChapters
    } else {
      nonBlankPages.chunked(PAGES_PER_SECTION).mapIndexed { chunkIdx, group ->
        val firstPage = group.first().first
        val lastPage = group.last().first
        val label = if (firstPage == lastPage) "Page $firstPage" else "Pages $firstPage–$lastPage"
        val sectionText = group.joinToString("\n\n") { it.second }
        EpubChapter(
          index = chunkIdx + 1,
          id = "pdf_sec_${chunkIdx + 1}",
          href = "pages_${firstPage}_$lastPage.txt",
          title = label,
          text = sectionText,
          wordCount = ReadAlongText.tokens(sectionText).size,
        )
      }
    }
    return EpubBook(title, author, chapters)
  }

  fun cleanPages(rawPages: List<String>): List<String> {
    if (rawPages.isEmpty()) return emptyList()
    val normalizedLinesPerPage = rawPages.map { page ->
      CRLF_REGEX.replace(page, "\n")
        .replace(SOFT_HYPHEN_REGEX, "")
        .lines()
        .map { it.replace(HORIZONTAL_WS_REGEX, " ").trimEnd() }
    }

    val topCounts = HashMap<String, Int>()
    val bottomCounts = HashMap<String, Int>()
    normalizedLinesPerPage.forEach { lines ->
      val nonEmpty = lines.filter { it.isNotBlank() }
      nonEmpty.firstOrNull()?.let { first ->
        val norm = normalizeHeaderCandidate(first)
        if (norm.length in 3..70) topCounts[norm] = (topCounts[norm] ?: 0) + 1
      }
      nonEmpty.lastOrNull()?.let { last ->
        val norm = normalizeHeaderCandidate(last)
        if (norm.length in 3..70) bottomCounts[norm] = (bottomCounts[norm] ?: 0) + 1
      }
    }
    val repeatedThreshold = (rawPages.size / 3).coerceAtLeast(3)
    val repeatedTops = topCounts.filterValues { it >= repeatedThreshold }.keys
    val repeatedBottoms = bottomCounts.filterValues { it >= repeatedThreshold }.keys

    return normalizedLinesPerPage.map { lines ->
      val trimmed = lines.toMutableList()
      while (trimmed.isNotEmpty() && trimmed.first().isBlank()) trimmed.removeAt(0)
      while (trimmed.isNotEmpty() && trimmed.last().isBlank()) trimmed.removeAt(trimmed.lastIndex)

      if (trimmed.isNotEmpty()) {
        val top = trimmed.first()
        if (PAGE_NUMBER_ONLY_REGEX.matches(top) || normalizeHeaderCandidate(top) in repeatedTops) {
          trimmed.removeAt(0)
        }
      }
      if (trimmed.isNotEmpty()) {
        val bottom = trimmed.last()
        if (PAGE_NUMBER_ONLY_REGEX.matches(bottom) || normalizeHeaderCandidate(bottom) in repeatedBottoms) {
          trimmed.removeAt(trimmed.lastIndex)
        }
      }

      val joined = trimmed
        .filterNot { PAGE_NUMBER_ONLY_REGEX.matches(it) }
        .joinToString("\n") { it.trim() }
      val dehyphenated = HYPHEN_BREAK_REGEX.replace(joined, "$1$2")
      LINE_WRAP_REGEX.replace(dehyphenated, " ")
        .replace(MULTI_NL_REGEX, "\n\n")
        .trim()
    }
  }

  private fun normalizeHeaderCandidate(line: String): String =
    DIGIT_NORM_REGEX.replace(line.trim().lowercase(), "#").replace(HORIZONTAL_WS_REGEX, " ")
}

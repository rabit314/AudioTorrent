package com.example.rivulet.data.lyrics

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object EpubExtractor {

  fun extractBookFromBytes(bytes: ByteArray, fileName: String): EpubBook {
    val lower = fileName.lowercase()
    if (lower.endsWith(".epub")) {
      return EpubStructureParser.parse(ByteArrayInputStream(bytes))
    }

    // TXT file handling: split by Chapter headers if present, or split into sensible segments
    val rawText = String(bytes, Charsets.UTF_8)
    val title = fileName.substringBeforeLast('.')
    val chapters = parseTextIntoChapters(rawText)
    return EpubBook(title, "Unknown Author", chapters)
  }

  fun parseTextIntoChapters(text: String): List<EpubChapter> {
    val clean = text.trim()
    if (clean.isBlank()) return emptyList()

    // Detect chapter split patterns (e.g., "Chapter 1", "CHAPTER I", "Section 1")
    val chapterPattern = Regex("(?m)^(?:chapter|part|section|book)\\s+([0-9ivxlcdm]+|[a-z]+)[\t :.-]*([^\r\n]*)", RegexOption.IGNORE_CASE)
    val matches = chapterPattern.findAll(clean).toList()

    if (matches.size >= 2) {
      val result = ArrayList<EpubChapter>()
      for (i in matches.indices) {
        val start = matches[i].range.first
        val end = if (i + 1 < matches.size) matches[i + 1].range.first else clean.length
        val chunk = clean.substring(start, end).trim()
        val titleLine = chunk.lines().firstOrNull()?.trim() ?: "Chapter ${i + 1}"
        val content = if (chunk.lines().size > 1) chunk.lines().drop(1).joinToString("\n").trim() else chunk
        val words = chunk.split(Regex("\\s+")).count { it.isNotBlank() }

        result.add(
          EpubChapter(
            index = i + 1,
            id = "txt_ch_${i + 1}",
            href = "ch_${i + 1}.txt",
            title = titleLine.take(80),
            text = content,
            wordCount = words,
          )
        )
      }
      return result
    }

    // Single chapter fallback
    val words = clean.split(Regex("\\s+")).count { it.isNotBlank() }
    return listOf(
      EpubChapter(
        index = 1,
        id = "txt_ch_1",
        href = "full_text.txt",
        title = "Full Text",
        text = clean,
        wordCount = words,
      )
    )
  }

  /**
   * Reads an EPUB stream and extracts clean text from all XHTML/HTML chapter documents.
   */
  fun extractTextFromEpub(inputStream: InputStream): String {
    val book = EpubStructureParser.parse(inputStream)
    return if (book.chapters.isNotEmpty()) {
      book.chapters.joinToString("\n\n") { it.text }
    } else {
      ""
    }
  }

  fun extractTextFromBytes(bytes: ByteArray, fileName: String): String {
    val lower = fileName.lowercase()
    return when {
      lower.endsWith(".epub") -> extractTextFromEpub(ByteArrayInputStream(bytes))
      lower.endsWith(".txt") -> String(bytes, Charsets.UTF_8)
      else -> String(bytes, Charsets.UTF_8)
    }
  }

  fun stripHtml(html: String): String {
    return html
      .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
      .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
      .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
      .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
      .replace(Regex("<[^>]+>"), "")
      .replace("&nbsp;", " ")
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&apos;", "'")
      .replace(Regex("[ \t]+"), " ")
      .replace(Regex("\n{3,}"), "\n\n")
      .trim()
  }
}

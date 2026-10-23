package com.example.rivulet.data.lyrics

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object EpubExtractor {

  /**
   * Reads an EPUB stream and extracts clean text from all XHTML/HTML chapter documents.
   */
  fun extractTextFromEpub(inputStream: InputStream): String {
    val textBuilder = StringBuilder()

    ZipInputStream(inputStream).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        val name = entry.name.lowercase()
        if ((name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) &&
          !name.contains("cover") && !name.contains("toc")
        ) {
          val content = zis.bufferedReader(Charsets.UTF_8).readText()
          val cleanText = stripHtml(content)
          if (cleanText.isNotBlank()) {
            if (textBuilder.isNotEmpty()) textBuilder.append("\n\n")
            textBuilder.append(cleanText)
          }
        }
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }

    return textBuilder.toString()
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

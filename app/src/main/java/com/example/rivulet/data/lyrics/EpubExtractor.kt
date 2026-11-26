package com.example.rivulet.data.lyrics

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

object EpubExtractor {
  private val CHAPTER_PATTERN = Regex("(?m)^(?:chapter|part|section|book)\\s+([0-9ivxlcdm]+|[a-z]+)[\t :.-]*([^\r\n]*)", RegexOption.IGNORE_CASE)
  private val WHITESPACE_REGEX = Regex("\\s+")
  private val HORIZONTAL_WS_REGEX = Regex("[ \\t]+")
  private val MULTI_NEWLINE_REGEX = Regex("\\n{3,}")
  private const val MAX_FB2_EXPANDED = 24 * 1024 * 1024

  fun extractBookFromBytes(bytes: ByteArray, fileName: String): EpubBook {
    val lower = fileName.lowercase()
    if (lower.endsWith(".epub")) {
      return EpubStructureParser.parse(ByteArrayInputStream(bytes))
    }
    if (lower.endsWith(".fb2")) {
      return parseFb2(String(bytes, Charsets.UTF_8), fileName)
    }

    // TXT file handling: split by Chapter headers if present, or split into sensible segments
    val rawText = String(bytes, Charsets.UTF_8)
    val title = fileName.substringBeforeLast('.')
    val chapters = parseTextIntoChapters(rawText)
    return EpubBook(title, "Unknown Author", chapters)
  }

  fun extractZipBookFromBytes(bytes: ByteArray, fileName: String): EpubBook {
    val epubError = runCatching { return EpubStructureParser.parse(ByteArrayInputStream(bytes)) }.exceptionOrNull()
    val fb2Bytes = extractFb2EntryFromZip(bytes)
    if (fb2Bytes != null) {
      return parseFb2(String(fb2Bytes, Charsets.UTF_8), fileName)
    }
    if (epubError != null) throw epubError
    error("This archive does not contain a readable EPUB or FB2 book.")
  }

  fun parseFb2(xml: String, fallbackFileName: String): EpubBook {
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    val title = doc.selectFirst("description > title-info > book-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
      ?: fallbackFileName.substringBeforeLast('.').ifBlank { "Imported FB2" }
    val author = doc.select("description > title-info > author").mapNotNull { authorEl ->
      listOfNotNull(
        authorEl.selectFirst("first-name")?.text()?.trim(),
        authorEl.selectFirst("middle-name")?.text()?.trim(),
        authorEl.selectFirst("last-name")?.text()?.trim(),
      ).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
    }.joinToString(", ").ifBlank { "Unknown Author" }

    val bodies = doc.select("FictionBook > body").filterNot { it.attr("name").equals("notes", true) }
    val sectionEls = bodies.flatMap { body ->
      val topSections = body.children().filter { it.tagName().equals("section", true) }
      if (topSections.isNotEmpty()) topSections else listOf(body)
    }

    val chapters = sectionEls.mapIndexedNotNull { idx, section ->
      val heading = section.children().firstOrNull { it.tagName().equals("title", true) }?.text()?.trim()
        ?.takeIf { it.isNotBlank() } ?: "Section ${idx + 1}"
      val text = extractFb2SectionText(section)
      if (text.isBlank()) null
      else EpubChapter(
        index = idx + 1,
        id = "fb2_ch_${idx + 1}",
        href = "fb2_${idx + 1}.xml",
        title = heading.take(80),
        text = text,
        wordCount = ReadAlongText.tokens(text).size,
      )
    }
    require(chapters.isNotEmpty()) { "FB2 file has no readable chapters." }
    return EpubBook(title, author, chapters)
  }

  private fun extractFb2SectionText(section: Element): String {
    val out = ArrayList<String>()
    fun walk(el: Element, skipTitle: Boolean) {
      for (child in el.children()) {
        when (child.tagName().lowercase()) {
          "title" -> if (!skipTitle) {
            val t = child.text().trim()
            if (t.isNotBlank()) out += t
          }
          "p", "subtitle", "v", "text-author" -> {
            val t = child.text().trim()
            if (t.isNotBlank()) out += t
          }
          "empty-line" -> Unit
          else -> walk(child, skipTitle = false)
        }
      }
    }
    walk(section, skipTitle = true)
    return out.joinToString("\n\n").replace(HORIZONTAL_WS_REGEX, " ").replace(MULTI_NEWLINE_REGEX, "\n\n").trim()
  }

  private fun extractFb2EntryFromZip(bytes: ByteArray): ByteArray? {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      var entry = zip.nextEntry
      while (entry != null) {
        if (!entry.isDirectory && entry.name.endsWith(".fb2", ignoreCase = true)) {
          val out = ByteArrayOutputStream()
          val buf = ByteArray(8192)
          while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            require(out.size() + n <= MAX_FB2_EXPANDED) { "FB2 archive exceeds the import limit." }
            out.write(buf, 0, n)
          }
          return out.toByteArray()
        }
        zip.closeEntry()
        entry = zip.nextEntry
      }
    }
    return null
  }

  fun parseTextIntoChapters(text: String): List<EpubChapter> {
    val clean = text.trim()
    if (clean.isBlank()) return emptyList()

    val matches = CHAPTER_PATTERN.findAll(clean).toList()

    if (matches.size >= 2) {
      val result = ArrayList<EpubChapter>()
      for (i in matches.indices) {
        val start = matches[i].range.first
        val end = if (i + 1 < matches.size) matches[i + 1].range.first else clean.length
        val chunk = clean.substring(start, end).trim()
        val titleLine = chunk.lines().firstOrNull()?.trim() ?: "Chapter ${i + 1}"
        val content = if (chunk.lines().size > 1) chunk.lines().drop(1).joinToString("\n").trim() else chunk
        val words = chunk.split(WHITESPACE_REGEX).count { it.isNotBlank() }

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
    val words = clean.split(WHITESPACE_REGEX).count { it.isNotBlank() }
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

  fun stripHtml(html: String): String {
    val doc = org.jsoup.Jsoup.parse(html)
    doc.select("script, style, nav").remove()
    doc.select("br").forEach { it.before(org.jsoup.nodes.TextNode("\n")) }
    doc.select("p, div, h1, h2, h3, li").forEach { it.appendChild(org.jsoup.nodes.TextNode("\n\n")) }
    return doc.body().wholeText().replace('\u00a0', ' ').replace(HORIZONTAL_WS_REGEX, " ")
      .replace(MULTI_NEWLINE_REGEX, "\n\n").trim()
  }
}
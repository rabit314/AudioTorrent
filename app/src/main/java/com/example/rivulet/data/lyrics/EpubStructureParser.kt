package com.example.rivulet.data.lyrics

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipInputStream

data class EpubBook(val title: String, val author: String, val chapters: List<EpubChapter>, val isbn: String? = null) {
  val totalWordCount: Int get() = chapters.sumOf { it.wordCount }
}

data class EpubChapter(val index: Int, val id: String, val href: String, val title: String, val text: String, val wordCount: Int)

/** Bounded EPUB container, OPF spine, EPUB 2 NCX and EPUB 3 navigation reader. */
object EpubStructureParser {
  private const val MAX_EXPANDED = 64 * 1024 * 1024
  private const val MAX_DOCUMENT = 8 * 1024 * 1024

  fun parse(inputStream: InputStream): EpubBook {
    val entries = LinkedHashMap<String, String>()
    var expanded = 0L
    var entryCount = 0
    ZipInputStream(inputStream).use { zip ->
      var entry = zip.nextEntry
      while (entry != null) {
        require(++entryCount <= 10_000) { "EPUB contains too many files." }
        val name = entry.name.replace('\\', '/')
        if (!entry.isDirectory) {
          val readable = name.substringAfterLast('.').lowercase() in setOf("xml", "opf", "xhtml", "html", "htm", "ncx")
          val buffer = if (readable) java.io.ByteArrayOutputStream() else null
          val chunk = ByteArray(8192)
          while (true) {
            val count = zip.read(chunk)
            if (count < 0) break
            require(buffer == null || buffer.size() + count <= MAX_DOCUMENT) { "An EPUB chapter is too large." }
            expanded += count
            require(expanded <= MAX_EXPANDED) { "EPUB text exceeds the import limit." }
            buffer?.write(chunk, 0, count)
          }
          if (buffer != null) entries[name] = buffer.toString("UTF-8")
        }
        zip.closeEntry()
        entry = zip.nextEntry
      }
    }
    require(entries.isNotEmpty()) { "This file is not a readable EPUB." }
    fun entry(path: String) = entries[path] ?: entries.entries.firstOrNull { it.key.equals(path, true) }?.value
    val container = entry("META-INF/container.xml")?.let { Jsoup.parse(it, "", Parser.xmlParser()) }
    val requestedOpf = container?.getAllElements()?.firstOrNull { it.localTag() == "rootfile" }?.attr("full-path")
    val opfPath = requestedOpf?.takeIf { entry(it) != null }
      ?: entries.keys.firstOrNull { it.endsWith(".opf", true) }
      ?: error("EPUB package metadata is missing.")
    val opf = Jsoup.parse(entry(opfPath)!!, "", Parser.xmlParser())
    fun metadata(tag: String) = opf.getAllElements().firstOrNull { it.localTag() == tag }?.text()
    val title = metadata("title") ?: "Imported Ebook"
    val author = metadata("creator") ?: "Unknown Author"
    val isbn = opf.getAllElements().filter { it.localTag() == "identifier" }
      .firstNotNullOfOrNull { IsbnValidator.extractFirstValidIsbn(it.text()) }
    val manifest = opf.getAllElements().filter { it.localTag() == "item" }.associateBy { it.attr("id") }
    val titles = LinkedHashMap<String, String>()
    manifest.values.filter { it.attr("media-type") == "application/x-dtbncx+xml" || "nav" in it.attr("properties").split(' ') }
      .forEach { item ->
        val tocPath = resolveHref(opfPath, item.attr("href"))
        val toc = entry(tocPath)?.let { Jsoup.parse(it, "", Parser.xmlParser()) } ?: return@forEach
        toc.getAllElements().filter { it.localTag() == "navPoint" }.forEach { point ->
          val content = point.getAllElements().firstOrNull { it.localTag() == "content" }
          val label = point.getAllElements().firstOrNull { it.localTag() == "navLabel" }?.text()
          if (content != null && !label.isNullOrBlank()) titles.putIfAbsent(resolveHref(tocPath, content.attr("src")), label)
        }
        toc.getAllElements().filter { it.localTag() == "a" && it.hasAttr("href") }.forEach { link ->
          if (link.text().isNotBlank()) titles.putIfAbsent(resolveHref(tocPath, link.attr("href")), link.text())
        }
      }
    val chapters = opf.getAllElements().filter { it.localTag() == "itemref" && it.attr("linear") != "no" }
      .mapNotNull { ref -> manifest[ref.attr("idref")] }.mapNotNull { item ->
        val path = resolveHref(opfPath, item.attr("href"))
        val html = entry(path) ?: return@mapNotNull null
        val text = EpubExtractor.stripHtml(html)
        if (text.isBlank()) return@mapNotNull null
        val heading = Jsoup.parse(html).selectFirst("h1, h2, h3")?.text()
        Triple(item.attr("id"), path, Triple(titles[path] ?: heading ?: "Chapter", text, ReadAlongText.tokens(text).size))
      }.mapIndexed { index, (id, path, content) ->
        EpubChapter(index + 1, id, path, content.first, content.second, content.third)
      }
    require(chapters.isNotEmpty()) { "EPUB has no readable chapters in its reading order." }
    return EpubBook(title, author, chapters, isbn)
  }

  fun resolveHref(baseFile: String, href: String): String {
    val uri = URI("/" + baseFile.replace(" ", "%20")).resolve(href.replace(" ", "%20")).normalize()
    require(uri.scheme == null && uri.host == null) { "EPUB contains an external chapter reference." }
    val path = uri.path.removePrefix("/")
    require(!path.startsWith("../")) { "Invalid EPUB chapter path." }
    return path
  }

  private fun Element.localTag() = tagName().substringAfter(':')
}

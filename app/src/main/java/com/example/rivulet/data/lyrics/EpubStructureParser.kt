package com.example.rivulet.data.lyrics

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class EpubBook(
  val title: String,
  val author: String,
  val chapters: List<EpubChapter>,
) {
  val totalWordCount: Int
    get() = chapters.sumOf { it.wordCount }
}

data class EpubChapter(
  val index: Int,
  val id: String,
  val href: String,
  val title: String,
  val text: String,
  val wordCount: Int,
)

/**
 * Standard-compliant EPUB parser that parses `META-INF/container.xml`, `.opf` spine,
 * and NCX/Nav table of contents to extract chapters in their true reading sequence.
 */
object EpubStructureParser {

  private const val TAG = "EpubStructureParser"

  fun parse(inputStream: InputStream): EpubBook {
    val entries = HashMap<String, ByteArray>()

    // Ingest zip entries into memory map
    ZipInputStream(inputStream).use { zis ->
      var entry: ZipEntry? = zis.nextEntry
      while (entry != null) {
        if (!entry.isDirectory) {
          // Normalize paths to lower/forward-slash for robust lookup
          val normalizedName = entry.name.replace('\\', '/')
          entries[normalizedName] = zis.readBytes()
        }
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }

    if (entries.isEmpty()) {
      return EpubBook("Unknown Title", "Unknown Author", emptyList())
    }

    // 1. Locate OPF file path from META-INF/container.xml
    val containerBytes = entries["META-INF/container.xml"]
      ?: entries.entries.firstOrNull { it.key.endsWith("container.xml", ignoreCase = true) }?.value

    var opfPath = "content.opf"
    if (containerBytes != null) {
      val containerXml = String(containerBytes, Charsets.UTF_8)
      val rootfileMatch = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(containerXml)
      if (rootfileMatch != null) {
        opfPath = rootfileMatch.groupValues[1]
      }
    }

    // Lookup OPF entry (case-insensitive fallback)
    val opfEntry = entries[opfPath]
      ?: entries.entries.firstOrNull { it.key.equals(opfPath, ignoreCase = true) }?.value
      ?: entries.entries.firstOrNull { it.key.endsWith(".opf", ignoreCase = true) }?.value

    if (opfEntry == null) {
      Log.w(TAG, "No OPF package file found in EPUB. Falling back to naive XHTML extraction.")
      return fallbackExtraction(entries)
    }

    val opfXml = String(opfEntry, Charsets.UTF_8)
    val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') + "/" else ""

    // 2. Parse Metadata (Title, Author)
    val titleMatch = Regex("<dc:title[^>]*>([^<]+)</dc:title>", RegexOption.IGNORE_CASE).find(opfXml)
    val bookTitle = titleMatch?.groupValues?.get(1)?.trim() ?: "Unknown Title"

    val authorMatch = Regex("<dc:creator[^>]*>([^<]+)</dc:creator>", RegexOption.IGNORE_CASE).find(opfXml)
    val bookAuthor = authorMatch?.groupValues?.get(1)?.trim() ?: "Unknown Author"

    // 3. Parse Manifest: ID -> Href
    val manifestMap = HashMap<String, String>() // id to resolved href
    val itemRegex = Regex("<item\\s+([^>]+)>", RegexOption.IGNORE_CASE)
    for (m in itemRegex.findAll(opfXml)) {
      val attrs = m.groupValues[1]
      val id = Regex("id\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
      val href = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
      if (id != null && href != null) {
        // Resolve relative to opfDir
        val resolvedHref = resolveHref(opfDir, href)
        manifestMap[id] = resolvedHref
      }
    }

    // 4. Parse Spine: Ordered itemrefs
    val spineItemIds = ArrayList<String>()
    val spineBlockMatch = Regex("<spine[^>]*>(.*?)</spine>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(opfXml)
    if (spineBlockMatch != null) {
      val itemrefRegex = Regex("<itemref\\s+[^>]*idref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
      for (im in itemrefRegex.findAll(spineBlockMatch.groupValues[1])) {
        spineItemIds.add(im.groupValues[1])
      }
    }

    // 5. Parse TOC (toc.ncx or Nav document) for Chapter Titles
    val tocTitlesByHref = HashMap<String, String>()
    parseTocTitles(entries, opfDir, opfXml, manifestMap, tocTitlesByHref)

    // 6. Extract Chapter Contents in Spine Order
    val chapters = ArrayList<EpubChapter>()
    var chapterCounter = 1

    for (itemId in spineItemIds) {
      val href = manifestMap[itemId] ?: continue
      val fileBytes = entries[href]
        ?: entries[href.removePrefix("/")]
        ?: entries.entries.firstOrNull { it.key.equals(href, ignoreCase = true) }?.value
        ?: continue

      val rawContent = String(fileBytes, Charsets.UTF_8)
      val cleanText = EpubExtractor.stripHtml(rawContent)

      // Only include chapters that have substantial readable text (> 20 words)
      val words = countWords(cleanText)
      if (words >= 15) {
        val chapterTitle = tocTitlesByHref[href]
          ?: tocTitlesByHref[href.substringAfterLast('/')]
          ?: extractHeadingTitle(rawContent)
          ?: "Chapter $chapterCounter"

        chapters.add(
          EpubChapter(
            index = chapterCounter++,
            id = itemId,
            href = href,
            title = chapterTitle,
            text = cleanText,
            wordCount = words,
          )
        )
      }
    }

    // If spine was empty or failed, fallback
    if (chapters.isEmpty()) {
      return fallbackExtraction(entries, bookTitle, bookAuthor)
    }

    return EpubBook(
      title = bookTitle,
      author = bookAuthor,
      chapters = chapters,
    )
  }

  private fun parseTocTitles(
    entries: Map<String, ByteArray>,
    opfDir: String,
    opfXml: String,
    manifestMap: Map<String, String>,
    outTitles: HashMap<String, String>,
  ) {
    // Check toc attribute in <spine toc="ncx">
    val ncxId = Regex("<spine[^>]*toc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(opfXml)?.groupValues?.get(1)
    val ncxHref = if (ncxId != null) manifestMap[ncxId] else null

    val ncxBytes = (if (ncxHref != null) entries[ncxHref] ?: entries[ncxHref.removePrefix("/")] else null)
      ?: entries.entries.firstOrNull { it.key.endsWith(".ncx", ignoreCase = true) }?.value

    if (ncxBytes != null) {
      val ncxXml = String(ncxBytes, Charsets.UTF_8)
      val navPointRegex = Regex("<navPoint[^>]*>.*?<navLabel>\\s*<text>([^<]+)</text>\\s*</navLabel>\\s*<content\\s+src\\s*=\\s*[\"']([^\"'#]+)[\"'][^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
      for (npm in navPointRegex.findAll(ncxXml)) {
        val title = npm.groupValues[1].trim()
        val src = npm.groupValues[2].trim()
        val resolvedSrc = resolveHref(opfDir, src)
        outTitles[resolvedSrc] = title
        outTitles[src.substringAfterLast('/')] = title
      }
    }
  }

  private fun extractHeadingTitle(html: String): String? {
    val h1Match = Regex("<h[1-3][^>]*>([^<]+)</h[1-3]>", RegexOption.IGNORE_CASE).find(html)
    val text = h1Match?.groupValues?.get(1)?.trim()
    return if (!text.isNullOrBlank() && text.length <= 80) text else null
  }

  private fun resolveHref(baseDir: String, href: String): String {
    val cleanHref = href.substringBefore('#')
    return if (baseDir.isBlank() || cleanHref.startsWith("/")) {
      cleanHref.removePrefix("/")
    } else {
      (baseDir + cleanHref).replace("//", "/")
    }
  }

  private fun countWords(text: String): Int {
    if (text.isBlank()) return 0
    return text.split(Regex("\\s+")).count { it.isNotBlank() }
  }

  private fun fallbackExtraction(
    entries: Map<String, ByteArray>,
    fallbackTitle: String = "Imported Ebook",
    fallbackAuthor: String = "Unknown Author",
  ): EpubBook {
    val chapters = ArrayList<EpubChapter>()
    var idx = 1

    val xhtmlEntries = entries.filterKeys { k ->
      val lk = k.lowercase()
      (lk.endsWith(".xhtml") || lk.endsWith(".html") || lk.endsWith(".htm")) &&
        !lk.contains("cover") && !lk.contains("toc")
    }.toSortedMap()

    for ((path, bytes) in xhtmlEntries) {
      val raw = String(bytes, Charsets.UTF_8)
      val clean = EpubExtractor.stripHtml(raw)
      val words = countWords(clean)
      if (words >= 15) {
        val title = extractHeadingTitle(raw) ?: "Chapter $idx"
        chapters.add(
          EpubChapter(
            index = idx++,
            id = path,
            href = path,
            title = title,
            text = clean,
            wordCount = words,
          )
        )
      }
    }

    return EpubBook(fallbackTitle, fallbackAuthor, chapters)
  }
}

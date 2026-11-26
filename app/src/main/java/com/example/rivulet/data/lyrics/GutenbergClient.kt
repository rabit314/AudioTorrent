package com.example.rivulet.data.lyrics

import com.example.rivulet.data.catalog.PublicCatalogHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException

/** The official machine-readable catalog; no HTML search-page crawling. */
interface GutenbergCatalog : BookCatalog { fun byId(id: String): List<BookEdition> }

class GutenbergClient : GutenbergCatalog {
  private val http = PublicCatalogHttp(setOf("gutenberg.org"))
  private val cache = object : LinkedHashMap<String, List<BookEdition>>(24, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<BookEdition>>?) = size > 24
  }
  private fun cached(key: String, load: () -> List<BookEdition>): List<BookEdition> {
    synchronized(cache) { cache[key]?.let { return it } }
    return load().also { synchronized(cache) { cache[key] = it } }
  }
  override fun search(query: String): List<BookEdition> = cached("search:$query") {
    require(query.isNotBlank()) { "Enter a book title." }
    val url = "$BASE/ebooks/search.opds/".toHttpUrl().newBuilder().addQueryParameter("query", query.trim()).build()
    val entries = parseSearch(http.text(url.toString()))
    // Navigation entries omit language/acquisitions. Hydrate only a bounded, relevant shortlist.
    val relevant = BookSearch.rank(entries, query).take(5).map { it.providerId }.toSet()
    entries.flatMap { entry ->
      if (entry.providerId in relevant) runCatching { byId(entry.providerId) }.getOrDefault(listOf(entry)) else listOf(entry)
    }
  }
  override fun byId(id: String): List<BookEdition> {
    require(id.matches(Regex("[1-9]\\d*"))) { "Invalid Gutenberg book ID." }
    return cached("book:$id") { parseDetails(http.text("$BASE/ebooks/$id.opds"), id) }
  }
  override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> {
    require(edition.provider == TextProvider.GUTENBERG)
    val resolved = edition.downloadUrl?.let { edition } ?: byId(edition.providerId).firstOrNull()
      ?: throw IOException("No EPUB is available for this Gutenberg edition.")
    val bytes = http.bytes(resolved.downloadUrl ?: throw IOException("No EPUB was linked."), BookImport.MAX_BYTES)
    require(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) { "Gutenberg returned a page instead of an EPUB. Try again later." }
    return Triple(bytes, edition.title.take(120) + ".epub", "application/epub+zip")
  }
  companion object {
    const val BASE = "https://www.gutenberg.org"
    fun bookId(raw: String?): String? {
      val url = runCatching { raw?.toHttpUrl() }.getOrNull() ?: return null
      if (url.host !in setOf("gutenberg.org", "www.gutenberg.org") || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
      return Regex("^/(?:ebooks|etext|files|cache/epub)/([1-9]\\d*)(?:[./]|$)").find(url.encodedPath)?.groupValues?.get(1)
    }
    fun parseSearch(xml: String): List<BookEdition> = Jsoup.parse(xml, BASE, Parser.xmlParser()).select("feed > entry").mapNotNull { entry ->
      val id = bookId(entry.selectFirst("id")?.text()) ?: return@mapNotNull null
      val title = entry.selectFirst("title")?.text().orEmpty()
      if (title.isBlank()) return@mapNotNull null
      BookEdition(title, entry.select("author > name").text().ifBlank { entry.selectFirst("content")?.text().orEmpty() }, "", "EPUB", null,
        "$BASE/ebooks/$id", provider = TextProvider.GUTENBERG, providerId = id)
    }.distinctBy { it.providerId }
    fun parseDetails(xml: String, id: String): List<BookEdition> {
      val entries = Jsoup.parse(xml, "$BASE/ebooks/$id.opds", Parser.xmlParser()).select("feed > entry")
      return entries.mapNotNull { entry ->
        if (!entry.selectFirst("rights")?.text().orEmpty().contains("public domain", true)) return@mapNotNull null
        val link = entry.select("link").firstOrNull { it.attr("rel").startsWith("http://opds-spec.org/acquisition") && it.attr("type") == "application/epub+zip" }
          ?: return@mapNotNull null
        val download = link.absUrl("href")
        val urlId = bookId(download) ?: return@mapNotNull null
        if (urlId != id) return@mapNotNull null
        val cover = entry.select("link").firstOrNull { it.attr("rel") == "http://opds-spec.org/image/thumbnail" }?.absUrl("href")
        val language = entry.getElementsByTag("dcterms:language").firstOrNull()?.text().orEmpty()
        BookEdition(entry.selectFirst("title")?.text().orEmpty(), entry.select("author > name").joinToString(", ") { it.text() },
          language, "EPUB", null, "$BASE/ebooks/$id", download, cover, TextProvider.GUTENBERG, id)
      }.distinctBy { it.providerId }
    }
  }
}

/**
 * Multi-source coordinator.
 * - Zero-arg constructor enables all sources (`Gutenberg`, `LibGen`, `Z-Library`, `Anna's Archive`, `WeLib`).
 * - Two-arg `(gutenberg, fallback)` preserves isolated unit-test behavior without invoking live secondary networks.
 */
class GutenbergFirstCatalog(
  private val gutenberg: BookCatalog,
  private val fallback: BookCatalog,
  private val libgen: BookCatalog?,
  private val annasArchive: BookCatalog?,
  private val welib: BookCatalog?,
) : BookCatalog {

  constructor() : this(
    gutenberg = GutenbergClient(),
    fallback = ZLibraryClient(),
    libgen = LibGenClient(),
    annasArchive = AnnasArchiveClient(),
    welib = WeLibClient(),
  )

  constructor(
    gutenberg: BookCatalog,
    fallback: BookCatalog,
  ) : this(
    gutenberg = gutenberg,
    fallback = fallback,
    libgen = null,
    annasArchive = null,
    welib = null,
  )

  override fun search(query: String): List<BookEdition> = lookup(BookLookupRequest(query)).editions

  override fun lookup(request: BookLookupRequest): BookLookupResult {
    when (request.provider) {
      TextProvider.ZLIBRARY -> return fallback.lookup(request)
      TextProvider.LIBGEN -> return (libgen ?: throw IOException("LibGen is unavailable.")).lookup(request)
      TextProvider.ANNAS_ARCHIVE -> return (annasArchive ?: throw IOException("Anna's Archive is unavailable.")).lookup(request)
      TextProvider.WELIB -> return (welib ?: throw IOException("WeLib is unavailable.")).lookup(request)
      TextProvider.GUTENBERG, TextProvider.AUTOMATIC -> Unit
    }

    var publicBooks = emptyList<BookEdition>()
    var warning: String? = null
    fun suitable(edition: BookEdition): Boolean {
      val author = ReadAlongText.tokens(request.author).toSet()
      return BookSearch.matchesTitle(edition, request.title) &&
        (author.isEmpty() || author == ReadAlongText.tokens(edition.author).toSet()) &&
        edition.language.trim().lowercase() in setOf("", "english", "en", "eng")
    }
    try {
      val linkedId = GutenbergClient.bookId(request.linkedTextUrl)
      if (linkedId != null && gutenberg is GutenbergCatalog) {
        publicBooks = try { gutenberg.byId(linkedId).map { it.copy(linkedSource = true) } }
          catch (error: java.util.concurrent.CancellationException) { throw error }
          catch (_: Exception) { emptyList() }
      }
      if (publicBooks.none(::suitable)) {
        publicBooks = gutenberg.search(request.query)
        if (publicBooks.none(::suitable) && request.author.isNotBlank() && request.query != request.title) {
          publicBooks = gutenberg.search(request.title)
        }
      }
      if (request.provider == TextProvider.GUTENBERG || publicBooks.any(::suitable)) return BookLookupResult(publicBooks)
    } catch (error: Exception) {
      if (error is java.util.concurrent.CancellationException) throw error
      if (request.provider == TextProvider.GUTENBERG) throw error
      warning = if (error is com.example.rivulet.data.catalog.CatalogHttpException && error.status == 503) {
        "Project Gutenberg is temporarily down for maintenance (HTTP 503)."
      } else {
        "Project Gutenberg is unavailable."
      }
    }

    var libgenBooks = emptyList<BookEdition>()
    var libgenError: Exception? = null
    if (libgen != null) {
      try {
        libgenBooks = libgen.lookup(request).editions
      } catch (error: Exception) {
        if (error is java.util.concurrent.CancellationException) throw error
        libgenError = error
      }
    }

    return try {
      val other = fallback.lookup(request)
      val merged = BookSearch.rank(publicBooks + libgenBooks + other.editions, request.query, request.title, request.author)
      val combinedMessage = listOfNotNull(warning, other.message).distinct().joinToString(" ").ifBlank { null }
      other.copy(editions = merged, message = combinedMessage)
    } catch (error: SiteAccessRequired) {
      val combinedSoFar = BookSearch.rank(publicBooks + libgenBooks, request.query, request.title, request.author)
      if (libgenBooks.isNotEmpty()) {
        // LibGen already produced matches without triggering a browser verification wall.
        BookLookupResult(combinedSoFar, warning, accessUrl = null, complete = true)
      } else {
        val combined = listOfNotNull(warning, "Z-Library needs a browser access check or login.").joinToString(" ")
        BookLookupResult(combinedSoFar, combined, error.url, complete = false)
      }
    } catch (error: Exception) {
      if (error is java.util.concurrent.CancellationException) throw error
      val combinedSoFar = BookSearch.rank(publicBooks + libgenBooks, request.query, request.title, request.author)
      if (combinedSoFar.isEmpty()) {
        val secondaryMsg = listOfNotNull(libgenError?.message, error.message).distinct().joinToString(" / ").ifBlank { "unavailable." }
        val pgPrefix = warning?.let { "$it " }.orEmpty()
        throw IOException("${pgPrefix}Book lookup failed: $secondaryMsg", error)
      }
      val statusNote = if (libgenBooks.isNotEmpty()) null else "Z-Library is unavailable. Showing Gutenberg results."
      BookLookupResult(combinedSoFar, listOfNotNull(warning, statusNote).joinToString(" ").ifBlank { null }, complete = libgenBooks.isNotEmpty())
    }
  }

  override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> = when (edition.provider) {
    TextProvider.GUTENBERG -> gutenberg.download(edition, null)
    TextProvider.ZLIBRARY -> fallback.download(edition, renderedHtml)
    TextProvider.LIBGEN -> {
      val client = libgen ?: error("LibGen is unavailable.")
      try {
        client.download(edition, renderedHtml)
      } catch (error: Exception) {
        if (error is java.util.concurrent.CancellationException || error is SiteAccessRequired) throw error
        val md5 = LibGenClient.extractMd5(edition.providerId) ?: LibGenClient.extractMd5(edition.url)
        val annas = annasArchive as? AnnasArchiveClient
        if (md5 != null && annas != null) {
          runCatching { annas.download(edition.copy(provider = TextProvider.ANNAS_ARCHIVE), null) }.getOrElse { throw error }
        } else {
          throw error
        }
      }
    }
    TextProvider.ANNAS_ARCHIVE -> (annasArchive ?: error("Anna's Archive is unavailable.")).download(edition, renderedHtml)
    TextProvider.WELIB -> (welib ?: error("WeLib is unavailable.")).download(edition, renderedHtml)
    TextProvider.AUTOMATIC -> error("Choose a text edition first.")
  }
}

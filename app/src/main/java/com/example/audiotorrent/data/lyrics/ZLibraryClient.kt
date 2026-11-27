package com.example.audiotorrent.data.lyrics

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

enum class TextProvider(val label: String) {
  AUTOMATIC("Automatic (All Sources)"),
  GUTENBERG("Public Domain (Gutenberg)"),
  LIBGEN("LibGen"),
  ZLIBRARY("Z-Library"),
  ANNAS_ARCHIVE("Anna's Archive"),
  WELIB("WeLib"),
}

data class BookEdition(
  val title: String,
  val author: String,
  val language: String,
  val format: String,
  val isbn: String?,
  val url: String,
  val downloadUrl: String? = null,
  val coverUrl: String? = null,
  val provider: TextProvider = TextProvider.ZLIBRARY,
  val providerId: String = "",
  val linkedSource: Boolean = false,
) {
  val catalogKey: String get() = providerId.ifBlank { url.substringBefore('#').trimEnd('/') }
}

data class BookLookupRequest(
  val query: String,
  val title: String = query,
  val author: String = "",
  val linkedTextUrl: String? = null,
  val provider: TextProvider = TextProvider.AUTOMATIC,
)

data class BookLookupResult(
  val editions: List<BookEdition>,
  val message: String? = null,
  val accessUrl: String? = null,
  val complete: Boolean = true,
)

class SiteAccessRequired(val url: String, message: String = "This site needs a browser check or login.") : IOException(message)

interface BookCatalog {
  fun search(query: String): List<BookEdition>
  fun lookup(request: BookLookupRequest): BookLookupResult = BookLookupResult(search(request.query))
  fun download(edition: BookEdition, renderedHtml: String? = null): Triple<ByteArray, String, String>
}

class ZLibraryClient(
  private val cookies: (String) -> String? = CatalogHttpHelpers.androidCookieReader(),
  private val saveCookies: (String, List<String>) -> Unit = CatalogHttpHelpers.androidCookieWriter(),
) : BookCatalog {
  @Volatile private var preferredBase: String = BASE

  private val client by lazy {
    CatalogHttpHelpers.sharedClient(
      cookies = cookies,
      saveCookies = saveCookies,
      connectTimeoutSec = 8L,
      readTimeoutSec = 15L,
    )
  }

  override fun search(query: String): List<BookEdition> {
    require(query.isNotBlank()) { "Enter a book title." }
    val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
    val candidates = (listOf(preferredBase) + MIRRORS).distinct()
    var firstAccess: SiteAccessRequired? = null
    var lastError: Exception? = null
    for (base in candidates) {
      val searchUrl = "$base/s/$encoded"
      try {
        val (html, resolvedUrl) = page(searchUrl, referer = "$base/")
        val resolvedHttp = resolvedUrl.toHttpUrl()
        if (isAllowedHost(resolvedHttp.host)) preferredBase = "${resolvedHttp.scheme}://${resolvedHttp.host}"
        return BookSearch.rank(parseResults(html, resolvedUrl), query)
      } catch (access: SiteAccessRequired) {
        if (firstAccess == null) firstAccess = access
      } catch (error: Exception) {
        if (error is java.util.concurrent.CancellationException) throw error
        lastError = error
      }
    }
    firstAccess?.let { throw it }
    throw lastError ?: IOException("Z-Library is unavailable. Retry or try LibGen / WeLib.")
  }

  override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> {
    CatalogDownloadBridge.parse(renderedHtml)?.let { capture ->
      return CatalogHttpHelpers.downloadBinaryFile(
        client = client,
        url = capture.url,
        edition = edition,
        referer = capture.referer ?: edition.url,
        userAgent = capture.userAgent ?: USER_AGENT,
        siteName = "Z-Library",
        isAccessRequired = ::needsAccess,
        accessFallbackUrl = edition.url,
      )
    }

    val html = if (renderedHtml != null) {
      if (needsAccess(200, renderedHtml)) throw SiteAccessRequired(edition.url)
      renderedHtml
    } else null

    val url = edition.downloadUrl ?: run {
      val pageHtml = html ?: page(edition.url).first
      val doc = Jsoup.parse(pageHtml, edition.url)
      val link = doc.select("a[href]").firstOrNull { element ->
        val href = element.attr("href")
        (href.contains("/dl/", true) || href.contains("/download/", true) || element.hasClass("dlButton") ||
          element.attr("download").let { dl -> dl.endsWith(".epub", true) || dl.endsWith(".pdf", true) || dl.endsWith(".txt", true) || dl.endsWith(".fb2", true) }) &&
          !element.text().contains("send to", true)
      } ?: run {
        if (needsAccess(200, pageHtml)) throw SiteAccessRequired(edition.url)
        throw IOException("No readable download is available for this edition. Check site access or choose another edition.")
      }
      link.absUrl("href")
    }
    return CatalogHttpHelpers.downloadBinaryFile(
      client = client,
      url = url,
      edition = edition,
      referer = edition.url,
      userAgent = USER_AGENT,
      siteName = "Z-Library",
      isAccessRequired = ::needsAccess,
      accessFallbackUrl = edition.url,
    )
  }

  private fun page(url: String, referer: String? = null): Pair<String, String> {
    validateUrl(url)
    client.newCall(CatalogHttpHelpers.browserRequest(url, referer, USER_AGENT)).execute().use { response ->
      val body = response.body ?: error("Z-Library returned an empty response.")
      val html = body.byteStream().use { BookImport.read(it, 4 * 1024 * 1024) }.toString(Charsets.UTF_8)
      val finalUrl = response.request.url.toString()
      if (needsAccess(response.code, html)) throw SiteAccessRequired(finalUrl)
      check(response.isSuccessful) { "Z-Library is unavailable (${response.code}). Retry or import a local ebook." }
      return html to finalUrl
    }
  }

  companion object {
    const val BASE = "https://1lib.sk"
    val MIRRORS = listOf(
      "https://1lib.sk",
      "https://z-lib.sk",
      "https://z-library.im",
      "https://z-library.sk",
      "https://zlib.li",
    )
    private val ALLOWED_HOSTS = setOf(
      "1lib.sk",
      "z-lib.sk",
      "z-library.im",
      "z-library.sk",
      "zlib.li",
      "z-lib.gd",
      "z-lib.fm",
      "singlelogin.co",
      "singlelogin.re",
    )
    const val USER_AGENT = CatalogHttpHelpers.DEFAULT_USER_AGENT

    fun isAllowedHost(host: String): Boolean = CatalogHttpHelpers.hostMatches(host, ALLOWED_HOSTS)

    fun validateUrl(url: String) {
      CatalogHttpHelpers.validateHttpsUrl(url)
    }

    fun needsAccess(code: Int, html: String): Boolean {
      if (CatalogHttpHelpers.isChallengeHtml(code, html)) return true
      val doc = Jsoup.parse(html)
      val bodyText = doc.body().text().lowercase(Locale.ROOT)
      val hasContentCards = doc.selectFirst("z-bookcard, .resItemBox, .bookRow, a[href*=/dl/], a[href*=/download/]") != null
      val loginWall = doc.selectFirst("input[type=password]") != null ||
        (!hasContentCards && doc.selectFirst("a[href*=/login], form[action*=/login]") != null &&
          (bodyText.contains("sign in") || bodyText.contains("log in") || bodyText.contains("daily limit")))
      return loginWall
    }

    fun parseResults(html: String, baseUrl: String): List<BookEdition> {
      val doc = Jsoup.parse(html, baseUrl)
      val result = LinkedHashMap<String, BookEdition>()
      val cards = doc.select("z-bookcard, .resItemBox, .bookRow, [data-book-id]")
      val roots: List<Element> = if (cards.isNotEmpty()) cards else doc.select("a[href*=/book/]").map { it.parent() ?: it }
      roots.forEach { card ->
        if (card.attr("deleted") == "1" || (card.parents() + card).any { it.hasAttr("hidden") || it.attr("aria-hidden") == "true" }) return@forEach
        val link = card.selectFirst("a[href*=/book/]")
        val url = link?.absUrl("href")?.ifBlank { null }
          ?: card.attr("href").takeIf { it.isNotBlank() }?.let { baseUrl.toHttpUrl().resolve(it)?.toString() }
          ?: return@forEach
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return@forEach
        if (!isAllowedHost(host)) return@forEach
        val title = card.attr("title").ifBlank { card.selectFirst("[slot=title], h3, h2, .bookTitle")?.text() ?: link?.text().orEmpty() }
        if (title.isBlank()) return@forEach
        val author = card.attr("author").ifBlank { card.select("[slot=author], .authors, a[href*=/author/]").text() }
        val language = card.attr("language").ifBlank { card.selectFirst(".bookProperty_language .property_value, [slot=language]")?.text().orEmpty() }
        val format = card.attr("extension").ifBlank { card.selectFirst(".bookProperty_type .property_value, [slot=extension]")?.text().orEmpty() }.uppercase(Locale.ROOT)
        if (format.isNotBlank() && !BookImport.isSupportedFormat(format)) return@forEach
        val sizeText = card.attr("filesize").ifBlank { card.selectFirst(".bookProperty_size .property_value, [slot=filesize]")?.text().orEmpty() }
        val sizeBytes = BookImport.parseSizeBytes(sizeText)
        if (sizeBytes != null && sizeBytes > BookImport.MAX_BYTES) return@forEach
        val isbn = IsbnValidator.extractFirstValidIsbn(card.attr("isbn").ifBlank { card.text() })
        val download = card.attr("download").takeIf { it.isNotBlank() }?.let { baseUrl.toHttpUrl().resolve(it)?.toString() }
        val image = card.selectFirst("img")
        val cover = CatalogHttpHelpers.resolveCoverUrl(
          baseUrl,
          sequenceOf(image?.attr("data-src"), image?.attr("data-original"), image?.attr("src"), card.attr("cover")),
        )
        result[url] = BookEdition(title, author, language, format.ifBlank { "EPUB" }, isbn, url, download, cover, TextProvider.ZLIBRARY)
      }
      return result.values.toList()
    }
  }
}

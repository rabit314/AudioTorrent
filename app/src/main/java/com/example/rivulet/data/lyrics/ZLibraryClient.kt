package com.example.rivulet.data.lyrics

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class TextProvider(val label: String) { AUTOMATIC("Automatic (All Sources)"), GUTENBERG("Public Domain (Gutenberg)"), ZLIBRARY("Z-Library") }
data class BookEdition(val title: String, val author: String, val language: String, val format: String, val isbn: String?, val url: String,
  val downloadUrl: String? = null, val coverUrl: String? = null, val provider: TextProvider = TextProvider.ZLIBRARY,
  val providerId: String = "", val linkedSource: Boolean = false) {
  val catalogKey: String get() = providerId.ifBlank { url.substringBefore('#').trimEnd('/') }
}
data class BookLookupRequest(val query: String, val title: String = query, val author: String = "",
  val linkedTextUrl: String? = null, val provider: TextProvider = TextProvider.AUTOMATIC)
data class BookLookupResult(val editions: List<BookEdition>, val message: String? = null, val accessUrl: String? = null, val complete: Boolean = true)
class SiteAccessRequired(val url: String) : IOException("Z-Library needs a browser access check or login.")

interface BookCatalog {
  fun search(query: String): List<BookEdition>
  fun lookup(request: BookLookupRequest): BookLookupResult = BookLookupResult(search(request.query))
  fun download(edition: BookEdition, renderedHtml: String? = null): Triple<ByteArray, String, String>
}

class ZLibraryClient(
  private val cookies: (String) -> String? = { null },
  private val saveCookies: (String, List<String>) -> Unit = { _, _ -> },
) : BookCatalog {
  @Volatile private var preferredBase: String = BASE

  private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .cookieJar(object : CookieJar {
      override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (runCatching { validateUrl(url.toString()) }.isFailure) return emptyList()
        val raw = cookies(url.toString())?.takeIf { it.isNotBlank() } ?: return emptyList()
        return raw.split(';').mapNotNull { entry ->
          val eq = entry.indexOf('=')
          if (eq <= 0) null
          else {
            val name = entry.substring(0, eq).trim()
            val value = entry.substring(eq + 1).trim()
            if (name.isEmpty()) null
            else runCatching {
              Cookie.Builder().name(name).value(value).domain(url.host).path("/").secure().build()
            }.getOrNull()
          }
        }
      }

      override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty() || runCatching { validateUrl(url.toString()) }.isFailure) return
        saveCookies(url.toString(), cookies.map { it.toString() })
      }
    })
    .addNetworkInterceptor { chain ->
      validateUrl(chain.request().url.toString())
      chain.proceed(chain.request())
    }.build()

  override fun search(query: String): List<BookEdition> {
    require(query.isNotBlank()) { "Enter a book title." }
    val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
    val candidates = (listOf(preferredBase) + MIRRORS).distinct()
    var firstAccess: SiteAccessRequired? = null
    var lastError: Exception? = null
    for (base in candidates) {
      val searchUrl = "$base/s/$encoded?extensions%5B%5D=epub"
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
    throw lastError ?: IOException("Z-Library is unavailable. Retry or import a local ebook.")
  }

  override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> {
    val html = if (renderedHtml != null) {
      if (needsAccess(200, renderedHtml)) throw SiteAccessRequired(edition.url)
      renderedHtml
    } else null
    val url = edition.downloadUrl ?: run {
      val pageHtml = html ?: page(edition.url).first
      val doc = Jsoup.parse(pageHtml, edition.url)
      val link = doc.select("a[href]").firstOrNull { element ->
        val href = element.attr("href")
        (href.contains("/dl/") || href.contains("/download/") || element.hasClass("dlButton") || element.attr("download").endsWith(".epub", true)) &&
          !element.text().contains("send to", true)
      } ?: run {
        if (needsAccess(200, pageHtml)) throw SiteAccessRequired(edition.url)
        throw IOException("No EPUB download is available for this edition. Check site access or choose another edition.")
      }
      link.absUrl("href")
    }
    validateUrl(url)
    client.newCall(browserRequest(url, referer = edition.url)).execute().use { response ->
      val contentType = response.header("Content-Type").orEmpty()
      val body = response.body ?: error("The ebook download was empty.")
      if (contentType.contains("html", true)) {
        val page = body.byteStream().use { BookImport.read(it, 2 * 1024 * 1024) }.toString(Charsets.UTF_8)
        if (needsAccess(response.code, page)) throw SiteAccessRequired(edition.url)
        error("Z-Library returned a web page instead of an EPUB. Open site access and try again.")
      }
      if (response.code in setOf(401, 403, 513)) throw SiteAccessRequired(edition.url)
      check(response.isSuccessful) { "Ebook download failed (${response.code}). Try another edition." }
      val bytes = body.byteStream().use { BookImport.read(it) }
      check(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) { "This download is not an EPUB." }
      return Triple(bytes, edition.title.take(120) + ".epub", "application/epub+zip")
    }
  }

  private fun browserRequest(url: String, referer: String? = null): Request = Request.Builder()
    .url(url)
    .header("User-Agent", USER_AGENT)
    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
    .header("Accept-Language", "en-US,en;q=0.9")
    .header("Upgrade-Insecure-Requests", "1")
    .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
    .build()

  private fun page(url: String, referer: String? = null): Pair<String, String> {
    validateUrl(url)
    client.newCall(browserRequest(url, referer)).execute().use { response ->
      val body = response.body ?: error("Z-Library returned an empty response.")
      val html = body.byteStream().use { BookImport.read(it, 4 * 1024 * 1024) }.toString(Charsets.UTF_8)
      val finalUrl = response.request.url.toString()
      if (needsAccess(response.code, html)) throw SiteAccessRequired(finalUrl)
      check(response.isSuccessful) { "Z-Library is unavailable (${response.code}). Retry or import a local ebook." }
      return html to finalUrl
    }
  }

  companion object {
    const val BASE = "https://zlib.li"
    val MIRRORS = listOf("https://zlib.li", "https://z-library.sk", "https://1lib.sk")
    private val ALLOWED_HOSTS = setOf("zlib.li", "z-library.sk", "1lib.sk")
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    fun isAllowedHost(host: String): Boolean {
      val clean = host.lowercase(java.util.Locale.ROOT)
      return clean in ALLOWED_HOSTS || ALLOWED_HOSTS.any { clean.endsWith(".$it") }
    }

    fun validateUrl(url: String) {
      val parsed = url.toHttpUrl()
      require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.host != "localhost" && !parsed.host.matches(Regex("[0-9.:]+")) && !parsed.host.endsWith(".local")) { "Unsupported ebook link." }
    }

    fun needsAccess(code: Int, html: String): Boolean {
      val doc = Jsoup.parse(html)
      val title = doc.title().lowercase()
      val bodyText = doc.body().text().lowercase()
      val challengePage = title.contains("checking your browser") ||
        title.contains("verifying your browser") ||
        title.contains("just a moment") ||
        title.contains("wait a moment") ||
        title.contains("attention required") ||
        title.contains("diamwall") ||
        title.contains("ddos") ||
        html.contains("c_token=", ignoreCase = true) ||
        html.contains("diamwall", ignoreCase = true) ||
        html.contains("cookies are required", ignoreCase = true) ||
        html.contains("/cdn-cgi/challenge-platform", ignoreCase = true)
      val hasContentCards = doc.selectFirst("z-bookcard, .resItemBox, .bookRow, a[href*=/dl/], a[href*=/download/]") != null
      val loginWall = doc.selectFirst("input[type=password]") != null ||
        (!hasContentCards && doc.selectFirst("a[href*=/login], form[action*=/login]") != null &&
          (bodyText.contains("sign in") || bodyText.contains("log in") || bodyText.contains("daily limit")))
      return code in setOf(401, 403, 513) ||
        (code in setOf(429, 503) && challengePage) ||
        challengePage ||
        loginWall ||
        doc.selectFirst("[class*=captcha], [id*=captcha]") != null
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
        val host = url.toHttpUrl().host
        if (!isAllowedHost(host)) return@forEach
        val title = card.attr("title").ifBlank { card.selectFirst("[slot=title], h3, h2, .bookTitle")?.text() ?: link?.text().orEmpty() }
        if (title.isBlank()) return@forEach
        val author = card.attr("author").ifBlank { card.select("[slot=author], .authors, a[href*=/author/]").text() }
        val language = card.attr("language").ifBlank { card.selectFirst(".bookProperty_language .property_value, [slot=language]")?.text().orEmpty() }
        val format = card.attr("extension").ifBlank { card.selectFirst(".bookProperty_type .property_value, [slot=extension]")?.text().orEmpty() }.uppercase()
        if (format.isNotBlank() && format !in setOf("EPUB", "TXT")) return@forEach
        val isbn = IsbnValidator.extractFirstValidIsbn(card.attr("isbn").ifBlank { card.text() })
        val download = card.attr("download").takeIf { it.isNotBlank() }?.let { baseUrl.toHttpUrl().resolve(it)?.toString() }
        val image = card.selectFirst("img")
        val cover = sequenceOf(image?.attr("data-src"), image?.attr("data-original"), image?.attr("src"), card.attr("cover"))
          .filterNotNull().map { it.trim() }.filter { it.isNotBlank() }
          .mapNotNull { baseUrl.toHttpUrl().resolve(it)?.toString() }
          .firstOrNull { runCatching { validateUrl(it) }.isSuccess }
        result[url] = BookEdition(title, author, language, format.ifBlank { "EPUB" }, isbn, url, download, cover)
      }
      return result.values.toList()
    }
  }
}

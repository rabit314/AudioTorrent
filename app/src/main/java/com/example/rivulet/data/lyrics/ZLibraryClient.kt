package com.example.rivulet.data.lyrics

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class BookEdition(val title: String, val author: String, val language: String, val format: String, val isbn: String?, val url: String, val downloadUrl: String? = null)
class SiteAccessRequired(val url: String) : IOException("Z-Library needs a browser access check or login.")

class ZLibraryClient(private val cookies: (String) -> String? = { null }) {
  private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .addNetworkInterceptor { chain ->
      val url = chain.request().url.toString()
      validateUrl(url)
      val builder = chain.request().newBuilder().removeHeader("Cookie")
      cookies(url)?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
      chain.proceed(builder.build())
    }.build()

  fun search(query: String): List<BookEdition> {
    require(query.isNotBlank()) { "Enter a book title." }
    val url = "$BASE/s/${URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")}?extensions%5B%5D=epub"
    val html = page(url)
    return parseResults(html, url).sortedByDescending { edition ->
      ReadAlongText.tokens(query).toSet().intersect(ReadAlongText.tokens(edition.title).toSet()).size * 10 + if (edition.language.contains("english", true)) 2 else 0
    }
  }

  fun download(edition: BookEdition, renderedHtml: String? = null): Triple<ByteArray, String, String> {
    val url = edition.downloadUrl ?: run {
      val html = renderedHtml ?: page(edition.url)
      val doc = Jsoup.parse(html, edition.url)
      val link = doc.select("a[href]").firstOrNull { element ->
        val href = element.attr("href")
        (href.contains("/dl/") || href.contains("/download/") || element.hasClass("dlButton") || element.attr("download").endsWith(".epub", true)) &&
          !element.text().contains("send to", true)
      } ?: throw IOException("No EPUB download is available for this edition. Check site access or choose another edition.")
      link.absUrl("href")
    }
    validateUrl(url)
    client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Referer", edition.url).build()).execute().use { response ->
      val contentType = response.header("Content-Type").orEmpty()
      val body = response.body ?: error("The ebook download was empty.")
      if (contentType.contains("html", true)) {
        val page = body.byteStream().use { BookImport.read(it, 2 * 1024 * 1024) }.toString(Charsets.UTF_8)
        if (needsAccess(response.code, page)) throw SiteAccessRequired(edition.url)
        error("Z-Library returned a web page instead of an EPUB. Open site access and try again.")
      }
      check(response.isSuccessful) { "Ebook download failed (${response.code}). Try another edition." }
      val bytes = body.byteStream().use { BookImport.read(it) }
      check(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) { "This download is not an EPUB." }
      return Triple(bytes, edition.title.take(120) + ".epub", "application/epub+zip")
    }
  }

  private fun page(url: String): String {
    validateUrl(url)
    client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
      val body = response.body ?: error("Z-Library returned an empty response.")
      val html = body.byteStream().use { BookImport.read(it, 4 * 1024 * 1024) }.toString(Charsets.UTF_8)
      if (needsAccess(response.code, html)) throw SiteAccessRequired(response.request.url.toString())
      check(response.isSuccessful) { "Z-Library is unavailable (${response.code}). Retry or import a local ebook." }
      return html
    }
  }

  companion object {
    const val BASE = "https://zlib.li"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    fun validateUrl(url: String) {
      val parsed = url.toHttpUrl()
      require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.host != "localhost" && !parsed.host.matches(Regex("[0-9.:]+")) && !parsed.host.endsWith(".local")) { "Unsupported ebook link." }
    }

    fun needsAccess(code: Int, html: String): Boolean {
      val doc = Jsoup.parse(html)
      val title = doc.title().lowercase()
      return code in setOf(401, 403) || title.contains("checking your browser") || title.contains("just a moment") ||
        doc.selectFirst("input[type=password]") != null || doc.selectFirst("[class*=captcha], [id*=captcha]") != null
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
        if (host != "zlib.li" && !host.endsWith(".zlib.li")) return@forEach
        val title = card.attr("title").ifBlank { card.selectFirst("[slot=title], h3, h2, .bookTitle")?.text() ?: link?.text().orEmpty() }
        if (title.isBlank()) return@forEach
        val author = card.attr("author").ifBlank { card.select("[slot=author], .authors, a[href*=/author/]").text() }
        val language = card.attr("language").ifBlank { card.selectFirst(".bookProperty_language .property_value, [slot=language]")?.text().orEmpty() }
        val format = card.attr("extension").ifBlank { card.selectFirst(".bookProperty_type .property_value, [slot=extension]")?.text().orEmpty() }.uppercase()
        if (format.isNotBlank() && format !in setOf("EPUB", "TXT")) return@forEach
        val isbn = IsbnValidator.extractFirstValidIsbn(card.attr("isbn").ifBlank { card.text() })
        val download = card.attr("download").takeIf { it.isNotBlank() }?.let { baseUrl.toHttpUrl().resolve(it)?.toString() }
        result[url] = BookEdition(title, author, language, format.ifBlank { "EPUB" }, isbn, url, download)
      }
      return result.values.toList()
    }
  }
}

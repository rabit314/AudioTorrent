package com.example.rivulet.data.lyrics

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

class LibGenClient(
  private val cookies: (String) -> String? = CatalogHttpHelpers.androidCookieReader(),
  private val saveCookies: (String, List<String>) -> Unit = CatalogHttpHelpers.androidCookieWriter(),
) : BookCatalog {
  @Volatile private var preferredBase: String = BASE

  private val client by lazy {
    CatalogHttpHelpers.sharedClient(
      cookies = cookies,
      saveCookies = saveCookies,
      connectTimeoutSec = 10L,
      readTimeoutSec = 20L,
    )
  }

  override fun search(query: String): List<BookEdition> {
    require(query.isNotBlank()) { "Enter a book title." }
    val encoded = URLEncoder.encode(query.trim(), "UTF-8")
    val candidates = (listOf(preferredBase) + MIRRORS).distinct()
    var firstAccess: SiteAccessRequired? = null
    var lastError: Exception? = null
    for (base in candidates) {
      val searchUrl = "$base/index.php?req=$encoded&columns%5B%5D=t&columns%5B%5D=a&objects%5B%5D=f&topics%5B%5D=l&topics%5B%5D=f&res=25&covers=on"
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
    throw lastError ?: IOException("LibGen is unavailable. Retry or try another source.")
  }

  override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> {
    CatalogDownloadBridge.parse(renderedHtml)?.let { capture ->
      return CatalogHttpHelpers.downloadBinaryFile(
        client = client,
        url = capture.url,
        edition = edition,
        referer = capture.referer ?: edition.url,
        userAgent = capture.userAgent ?: CatalogHttpHelpers.DEFAULT_USER_AGENT,
        siteName = "LibGen",
        accessFallbackUrl = edition.url,
      )
    }

    if (renderedHtml != null) {
      if (CatalogHttpHelpers.isChallengeHtml(200, renderedHtml)) throw SiteAccessRequired(edition.url)
      val directLink = extractGetUrl(renderedHtml, edition.url)
      if (directLink != null) {
        return CatalogHttpHelpers.downloadBinaryFile(
          client = client,
          url = directLink,
          edition = edition,
          referer = edition.url,
          siteName = "LibGen",
          accessFallbackUrl = edition.url,
        )
      }
    }

    val md5 = extractMd5(edition.providerId) ?: extractMd5(edition.url)
    val originOrder = buildMirrorOrigins(edition.url)
    var lastBusyError: Exception? = null
    var firstAccess: SiteAccessRequired? = null

    if (md5 != null) {
      for (origin in originOrder) {
        val adsUrl = "$origin/ads.php?md5=$md5"
        try {
          val (adsHtml, resolvedAdsUrl) = page(adsUrl, referer = "$origin/")
          val getUrl = extractGetUrl(adsHtml, resolvedAdsUrl) ?: continue
          val outcome = CatalogHttpHelpers.downloadBinaryFile(
            client = client,
            url = getUrl,
            edition = edition,
            referer = resolvedAdsUrl,
            siteName = "LibGen",
            accessFallbackUrl = adsUrl,
          )
          preferredBase = origin
          return outcome
        } catch (access: SiteAccessRequired) {
          if (firstAccess == null) firstAccess = access
        } catch (error: Exception) {
          if (error is java.util.concurrent.CancellationException) throw error
          lastBusyError = error
        }
      }
    } else {
      val direct = edition.downloadUrl ?: edition.url
      return CatalogHttpHelpers.downloadBinaryFile(
        client = client,
        url = direct,
        edition = edition,
        referer = edition.url,
        siteName = "LibGen",
        accessFallbackUrl = edition.url,
      )
    }

    firstAccess?.let { throw it }
    throw lastBusyError ?: IOException("LibGen download servers are busy. Retry or use WeLib / Anna's Archive.")
  }

  private fun buildMirrorOrigins(editionUrl: String): List<String> {
    val parsedOrigin = runCatching {
      val http = editionUrl.toHttpUrl()
      "${http.scheme}://${http.host}".takeIf { isAllowedHost(http.host) }
    }.getOrNull()
    return (listOfNotNull(preferredBase, parsedOrigin) + MIRRORS).distinct()
  }

  private fun page(url: String, referer: String? = null): Pair<String, String> {
    validateUrl(url)
    client.newCall(CatalogHttpHelpers.browserRequest(url, referer)).execute().use { response ->
      val body = response.body ?: throw IOException("LibGen returned an empty response.")
      val html = body.byteStream().use { BookImport.read(it, 4 * 1024 * 1024) }.toString(Charsets.UTF_8)
      val finalUrl = response.request.url.toString()
      if (isDbBusy(response.code, html)) {
        throw IOException("LibGen download servers are temporarily busy (HTTP ${response.code}).")
      }
      if (CatalogHttpHelpers.isChallengeHtml(response.code, html)) throw SiteAccessRequired(finalUrl)
      check(response.isSuccessful) { "LibGen is unavailable (${response.code})." }
      return html to finalUrl
    }
  }

  companion object {
    const val BASE = "https://libgen.li"
    val MIRRORS = listOf(
      "https://libgen.li",
      "https://libgen.la",
      "https://libgen.bz",
      "https://libgen.gl",
      "https://libgen.vg",
    )
    private val ALLOWED_HOSTS = setOf("libgen.li", "libgen.la", "libgen.bz", "libgen.gl", "libgen.vg")
    private val MD5_REGEX = Regex("\\b([a-f0-9]{32})\\b", RegexOption.IGNORE_CASE)

    fun isAllowedHost(host: String): Boolean = CatalogHttpHelpers.hostMatches(host, ALLOWED_HOSTS)
    fun validateUrl(url: String) {
      CatalogHttpHelpers.validateHttpsUrl(url)
    }

    fun extractMd5(raw: String?): String? =
      raw?.let { MD5_REGEX.find(it)?.groupValues?.get(1)?.lowercase(Locale.ROOT) }

    fun isDbBusy(code: Int, html: String): Boolean =
      code >= 500 && (html.contains("max_user_connections", ignoreCase = true) ||
        html.contains("libgen_get", ignoreCase = true) ||
        html.contains("could not connect to the database", ignoreCase = true))

    fun extractGetUrl(html: String, baseUrl: String): String? {
      val doc = Jsoup.parse(html, baseUrl)
      val link = doc.select("a[href]").firstOrNull { el ->
        val href = el.attr("href")
        href.contains("get.php", ignoreCase = true) || href.contains("/get/", ignoreCase = true)
      } ?: return null
      val resolved = link.absUrl("href")
      return resolved.takeIf { runCatching { validateUrl(it) }.isSuccess }
    }

    fun parseResults(html: String, baseUrl: String): List<BookEdition> {
      val doc = Jsoup.parse(html, baseUrl)
      val baseHttp = runCatching { baseUrl.toHttpUrl() }.getOrNull() ?: return emptyList()
      val origin = "${baseHttp.scheme}://${baseHttp.host}"
      val table = doc.selectFirst("#tablelibgen") ?: return emptyList()
      val headers = table.select("thead th").map { it.text().trim().lowercase(Locale.ROOT) }
      val authorCol = headers.indexOfFirst { it.contains("author") }
      val langCol = headers.indexOfFirst { it.contains("language") }
      val extCol = headers.indexOfFirst { it.contains("ext") }

      val result = LinkedHashMap<String, BookEdition>()
      val rows = table.select("tbody tr")
      for (row in rows) {
        val cells = row.select("td")
        if (cells.size < 6) continue

        val adsLink = row.select("a[href*=ads.php], a[href*=get.php]").firstOrNull()
        val md5 = extractMd5(adsLink?.attr("href")) ?: continue
        val detailUrl = "$origin/ads.php?md5=$md5"

        val detectedTitleIdx = cells.indexOfFirst { it.selectFirst("a[href*=edition.php]") != null }
        val shift = if (detectedTitleIdx >= 0) detectedTitleIdx else if (cells.firstOrNull()?.selectFirst("img") != null || cells.size >= 10) 1 else 0
        val titleCellIdx = shift
        val authorIdx = if (authorCol >= 0) authorCol + shift else shift + 1
        val langIdx = if (langCol >= 0) langCol + shift else shift + 4
        val extIdx = if (extCol >= 0) extCol + shift else shift + 7

        val titleCell = cells.getOrNull(titleCellIdx) ?: continue
        val title = titleCell.selectFirst("a[href*=edition.php]")?.text()?.trim()
          ?.ifBlank { titleCell.selectFirst("b")?.text()?.trim() }
          ?.ifBlank { titleCell.ownText().trim() }
          .orEmpty()
        if (title.isBlank()) continue

        val author = cells.getOrNull(authorIdx)?.text()?.trim().orEmpty()
        val language = cells.getOrNull(langIdx)?.text()?.trim().orEmpty()

        val extRaw = cells.getOrNull(extIdx)?.text()?.trim().orEmpty()
        val format = extRaw.uppercase(Locale.ROOT)
        if (!BookImport.isSupportedFormat(format)) continue

        val sizeText = row.selectFirst("a[href*=file.php]")?.text()
          ?: cells.getOrNull(extIdx - 1)?.text().orEmpty()
        val sizeBytes = BookImport.parseSizeBytes(sizeText)
        if (sizeBytes != null && sizeBytes > BookImport.MAX_BYTES) continue

        val isbn = IsbnValidator.extractFirstValidIsbn(titleCell.text())
        val image = cells.firstOrNull()?.selectFirst("img")
        val cover = CatalogHttpHelpers.resolveCoverUrl(
          baseUrl,
          sequenceOf(image?.attr("data-src"), image?.attr("src")),
        )

        result.putIfAbsent(
          md5,
          BookEdition(
            title = title,
            author = author,
            language = language,
            format = format,
            isbn = isbn,
            url = detailUrl,
            downloadUrl = null,
            coverUrl = cover,
            provider = TextProvider.LIBGEN,
            providerId = md5,
          ),
        )
      }
      return result.values.toList()
    }
  }
}

package com.example.audiotorrent.data.lyrics

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

class WeLibClient(
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
    val encoded = URLEncoder.encode(query.trim(), "UTF-8")
    val candidates = (listOf(preferredBase) + MIRRORS).distinct()
    var firstAccess: SiteAccessRequired? = null
    var lastError: Exception? = null
    for (base in candidates) {
      val searchUrl = "$base/search?q=$encoded"
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
    throw lastError ?: IOException("WeLib is unavailable. Open site access or try another source.")
  }

  override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> {
    CatalogDownloadBridge.parse(renderedHtml)?.let { capture ->
      return CatalogHttpHelpers.downloadBinaryFile(
        client = client,
        url = capture.url,
        edition = edition,
        referer = capture.referer ?: edition.url,
        userAgent = capture.userAgent ?: CatalogHttpHelpers.DEFAULT_USER_AGENT,
        siteName = "WeLib",
        isAccessRequired = ::needsAccess,
        accessFallbackUrl = edition.url,
      )
    }

    val html = if (renderedHtml != null) {
      if (needsAccess(200, renderedHtml)) throw SiteAccessRequired(edition.url)
      renderedHtml
    } else null

    // Prefer fast_preview / Foliate public server URLs when available (no waitlist or CAPTCHA).
    if (html != null) {
      extractPreviewFileUrl(html, edition.url)?.let { directPreview ->
        runCatching {
          return CatalogHttpHelpers.downloadBinaryFile(
            client = client,
            url = directPreview,
            edition = edition,
            referer = edition.url,
            siteName = "WeLib",
            isAccessRequired = ::needsAccess,
            accessFallbackUrl = edition.url,
          )
        }.onFailure { if (it is java.util.concurrent.CancellationException) throw it }
      }
    }

    val md5 = LibGenClient.extractMd5(edition.providerId) ?: LibGenClient.extractMd5(edition.url)
    val previewEndpoint = html?.let { extractFastPreviewUrl(it, edition.url) }
      ?: md5?.let { hash ->
        val origin = runCatching {
          edition.url.toHttpUrl().let { u -> "${u.scheme}://${u.host}" }
        }.getOrDefault(preferredBase)
        buildFastPreviewUrl(origin, hash)
      }
    if (previewEndpoint != null) {
      runCatching {
        val (previewHtml, previewResolvedUrl) = page(previewEndpoint, referer = edition.url)
        extractPreviewFileUrl(previewHtml, previewResolvedUrl)?.let { directFileUrl ->
          return CatalogHttpHelpers.downloadBinaryFile(
            client = client,
            url = directFileUrl,
            edition = edition,
            referer = previewResolvedUrl,
            siteName = "WeLib",
            isAccessRequired = ::needsAccess,
            accessFallbackUrl = edition.url,
          )
        }
      }.onFailure { if (it is java.util.concurrent.CancellationException) throw it }
    }

    val downloadTarget = if (html != null) {
      extractDownloadLink(html, edition.url)
        ?: throw SiteAccessRequired(edition.url, "Tap Read or Slow download in the WeLib browser to download.")
    } else {
      val (detailHtml, resolvedUrl) = page(edition.url)
      extractDownloadLink(detailHtml, resolvedUrl)
        ?: edition.downloadUrl
        ?: throw SiteAccessRequired(resolvedUrl, "Complete WeLib verification to download this edition.")
    }

    return CatalogHttpHelpers.downloadBinaryFile(
      client = client,
      url = downloadTarget,
      edition = edition,
      referer = edition.url,
      siteName = "WeLib",
      isAccessRequired = ::needsAccess,
      accessFallbackUrl = downloadTarget.takeIf { it.contains("/slow_download/") } ?: edition.url,
    )
  }

  private fun page(url: String, referer: String? = null): Pair<String, String> {
    validateUrl(url)
    client.newCall(CatalogHttpHelpers.browserRequest(url, referer)).execute().use { response ->
      val body = response.body ?: throw IOException("WeLib returned an empty response.")
      val html = body.byteStream().use { BookImport.read(it, 4 * 1024 * 1024) }.toString(Charsets.UTF_8)
      val finalUrl = response.request.url.toString()
      if (needsAccess(response.code, html)) {
        // Keep the requested search/edition URL when redirected to /verify_captcha so WebView gets a fresh session redirect.
        val accessTarget = if (finalUrl.contains("/verify_captcha", ignoreCase = true)) url else finalUrl
        throw SiteAccessRequired(accessTarget, "WeLib needs browser verification in the in-app browser.")
      }
      check(response.isSuccessful) { "WeLib is unavailable (${response.code})." }
      return html to finalUrl
    }
  }

  companion object {
    const val BASE = "https://welib.gl"
    val MIRRORS = listOf("https://welib.gl", "https://welib.org")
    private val ALLOWED_HOSTS = setOf("welib.gl", "welib.org")
    private val KNOWN_UNSUPPORTED_EXTS = setOf("MOBI", "AZW3", "AZW", "DJVU", "CBR", "CBZ", "DOC", "DOCX", "RTF", "CHM")
    private val FORMAT_PRIORITY = listOf("EPUB", "TXT", "FB2", "PDF")
    private val YEAR_REGEX = Regex("^\\d{4}$")

    fun isAllowedHost(host: String): Boolean = CatalogHttpHelpers.hostMatches(host, ALLOWED_HOSTS)
    fun validateUrl(url: String) {
      CatalogHttpHelpers.validateHttpsUrl(url)
    }

    fun needsAccess(code: Int, html: String): Boolean {
      if (CatalogHttpHelpers.isChallengeHtml(code, html)) return true
      val doc = Jsoup.parse(html)
      val hasCards = doc.selectFirst("#aarecord-list .book-card, #md5-panel-downloads a[href*=_download/]") != null
      val bodyText = doc.body().text().lowercase(Locale.ROOT)
      return !hasCards && (bodyText.contains("waitlist") || bodyText.contains("checking your browser") ||
        bodyText.contains("verify you are human") || bodyText.contains("security verification") ||
        doc.selectFirst("input[type=password], #captcha-form, .h-captcha") != null)
    }

    fun buildFastPreviewUrl(origin: String, md5: String): String =
      "${origin.trimEnd('/')}/fast_preview/$md5/0/0?viewer=1"

    fun extractFastPreviewUrl(html: String, baseUrl: String): String? {
      val doc = Jsoup.parse(html, baseUrl)
      val el = doc.selectFirst("span.preview[data-book-url], [data-book-url*=/fast_preview/], a[href*=/fast_preview/]")
        ?: return null
      val raw = el.attr("data-book-url").ifBlank { el.attr("href") }.trim()
      if (raw.isBlank() || raw.contains("/convert/", ignoreCase = true)) return null
      val resolved = runCatching { baseUrl.toHttpUrl().resolve(raw)?.toString() }.getOrNull() ?: return null
      return resolved.takeIf { runCatching { validateUrl(it) }.isSuccess }
    }

    fun extractPreviewFileUrl(html: String, baseUrl: String): String? {
      val doc = Jsoup.parse(html, baseUrl)
      val candidates = sequenceOf(
        doc.selectFirst("iframe#foliate-iframe[src]")?.absUrl("src"),
        doc.selectFirst("a[href*=/fast_view]")?.absUrl("href"),
        doc.selectFirst("iframe#bookIframe[src]")?.absUrl("src"),
      ).filterNotNull().map { it.trim() }.filter { it.isNotBlank() }
      for (candidate in candidates) {
        val extracted = extractFileUrlFromViewerSrc(candidate)
        if (extracted != null) return extracted
      }
      return null
    }

    fun extractFileUrlFromViewerSrc(viewerSrc: String): String? {
      val parsed = runCatching { viewerSrc.trim().toHttpUrl() }.getOrNull() ?: return null
      val target = parsed.queryParameter("url")?.trim()?.takeIf { it.isNotBlank() } ?: return null
      val targetHttp = runCatching { CatalogHttpHelpers.validateHttpsUrl(target) }.getOrNull() ?: return null
      val lowerPath = targetHttp.encodedPath.lowercase(Locale.ROOT)
      if (lowerPath.contains("/covers/") || lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") ||
        lowerPath.endsWith(".png") || lowerPath.endsWith(".webp") || lowerPath.endsWith(".gif")
      ) {
        return null
      }
      return targetHttp.toString()
    }

    fun extractDownloadLink(html: String, baseUrl: String): String? {
      val doc = Jsoup.parse(html, baseUrl)
      val slow = doc.select("#md5-panel-downloads a[href*=/slow_download/]").firstOrNull {
        !it.attr("href").contains("/convert/", ignoreCase = true)
      }
      val auto = doc.selectFirst("a[href*=/auto_download/]")
      val fast = doc.select("#md5-panel-downloads a[href*=/fast_download/]").firstOrNull {
        !it.attr("href").contains("/convert/", ignoreCase = true)
      }
      val chosen = slow ?: auto ?: fast ?: return null
      val resolved = chosen.absUrl("href")
      return resolved.takeIf { runCatching { validateUrl(it) }.isSuccess }
    }

    fun parseResults(html: String, baseUrl: String): List<BookEdition> {
      val doc = Jsoup.parse(html, baseUrl)
      val baseHttp = runCatching { baseUrl.toHttpUrl() }.getOrNull() ?: return emptyList()
      val origin = "${baseHttp.scheme}://${baseHttp.host}"
      val cards = doc.select("#aarecord-list .book-card, .book-list .book-card")
      val result = LinkedHashMap<String, BookEdition>()

      for (card in cards) {
        val md5Link = card.selectFirst("a[href*=/md5/]") ?: continue
        val href = md5Link.absUrl("href").ifBlank { continue }
        val host = runCatching { href.toHttpUrl().host }.getOrNull() ?: continue
        if (!isAllowedHost(host)) continue
        val md5 = LibGenClient.extractMd5(href) ?: continue

        val coverImg = card.selectFirst(".book-cover img, img")
        val title = card.selectFirst("h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
          ?: coverImg?.attr("data-title")?.trim()?.takeIf { it.isNotBlank() }
          ?: md5Link.text().trim()
        if (title.isBlank()) continue

        val author = card.selectFirst("a[href*=/search?q=]")?.text()?.trim()?.takeIf { it.isNotBlank() }
          ?: coverImg?.attr("data-author")?.trim().orEmpty()

        val metaDiv = card.selectFirst("div.mb-1")
        val metaText = metaDiv?.text().orEmpty()
        val sizeBytes = BookImport.parseSizeBytes(metaText)
        if (sizeBytes != null && sizeBytes > BookImport.MAX_BYTES) continue

        val rawFormats = metaDiv?.selectFirst("span.uppercase")?.text().orEmpty()
          .split('·', ',', '/')
          .map { it.trim().uppercase(Locale.ROOT) }
          .filter { it.isNotBlank() }
        val chosenFormat = FORMAT_PRIORITY.firstOrNull { it in rawFormats }
          ?: if (rawFormats.isEmpty()) "EPUB" else if (rawFormats.any { it in KNOWN_UNSUPPORTED_EXTS }) continue else continue

        val language = metaDiv?.select("span")
          ?.map { it.text().removePrefix("·").trim() }
          ?.firstOrNull { token ->
            token.isNotBlank() &&
              !YEAR_REGEX.matches(token) &&
              BookImport.parseSizeBytes(token) == null &&
              token.uppercase(Locale.ROOT) !in BookImport.SUPPORTED_FORMATS &&
              !token.contains("·") &&
              token.any { it.isLetter() }
          }
          .orEmpty()

        val cover = CatalogHttpHelpers.resolveCoverUrl(
          baseUrl,
          sequenceOf(coverImg?.attr("data-src"), coverImg?.attr("src")),
        )
        val autoDownload = card.selectFirst("a[href*=/auto_download/]")?.absUrl("href")
          ?.takeIf { runCatching { validateUrl(it) }.isSuccess }

        result.putIfAbsent(
          md5,
          BookEdition(
            title = title,
            author = author,
            language = language,
            format = chosenFormat,
            isbn = IsbnValidator.extractFirstValidIsbn(card.text()),
            url = "$origin/md5/$md5",
            downloadUrl = autoDownload,
            coverUrl = cover,
            provider = TextProvider.WELIB,
            providerId = md5,
          ),
        )
      }
      return result.values.toList()
    }
  }
}

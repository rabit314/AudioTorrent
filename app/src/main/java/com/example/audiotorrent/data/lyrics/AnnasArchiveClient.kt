package com.example.audiotorrent.data.lyrics

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

class AnnasArchiveClient(
  private val cookies: (String) -> String? = CatalogHttpHelpers.androidCookieReader(),
  private val saveCookies: (String, List<String>) -> Unit = CatalogHttpHelpers.androidCookieWriter(),
  private val secretKey: () -> String? = { null },
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
    throw lastError ?: IOException("Anna's Archive is unavailable. Open site access or try LibGen.")
  }

  fun downloadByMd5(md5: String, key: String, titleHint: String = md5, formatHint: String = "EPUB"): Triple<ByteArray, String, String> {
    val cleanMd5 = LibGenClient.extractMd5(md5) ?: throw IllegalArgumentException("Invalid MD5 hash.")
    val cleanKey = key.trim()
    require(cleanKey.isNotEmpty()) { "Enter your Anna's Archive secret key." }
    val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")
    var lastError: Exception? = null

    for (base in (listOf(preferredBase) + MIRRORS).distinct()) {
      val apiUrl = "$base/dyn/api/fast_download.json?md5=$cleanMd5&key=$encodedKey"
      try {
        validateUrl(apiUrl)
        val jsonText = client.newCall(CatalogHttpHelpers.browserRequest(apiUrl, referer = "$base/")).execute().use { response ->
          val body = response.body ?: throw IOException("Empty response from Anna's Archive API.")
          val raw = body.byteStream().use { BookImport.read(it, 512 * 1024) }.toString(Charsets.UTF_8)
          if (CatalogHttpHelpers.isChallengeHtml(response.code, raw)) {
            throw SiteAccessRequired("$base/md5/$cleanMd5", "Anna's Archive requested browser verification.")
          }
          raw
        }
        val payload = JSONObject(jsonText)
        val err = payload.optString("error", "").takeIf { !payload.isNull("error") && it.isNotBlank() }
        if (err != null) {
          throw IOException("Anna's Archive API: $err")
        }
        val dlUrl = payload.optString("download_url", "").takeIf { !payload.isNull("download_url") && it.isNotBlank() }
          ?: throw IOException("Anna's Archive did not return a download link.")
        val edition = BookEdition(titleHint, "", "", formatHint, null, "$base/md5/$cleanMd5", dlUrl, provider = TextProvider.ANNAS_ARCHIVE, providerId = cleanMd5)
        return CatalogHttpHelpers.downloadBinaryFile(
          client = client,
          url = dlUrl,
          edition = edition,
          referer = "$base/md5/$cleanMd5",
          siteName = "Anna's Archive",
          isAccessRequired = ::needsAccess,
          accessFallbackUrl = "$base/md5/$cleanMd5",
        )
      } catch (access: SiteAccessRequired) {
        throw access
      } catch (error: Exception) {
        if (error is java.util.concurrent.CancellationException) throw error
        lastError = error
        // Auth / quota errors should not retry all mirrors blindly.
        if (error.message?.startsWith("Anna's Archive API:") == true) throw error
      }
    }
    throw lastError ?: IOException("Anna's Archive fast download failed.")
  }

  override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> {
    CatalogDownloadBridge.parse(renderedHtml)?.let { capture ->
      return CatalogHttpHelpers.downloadBinaryFile(
        client = client,
        url = capture.url,
        edition = edition,
        referer = capture.referer ?: edition.url,
        userAgent = capture.userAgent ?: CatalogHttpHelpers.DEFAULT_USER_AGENT,
        siteName = "Anna's Archive",
        isAccessRequired = ::needsAccess,
        accessFallbackUrl = edition.url,
      )
    }

    val md5 = LibGenClient.extractMd5(edition.providerId) ?: LibGenClient.extractMd5(edition.url)
    val savedKey = secretKey()?.trim()?.takeIf { it.isNotEmpty() }
    if (md5 != null && savedKey != null) {
      runCatching { return downloadByMd5(md5, savedKey, edition.title, edition.format) }
        .onFailure { if (it is java.util.concurrent.CancellationException) throw it }
    }

    val html = if (renderedHtml != null) {
      if (needsAccess(200, renderedHtml)) throw SiteAccessRequired(edition.url)
      renderedHtml
    } else null

    val downloadTarget = if (html != null) {
      extractDownloadLink(html, edition.url) ?: throw SiteAccessRequired(edition.url, "Tap Slow download in Anna's Archive to download.")
    } else {
      val (detailHtml, resolvedUrl) = page(edition.url)
      extractDownloadLink(detailHtml, resolvedUrl)
        ?: throw SiteAccessRequired(resolvedUrl, "Complete Anna's Archive verification in the in-app browser.")
    }

    return CatalogHttpHelpers.downloadBinaryFile(
      client = client,
      url = downloadTarget,
      edition = edition,
      referer = edition.url,
      siteName = "Anna's Archive",
      isAccessRequired = ::needsAccess,
      accessFallbackUrl = downloadTarget.takeIf { it.contains("/slow_download/") } ?: edition.url,
    )
  }

  private fun page(url: String, referer: String? = null): Pair<String, String> {
    validateUrl(url)
    client.newCall(CatalogHttpHelpers.browserRequest(url, referer)).execute().use { response ->
      val body = response.body ?: throw IOException("Anna's Archive returned an empty response.")
      val html = body.byteStream().use { BookImport.read(it, 4 * 1024 * 1024) }.toString(Charsets.UTF_8)
      val finalUrl = response.request.url.toString()
      if (needsAccess(response.code, html)) throw SiteAccessRequired(finalUrl, "Anna's Archive needs browser verification in the in-app browser.")
      check(response.isSuccessful) { "Anna's Archive is unavailable (${response.code})." }
      return html to finalUrl
    }
  }

  companion object {
    const val BASE = "https://annas-archive.gl"
    val MIRRORS = listOf(
      "https://annas-archive.gl",
      "https://en.annas-archive.gl",
      "https://annas-archive.se",
      "https://annas-archive.org",
      "https://annas-archive.li",
    )
    private val ALLOWED_HOSTS = setOf("annas-archive.gl", "annas-archive.se", "annas-archive.org", "annas-archive.li")
    private val KNOWN_UNSUPPORTED_EXTS = setOf("MOBI", "AZW3", "AZW", "DJVU", "CBR", "CBZ", "DOC", "DOCX", "RTF", "CHM")
    private val LANG_TAG_REGEX = Regex("\\[[a-z]{2,3}\\]", RegexOption.IGNORE_CASE)

    fun isAllowedHost(host: String): Boolean = CatalogHttpHelpers.hostMatches(host, ALLOWED_HOSTS)
    fun validateUrl(url: String) {
      CatalogHttpHelpers.validateHttpsUrl(url)
    }

    fun needsAccess(code: Int, html: String): Boolean {
      if (CatalogHttpHelpers.isChallengeHtml(code, html)) return true
      val doc = Jsoup.parse(html)
      val hasCards = doc.selectFirst(".js-aarecord-list-outer a[href*=/md5/], a[href*=/slow_download/], a[href*=/fast_download/]") != null
      val bodyText = doc.body().text().lowercase(Locale.ROOT)
      return !hasCards && (bodyText.contains("checking your browser") || bodyText.contains("waitlist") || doc.selectFirst("input[type=password]") != null)
    }

    fun extractDownloadLink(html: String, baseUrl: String): String? {
      val doc = Jsoup.parse(html, baseUrl)
      val link = doc.select("a[href*=/slow_download/], a[href*=/fast_download/]").firstOrNull {
        !it.attr("href").contains("/convert/", ignoreCase = true)
      } ?: return null
      val resolved = link.absUrl("href")
      return resolved.takeIf { runCatching { validateUrl(it) }.isSuccess }
    }

    fun parseResults(html: String, baseUrl: String): List<BookEdition> {
      val doc = Jsoup.parse(html, baseUrl)
      val baseHttp = runCatching { baseUrl.toHttpUrl() }.getOrNull() ?: return emptyList()
      val origin = "${baseHttp.scheme}://${baseHttp.host}"
      val cards = doc.select(".js-aarecord-list-outer > div:has(a[href*=/md5/])")
      val result = LinkedHashMap<String, BookEdition>()

      for (card in cards) {
        val md5Links = card.select("a[href*=/md5/]")
        val titleLink = md5Links.firstOrNull { it.hasClass("js-vim-focus") }
          ?: md5Links.firstOrNull { it.text().isNotBlank() }
          ?: md5Links.firstOrNull()
          ?: continue
        val href = titleLink.absUrl("href").ifBlank { continue }
        val host = runCatching { href.toHttpUrl().host }.getOrNull() ?: continue
        if (!isAllowedHost(host)) continue
        val md5 = LibGenClient.extractMd5(href) ?: continue

        val title = titleLink.text().trim()
        if (title.isBlank()) continue

        val searchLinks = card.select("a[href*=/search?q=]")
        val author = searchLinks.firstOrNull { it.selectFirst("[class*=user-edit]") != null }?.text()?.trim()
          ?: searchLinks.firstOrNull()?.text()?.trim().orEmpty()

        val metaText = card.selectFirst("div.text-gray-800")?.text().orEmpty()
        val sizeBytes = BookImport.parseSizeBytes(metaText)
        if (sizeBytes != null && sizeBytes > BookImport.MAX_BYTES) continue

        val parts = metaText.split('·').map { it.trim() }.filter { it.isNotBlank() }
        val formatToken = parts.firstOrNull { it.uppercase(Locale.ROOT) in BookImport.SUPPORTED_FORMATS }?.uppercase(Locale.ROOT)
        if (formatToken == null) {
          if (parts.any { it.uppercase(Locale.ROOT) in KNOWN_UNSUPPORTED_EXTS }) continue
          continue
        }

        val language = parts.firstOrNull { part ->
          val cleaned = LANG_TAG_REGEX.replace(part, "").replace("✅", "").trim()
          cleaned.isNotBlank() &&
            cleaned.uppercase(Locale.ROOT) !in BookImport.SUPPORTED_FORMATS &&
            BookImport.parseSizeBytes(cleaned) == null &&
            !cleaned.all { it.isDigit() } &&
            !cleaned.contains("Book", true) &&
            !cleaned.startsWith("🚀")
        }?.let { raw ->
          LANG_TAG_REGEX.replace(raw, "").replace("✅", "").trim()
        }.orEmpty()

        val cover = CatalogHttpHelpers.resolveCoverUrl(
          baseUrl,
          sequenceOf(card.selectFirst("img")?.attr("data-src"), card.selectFirst("img")?.attr("src")),
        )

        result.putIfAbsent(
          md5,
          BookEdition(
            title = title,
            author = author,
            language = language,
            format = formatToken,
            isbn = IsbnValidator.extractFirstValidIsbn(card.text()),
            url = "$origin/md5/$md5",
            downloadUrl = null,
            coverUrl = cover,
            provider = TextProvider.ANNAS_ARCHIVE,
            providerId = md5,
          ),
        )
      }
      return result.values.toList()
    }
  }
}

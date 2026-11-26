package com.example.rivulet.data.lyrics

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DownloadCapture(
  val url: String,
  val referer: String? = null,
  val userAgent: String? = null,
  val filename: String? = null,
  val mimeType: String? = null,
)

object CatalogDownloadBridge {
  private const val META_NAME = "rivulet-download"

  fun encode(
    downloadUrl: String,
    referer: String? = null,
    userAgent: String? = null,
    filename: String? = null,
    mimeType: String? = null,
  ): String {
    fun esc(v: String?): String = v.orEmpty()
      .replace("&", "&amp;")
      .replace("\"", "&quot;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
    val safeUrl = esc(downloadUrl)
    return buildString {
      append("<html><head><meta name=\"$META_NAME\" content=\"1\"")
      append(" data-url=\"").append(safeUrl).append('"')
      if (!referer.isNullOrBlank()) append(" data-referer=\"").append(esc(referer)).append('"')
      if (!userAgent.isNullOrBlank()) append(" data-ua=\"").append(esc(userAgent)).append('"')
      if (!filename.isNullOrBlank()) append(" data-filename=\"").append(esc(filename)).append('"')
      if (!mimeType.isNullOrBlank()) append(" data-mime=\"").append(esc(mimeType)).append('"')
      append("></head><body><a href=\"").append(safeUrl).append("\" class=\"dlButton\">Download</a></body></html>")
    }
  }

  fun isDownloadCapture(html: String?): Boolean =
    html != null && html.contains("name=\"$META_NAME\"", ignoreCase = true)

  fun parse(html: String?): DownloadCapture? {
    if (!isDownloadCapture(html)) return null
    val meta = Jsoup.parse(html!!).selectFirst("meta[name=$META_NAME]") ?: return null
    val url = meta.attr("data-url").trim().takeIf { it.isNotEmpty() } ?: return null
    return DownloadCapture(
      url = url,
      referer = meta.attr("data-referer").trim().takeIf { it.isNotEmpty() },
      userAgent = meta.attr("data-ua").trim().takeIf { it.isNotEmpty() },
      filename = meta.attr("data-filename").trim().takeIf { it.isNotEmpty() },
      mimeType = meta.attr("data-mime").trim().takeIf { it.isNotEmpty() },
    )
  }
}

object CatalogHttpHelpers {
  const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

  private val IP_OR_PORT_REGEX = Regex("[0-9.:]+")
  private val FILENAME_CLEAN_REGEX = Regex("[^\\w.\\- ()\\[\\]]+")

  fun androidCookieReader(): (String) -> String? = { url ->
    runCatching {
      android.webkit.CookieManager.getInstance().apply { setAcceptCookie(true) }.getCookie(url)
    }.getOrNull()
  }

  fun androidCookieWriter(): (String, List<String>) -> Unit = { url, headers ->
    runCatching {
      val cm = android.webkit.CookieManager.getInstance()
      cm.setAcceptCookie(true)
      headers.forEach { header -> cm.setCookie(url, header) }
      cm.flush()
    }
  }

  fun validateHttpsUrl(url: String): HttpUrl {
    val parsed = url.toHttpUrl()
    require(
      parsed.isHttps &&
        parsed.username.isEmpty() &&
        parsed.password.isEmpty() &&
        parsed.host != "localhost" &&
        !parsed.host.matches(IP_OR_PORT_REGEX) &&
        !parsed.host.endsWith(".local")
    ) { "Unsupported ebook link." }
    return parsed
  }

  fun hostMatches(host: String, allowedHosts: Set<String>): Boolean {
    val clean = host.lowercase(Locale.ROOT)
    return clean in allowedHosts || allowedHosts.any { clean.endsWith(".$it") }
  }

  fun isChallengeHtml(code: Int, html: String): Boolean {
    val doc = Jsoup.parse(html)
    val title = doc.title().lowercase(Locale.ROOT)
    val challengePage = title.contains("checking your browser") ||
      title.contains("verifying your browser") ||
      title.contains("verify you are human") ||
      title.contains("verify you're human") ||
      title.contains("just a moment") ||
      title.contains("wait a moment") ||
      title.contains("attention required") ||
      title.contains("performing security verification") ||
      title.contains("security verification") ||
      title.contains("diamwall") ||
      title.contains("ddos-guard") ||
      title.contains("ddos") ||
      html.contains("c_token=", ignoreCase = true) ||
      html.contains("diamwall", ignoreCase = true) ||
      html.contains("/verify_captcha", ignoreCase = true) ||
      html.contains("h-captcha", ignoreCase = true) ||
      html.contains("cookies are required", ignoreCase = true) ||
      html.contains("/cdn-cgi/challenge-platform", ignoreCase = true) ||
      html.contains("/.well-known/ddos-guard", ignoreCase = true) ||
      html.contains("check.ddos-guard.net", ignoreCase = true)
    return code in setOf(401, 403, 513) ||
      (code in setOf(429, 503) && challengePage) ||
      challengePage ||
      doc.selectFirst("[class*=captcha], [id*=captcha], iframe[src*=turnstile], iframe[src*=hcaptcha], iframe[src*=recaptcha]") != null
  }

  private val baseClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(8L, TimeUnit.SECONDS)
      .readTimeout(15L, TimeUnit.SECONDS)
      .writeTimeout(15L, TimeUnit.SECONDS)
      .callTimeout(35L, TimeUnit.SECONDS)
      .build()
  }

  fun sharedClient(
    cookies: (String) -> String?,
    saveCookies: (String, List<String>) -> Unit,
    connectTimeoutSec: Long = 8L,
    readTimeoutSec: Long = 15L,
  ): OkHttpClient = baseClient.newBuilder()
    .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
    .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
    .cookieJar(object : CookieJar {
      override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (runCatching { validateHttpsUrl(url.toString()) }.isFailure) return emptyList()
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
        if (cookies.isEmpty() || runCatching { validateHttpsUrl(url.toString()) }.isFailure) return
        saveCookies(url.toString(), cookies.map { it.toString() })
      }
    })
    .addNetworkInterceptor { chain ->
      validateHttpsUrl(chain.request().url.toString())
      chain.proceed(chain.request())
    }
    .build()

  fun browserRequest(
    url: String,
    referer: String? = null,
    userAgent: String = DEFAULT_USER_AGENT,
  ): Request = Request.Builder()
    .url(url)
    .header("User-Agent", userAgent)
    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
    .header("Accept-Language", "en-US,en;q=0.9")
    .header("Upgrade-Insecure-Requests", "1")
    .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
    .build()

  fun downloadBinaryFile(
    client: OkHttpClient,
    url: String,
    edition: BookEdition,
    referer: String? = edition.url,
    userAgent: String = DEFAULT_USER_AGENT,
    siteName: String,
    isAccessRequired: (Int, String) -> Boolean = ::isChallengeHtml,
    accessFallbackUrl: String = edition.url,
  ): Triple<ByteArray, String, String> {
    validateHttpsUrl(url)
    client.newCall(browserRequest(url, referer = referer, userAgent = userAgent)).execute().use { response ->
      val contentType = response.header("Content-Type").orEmpty()
      val contentDisposition = response.header("Content-Disposition").orEmpty()
      val finalUrl = response.request.url.toString()
      val body = response.body ?: throw IOException("The ebook download was empty.")
      if (contentType.contains("html", true)) {
        val page = body.byteStream().use { BookImport.read(it, 2 * 1024 * 1024) }.toString(Charsets.UTF_8)
        if (isAccessRequired(response.code, page)) {
          throw SiteAccessRequired(accessFallbackUrl, "$siteName blocked direct app download. Try Read / Slow download in site access, or open in Browser and Import.")
        }
        throw IOException("$siteName returned a web page instead of an ebook. Open site access or Browser and Import.")
      }
      if (response.code in setOf(401, 403, 513)) throw SiteAccessRequired(accessFallbackUrl)
      if (!response.isSuccessful) {
        val errText = runCatching {
          body.byteStream().use { BookImport.read(it, 256 * 1024) }.toString(Charsets.UTF_8)
        }.getOrDefault("")
        if (errText.isNotBlank() && isAccessRequired(response.code, errText)) throw SiteAccessRequired(accessFallbackUrl)
        throw IOException("Ebook download failed (${response.code}) from $siteName ($finalUrl).")
      }
      val bytes = body.byteStream().use { BookImport.read(it) }
      return detectDownloadedBook(bytes, edition.title, edition.format, contentType, contentDisposition, siteName, accessFallbackUrl)
    }
  }

  fun detectDownloadedBook(
    bytes: ByteArray,
    title: String,
    claimedFormat: String?,
    contentType: String,
    contentDisposition: String,
    siteName: String,
    accessFallbackUrl: String,
  ): Triple<ByteArray, String, String> {
    val head = bytes.take(256).toByteArray().toString(Charsets.UTF_8).trimStart().lowercase(Locale.ROOT)
    if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
      if (isChallengeHtml(200, bytes.take(8192).toByteArray().toString(Charsets.UTF_8))) {
        throw SiteAccessRequired(accessFallbackUrl)
      }
      throw IOException("$siteName returned an HTML page instead of a book file.")
    }
    val safeStem = title.trim().replace(FILENAME_CLEAN_REGEX, " ").replace(Regex("\\s+"), " ").trim()
      .take(100).ifBlank { "book" }
    val cdLower = contentDisposition.lowercase(Locale.ROOT)
    val formatLower = claimedFormat.orEmpty().lowercase(Locale.ROOT)

    return when {
      BookImport.isPdf(bytes) -> Triple(bytes, "$safeStem.pdf", "application/pdf")
      BookImport.isZip(bytes) -> {
        val isFb2Zip = formatLower == "fb2" || cdLower.contains(".fb2")
        val ext = if (isFb2Zip) "fb2.zip" else "epub"
        val mime = if (isFb2Zip) "application/zip" else "application/epub+zip"
        Triple(bytes, "$safeStem.$ext", mime)
      }
      BookImport.isFb2Xml(bytes, contentDisposition) -> Triple(bytes, "$safeStem.fb2", "application/x-fictionbook+xml")
      formatLower == "txt" || contentType.contains("text/plain", true) || cdLower.contains(".txt") -> {
        check(bytes.none { it == 0.toByte() }) { "The downloaded text file contains invalid binary data." }
        Triple(bytes, "$safeStem.txt", "text/plain")
      }
      else -> throw IOException("Unsupported downloaded file format from $siteName. Try an EPUB, PDF, FB2, or TXT edition.")
    }
  }

  fun resolveCoverUrl(baseUrl: String, candidates: Sequence<String?>): String? {
    val parsedBase = runCatching { baseUrl.toHttpUrl() }.getOrNull() ?: return null
    return candidates
      .filterNotNull()
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .mapNotNull { parsedBase.resolve(it)?.toString() }
      .firstOrNull { runCatching { validateHttpsUrl(it) }.isSuccess }
  }
}

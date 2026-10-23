package com.example.rivulet.data.audiobookbay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class AudioBookBayClient(
  private val baseUrl: String = AudioBookBayConfig.BASE_URL,
) {
  private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

  private val cookieJar = object : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
      val list = cookieStore.getOrPut(url.host) { mutableListOf() }
      synchronized(list) {
        for (cookie in cookies) {
          list.removeAll { it.name == cookie.name }
          list.add(cookie)
        }
      }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
      val list = cookieStore[url.host] ?: return emptyList()
      synchronized(list) {
        val now = System.currentTimeMillis()
        list.removeAll { it.expiresAt < now }
        return ArrayList(list)
      }
    }
  }

  private val client =
    OkHttpClient.Builder()
      .cookieJar(cookieJar)
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()

  private val userAgent =
    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

  private var isLoggedIn = false

  suspend fun login(): Boolean = withContext(Dispatchers.IO) {
    if (AudioBookBayConfig.USERNAME.isBlank()) {
      isLoggedIn = true
      return@withContext true
    }
    runCatching {
      val loginUrl = "$baseUrl/member/login"
      val form = FormBody.Builder()
        .add("log", AudioBookBayConfig.USERNAME)
        .add("pwd", AudioBookBayConfig.PASSWORD)
        .add("rememberme", "forever")
        .add("redirect_to", baseUrl)
        .build()

      val request = Request.Builder()
        .url(loginUrl)
        .header("User-Agent", userAgent)
        .header("Referer", loginUrl)
        .post(form)
        .build()

      client.newCall(request).execute().use { response ->
        isLoggedIn = response.isSuccessful
        Log.d(TAG, "AudioBookBay login attempt: code=${response.code}, success=$isLoggedIn")
        isLoggedIn
      }
    }.getOrDefault(false)
  }

  fun categoryToSlug(category: String): String =
    when (category.lowercase().trim()) {
      "sci-fi", "science fiction", "scifi" -> "sci-fi"
      "fantasy" -> "fantasy"
      "mystery" -> "mystery"
      "thriller" -> "thriller"
      "history" -> "history"
      "fiction" -> "fiction"
      "non-fiction", "nonfiction" -> "non-fiction"
      "romance" -> "romance"
      "horror" -> "horror"
      "action", "adventure", "action-adventure" -> "action-adventure"
      "crime" -> "crime"
      "business" -> "business"
      "biography", "biographies" -> "bios-autobiography"
      else -> category.lowercase().trim().replace(" ", "-")
    }

  suspend fun getLatest(page: Int = 1): Result<List<AudioBookItem>> = withContext(Dispatchers.IO) {
    runCatching {
      val url = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"
      fetchAndParsePage(url)
    }
  }

  suspend fun getByCategory(category: String, page: Int = 1): Result<List<AudioBookItem>> = withContext(Dispatchers.IO) {
    runCatching {
      val slug = categoryToSlug(category)
      val url = if (page <= 1) "$baseUrl/audio-books/$slug/" else "$baseUrl/audio-books/$slug/page/$page/"
      try {
        fetchAndParsePage(url)
      } catch (t: Throwable) {
        val fallbackUrl = if (page <= 1) "$baseUrl/category/$slug/" else "$baseUrl/category/$slug/page/$page/"
        fetchAndParsePage(fallbackUrl)
      }
    }
  }

  suspend fun search(query: String, category: String? = null, page: Int = 1): Result<List<AudioBookItem>> = withContext(Dispatchers.IO) {
    runCatching {
      val trimmed = query.trim()
      val fullQuery = if (!category.isNullOrBlank() && category != "Latest" && !trimmed.contains(category, ignoreCase = true)) {
        "$trimmed $category"
      } else {
        trimmed
      }
      val encoded = URLEncoder.encode(fullQuery, "UTF-8")
      val url = if (page <= 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
      fetchAndParsePage(url)
    }
  }

  private fun fetchAndParsePage(url: String): List<AudioBookItem> {
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", userAgent)
      .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
      .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IllegalStateException("AudiobookBay returned HTTP ${response.code}")
      }
      val html = response.body?.string().orEmpty()
      return parseListHtml(html)
    }
  }

  fun parseListHtml(html: String): List<AudioBookItem> {
    val doc = Jsoup.parse(html, baseUrl)
    val posts = doc.select("div.post")
    val items = ArrayList<AudioBookItem>()

    for (post in posts) {
      val titleElem = post.select("div.postTitle h2 a").first()
        ?: post.select("div.postTitle a").first()
        ?: continue

      val rawTitle = titleElem.text().trim()
      val href = titleElem.attr("href").trim()
      val detailUrl = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("/") -> baseUrl.trimEnd('/') + href
        else -> "$baseUrl/$href"
      }

      val imgElem = post.select("div.postContent img").first()
      val coverUrl = imgElem?.absUrl("src")?.takeIf { it.isNotBlank() }
        ?: imgElem?.attr("src")?.takeIf { it.isNotBlank() }

      val contentText = post.select("div.postContent").text()
      val postInfoText = post.select("div.postInfo").text()

      val formatMatch = FORMAT_REGEX.find(contentText)
      val format = formatMatch?.groupValues?.getOrNull(1)?.trim() ?: ""

      val sizeMatch = SIZE_REGEX.find(contentText)
      val fileSize = sizeMatch?.groupValues?.getOrNull(1)?.trim() ?: ""

      val dateMatch = DATE_REGEX.find(contentText)
      val postedDate = dateMatch?.groupValues?.getOrNull(1)?.trim() ?: ""

      val category = postInfoText.substringAfter("Category:").substringBefore("Language:").trim()
      val language = postInfoText.substringAfter("Language:").substringBefore("Keywords:").trim()

      items.add(
        AudioBookItem(
          title = cleanTitle(rawTitle),
          author = extractAuthor(rawTitle),
          detailUrl = detailUrl,
          coverUrl = coverUrl,
          category = category,
          language = language.ifBlank { "English" },
          format = format,
          fileSize = fileSize,
          postedDate = postedDate,
        )
      )
    }

    return items
  }

  suspend fun getDetailAndMagnet(item: AudioBookItem): Result<AudioBookDetail> = withContext(Dispatchers.IO) {
    runCatching {
      val request = Request.Builder()
        .url(item.detailUrl)
        .header("User-Agent", userAgent)
        .build()

      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          throw IllegalStateException("AudiobookBay details failed HTTP ${response.code}")
        }
        val html = response.body?.string().orEmpty()
        parseDetailHtml(html, item)
      }
    }
  }

  fun parseDetailHtml(html: String, item: AudioBookItem): AudioBookDetail {
    val doc = Jsoup.parse(html, item.detailUrl)

    // Check direct magnet link
    val directMagnet = doc.select("a[href^=magnet:]").first()?.attr("href")

    // Extract Info Hash (40 hex chars)
    var infoHash: String? = null
    val hashTd = doc.select("td:contains(Info Hash)").first()
    if (hashTd != null) {
      val nextTd = hashTd.nextElementSibling()
      val text = nextTd?.text().orEmpty().trim()
      val m = HASH_REGEX.find(text)
      if (m != null) infoHash = m.value
    }
    if (infoHash == null) {
      val m = HASH_REGEX.find(html)
      if (m != null) infoHash = m.value
    }

    // Extract trackers
    val trackers = LinkedHashSet<String>()
    val trackerMatches = TRACKER_REGEX.findAll(html)
    for (match in trackerMatches) {
      val tr = match.value.trim()
      if (tr.startsWith("udp://") || tr.startsWith("http://") || tr.startsWith("https://")) {
        trackers.add(tr)
      }
    }
    trackers.addAll(AudioBookBayConfig.DEFAULT_TRACKERS)

    // Description text
    val desc = doc.select("div.desc").first()?.text()
      ?: doc.select("div.postContent p").joinToString("\n\n") { it.text() }

    // Cover image if not already resolved
    val cover = item.coverUrl
      ?: doc.select("div.postContent img").first()?.absUrl("src")

    val finalMagnet = when {
      !directMagnet.isNullOrBlank() -> directMagnet
      !infoHash.isNullOrBlank() -> {
        val dn = URLEncoder.encode(item.title, "UTF-8").replace("+", "%20")
        val trParams = trackers.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
        "magnet:?xt=urn:btih:${infoHash.lowercase()}&dn=$dn$trParams"
      }
      else -> error("Could not find Info Hash or magnet link for ${item.title}")
    }

    return AudioBookDetail(
      title = item.title,
      infoHash = infoHash.orEmpty().lowercase(),
      magnetUri = finalMagnet,
      coverUrl = cover,
      description = desc.trim(),
      trackers = trackers.toList(),
    )
  }

  private fun cleanTitle(title: String): String {
    // If title is "Book Title - Author Name", keep clean
    return title.replace(Regex("\\s+"), " ").trim()
  }

  private fun extractAuthor(title: String): String {
    if (title.contains(" - ")) {
      return title.substringAfterLast(" - ").trim()
    }
    if (title.contains(" by ", ignoreCase = true)) {
      return title.substringAfterLast(" by ", "").trim()
    }
    return ""
  }

  companion object {
    private const val TAG = "AudioBookBayClient"
    private val FORMAT_REGEX = Regex("(?i)Format:\\s*([^/\\n]+)")
    private val SIZE_REGEX = Regex("(?i)File Size:\\s*([0-9.]+\\s*[KMGTP]?Bs?)")
    private val DATE_REGEX = Regex("(?i)Posted:\\s*([^/\\n]+?)(?:Format:|$)")
    private val HASH_REGEX = Regex("(?i)\\b[a-f0-9]{40}\\b")
    private val TRACKER_REGEX = Regex("(?i)(udp|https?):\\/\\/[a-z0-9.\\-_]+(:[0-9]+)?\\/[a-z0-9._\\-/?=&%]+")
  }
}

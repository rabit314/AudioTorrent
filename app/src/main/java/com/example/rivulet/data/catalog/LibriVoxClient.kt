package com.example.rivulet.data.catalog

import com.example.rivulet.data.CatalogBook
import com.example.rivulet.data.CatalogChapter
import com.example.rivulet.data.audiobookbay.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import kotlin.coroutines.coroutineContext

class LibriVoxClient(private val http: PublicCatalogHttp = PublicCatalogHttp(setOf("librivox.org", "archive.org"))) {

  suspend fun search(query: String, page: Int): List<AudioBookItem> = this.page(query, page).books

  suspend fun page(query: String, page: Int): AudioCatalogPage = withContext(Dispatchers.IO) {
    supervisorScope {
      suspend fun result(field: String, term: String): Result<AudioCatalogPage> = try { Result.success(feed(term, page, field)) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
      val title = async { result("title", query) }
      val author: Deferred<Result<AudioCatalogPage>>? = if (query.isNotBlank()) async { result("author", authorQuery(query)) } else null
      val results = listOfNotNull(title.await(), author?.await())
      coroutineContext.ensureActive()
      val raw = results.flatMap { it.getOrNull()?.books.orEmpty() }
      var books = raw.distinctBy { it.providerId }.filter { matches(it, query) }
      var more = results.any { it.getOrNull()?.hasMore == true }
      if (books.isEmpty() && query.trim().contains(' ')) {
        val token = words(query).maxByOrNull { it.length }.orEmpty()
        if (token.isNotBlank() && token != query.trim().lowercase()) {
          val broad = feed(token, page, "title")
          books = broad.books.filter { matches(it, query) }
          more = more || broad.hasMore
        }
      }
      if (books.isEmpty()) results.firstOrNull { it.isFailure }?.getOrThrow()
      AudioCatalogPage(books, more)
    }
  }

  private suspend fun feed(query: String, page: Int, field: String): AudioCatalogPage {
    val url = "$BASE/audiobooks/".toHttpUrl().newBuilder().addQueryParameter("format", "json")
      .addQueryParameter("coverart", "1").addQueryParameter("limit", PAGE_SIZE.toString())
      .addQueryParameter("offset", ((page - 1).coerceAtLeast(0) * PAGE_SIZE).toString())
      .addQueryParameter("sort_order", "asc").apply { if (query.isNotBlank()) addQueryParameter(field, query.trim()) }.build()
    return try { parsePage(http.textAsync(url.toString())) }
    catch (error: CatalogHttpException) { if (error.status == 404) AudioCatalogPage(emptyList(), false) else throw error }
  }

  suspend fun detail(item: AudioBookItem): AudioBookDetail = withContext(Dispatchers.IO) {
    require(item.source == AudioBookSource.LIBRIVOX && item.providerId.matches(Regex("\\d+")))
    val response = JSONObject(http.textAsync("$BASE/audiobooks/?id=${item.providerId}&format=json&extended=1&coverart=1"))
    val book = response.optJSONArray("books")?.optJSONObject(0) ?: throw IOException("This LibriVox recording is unavailable.")
    val archiveLink = book.optString("url_iarchive").ifBlank { book.optString("url_zip_file") }
    if (archiveLink.isBlank()) throw IOException("This LibriVox recording is not available yet. Choose another recording.")
    val archive = http.validate(archiveLink)
    val parsed = archive.toHttpUrl()
    require(parsed.host == "archive.org" || parsed.host == "www.archive.org") { "Unsupported archive item." }
    val segments = parsed.pathSegments
    val id = segments.getOrNull(segments.indexOfFirst { it in setOf("details", "download", "compress") } + 1)
      ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: throw IOException("No Archive recording was linked.")
    coroutineContext.ensureActive()
    val metadata = JSONObject(http.textAsync("https://archive.org/metadata/$id"))
    val files = metadata.optJSONArray("files") ?: throw IOException("The Archive file list is unavailable.")
    val torrent = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
      .firstOrNull { it.optString("name").endsWith("_archive.torrent") && it.optString("format") == "Archive BitTorrent" }
      ?.optString("name") ?: throw IOException("No torrent is available for this recording. Choose another recording.")
    val torrentUrl = "https://archive.org/download/$id/".toHttpUrl().newBuilder().addPathSegment(torrent).build().toString()
    val catalog = parseCatalogBook(book).copy(webSeeds = archiveWebSeeds(metadata, id).ifEmpty { listOf("https://archive.org/download/") })
    AudioBookDetail(catalog.title, "", "", cover(book), Jsoup.parse(book.optString("description")).text(),
      source = AudioBookSource.LIBRIVOX, torrentUrl = torrentUrl, catalogBook = catalog)
  }

  suspend fun torrent(detail: AudioBookDetail): ByteArray = withContext(Dispatchers.IO) {
    require(detail.source == AudioBookSource.LIBRIVOX)
    http.bytesAsync(detail.torrentUrl ?: throw IOException("No torrent was linked."), 8 * 1024 * 1024)
  }

  companion object {
    const val BASE = "https://librivox.org/api/feed"
    const val PAGE_SIZE = 25
    fun archiveWebSeeds(metadata: JSONObject, id: String): List<String> {
      val directory = metadata.optString("dir")
      if (!directory.matches(Regex("/\\d+/items/${Regex.escape(id)}"))) return emptyList()
      val servers = metadata.optJSONArray("workable_servers") ?: JSONArray()
      return (listOf(metadata.optString("d1"), metadata.optString("d2"), metadata.optString("server")) +
        (0 until servers.length()).map { servers.optString(it) }).distinct().filter {
        it.matches(Regex("[A-Za-z0-9.-]+\\.archive\\.org"))
      }.map { "https://$it${directory.substringBeforeLast('/')}/" }
    }
    private fun words(value: String) = value.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank).toSet()
    fun authorQuery(query: String): String = if (',' in query) query.substringBefore(',').trim() else query.trim().substringAfterLast(' ')
    fun matches(item: AudioBookItem, query: String): Boolean = words("${item.title} ${item.author}").containsAll(words(query))
    private fun authors(book: JSONObject): String {
      val authors = book.optJSONArray("authors") ?: JSONArray()
      return (0 until authors.length()).mapNotNull { authors.optJSONObject(it) }.joinToString(", ") {
        listOf(it.optString("first_name"), it.optString("last_name")).filter(String::isNotBlank).joinToString(" ")
      }
    }
    private fun cover(book: JSONObject) = book.optString("coverart_jpg").ifBlank { book.optString("coverart_thumbnail") }.takeIf(String::isNotBlank)
    fun cleanTitle(title: String) = title.replace(Regex("\\s*\\((?:version|version\\s+|version:?[ ]*)\\d+[^)]*\\)\\s*", RegexOption.IGNORE_CASE), " ").trim()
    fun parsePage(json: String) = AudioCatalogPage(parseBooks(json), (JSONObject(json).optJSONArray("books")?.length() ?: 0) >= PAGE_SIZE)
    fun parseBooks(json: String): List<AudioBookItem> {
      val response = JSONObject(json)
      val books = response.optJSONArray("books") ?: return emptyList()
      return (0 until books.length()).mapNotNull { index ->
        val book = books.optJSONObject(index) ?: return@mapNotNull null
        val id = book.optString("id").takeIf { it.matches(Regex("\\d+")) } ?: return@mapNotNull null
        val title = book.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
        // The public feed also includes unfinished projects with no published audio.
        if (book.optString("url_zip_file").isBlank() && book.optString("url_iarchive").isBlank()) return@mapNotNull null
        AudioBookItem(title, authors(book), book.optString("url_librivox"), cover(book), language = book.optString("language"),
          format = "Audiobook", source = AudioBookSource.LIBRIVOX, providerId = id)
      }.distinctBy { it.providerId }
    }
    fun parseCatalogBook(book: JSONObject): CatalogBook {
      val sections = book.optJSONArray("sections") ?: JSONArray()
      val ordered = (0 until sections.length()).mapNotNull { sections.optJSONObject(it) }.sortedBy { it.optInt("section_number") }
      val chapters = ordered.map {
        val url = it.optString("listen_url")
        require(url.isNotBlank()) { "This recording has an incomplete chapter list." }
        CatalogChapter(CatalogBook.fileName(url), it.optString("title").ifBlank { "Chapter ${it.optInt("section_number")}" })
      }
      require(chapters.isNotEmpty() && chapters.size == book.optInt("num_sections", chapters.size)) { "This recording has an incomplete chapter list." }
      return CatalogBook(book.optString("id"), cleanTitle(book.optString("title")), authors(book),
        book.optString("url_text_source").takeIf(String::isNotBlank), chapters, book.optString("language"))
    }
  }
}

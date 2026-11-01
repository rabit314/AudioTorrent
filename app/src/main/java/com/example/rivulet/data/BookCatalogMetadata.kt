package com.example.rivulet.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Catalog labels are identity hints, not torrent metadata; a root folder may name only the author. */
class BookCatalogMetadata(context: Context) {
  private val preferences = context.applicationContext.getSharedPreferences("book_catalog_metadata", Context.MODE_PRIVATE)
  private val books = ConcurrentHashMap<String, Pair<String, CatalogBook>>()
  fun title(hash: String): String? = preferences.getString(hash, null)
  fun save(hash: String, title: String) { preferences.edit().putString(hash, title.take(1000)).apply() }
  fun book(hash: String): CatalogBook? = preferences.getString("$hash:book", null)?.let { json ->
    books[hash]?.takeIf { it.first == json }?.let { return it.second }
    runCatching {
      val value = JSONObject(json)
      val entries = value.getJSONArray("chapters")
      CatalogBook(value.getString("id"), value.getString("title"), value.getString("author"),
        value.optString("text").takeIf(String::isNotBlank), (0 until entries.length()).map {
          val chapter = entries.getJSONObject(it)
          CatalogChapter(chapter.getString("path"), chapter.getString("title"), chapter.getInt("index"))
        }, value.optString("language", "English"), value.optJSONArray("webSeeds")?.let { seeds ->
          (0 until seeds.length()).map { seeds.getString(it) }
        }.orEmpty())
    }.getOrNull()?.also { books[hash] = json to it }
  }
  fun saveBook(hash: String, book: CatalogBook) {
    val json = JSONObject().put("id", book.providerId).put("title", book.title).put("author", book.author)
      .put("text", book.textSourceUrl.orEmpty()).put("language", book.language).put("webSeeds", JSONArray(book.webSeeds)).put("chapters", JSONArray().apply {
        book.chapters.forEach { put(JSONObject().put("path", it.path).put("title", it.title).put("index", it.fileIndex)) }
      })
    check(preferences.edit().putString("$hash:book", json.toString()).putString(hash, "${book.title} - ${book.author}").commit()) { "Could not save the audiobook catalog metadata." }
    books[hash] = json.toString() to book
  }
  fun remove(hash: String) { books.remove(hash); preferences.edit().remove(hash).remove("$hash:book").apply() }
}

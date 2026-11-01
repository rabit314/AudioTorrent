package com.example.rivulet.data.catalog

import com.example.rivulet.data.audiobookbay.*
import kotlinx.coroutines.*

data class AudioCatalogPage(val books: List<AudioBookItem>, val hasMore: Boolean)

/** Sources are independent: an empty or failed ABB response never gates LibriVox. */
class AudiobookCatalog(private val bay: AudioBookBayClient, private val librivox: LibriVoxClient) {
  suspend fun page(source: AudioBookSource, query: String, page: Int): AudioCatalogPage {
    return when (source) {
      AudioBookSource.AUDIOBOOKBAY -> {
        val books = (if (query.isBlank()) bay.getLatest(page) else bay.search(query, page = page)).getOrThrow()
        AudioCatalogPage(books, books.isNotEmpty())
      }
      AudioBookSource.LIBRIVOX -> librivox.page(query, page)
    }
  }
  suspend fun detail(item: AudioBookItem): AudioBookDetail = when (item.source) {
    AudioBookSource.AUDIOBOOKBAY -> {
      bay.login() // Preserve the old screen's access step without delaying the independent public catalog.
      bay.getDetailAndMagnet(item).getOrThrow()
    }
    AudioBookSource.LIBRIVOX -> librivox.detail(item)
  }
  companion object {
    fun rank(books: List<AudioBookItem>, query: String): List<AudioBookItem> {
      val words = query.lowercase().split(Regex("\\W+")).filter(String::isNotBlank).toSet()
      return books.distinctBy { it.source to it.providerId }.sortedWith(compareByDescending<AudioBookItem> { book ->
        val title = book.title.lowercase().split(Regex("\\W+")).filter(String::isNotBlank).toSet()
        val author = book.author.lowercase().split(Regex("\\W+")).filter(String::isNotBlank).toSet()
        words.intersect(title).size * 10 + words.intersect(author).size * 5 +
          (if (title == words && words.isNotEmpty()) 100 else 0) + (if (author == words && words.isNotEmpty()) 150 else 0)
      }.thenBy { if (it.source == AudioBookSource.LIBRIVOX) 0 else 1 })
    }
  }
}

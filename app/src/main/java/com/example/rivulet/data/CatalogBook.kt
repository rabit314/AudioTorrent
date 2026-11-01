package com.example.rivulet.data

import java.net.URLDecoder

data class CatalogChapter(val path: String, val title: String, val fileIndex: Int = -1)

/** Provider identity and order are independent of torrent names and file indices. */
data class CatalogBook(
  val providerId: String,
  val title: String,
  val author: String,
  val textSourceUrl: String? = null,
  val chapters: List<CatalogChapter>,
  val language: String = "English",
  val webSeeds: List<String> = emptyList(),
) {
  val canAutoMatchText: Boolean get() = language.trim().lowercase() in setOf("english", "en", "eng")
  fun resolve(files: List<Pair<Int, String>>): CatalogBook {
    require(chapters.isNotEmpty()) { "This LibriVox recording has no chapters." }
    fun normalized(value: String) = value.replace('\\', '/').trimStart('/')
    val resolved = chapters.map { chapter ->
      val path = normalized(chapter.path)
      val exact = files.filter { normalized(it.second) == path || normalized(it.second).endsWith("/$path") }
      val matches = if (exact.isNotEmpty()) exact else files.filter { normalized(it.second).substringAfterLast('/') == path.substringAfterLast('/') }
      require(matches.size == 1 && isAudioFile(matches.single().second)) {
        "The torrent does not contain a unique audio file for ${chapter.title}. Choose another recording."
      }
      chapter.copy(fileIndex = matches.single().first)
    }
    require(resolved.map { it.fileIndex }.distinct().size == resolved.size) { "The recording contains duplicate chapter references." }
    return copy(chapters = resolved)
  }

  companion object {
    fun fileName(url: String): String = URLDecoder.decode(url.substringBefore('?').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
  }
}

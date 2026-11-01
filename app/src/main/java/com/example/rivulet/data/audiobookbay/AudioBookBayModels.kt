package com.example.rivulet.data.audiobookbay

import com.example.rivulet.data.CatalogBook

enum class AudioBookSource(val label: String) { AUDIOBOOKBAY("AudiobookBay"), LIBRIVOX("LibriVox") }

data class AudioBookItem(
  val title: String,
  val author: String = "",
  val detailUrl: String,
  val coverUrl: String? = null,
  val category: String = "",
  val language: String = "English",
  val format: String = "",
  val fileSize: String = "",
  val postedDate: String = "",
  val source: AudioBookSource = AudioBookSource.AUDIOBOOKBAY,
  val providerId: String = detailUrl,
)

data class AudioBookDetail(
  val title: String,
  val infoHash: String,
  val magnetUri: String,
  val coverUrl: String? = null,
  val description: String = "",
  val trackers: List<String> = emptyList(),
  val source: AudioBookSource = AudioBookSource.AUDIOBOOKBAY,
  val torrentUrl: String? = null,
  val catalogBook: CatalogBook? = null,
)

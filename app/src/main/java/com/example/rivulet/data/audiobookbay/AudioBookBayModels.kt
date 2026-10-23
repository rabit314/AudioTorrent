package com.example.rivulet.data.audiobookbay

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
)

data class AudioBookDetail(
  val title: String,
  val infoHash: String,
  val magnetUri: String,
  val coverUrl: String? = null,
  val description: String = "",
  val trackers: List<String> = emptyList(),
)

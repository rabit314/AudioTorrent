package com.example.rivulet.data

/** A single file inside a torrent. */
data class AudioFile(
  val index: Int,
  val path: String,
  val name: String,
  val size: Long,
  val downloadedBytes: Long,
  val isAudio: Boolean,
  val isWanted: Boolean = false,
  val savedPositionMs: Long = 0L,
) {
  val progress: Float
    get() = if (size <= 0L) 0f else (downloadedBytes.toFloat() / size).coerceIn(0f, 1f)

  val isDownloaded: Boolean
    get() = size > 0L && downloadedBytes >= size

  /** Subdirectory path inside the torrent (excluding root folder name), e.g. "[01] Soul Hunter". */
  val directory: String
    get() {
      val normalized = path.replace('\\', '/')
      val parts = normalized.split('/')
      return if (parts.size > 2) {
        parts.subList(1, parts.size - 1).joinToString("/")
      } else if (parts.size == 2) {
        parts[0]
      } else {
        ""
      }
    }

  /** Formatted title showing folder context if inside subfolders, e.g. "[01] Soul Hunter / Soul Hunter.m4b". */
  val displayTitle: String
    get() = if (directory.isNotBlank()) "$directory / $name" else name
}

/** UI snapshot of one torrent. */
data class TorrentUi(
  val infoHash: String,
  val name: String,
  val state: String,
  val progress: Float,
  val downloadRate: Long,
  val uploadRate: Long,
  val peers: Int,
  val seeds: Int,
  val totalBytes: Long,
  val doneBytes: Long,
  val hasMetadata: Boolean,
  val files: List<AudioFile>,
  val error: String? = null,
  val coverUrl: String? = null,
  val catalogTitle: String? = null,
) {
  /**
   * Audio files serialized by the exact sequence defined by the torrent provider (index order).
   * Preserves author/creator sequencing by default.
   */
  val audioFiles: List<AudioFile> = files.filter { it.isAudio }.sortedBy { it.index }

  val isReady: Boolean
    get() = hasMetadata && audioFiles.isNotEmpty()

  val isFullyDownloaded: Boolean
    get() = isReady && audioFiles.isNotEmpty() && audioFiles.all { it.isDownloaded }
}

/** Natural alphanumeric comparator for chapters and filenames (e.g., Chapter 2 before Chapter 10). */
val NATURAL_NAME_COMPARATOR = Comparator<String> { s1, s2 ->
  val r = Regex("(\\d+)|(\\D+)")
  val m1 = r.findAll(s1).map { it.value }.toList()
  val m2 = r.findAll(s2).map { it.value }.toList()
  val minLen = minOf(m1.size, m2.size)
  for (i in 0 until minLen) {
    val p1 = m1[i]
    val p2 = m2[i]
    if (p1 != p2) {
      val n1 = p1.toLongOrNull()
      val n2 = p2.toLongOrNull()
      if (n1 != null && n2 != null) {
        val cmp = n1.compareTo(n2)
        if (cmp != 0) return@Comparator cmp
      } else {
        val cmp = p1.compareTo(p2, ignoreCase = true)
        if (cmp != 0) return@Comparator cmp
      }
    }
  }
  m1.size.compareTo(m2.size)
}

/** Alphanumeric comparator that respects directory paths and natural numbering (e.g. [01] before [02]). */
val NATURAL_PATH_COMPARATOR = Comparator<AudioFile> { a, b ->
  val cmp = NATURAL_NAME_COMPARATOR.compare(a.path, b.path)
  if (cmp != 0) cmp else a.index.compareTo(b.index)
}

private val AUDIO_EXTENSIONS =
  setOf(
    "mp3",
    "m4a",
    "m4b",
    "mp4a",
    "aac",
    "flac",
    "ogg",
    "oga",
    "opus",
    "wav",
    "wma",
    "ape",
    "alac",
    "mka",
    "aiff",
    "aif",
    "mp2",
  )

fun isAudioFile(name: String): Boolean {
  val ext = name.substringAfterLast('.', "").lowercase()
  return ext in AUDIO_EXTENSIONS
}

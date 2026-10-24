package com.example.rivulet.data.lyrics

data class TimedLine(
  val id: Long,
  val startTimeMs: Long,
  val endTimeMs: Long,
  val text: String,
  val isParagraphStart: Boolean = false,
) {
  val isTimed: Boolean get() = startTimeMs >= 0L && endTimeMs > startTimeMs
  val durationMs: Long
    get() = (endTimeMs - startTimeMs).coerceAtLeast(0L)
}

enum class TextSyncSource(val displayName: String) {
  NARRATION("Matched to narration"),
  LRC("Synced LRC"),
  SRT("Subtitle SRT"),
  EPUB_PACED("Estimated book timing"),
  TXT_PACED("Paced Text"),
  LOCAL_ATTACHMENT("Attached File"),
}

data class PageTranscript(
  val infoHash: String,
  val fileIndex: Int,
  val pageIndex: Int,
  val startTimeMs: Long,
  val endTimeMs: Long,
  val source: TextSyncSource,
  val lines: List<TimedLine>,
)

package com.example.rivulet.data.lyrics

import android.util.Log
import com.example.rivulet.data.TorrentDatabase
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages rolling sliding-window page caching (~90 seconds / ~250 words per page)
 * so the app never has to load or process huge books into memory all at once.
 */
class PageTextManager(
  private val database: TorrentDatabase,
) {

  private val memoryCache = ConcurrentHashMap<String, PageTranscript>()
  private var sourceLines: List<TimedLine> = emptyList()
  private var currentSource: TextSyncSource = TextSyncSource.TXT_PACED
  private var currentTrackHash: String? = null
  private var currentTrackFileIndex: Int = -1

  fun setSourceLines(
    lines: List<TimedLine>,
    source: TextSyncSource,
    infoHash: String? = null,
    fileIndex: Int = -1,
  ) {
    sourceLines = lines
    currentSource = source
    currentTrackHash = infoHash
    currentTrackFileIndex = fileIndex
    memoryCache.clear()
  }

  fun clear() {
    sourceLines = emptyList()
    currentTrackHash = null
    currentTrackFileIndex = -1
    memoryCache.clear()
  }

  fun invalidate(infoHash: String, fileIndex: Int? = null) {
    clear()
    database.deletePageText(infoHash, fileIndex)
  }

  /**
   * Retrieves the rolling 3-page buffer (Page N-1, Page N, Page N+1) around [positionMs].
   */
  fun getActiveWindow(
    infoHash: String,
    fileIndex: Int,
    positionMs: Long,
    durationMs: Long,
  ): List<TimedLine> {
    if (sourceLines.isEmpty()) return emptyList()
    if (currentTrackHash != null && (currentTrackHash != infoHash || currentTrackFileIndex != fileIndex)) {
      clear()
      return emptyList()
    }

    val pageIndex = (positionMs / PAGE_DURATION_MS).toInt().coerceAtLeast(0)
    val maxPage = (durationMs / PAGE_DURATION_MS).toInt().coerceAtLeast(pageIndex)

    val windowLines = ArrayList<TimedLine>()
    val startPage = maxOf(0, pageIndex - 1)
    val endPage = minOf(maxPage, pageIndex + 1)

    // Bound memory cache to only keep active window pages
    if (memoryCache.size > 8) {
      val validKeys = (maxOf(0, startPage - 1)..endPage + 1).map { "$infoHash:$fileIndex:$it" }.toSet()
      for (k in ArrayList(memoryCache.keys)) {
        if (!validKeys.contains(k)) memoryCache.remove(k)
      }
    }

    for (p in startPage..endPage) {
      val page = getOrLoadPage(infoHash, fileIndex, p, durationMs)
      if (page != null) {
        windowLines.addAll(page.lines)
      }
    }

    return windowLines
  }

  private fun getOrLoadPage(
    infoHash: String,
    fileIndex: Int,
    pageIndex: Int,
    durationMs: Long,
  ): PageTranscript? {
    val key = "$infoHash:$fileIndex:$pageIndex"
    memoryCache[key]?.let { return it }

    val startMs = pageIndex * PAGE_DURATION_MS
    val endMs = minOf(durationMs, (pageIndex + 1) * PAGE_DURATION_MS)

    // Check SQLite cache
    val cachedData = database.getPageText(infoHash, fileIndex, pageIndex)
    if (!cachedData.isNullOrBlank()) {
      val parsed = deserializeLines(cachedData)
      val page = PageTranscript(
        infoHash = infoHash,
        fileIndex = fileIndex,
        pageIndex = pageIndex,
        startTimeMs = startMs,
        endTimeMs = endMs,
        source = currentSource,
        lines = parsed,
      )
      memoryCache[key] = page
      return page
    }

    // Slice from source lines
    val matchingLines = sourceLines.filter { line ->
      line.startTimeMs < endMs && line.endTimeMs > startMs
    }

    if (matchingLines.isNotEmpty()) {
      val page = PageTranscript(
        infoHash = infoHash,
        fileIndex = fileIndex,
        pageIndex = pageIndex,
        startTimeMs = startMs,
        endTimeMs = endMs,
        source = currentSource,
        lines = matchingLines,
      )
      memoryCache[key] = page

      // Persist page in background
      try {
        val serialized = serializeLines(matchingLines)
        database.savePageText(infoHash, fileIndex, pageIndex, startMs, endMs, serialized)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to cache page $pageIndex to SQLite", t)
      }

      return page
    }

    return null
  }

  companion object {
    private const val TAG = "PageTextManager"
    const val PAGE_DURATION_MS = 90_000L // 90 seconds per page

    fun serializeLines(lines: List<TimedLine>): String =
      lines.joinToString("\n") { "${it.id}\t${it.startTimeMs}\t${it.endTimeMs}\t${if (it.isParagraphStart) 1 else 0}\t${it.text.replace("\t", " ").replace("\n", " ")}" }

    fun deserializeLines(data: String): List<TimedLine> =
      data.lines().mapNotNull { raw ->
        val parts = raw.split("\t")
        if (parts.size >= 5) {
          val id = parts[0].toLongOrNull() ?: 0L
          val start = parts[1].toLongOrNull() ?: 0L
          val end = parts[2].toLongOrNull() ?: 0L
          val isPara = parts[3] == "1"
          val text = parts.subList(4, parts.size).joinToString("\t")
          TimedLine(id, start, end, text, isPara)
        } else null
      }
  }
}

package com.example.rivulet.stream

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo

/**
 * A playback lease over one file in a torrent.
 *
 * Bytes are read straight from the (sparse) file on disk, but only after libtorrent reports the
 * covering piece as complete. Piece deadlines keep the download window just ahead of the reader.
 */
class StreamSession(
  val infoHash: String,
  private val handle: TorrentHandle,
  private val info: TorrentInfo,
  val fileIndex: Int,
  val file: File,
) {
  private val files = info.files()
  private val fileOffset: Long = files.fileOffset(fileIndex)
  private val pieceLength: Int = info.pieceLength()
  private val numPieces: Int = info.numPieces()
  private val deadlinePieces = HashSet<Int>()
  private val tailPieces = HashSet<Int>()
  private val closed = AtomicBoolean(false)
  private val refs = AtomicInteger(0)
  private var lastWindowPos = -1L

  val name: String = files.fileName(fileIndex)
  val fileSize: Long = files.fileSize(fileIndex)
  val mimeType: String = mimeTypeFor(name)

  val isClosed: Boolean
    get() = closed.get()
  val referenceCount: Int get() = refs.get()

  /** Called by the HTTP server for each connection that reuses this lease. */
  fun retain() {
    refs.incrementAndGet()
  }

  /** Returns the number of connections still using this lease. */
  fun release(): Int = refs.updateAndGet { maxOf(0, it - 1) }

  /** Waits until the first [bufferBytes] of the file are verified, so ExoPlayer can start cleanly. */
  suspend fun awaitStart(
    bufferBytes: Long = INITIAL_BUFFER_BYTES,
    timeoutMs: Long = START_TIMEOUT_MS,
  ) {
    if (fileSize <= 0L) error("This file is empty.")
    val end = min(fileSize, bufferBytes.coerceAtLeast(1L)) - 1
    Log.d(TAG, "awaitStart: waiting for bytes 0..$end of $name")
    awaitRange(0, end, timeoutMs)
  }

  fun start() {
    runCatching { handle.resume() }
    runCatching { handle.forceReannounce() }
    runCatching { handle.forceDHTAnnounce() }
    // Must be set before piece deadlines: changing file priorities resets piece priorities.
    handle.filePriority(fileIndex, Priority.TOP_PRIORITY)
    handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)
    armWindow(0)
    armMp4TailIfNeeded()
  }

  /**
   * For MP4/M4B audiobooks and albums where moov atom might be located at the tail,
   * prioritize the trailing 2 MB so player can parse headers without stalling.
   */
  private fun armMp4TailIfNeeded() {
    val isMp4 =
      mimeType == "audio/mp4" ||
        name.endsWith(".m4b", ignoreCase = true) ||
        name.endsWith(".m4a", ignoreCase = true) ||
        name.endsWith(".mp4", ignoreCase = true)

    if (isMp4 && fileSize > READ_AHEAD_BYTES) {
      val tailPos = max(0L, fileSize - 2L * 1024 * 1024)
      val first = pieceAt(tailPos)
      val last = min(numPieces - 1, pieceAt(fileSize - 1))
      for (piece in first..last) {
        if (tailPieces.add(piece)) {
          runCatching { handle.setPieceDeadline(piece, 0) }
        }
      }
      Log.d(TAG, "Armed MP4/M4B tail pieces $first..$last for $name")
    }
  }

  /** Requests the pieces covering [position, position + 8 MiB]. Cheap to call repeatedly. */
  fun armWindow(position: Long) {
    if (closed.get()) return
    if (lastWindowPos >= 0 && abs(position - lastWindowPos) < pieceLength) return
    lastWindowPos = position

    val first = pieceAt(position)
    val last = min(numPieces - 1, pieceAt(position + READ_AHEAD_BYTES))

    for (piece in first..last) {
      if (deadlinePieces.add(piece)) {
        val deadlineMs = ((piece - first) * 200).coerceAtLeast(0)
        runCatching { handle.setPieceDeadline(piece, deadlineMs) }
      }
    }

    val iterator = deadlinePieces.iterator()
    while (iterator.hasNext()) {
      val piece = iterator.next()
      if ((piece < first - 1 || piece > last) && !tailPieces.contains(piece)) {
        runCatching { handle.resetPieceDeadline(piece) }
        iterator.remove()
      }
    }
  }

  /** Suspends until every piece covering [from, to] is verified on disk. */
  suspend fun awaitRange(from: Long, to: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    val first = pieceAt(from)
    val last = pieceAt(to)
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    var loopCount = 0

    while (true) {
      if (closed.get()) error("Stream closed")
      var ready = true
      for (piece in first..last) {
        val have = try {
          handle.havePiece(piece)
        } catch (_: Throwable) {
          false
        }
        if (!have) {
          ready = false
          break
        }
      }
      if (ready) {
        Log.d(TAG, "Ready: pieces $first-$last available for $name")
        return
      }
      if (SystemClock.elapsedRealtime() > deadline) {
        Log.w(TAG, "Timed out waiting for pieces $first-$last of $name")
        error("Timed out waiting for torrent data. The swarm may be slow or empty.")
      }
      loopCount++
      if (loopCount % 50 == 0) {
        Log.d(TAG, "Still awaiting pieces $first-$last for $name (remaining: ${(deadline - SystemClock.elapsedRealtime()) / 1000}s)")
      }
      delay(100)
    }
  }

  fun close(restorePriority: Priority = Priority.IGNORE) {
    if (closed.compareAndSet(false, true)) {
      runCatching { handle.clearPieceDeadlines() }
      runCatching { handle.filePriority(fileIndex, restorePriority) }
    }
  }

  fun pieceAt(position: Long): Int {
    val clamped = position.coerceIn(0, (fileSize - 1).coerceAtLeast(0))
    val absolute = fileOffset + clamped
    return (absolute / pieceLength).toInt().coerceIn(0, numPieces - 1)
  }

  companion object {
    private const val TAG = "RivuletStream"
    private const val READ_AHEAD_BYTES = 8L * 1024 * 1024
    private const val INITIAL_BUFFER_BYTES = 256L * 1024
    private const val DEFAULT_TIMEOUT_MS = 90_000L
    private const val START_TIMEOUT_MS = 120_000L

    fun mimeTypeFor(name: String): String =
      when (name.substringAfterLast('.', "").lowercase()) {
        "mp3", "mp2" -> "audio/mpeg"
        "m4a", "m4b", "mp4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "mka" -> "audio/x-matroska"
        "wma" -> "audio/x-ms-wma"
        "ape" -> "audio/x-ape"
        "aiff", "aif" -> "audio/aiff"
        else -> "application/octet-stream"
      }
  }
}

package com.example.rivulet.torrent

import android.content.Context
import android.util.Log
import com.example.rivulet.data.AudioFile
import com.example.rivulet.data.BookCatalogMetadata
import com.example.rivulet.data.SavedTorrent
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.TorrentUi
import com.example.rivulet.data.isAudioFile
import com.example.rivulet.stream.StreamSession
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.swig.torrent_flags_t

/**
 * Owns the libtorrent session and exposes torrents as UI state.
 *
 * Torrents and download settings are backed by SQLite so state survives app restarts.
 */
class TorrentEngine(
  context: Context,
  val database: TorrentDatabase = TorrentDatabase(context),
  private val streamIdleTimeoutMs: Long = 60_000L,
) : AlertListener {

  private val appContext = context.applicationContext
  private val session = SessionManager(false)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val saveDir = File(appContext.filesDir, "torrents")
  private val magnetTempDir = File(appContext.cacheDir, "magnet")
  private val catalogMetadata = BookCatalogMetadata(appContext)

  private val handles = ConcurrentHashMap<String, TorrentHandle>()
  private val errors = ConcurrentHashMap<String, String>()
  private val coverCache = ConcurrentHashMap<String, String>()
  private val ready = AtomicBoolean(false)
  private val addLock = Mutex()
  private val streamLock = Mutex()

  @Volatile private var activeStream: StreamSession? = null
  @Volatile private var selectedStreamKey: Pair<String, Int>? = null
  private var streamIdleJob: Job? = null

  private val _torrents = MutableStateFlow<List<TorrentUi>>(emptyList())
  val torrents: StateFlow<List<TorrentUi>> = _torrents.asStateFlow()

  init {
    saveDir.mkdirs()
    magnetTempDir.mkdirs()
    scope.launch { runSession() }
  }

  private suspend fun runSession() {
    try {
      val settings =
        SettingsPack.defaultSettings().apply {
          // Mobile efficiency: 64 connections avoids battery drain and baseband saturation
          connectionsLimit(64)
          activeDownloads(3)
          activeSeeds(2)
          uploadRateLimit(32 * 1024)
          setEnableDht(true)
          setEnableLsd(true)
          setMaxMetadataSize(16 * 1024 * 1024)
        }
      session.start(SessionParams(settings))
      session.addListener(this@TorrentEngine)

      ready.set(true)
      Log.i(TAG, "Torrent engine started")

      // Restore saved torrents from database
      restoreSavedTorrents()

      var logCounter = 0
      while (scope.isActive) {
        val hasActivity = handles.values.any { runCatching { it.isValid && it.status().downloadRate() > 0 }.getOrDefault(false) }
        refresh(logStats = (++logCounter % 5 == 0))
        // Adaptive polling: 1000ms when actively downloading, 3000ms when idle to save CPU/battery
        delay(if (hasActivity) 1000L else 3000L)
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Torrent session failed", t)
      errors[SESSION_KEY] = t.message ?: "Torrent engine failed to start"
      _torrents.value = emptyList()
    }
  }

  private suspend fun restoreSavedTorrents() {
    val saved = database.getAllTorrents()
    for (st in saved) {
      st.coverUrl?.let { coverCache[st.infoHash] = it }
      try {
        if (st.torrentBytes != null) {
          val info = TorrentInfo(st.torrentBytes)
          val hash = info.infoHash().toHex()
          val wantedMap = database.getFilePriorities(st.infoHash)
          val priorities = Array(info.numFiles()) { idx ->
            if (wantedMap[idx] == true) Priority.DEFAULT else Priority.IGNORE
          }
          session.download(info, saveDir, null, priorities, null, torrent_flags_t())
          val handle = awaitHandle(info.infoHash())
          if (handle != null && handle.isValid) {
            handles[hash] = handle
            Log.i(TAG, "Restored torrent $hash: ${info.name()}")
          }
        } else if (!st.magnetUri.isNullOrBlank()) {
          scope.launch {
            runCatching {
              val bytes = session.fetchMagnet(st.magnetUri, MAGNET_TIMEOUT_SECONDS, magnetTempDir)
              if (bytes != null) {
                val info = TorrentInfo(bytes)
                val hash = info.infoHash().toHex()
                database.saveTorrent(
                  SavedTorrent(
                    infoHash = hash,
                    name = info.name(),
                    addedAt = st.addedAt,
                    magnetUri = st.magnetUri,
                    torrentBytes = bytes,
                  )
                )
                addTorrentInfo(info)
              }
            }
          }
        }
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to restore torrent ${st.infoHash}", t)
      }
    }
    refresh(logStats = true)
  }

  // ---------------------------------------------------------------------------------------------
  // Adding torrents
  // ---------------------------------------------------------------------------------------------

  suspend fun addMagnet(uri: String, coverUrl: String? = null, catalogTitle: String? = null): Result<String> = withContext(Dispatchers.IO) {
    addLock.withLock {
      runCatching {
        ensureReady()
        val trimmed = uri.trim()
        require(trimmed.startsWith("magnet:", ignoreCase = true)) {
          "That doesn't look like a magnet link."
        }
        val bytes =
          session.fetchMagnet(trimmed, MAGNET_TIMEOUT_SECONDS, magnetTempDir)
            ?: error("Couldn't fetch metadata. The swarm may be empty or the link may be dead.")
        val info = TorrentInfo(bytes)
        val hash = info.infoHash().toHex()
        catalogTitle?.takeIf { it.isNotBlank() }?.let { catalogMetadata.save(hash, it) }
        database.saveTorrent(
          SavedTorrent(
            infoHash = hash,
            name = info.name(),
            addedAt = System.currentTimeMillis(),
            magnetUri = trimmed,
            torrentBytes = bytes,
            coverUrl = coverUrl,
          )
        )
        if (coverUrl != null) {
          coverCache[hash] = coverUrl
          database.updateTorrentCover(hash, coverUrl)
        }
        addTorrentInfo(info)
      }
    }
  }

  suspend fun addTorrentFile(bytes: ByteArray, displayName: String? = null): Result<String> =
    withContext(Dispatchers.IO) {
      addLock.withLock {
        runCatching {
          ensureReady()
          val info = TorrentInfo(bytes)
          val hash = info.infoHash().toHex()
          val name = displayName?.takeIf { it.isNotBlank() } ?: info.name()
          database.saveTorrent(
            SavedTorrent(
              infoHash = hash,
              name = name,
              addedAt = System.currentTimeMillis(),
              magnetUri = null,
              torrentBytes = bytes,
            )
          )
          addTorrentInfo(info)
        }
      }
    }

  private suspend fun addTorrentInfo(info: TorrentInfo): String {
    val hash = info.infoHash().toHex()
    val existing = getLiveHandle(hash)
    if (existing != null && existing.isValid) return hash

    val wantedMap = database.getFilePriorities(hash)
    val priorities = Array(info.numFiles()) { idx ->
      if (wantedMap[idx] == true) Priority.DEFAULT else Priority.IGNORE
    }
    session.download(info, saveDir, null, priorities, null, torrent_flags_t())

    val handle = awaitHandle(info.infoHash()) ?: error("The torrent could not be added.")
    handles[hash] = handle
    refresh(logStats = true)
    return hash
  }

  private suspend fun awaitHandle(hash: Sha1Hash): TorrentHandle? {
    repeat(50) {
      val handle = runCatching { session.find(hash) }.getOrNull()
      if (handle != null && handle.isValid) return handle
      delay(100)
    }
    return null
  }

  private fun getLiveHandle(hash: String): TorrentHandle? {
    val sha1 = runCatching { Sha1Hash.parseHex(hash) }.getOrNull()
    if (sha1 != null) {
      val live = runCatching { session.find(sha1) }.getOrNull()
      if (live != null && live.isValid) {
        handles[hash] = live
        return live
      }
    }
    return handles[hash]?.takeIf { it.isValid }
  }

  private suspend fun ensureReady() {
    if (ready.get()) return
    repeat(100) {
      if (ready.get()) return
      delay(100)
    }
    error(errors[SESSION_KEY] ?: "Torrent engine is not running.")
  }

  // ---------------------------------------------------------------------------------------------
  // Streaming
  // ---------------------------------------------------------------------------------------------

  /** Creates (or reuses) the lease for a file and starts prioritizing its data. */
  suspend fun prepareStream(hash: String, fileIndex: Int): StreamSession {
    var prepared: StreamSession? = null
    try {
      return withContext(Dispatchers.IO) {
        streamLock.withLock { prepareStreamLocked(hash, fileIndex).also { prepared = it } }
      }
    } catch (cancelled: CancellationException) {
      withContext(NonCancellable) { prepared?.let { abandonPreparation(it) } }
      throw cancelled
    }
  }

  /** Never close another request's stream, nor one already acquired by playback/analysis. */
  suspend fun abandonPreparation(stream: StreamSession) = withContext(Dispatchers.IO) {
    streamLock.withLock {
      if (activeStream === stream && stream.referenceCount == 0) {
        activeStream = null
        selectedStreamKey = null
        stream.close(calculateEffectivePriority(stream.infoHash, stream.fileIndex))
      }
    }
  }

  private fun prepareStreamLocked(hash: String, fileIndex: Int): StreamSession {
    val handle = getLiveHandle(hash) ?: error("Torrent not found.")
    val info = handle.torrentFile() ?: error("Metadata is not available yet.")
    val storage = info.files()
    require(fileIndex in 0 until storage.numFiles()) { "Unknown file." }

    val size = storage.fileSize(fileIndex)
    require(size > 0L) { "This file is empty." }
    val file = File(handle.savePath(), storage.filePath(fileIndex))
    file.parentFile?.mkdirs()
    if (!file.exists() || file.length() < size) {
      RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    val stream = StreamSession(hash, handle, info, fileIndex, file)
    streamIdleJob?.cancel()
    streamIdleJob = null
    val prevStream = activeStream
    activeStream = null
    if (prevStream != null && !prevStream.isClosed) {
      val restorePriority = calculateEffectivePriority(prevStream.infoHash, prevStream.fileIndex)
      prevStream.close(restorePriority)
    }
    activeStream = stream
    selectedStreamKey = hash to fileIndex
    stream.start()
    Log.d(TAG, "Prepared stream $hash/$fileIndex: ${stream.name}, $size bytes")
    return stream
  }

  /** Used by the HTTP server: reuses the prepared lease when it matches. */
  suspend fun acquireStream(hash: String, fileIndex: Int): StreamSession =
    withContext(Dispatchers.IO) {
      streamLock.withLock {
        streamIdleJob?.cancel()
        streamIdleJob = null
        val current = activeStream
        if (current != null && !current.isClosed && current.infoHash == hash && current.fileIndex == fileIndex) {
          current.retain()
          current
        } else {
          check(selectedStreamKey == (hash to fileIndex)) { "This audio request is no longer active. Start the selected track again." }
          prepareStreamLocked(hash, fileIndex).also { it.retain() }
        }
      }
    }

  fun calculateEffectivePriority(hash: String, fileIndex: Int): Priority {
    val isStreaming = activeStream?.let { it.infoHash == hash && it.fileIndex == fileIndex && !it.isClosed } ?: false
    if (isStreaming) return Priority.TOP_PRIORITY
    val isWanted = database.isFileWanted(hash, fileIndex)
    return if (isWanted) Priority.DEFAULT else Priority.IGNORE
  }

  /** Analysis borrows the current lease; it never prepares a different stream or arms deadlines. */
  suspend fun analysisDataSource(hash: String, fileIndex: Int): android.media.MediaDataSource = withContext(Dispatchers.IO) {
    val stream = streamLock.withLock {
      val current = activeStream
      check(current != null && !current.isClosed && current.infoHash == hash && current.fileIndex == fileIndex) {
        "Start this audio track before preparing read-along."
      }
      current.retain()
      current
    }
    object : android.media.MediaDataSource() {
      private val input = RandomAccessFile(stream.file, "r")
      private var closed = false
      @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed || stream.isClosed) throw java.io.IOException("Audio analysis was cancelled.")
        if (position >= stream.fileSize) return -1
        if (size == 0) return 0
        val count = minOf(size.toLong(), stream.fileSize - position, 64 * 1024L).toInt()
        try {
          kotlinx.coroutines.runBlocking { stream.awaitRange(position, position + count - 1, 2_000) }
          input.seek(position); input.readFully(buffer, offset, count)
          return count
        } catch (error: Exception) { throw java.io.IOException("Waiting for verified audio data. Playback can continue.", error) }
      }
      override fun getSize() = stream.fileSize
      @Synchronized override fun close() {
        if (!closed) {
          closed = true
          input.close()
          kotlinx.coroutines.runBlocking { releaseStream(stream) }
        }
      }
    }
  }

  suspend fun releaseStream(stream: StreamSession) {
    streamLock.withLock {
      if (stream.release() > 0 || activeStream !== stream) return@withLock
      // Stream is idle. Grace period: keep piece priorities for 60s to avoid churning swarm on pause
      streamIdleJob?.cancel()
      streamIdleJob = scope.launch(Dispatchers.IO) {
        delay(streamIdleTimeoutMs)
        streamLock.withLock {
          if (activeStream === stream && !stream.isClosed) {
            activeStream = null
            val restorePriority = calculateEffectivePriority(stream.infoHash, stream.fileIndex)
            stream.close(restorePriority)
            Log.d(TAG, "Idle stream lease closed for ${stream.name} (restored priority: $restorePriority)")
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Offline Downloads & Management
  // ---------------------------------------------------------------------------------------------

  fun setFileDownloadWanted(hash: String, fileIndex: Int, wanted: Boolean) {
    val handle = getLiveHandle(hash) ?: return
    handle.filePriority(fileIndex, if (wanted) Priority.DEFAULT else Priority.IGNORE)
    database.saveFileWanted(hash, fileIndex, wanted)
    refresh(logStats = true)
  }

  fun setTorrentDownloadWanted(hash: String, wanted: Boolean) {
    val handle = getLiveHandle(hash) ?: return
    val info = handle.torrentFile() ?: return
    val count = info.numFiles()
    for (i in 0 until count) {
      val isAudio = isAudioFile(info.files().filePath(i))
      if (isAudio) {
        handle.filePriority(i, if (wanted) Priority.DEFAULT else Priority.IGNORE)
        database.saveFileWanted(hash, i, wanted)
      }
    }
    refresh(logStats = true)
  }

  fun findCompanionTextFile(hash: String): Pair<Int, String>? {
    val handle = getLiveHandle(hash) ?: return null
    val info = handle.torrentFile() ?: return null
    val count = info.numFiles()
    for (i in 0 until count) {
      val name = info.files().fileName(i).lowercase()
      if (name.endsWith(".lrc") || name.endsWith(".srt") || name.endsWith(".epub") || name.endsWith(".txt")) {
        return i to info.files().fileName(i)
      }
    }
    return null
  }

  suspend fun deleteTorrent(hash: String, deleteFiles: Boolean) = removeTorrent(hash, deleteFiles)

  suspend fun removeTorrent(hash: String, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
    addLock.withLock {
      val handle = handles.remove(hash)
      val info = handle?.let { runCatching { it.torrentFile() }.getOrNull() }
      val savePath = handle?.let { runCatching { it.savePath() }.getOrNull() }
      // The native file storage is borrowed from the handle and dies on session.remove.
      val paths = if (deleteFiles && info != null) {
        val storage = info.files()
        (0 until storage.numFiles()).map { storage.filePath(it) }
      } else emptyList()

      streamLock.withLock {
        if (activeStream?.infoHash == hash) {
          activeStream?.close()
          activeStream = null
        }
      }
      if (handle != null && handle.isValid) {
        runCatching { session.remove(handle) }
      }
      database.deleteTorrent(hash)
      catalogMetadata.remove(hash)

      if (deleteFiles && savePath != null) {
        val root = File(savePath)
        for (relPath in paths) {
          val file = File(root, relPath)
          runCatching { file.delete() }
          var parent = file.parentFile
          while (parent != null && parent != root && (parent.listFiles()?.isEmpty() == true)) {
            parent.delete()
            parent = parent.parentFile
          }
        }
      }

      errors.remove(hash)
      refresh(logStats = true)
    }
  }

  fun find(hash: String): TorrentUi? = _torrents.value.firstOrNull { it.infoHash == hash }

  fun shutdown() {
    activeStream?.close()
    activeStream = null
    scope.cancel()
    runCatching { session.stop() }
  }

  // ---------------------------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------------------------

  private fun refresh(logStats: Boolean = false) {
    val list = ArrayList<TorrentUi>()
    val currentHashes = HashSet(handles.keys)
    for (hash in currentHashes) {
      try {
        val handle = getLiveHandle(hash) ?: continue
        if (!handle.isValid) continue

        val status = handle.status(true)
        val info = handle.torrentFile()
        val files = if (info != null) readFiles(hash, handle, info) else emptyList()
        val name =
          info?.name()?.takeIf { it.isNotBlank() }
            ?: status.name().takeIf { it.isNotBlank() }
            ?: hash

        val peers = status.numPeers()
        val seeds = status.numSeeds()
        val downRate = status.downloadRate().toLong()

        if (logStats) {
          Log.d(TAG, "Torrent '$name': peers=$peers, seeds=$seeds, downRate=${downRate / 1024} KB/s, progress=${(status.progress() * 100).toInt()}%")
        }

        val coverUrl = coverCache[hash] ?: database.getTorrent(hash)?.coverUrl?.also { coverCache[hash] = it }

        list.add(
          TorrentUi(
            infoHash = hash,
            name = name,
            state = stateLabel(status.state()),
            progress = status.progress().coerceIn(0f, 1f),
            downloadRate = downRate,
            uploadRate = status.uploadRate().toLong(),
            peers = peers,
            seeds = seeds,
            totalBytes = info?.totalSize() ?: status.total(),
            doneBytes = status.totalDone(),
            hasMetadata = info != null,
            files = files,
            error = errors[hash],
            coverUrl = coverUrl,
            catalogTitle = catalogMetadata.title(hash),
          )
        )
      } catch (t: Throwable) {
        Log.w(TAG, "Error refreshing torrent $hash", t)
      }
    }
    _torrents.value = list.sortedBy { it.name.lowercase() }
  }

  private fun readFiles(hash: String, handle: TorrentHandle, info: TorrentInfo): List<AudioFile> {
    val storage = info.files()
    val count = storage.numFiles()
    val progress = runCatching { handle.fileProgress() }.getOrDefault(LongArray(0))
    val wantedMap = database.getFilePriorities(hash)
    val positionsMap = database.getAllPlaybackPositions(hash)
    val result = ArrayList<AudioFile>(count)

    for (index in 0 until count) {
      val path = storage.filePath(index)
      result.add(
        AudioFile(
          index = index,
          path = path,
          name = storage.fileName(index),
          size = storage.fileSize(index),
          downloadedBytes = progress.getOrElse(index) { 0L },
          isAudio = isAudioFile(path),
          isWanted = wantedMap[index] == true,
          savedPositionMs = positionsMap[index] ?: 0L,
        )
      )
    }
    return result
  }

  private fun stateLabel(state: TorrentStatus.State): String =
    when (state) {
      TorrentStatus.State.DOWNLOADING_METADATA -> "Fetching metadata"
      TorrentStatus.State.DOWNLOADING -> "Downloading"
      TorrentStatus.State.FINISHED -> "Finished"
      TorrentStatus.State.SEEDING -> "Seeding"
      TorrentStatus.State.CHECKING_FILES -> "Checking files"
      TorrentStatus.State.CHECKING_RESUME_DATA -> "Checking"
      TorrentStatus.State.UNKNOWN -> "Idle"
    }

  // ---------------------------------------------------------------------------------------------
  // Alerts
  // ---------------------------------------------------------------------------------------------

  override fun types(): IntArray =
    intArrayOf(
      AlertType.ADD_TORRENT.swig(),
      AlertType.METADATA_RECEIVED.swig(),
      AlertType.METADATA_FAILED.swig(),
      AlertType.TORRENT_ERROR.swig(),
      AlertType.FILE_ERROR.swig(),
      AlertType.TORRENT_FINISHED.swig(),
    )

  override fun alert(alert: Alert<*>) {
    try {
      when (alert.type()) {
        AlertType.ADD_TORRENT -> {
          val alertHandle = (alert as? TorrentAlert<*>)?.handle() ?: return
          val sha1 = runCatching { alertHandle.infoHash() }.getOrNull() ?: return
          val hash = sha1.toHex()
          val live = runCatching { session.find(sha1) }.getOrNull()
          if (live != null && live.isValid) {
            handles[hash] = live
          }
        }
        AlertType.METADATA_RECEIVED -> {
          val handle = (alert as? MetadataReceivedAlert)?.handle()
          if (handle != null && handle.isValid) {
            val hash = handle.infoHash().toHex()
            val info = handle.torrentFile()
            if (info != null) {
              database.updateTorrentName(hash, info.name())
            }
          }
        }
        AlertType.METADATA_FAILED -> {
          val handle = (alert as? TorrentAlert<*>)?.handle()
          if (handle != null) errors[handle.infoHash().toHex()] = "Metadata download failed."
        }
        AlertType.TORRENT_ERROR,
        AlertType.FILE_ERROR -> {
          val handle = (alert as? TorrentAlert<*>)?.handle()
          if (handle != null) errors[handle.infoHash().toHex()] = alert.message()
        }
        else -> Unit
      }
    } catch (_: Throwable) {
      // Never let alert handling kill libtorrent's alert thread.
    }
    scope.launch { refresh(logStats = false) }
  }

  private companion object {
    const val TAG = "RivuletEngine"
    const val SESSION_KEY = "__session__"
    const val MAGNET_TIMEOUT_SECONDS = 45
  }
}

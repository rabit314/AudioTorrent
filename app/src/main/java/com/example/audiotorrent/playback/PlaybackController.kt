package com.example.audiotorrent.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.audiotorrent.data.BookCatalogMetadata
import com.example.audiotorrent.data.BookmarkEntry
import com.example.audiotorrent.data.TorrentDatabase
import com.example.audiotorrent.data.lyrics.TimedLine
import com.example.audiotorrent.data.lyrics.accepts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QueueTrack(
  val infoHash: String,
  val fileIndex: Int,
  val title: String,
  val subtitle: String,
  val coverUrl: String? = null,
)

data class PlaybackUi(
  val title: String = "",
  val subtitle: String = "",
  val coverUrl: String? = null,
  val infoHash: String? = null,
  val fileIndex: Int = -1,
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val hasMedia: Boolean = false,
  val isVoiceBoost: Boolean = false,
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val bufferedMs: Long = 0L,
  val speed: Float = 1.0f,
  val hasNext: Boolean = false,
  val hasPrevious: Boolean = false,
  val error: String? = null,
)

/**
 * App-scoped ExoPlayer with background MediaSessionService coordination,
 * per-track resume persistence, queue management, and speed control.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackController(
  private val context: Context,
  private val database: TorrentDatabase = TorrentDatabase(context),
) {

  val player: ExoPlayer =
    ExoPlayer.Builder(context.applicationContext)
      .setMediaSourceFactory(
        DefaultMediaSourceFactory(
          DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(120_000)
            .setUserAgent("AudioTorrent/0.3.0")
        )
      )
      .setSeekBackIncrementMs(15_000)
      .setSeekForwardIncrementMs(30_000)
      .build()
      .apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build(),
          true,
        )
        setHandleAudioBecomingNoisy(true)
      }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val catalogMetadata = BookCatalogMetadata(context.applicationContext)
  private val _state = MutableStateFlow(PlaybackUi())
  val state: StateFlow<PlaybackUi> = _state.asStateFlow()

  private val tickActive = MutableStateFlow(false)

  private var currentTitle = ""
  private var currentSubtitle = ""
  private var currentCoverUrl: String? = null
  private var currentInfoHash: String? = null
  private var currentFileIndex: Int = -1
  private var lastError: String? = null
  private var lastPauseTimeMs: Long = 0L

  private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
  private var isVoiceBoost: Boolean = false

  private var queue: List<QueueTrack> = emptyList()
  private var queueIndex: Int = -1
  val currentQueue = MutableStateFlow<List<QueueTrack>>(emptyList())
  val currentQueueIndex = MutableStateFlow<Int>(-1)
  val bookmarks = MutableStateFlow<List<BookmarkEntry>>(emptyList())
  var queueProvider: ((infoHash: String) -> List<QueueTrack>)? = null
    set(value) {
      field = value
      refreshRestoredMetadata()
      updateTickState()
    }
  var trackPreparer: (suspend (infoHash: String, fileIndex: Int) -> PreparedPlayback)? = null
  private var requestSequence = 0L
  private var requestOwner: Job? = null

  var analysisDataSource: (suspend (com.example.audiotorrent.data.lyrics.ReadAlongTrack) -> android.media.MediaDataSource)? = null
  val readAlong = com.example.audiotorrent.data.lyrics.ReadAlongRepository(
    context.applicationContext,
    com.example.audiotorrent.data.lyrics.ReadAlongStore(database),
    com.example.audiotorrent.data.lyrics.AudioWindowDecoder { track ->
      analysisDataSource?.invoke(track) ?: error("Audio analysis is not configured.")
    },
    library = com.example.audiotorrent.data.lyrics.GutenbergFirstCatalog(
      gutenberg = com.example.audiotorrent.data.lyrics.GutenbergClient(),
      fallback = com.example.audiotorrent.data.lyrics.ZLibraryClient(),
      libgen = com.example.audiotorrent.data.lyrics.LibGenClient(),
      annasArchive = com.example.audiotorrent.data.lyrics.AnnasArchiveClient(
        secretKey = { com.example.audiotorrent.data.lyrics.ReaderPreferences.readAnnasSecretKey(context) },
      ),
      welib = com.example.audiotorrent.data.lyrics.WeLibClient(),
    ),
  )

  init {
    // Prime state with latest played audiobook from history
    scope.launch {
      val latest = withContext(Dispatchers.IO) { database.getLatestPlayedBook() }
      if (latest != null && player.mediaItemCount == 0 && currentInfoHash == null) {
        currentTitle = latest.bookTitle
        currentCoverUrl = latest.coverUrl
        currentInfoHash = latest.infoHash
        currentFileIndex = latest.fileIndex
        val savedSpeed = catalogMetadata.speed(latest.infoHash)
        if (savedSpeed != 1.0f) player.playbackParameters = PlaybackParameters(savedSpeed)
        readAlong.activate(com.example.audiotorrent.data.lyrics.ReadAlongTrack(latest.infoHash, latest.fileIndex), latest.positionMs, latest.durationMs)
        _state.value =
          PlaybackUi(
            title = latest.bookTitle,
            subtitle = "Resume chapter",
            coverUrl = latest.coverUrl,
            infoHash = latest.infoHash,
            fileIndex = latest.fileIndex,
            hasMedia = true,
            positionMs = latest.positionMs,
            durationMs = latest.durationMs,
            speed = savedSpeed,
          )
        refreshRestoredMetadata()
        refreshBookmarks()
        updateTickState()
      }
    }
    player.addListener(
      object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
          if (!isPlaying) saveCurrentPosition()
          publish()
          syncReadAlongSnapshot()
          updateTickState()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
          val artUri = mediaMetadata.artworkUri?.toString()
          if (currentCoverUrl.isNullOrBlank() && !artUri.isNullOrBlank()) {
            currentCoverUrl = artUri
            publish()
          }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
          if (playbackState == Player.STATE_ENDED) {
            saveCurrentPosition()
            if (hasNext()) {
              playNext()
            }
          }
          publish()
          syncReadAlongSnapshot()
          updateTickState()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
          publish()
          syncReadAlongSnapshot()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
          if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            readAlong.playbackDiscontinuity()
          }
          publish()
          syncReadAlongSnapshot()
        }

        override fun onPlayerError(error: PlaybackException) {
          Log.e(TAG, "Playback failed: ${error.errorCodeName}", error)
          lastError = describePlaybackError(error)
          publish()
          updateTickState()
        }
      }
    )

    // Event-gated periodic UI update & position saver: suspends at 0% CPU when paused and metadata is resolved.
    scope.launch {
      tickActive.collectLatest { active ->
        if (!active) return@collectLatest
        var ticks = 0
        while (isActive && tickActive.value) {
          refreshRestoredMetadata()
          publish()
          syncReadAlongSnapshot()
          delay(350)
          ticks++
          if (ticks % 10 == 0 && player.isPlaying) {
            saveCurrentPosition()
          }
          updateTickState()
        }
      }
    }
  }

  private fun updateTickState() {
    val awaitingMetadata = player.mediaItemCount == 0 && currentInfoHash != null && queue.isEmpty()
    val playingOrBuffering = player.isPlaying || player.playbackState == Player.STATE_BUFFERING
    tickActive.value = playingOrBuffering || awaitingMetadata
  }

  private fun syncReadAlongSnapshot() {
    val hash = currentInfoHash ?: return
    if (currentFileIndex < 0) return
    val prepared = player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE
    val snapshot = _state.value
    val speed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1.0f
    readAlong.playback(
      position = if (prepared) player.currentPosition else snapshot.positionMs,
      duration = if (prepared) player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L else snapshot.durationMs,
      track = com.example.audiotorrent.data.lyrics.ReadAlongTrack(hash, currentFileIndex),
      prepared = prepared,
      isPlaying = player.isPlaying,
      speed = speed,
    )
  }

  fun setQueue(tracks: List<QueueTrack>, startIndex: Int) {
    queue = tracks
    queueIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
    currentQueue.value = tracks
    currentQueueIndex.value = queueIndex
    publish()
    updateTickState()
  }

  private var activePlaybackJob: Job? = null

  fun jumpToQueueIndex(index: Int) {
    if (index < 0 || index >= queue.size) return
    val track = queue[index]
    activePlaybackJob?.cancel()
    activePlaybackJob = scope.launch {
      requestPlayback(track.infoHash, track.fileIndex)
    }
  }

  fun refreshBookmarks() {
    val hash = currentInfoHash ?: return
    scope.launch(Dispatchers.IO) {
      val list = database.getBookmarks(hash)
      bookmarks.value = list
    }
  }

  fun addBookmark(note: String? = null) {
    val hash = currentInfoHash ?: return
    val fileIdx = currentFileIndex
    val title = currentTitle.ifBlank { "Chapter ${fileIdx + 1}" }
    val pos = player.currentPosition
    scope.launch(Dispatchers.IO) {
      database.addBookmark(
        BookmarkEntry(
          infoHash = hash,
          fileIndex = fileIdx,
          chapterTitle = title,
          positionMs = pos,
          note = note?.takeIf { it.isNotBlank() },
        )
      )
      refreshBookmarks()
    }
  }

  fun deleteBookmark(id: Long) {
    scope.launch(Dispatchers.IO) {
      database.deleteBookmark(id)
      refreshBookmarks()
    }
  }

  fun hasNext(): Boolean = queueIndex >= 0 && queueIndex < queue.size - 1

  fun hasPrevious(): Boolean = queueIndex > 0

  fun playNext() {
    if (!hasNext()) return
    val nextIdx = queueIndex + 1
    val track = queue[nextIdx]
    activePlaybackJob?.cancel()
    activePlaybackJob = scope.launch {
      requestPlayback(track.infoHash, track.fileIndex)
    }
  }

  fun playPrevious() {
    if (player.currentPosition > 3000L) {
      seekTo(0L)
      return
    }
    if (!hasPrevious()) return
    val prevIdx = queueIndex - 1
    val track = queue[prevIdx]
    activePlaybackJob?.cancel()
    activePlaybackJob = scope.launch {
      requestPlayback(track.infoHash, track.fileIndex)
    }
  }

  /**
   * Centralized, safe playback request method.
   * Cancels in-flight preparation jobs, synchronizes queue and indices,
   * prepares the audio stream, and begins playback at [startPositionMs] (or saved position).
   */
  suspend fun requestPlayback(
    infoHash: String,
    fileIndex: Int,
    startPositionMs: Long? = null,
    queueOverride: List<QueueTrack>? = null,
    autoplay: Boolean = true,
  ): Result<Unit> = withContext(Dispatchers.Main) {
    val owner = currentCoroutineContext().job
    requestOwner?.takeIf { it !== owner }?.cancel()
    requestOwner = owner
    val request = ++requestSequence
    var prepared: PreparedPlayback? = null
    var handedOff = false
    try {
      val tracks = queueOverride ?: queueProvider?.invoke(infoHash)?.takeIf { it.isNotEmpty() }
        ?: queue.takeIf { it.firstOrNull()?.infoHash == infoHash }.orEmpty()
      val targetIdx = tracks.indexOfFirst { it.infoHash == infoHash && it.fileIndex == fileIndex }
      val saved = withContext(Dispatchers.IO) { database.getTorrent(infoHash) }
      val track = tracks.getOrNull(targetIdx)
      val trackTitle = track?.title ?: saved?.name ?: "Chapter ${fileIndex + 1}"
      val bookTitle = track?.subtitle ?: saved?.name ?: "Audiobook"
      val coverUrl = track?.coverUrl ?: saved?.coverUrl
      val preparer = trackPreparer ?: error("Playback stream preparer is not configured.")
      prepared = preparer(infoHash, fileIndex)
      currentCoroutineContext().ensureActive()
      if (request != requestSequence) throw CancellationException("Superseded playback request")
      if (tracks.isNotEmpty()) setQueue(tracks, targetIdx.coerceAtLeast(0))
      // No suspension between accepting the preparation and handing it to ExoPlayer.
      requestOwner = null
      play(prepared.url, trackTitle, bookTitle, infoHash, fileIndex, coverUrl, startPositionMs, autoplay)
      handedOff = true
      Result.success(Unit)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      if (request == requestSequence) {
        Log.e(TAG, "requestPlayback failed for $infoHash / $fileIndex", error)
        lastError = error.message ?: "Failed to start playback."
        publish()
      }
      Result.failure(error)
    } finally {
      if (!handedOff) withContext(NonCancellable) { prepared?.abandon?.invoke() }
      if (requestOwner === owner) requestOwner = null
    }
  }

  private fun refreshRestoredMetadata() {
    if (player.mediaItemCount != 0) return
    val hash = currentInfoHash ?: return
    val tracks = queueProvider?.invoke(hash).orEmpty()
    val index = tracks.indexOfFirst { it.fileIndex == currentFileIndex }
    val track = tracks.getOrNull(index) ?: return
    if (currentTitle != track.title || currentSubtitle != track.subtitle || queue.isEmpty()) {
      currentTitle = track.title
      currentSubtitle = track.subtitle
      currentCoverUrl = track.coverUrl ?: currentCoverUrl
      queue = tracks; queueIndex = index
      currentQueue.value = tracks; currentQueueIndex.value = index
      publish()
      updateTickState()
    }
  }

  fun play(
    url: String,
    title: String,
    subtitle: String,
    infoHash: String? = null,
    fileIndex: Int = -1,
    coverUrl: String? = null,
    overridePositionMs: Long? = null,
    autoplay: Boolean = true,
  ) {
    if (requestOwner != null) { requestOwner?.cancel(); requestOwner = null; requestSequence++ }
    // Save previous position before switching
    saveCurrentPosition()

    val previousInfoHash = currentInfoHash
    val resolvedCover = coverUrl ?: infoHash?.let { database.getTorrent(it)?.coverUrl }

    currentTitle = title
    currentSubtitle = subtitle
    currentCoverUrl = resolvedCover
    currentInfoHash = infoHash
    currentFileIndex = fileIndex
    lastError = null

    if (infoHash != null && infoHash != previousInfoHash) {
      val bookSpeed = catalogMetadata.speed(infoHash)
      if (player.playbackParameters.speed != bookSpeed) {
        player.playbackParameters = PlaybackParameters(bookSpeed)
      }
    }

    // Start background MediaSessionService
    runCatching {
      val serviceIntent = Intent(context, AudioTorrentMediaService::class.java)
      ContextCompat.startForegroundService(context, serviceIntent)
    }

    val metaBuilder = MediaMetadata.Builder().setTitle(title).setArtist(subtitle)
    if (!resolvedCover.isNullOrBlank()) {
      runCatching { metaBuilder.setArtworkUri(Uri.parse(resolvedCover)) }
    }

    val item =
      MediaItem.Builder()
        .setUri(url)
        .setMediaMetadata(metaBuilder.build())
        .build()

    player.setMediaItem(item)

    // Check saved resume position & duration
    val savedPos =
      if (infoHash != null && fileIndex >= 0) {
        database.getPlaybackPosition(infoHash, fileIndex)
      } else {
        0L
      }
    val savedDuration =
      if (infoHash != null && fileIndex >= 0) {
        database.getPlaybackDuration(infoHash, fileIndex)
      } else {
        0L
      }
    // If overridePositionMs is provided, use it directly (e.g. for bookmark seeking)
    val effectivePos =
      overridePositionMs ?: if (savedDuration > 0L && savedPos >= savedDuration - 3000L) {
        0L
      } else {
        savedPos
      }

    // Auto-populate or sync queue if same book
    if (infoHash != null) {
      if (queue.isEmpty() || queue.firstOrNull()?.infoHash != infoHash) {
        val provider = queueProvider
        if (provider != null) {
          val tracks = provider(infoHash)
          if (tracks.isNotEmpty()) {
            val idx = tracks.indexOfFirst { it.fileIndex == fileIndex }.coerceAtLeast(0)
            setQueue(tracks, idx)
          }
        }
      } else {
        val idx = queue.indexOfFirst { it.fileIndex == fileIndex }
        if (idx >= 0 && idx != queueIndex) {
          queueIndex = idx
          currentQueueIndex.value = idx
        }
      }
    }

    refreshBookmarks()
    if (infoHash != null && fileIndex >= 0) {
      readAlong.activate(com.example.audiotorrent.data.lyrics.ReadAlongTrack(infoHash, fileIndex), effectivePos, savedDuration)
    }

    player.prepare()
    if (effectivePos > 0L) {
      player.seekTo(effectivePos)
    }
    if (autoplay) {
      player.play()
    }
    publish()
    syncReadAlongSnapshot()
    updateTickState()
  }

  fun togglePlayPause() {
    when {
      player.playbackState == Player.STATE_ENDED -> {
        player.seekTo(0L)
        player.play()
      }
      player.isPlaying -> {
        lastPauseTimeMs = System.currentTimeMillis()
        player.pause()
        saveCurrentPosition()
      }
      player.playbackState == Player.STATE_IDLE && player.mediaItemCount == 0 -> {
        // Resume from primed history item if available
        val hash = currentInfoHash
        val index = currentFileIndex
        if (hash != null && index >= 0 && trackPreparer != null) {
          activePlaybackJob?.cancel()
          activePlaybackJob = scope.launch { requestPlayback(hash, index) }
        }
      }
      else -> {
        // Smart Context Auto-Rewind: If paused for > 60s, rewind 5-10s
        if (lastPauseTimeMs > 0L) {
          val pauseDurationMs = System.currentTimeMillis() - lastPauseTimeMs
          if (pauseDurationMs > 60_000L) {
            val rewindSec = ((pauseDurationMs / 60_000L) * 3L).coerceIn(5L, 10L)
            seekBy(-rewindSec * 1000L)
          }
          lastPauseTimeMs = 0L
        }
        player.play()
      }
    }
  }

  fun pause() {
    if (player.isPlaying) {
      lastPauseTimeMs = System.currentTimeMillis()
      player.pause()
      saveCurrentPosition()
    }
  }

  fun resume() {
    if (!player.isPlaying) {
      if (player.playbackState == Player.STATE_ENDED) {
        player.seekTo(0L)
      }
      if (lastPauseTimeMs > 0L) {
        val pauseDurationMs = System.currentTimeMillis() - lastPauseTimeMs
        if (pauseDurationMs > 60_000L) {
          val rewindSec = ((pauseDurationMs / 60_000L) * 3L).coerceIn(5L, 10L)
          seekBy(-rewindSec * 1000L)
        }
        lastPauseTimeMs = 0L
      }
      player.play()
    }
  }

  /** Prepares a cold-restored track without starting audible playback so audio analysis can run paused. */
  fun prepareCurrentTrackPaused() {
    val hash = currentInfoHash ?: return
    val index = currentFileIndex
    if (index < 0 || trackPreparer == null) return
    if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) {
      if (activePlaybackJob?.isActive == true) return
      activePlaybackJob = scope.launch { requestPlayback(hash, index, autoplay = false) }
    } else {
      syncReadAlongSnapshot()
    }
  }

  fun seekTo(positionMs: Long) {
    readAlong.playbackDiscontinuity()
    val duration = player.duration
    val max = if (duration > 0 && duration != C.TIME_UNSET) duration else positionMs
    player.seekTo(positionMs.coerceIn(0L, max))
    publish()
    syncReadAlongSnapshot()
  }

  fun seekBy(offsetMs: Long) {
    readAlong.playbackDiscontinuity()
    val cur = player.currentPosition
    val duration = player.duration
    val max = if (duration > 0 && duration != C.TIME_UNSET) duration else Long.MAX_VALUE
    val target = (cur + offsetMs).coerceIn(0L, max)
    player.seekTo(target)
    publish()
    syncReadAlongSnapshot()
  }

  fun toggleVoiceBoost(): Boolean {
    isVoiceBoost = !isVoiceBoost
    applyVoiceBoost()
    publish()
    return isVoiceBoost
  }

  private fun applyVoiceBoost() {
    try {
      val sessionId = player.audioSessionId
      if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
        if (loudnessEnhancer == null) {
          loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(sessionId)
        }
        loudnessEnhancer?.setTargetGain(if (isVoiceBoost) 600 else 0) // +6 dB boost
        loudnessEnhancer?.enabled = isVoiceBoost
      }
    } catch (t: Throwable) {
      Log.w(TAG, "LoudnessEnhancer not available", t)
    }
  }

  fun setSpeed(speed: Float) {
    val clamped = speed.coerceIn(0.5f, 3.0f)
    player.playbackParameters = PlaybackParameters(clamped)
    currentInfoHash?.let { catalogMetadata.saveSpeed(it, clamped) }
    publish()
  }

  fun stop() {
    requestOwner?.cancel(); requestOwner = null; requestSequence++
    saveCurrentPosition()
    player.stop()
    player.clearMediaItems()
    currentTitle = ""
    currentSubtitle = ""
    currentInfoHash = null
    currentFileIndex = -1
    lastError = null
    publish()
    updateTickState()
  }

  private fun saveCurrentPosition() {
    val hash = currentInfoHash ?: return
    val index = currentFileIndex
    if (index < 0) return
    val pos = player.currentPosition
    val duration = player.duration
    val durationMs = if (duration > 0 && duration != C.TIME_UNSET) duration else 0L
    if (pos > 0L) {
      database.savePlaybackPosition(
        hash,
        index,
        pos,
        durationMs,
      )
      val fileName = currentSubtitle.ifBlank { "Chapter ${index + 1}" }
      database.recordPlaybackHistory(
        hash,
        index,
        fileName,
        pos,
        durationMs,
      )
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Synchronized Text & Lyrics Controls
  // ---------------------------------------------------------------------------------------------

  fun seekToLine(line: TimedLine) {
    if (line.isTimed) seekTo(com.example.audiotorrent.data.lyrics.ReadAlongTimeline.seekPosition(line, readAlong.state.value.offsetMs))
  }

  fun syncReadAlongNow(target: com.example.audiotorrent.data.lyrics.SyncTarget) {
    if (!readAlong.state.value.accepts(target)) return
    val duration = player.duration
    if (player.currentMediaItem == null || player.playbackState == Player.STATE_IDLE ||
      currentInfoHash != target.track.infoHash || currentFileIndex != target.track.fileIndex || duration <= 0 || duration == C.TIME_UNSET) {
      readAlong.correctionUnavailable("Start or prepare the audio before correcting sync.")
      return
    }
    readAlong.syncNow(com.example.audiotorrent.data.lyrics.SyncReference(target, player.currentPosition.coerceAtLeast(0), duration))
  }

  /** Releases an owned controller; app-scoped instances live for the application's lifetime. */
  internal fun close() {
    requestOwner?.cancel(); requestOwner = null; requestSequence++
    scope.cancel()
    readAlong.close()
    loudnessEnhancer?.release()
    player.release()
  }

  private fun describePlaybackError(error: PlaybackException): String {
    val causes = generateSequence(error.cause) { it.cause }.toList()
    val detail = causes.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
    val timedOut =
      error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        causes.any {
          it is java.net.SocketTimeoutException ||
            it.message?.contains("timeout", ignoreCase = true) == true
        }

    return when {
      timedOut ->
        "Timed out waiting for torrent data. The swarm may be slow or have no seeds."
      error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
        "Couldn't connect to the local stream server."
      detail != null -> "${error.message ?: error.errorCodeName}: $detail"
      else -> error.message ?: error.errorCodeName
    }
  }

  private fun publish() {
    val duration = player.duration
    val restored = _state.value.takeIf { player.mediaItemCount == 0 && it.infoHash == currentInfoHash && it.fileIndex == currentFileIndex }
    _state.value =
      PlaybackUi(
        title = currentTitle,
        subtitle = currentSubtitle,
        coverUrl = currentCoverUrl,
        infoHash = currentInfoHash,
        fileIndex = currentFileIndex,
        isPlaying = player.isPlaying,
        isBuffering = player.playbackState == Player.STATE_BUFFERING,
        hasMedia = player.mediaItemCount > 0 || (currentInfoHash != null && currentFileIndex >= 0),
        isVoiceBoost = isVoiceBoost,
        positionMs = restored?.positionMs ?: player.currentPosition.coerceAtLeast(0L),
        durationMs = restored?.durationMs ?: if (duration > 0L && duration != C.TIME_UNSET) duration else 0L,
        bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
        speed = player.playbackParameters.speed,
        hasNext = hasNext(),
        hasPrevious = hasPrevious(),
        error = lastError,
      )
  }

  private companion object {
    const val TAG = "AudioTorrentPlayback"
  }
}

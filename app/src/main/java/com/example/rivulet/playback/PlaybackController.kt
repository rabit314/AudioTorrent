package com.example.rivulet.playback

import android.content.Context
import android.content.Intent
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
import com.example.rivulet.data.AudioFile
import com.example.rivulet.data.BookmarkEntry
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.AudioBookAligner
import com.example.rivulet.data.lyrics.EpubChapter
import com.example.rivulet.data.lyrics.EpubExtractor
import com.example.rivulet.data.lyrics.LrcParser
import com.example.rivulet.data.lyrics.PageTextManager
import com.example.rivulet.data.lyrics.PacedTimingEngine
import com.example.rivulet.data.lyrics.TextSyncSource
import com.example.rivulet.data.lyrics.TimedLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
  val isSkipSilence: Boolean = false,
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val bufferedMs: Long = 0L,
  val speed: Float = 1.0f,
  val hasNext: Boolean = false,
  val hasPrevious: Boolean = false,
  val sleepTimerRemainingSec: Int? = null,
  val isSleepTimerEndOfTrack: Boolean = false,
  val error: String? = null,
)

/**
 * App-scoped ExoPlayer with background MediaSessionService coordination,
 * per-track resume persistence, queue management, speed control and sleep timer.
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
            .setUserAgent("AudioTorrent/0.2.0")
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
  private val _state = MutableStateFlow(PlaybackUi())
  val state: StateFlow<PlaybackUi> = _state.asStateFlow()

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
  var trackPreparer: (suspend (infoHash: String, fileIndex: Int) -> String)? = null

  private var sleepTimerJob: Job? = null
  private var sleepTimerRemainingSec: Int? = null
  private var sleepTimerEndOfTrack: Boolean = false

  var analysisDataSource: (suspend (com.example.rivulet.data.lyrics.ReadAlongTrack) -> android.media.MediaDataSource)? = null
  val readAlong = com.example.rivulet.data.lyrics.ReadAlongRepository(
    context.applicationContext,
    com.example.rivulet.data.lyrics.ReadAlongStore(database),
    com.example.rivulet.data.lyrics.AudioWindowDecoder { track ->
      analysisDataSource?.invoke(track) ?: error("Audio analysis is not configured.")
    },
  )

  init {
    // Prime state with latest played audiobook from history
    scope.launch {
      val latest = database.getLatestPlayedBook()
      if (latest != null && player.mediaItemCount == 0) {
        currentTitle = latest.bookTitle
        currentCoverUrl = latest.coverUrl
        currentInfoHash = latest.infoHash
        currentFileIndex = latest.fileIndex
        readAlong.activate(com.example.rivulet.data.lyrics.ReadAlongTrack(latest.infoHash, latest.fileIndex))
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
          )
      }
    }
    player.addListener(
      object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
          if (!isPlaying) saveCurrentPosition()
          publish()
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
            if (sleepTimerEndOfTrack) {
              sleepTimerEndOfTrack = false
              sleepTimerRemainingSec = null
              player.pause()
              publish()
            } else if (hasNext()) {
              playNext()
            }
          }
          publish()
        }

        override fun onPlayerError(error: PlaybackException) {
          Log.e(TAG, "Playback failed: ${error.errorCodeName}", error)
          lastError = describePlaybackError(error)
          publish()
        }
      }
    )

    // Periodic UI update and position saver
    scope.launch {
      var ticks = 0
      while (isActive) {
        publish()
        readAlong.playback(player.currentPosition, player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L)
        delay(350)
        ticks++
        if (ticks % 10 == 0 && player.isPlaying) {
          saveCurrentPosition()
        }
      }
    }
  }

  fun setQueue(tracks: List<QueueTrack>, startIndex: Int) {
    queue = tracks
    queueIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
    currentQueue.value = tracks
    currentQueueIndex.value = queueIndex
    publish()
  }

  private var activePlaybackJob: Job? = null

  fun jumpToQueueIndex(index: Int) {
    if (index < 0 || index >= queue.size) return
    queueIndex = index
    currentQueueIndex.value = index
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
    queueIndex = nextIdx
    currentQueueIndex.value = nextIdx
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
    queueIndex = prevIdx
    currentQueueIndex.value = prevIdx
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
    try {
      lastError = null

      // Resolve and synchronize queue
      val tracks = when {
        queueOverride != null -> queueOverride
        queue.isNotEmpty() && queue.firstOrNull()?.infoHash == infoHash -> queue
        else -> queueProvider?.invoke(infoHash) ?: emptyList()
      }

      val targetIdx = tracks.indexOfFirst { it.infoHash == infoHash && it.fileIndex == fileIndex }
      if (tracks.isNotEmpty()) {
        queue = tracks
        queueIndex = if (targetIdx >= 0) targetIdx else 0
        currentQueue.value = tracks
        currentQueueIndex.value = queueIndex
      } else if (targetIdx >= 0) {
        queueIndex = targetIdx
        currentQueueIndex.value = targetIdx
      }

      val trackTitle = tracks.getOrNull(targetIdx)?.title
        ?: database.getTorrent(infoHash)?.name
        ?: "Chapter ${fileIndex + 1}"
      val bookTitle = tracks.getOrNull(targetIdx)?.subtitle
        ?: database.getTorrent(infoHash)?.name
        ?: "Audiobook"
      val coverUrl = tracks.getOrNull(targetIdx)?.coverUrl
        ?: database.getTorrent(infoHash)?.coverUrl

      // Prepare stream
      val preparer = trackPreparer ?: error("Playback stream preparer is not configured.")
      val streamUrl = preparer(infoHash, fileIndex)

      play(
        url = streamUrl,
        title = trackTitle,
        subtitle = bookTitle,
        infoHash = infoHash,
        fileIndex = fileIndex,
        coverUrl = coverUrl,
        overridePositionMs = startPositionMs,
        autoplay = autoplay,
      )

      Result.success(Unit)
    } catch (t: Throwable) {
      Log.e(TAG, "requestPlayback failed for $infoHash / $fileIndex", t)
      lastError = t.message ?: "Failed to start playback."
      publish()
      Result.failure(t)
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
    // Save previous position before switching
    saveCurrentPosition()

    val resolvedCover = coverUrl ?: infoHash?.let { database.getTorrent(it)?.coverUrl }

    currentTitle = title
    currentSubtitle = subtitle
    currentCoverUrl = resolvedCover
    currentInfoHash = infoHash
    currentFileIndex = fileIndex
    lastError = null

    // Start background MediaSessionService
    runCatching {
      val serviceIntent = Intent(context, RivuletMediaService::class.java)
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
      readAlong.activate(com.example.rivulet.data.lyrics.ReadAlongTrack(infoHash, fileIndex))
    }

    player.prepare()
    if (effectivePos > 0L) {
      player.seekTo(effectivePos)
    }
    if (autoplay) {
      player.play()
    }
    publish()
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
        val preparer = trackPreparer
        if (hash != null && index >= 0 && preparer != null) {
          scope.launch {
            runCatching {
              val url = preparer(hash, index)
              play(url, currentTitle, currentSubtitle, hash, index, currentCoverUrl)
            }
          }
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

  fun seekTo(positionMs: Long) {
    val duration = player.duration
    val max = if (duration > 0 && duration != C.TIME_UNSET) duration else positionMs
    player.seekTo(positionMs.coerceIn(0L, max))
    publish()
  }

  fun seekBy(offsetMs: Long) {
    val cur = player.currentPosition
    val duration = player.duration
    val max = if (duration > 0 && duration != C.TIME_UNSET) duration else Long.MAX_VALUE
    val target = (cur + offsetMs).coerceIn(0L, max)
    player.seekTo(target)
    publish()
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

  fun toggleSkipSilence(): Boolean {
    val newMode = !player.skipSilenceEnabled
    player.skipSilenceEnabled = newMode
    publish()
    return newMode
  }

  fun setSpeed(speed: Float) {
    val clamped = speed.coerceIn(0.5f, 3.0f)
    player.playbackParameters = PlaybackParameters(clamped)
    publish()
  }

  fun startSleepTimer(minutes: Int) {
    cancelSleepTimer()
    if (minutes <= 0) return
    sleepTimerRemainingSec = minutes * 60
    sleepTimerEndOfTrack = false
    publish()

    sleepTimerJob =
      scope.launch {
        while (isActive && (sleepTimerRemainingSec ?: 0) > 0) {
          delay(1000)
          val remaining = (sleepTimerRemainingSec ?: 1) - 1
          sleepTimerRemainingSec = remaining
          // Smooth 30s gradual volume fade-out
          if (remaining in 1..30) {
            val fadeVol = (remaining / 30.0f).coerceIn(0.0f, 1.0f)
            player.volume = fadeVol
          }
          if (remaining <= 0) {
            player.pause()
            player.volume = 1.0f
            saveCurrentPosition()
            sleepTimerRemainingSec = null
            publish()
            break
          }
          publish()
        }
      }
  }

  fun startSleepTimerEndOfTrack() {
    cancelSleepTimer()
    sleepTimerEndOfTrack = true
    sleepTimerRemainingSec = null
    publish()
  }

  fun cancelSleepTimer() {
    sleepTimerJob?.cancel()
    sleepTimerJob = null
    sleepTimerRemainingSec = null
    sleepTimerEndOfTrack = false
    player.volume = 1.0f
    publish()
  }

  fun stop() {
    saveCurrentPosition()
    player.stop()
    player.clearMediaItems()
    currentTitle = ""
    currentSubtitle = ""
    currentInfoHash = null
    currentFileIndex = -1
    lastError = null
    cancelSleepTimer()
    publish()
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
    if (line.isTimed) seekTo(com.example.rivulet.data.lyrics.ReadAlongTimeline.seekPosition(line, readAlong.state.value.offsetMs))
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
        isSkipSilence = player.skipSilenceEnabled,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = if (duration > 0L && duration != C.TIME_UNSET) duration else 0L,
        bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
        speed = player.playbackParameters.speed,
        hasNext = hasNext(),
        hasPrevious = hasPrevious(),
        sleepTimerRemainingSec = sleepTimerRemainingSec,
        isSleepTimerEndOfTrack = sleepTimerEndOfTrack,
        error = lastError,
      )
  }

  private companion object {
    const val TAG = "RivuletPlayback"
  }
}

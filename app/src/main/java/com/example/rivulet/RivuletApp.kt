package com.example.rivulet

import android.app.Application
import android.content.Context
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.audiobookbay.AudioBookBayClient
import com.example.rivulet.playback.PlaybackController
import com.example.rivulet.stream.StreamServer
import com.example.rivulet.torrent.TorrentEngine
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer(context: Context) {
  val database = TorrentDatabase(context)
  val engine = TorrentEngine(context, database)
  val streamServer = StreamServer(engine)
  val playback = PlaybackController(context, database)
  val audiobookbay = AudioBookBayClient()

  /** Set when a torrent is added from an external intent; consumed by the library screen. */
  val pendingTorrent = MutableStateFlow<String?>(null)

  init {
    streamServer.start()
    playback.trackPreparer = { infoHash, fileIndex ->
      val stream = engine.prepareStream(infoHash, fileIndex)
      stream.awaitStart()
      streamServer.streamUrl(infoHash, fileIndex)
    }
    playback.queueProvider = { infoHash ->
      val torrent = engine.torrents.value.firstOrNull { it.infoHash == infoHash }
      torrent?.audioFiles?.map {
        com.example.rivulet.playback.QueueTrack(
          infoHash = torrent.infoHash,
          fileIndex = it.index,
          title = it.name,
          subtitle = torrent.name,
          coverUrl = torrent.coverUrl,
        )
      } ?: emptyList()
    }
  }
}

class RivuletApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
  }
}

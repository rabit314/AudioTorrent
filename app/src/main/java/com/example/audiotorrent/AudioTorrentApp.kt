package com.example.audiotorrent

import android.app.Application
import android.content.Context
import com.example.audiotorrent.data.TorrentDatabase
import com.example.audiotorrent.data.audiobookbay.AudioBookBayClient
import com.example.audiotorrent.playback.PlaybackController
import com.example.audiotorrent.playback.PreparedPlayback
import com.example.audiotorrent.stream.StreamServer
import com.example.audiotorrent.torrent.TorrentEngine
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer(context: Context) {
  val database = TorrentDatabase(context)
  val engine = TorrentEngine(context, database)
  val streamServer = StreamServer(engine)
  val playback = PlaybackController(context, database)
  val audiobookbay = AudioBookBayClient()
  val librivox = com.example.audiotorrent.data.catalog.LibriVoxClient()
  val catalog = com.example.audiotorrent.data.catalog.AudiobookCatalog(audiobookbay, librivox)
  val catalogSearch = com.example.audiotorrent.data.catalog.AudioCatalogSearch(loader = catalog::page)

  suspend fun addAudiobook(detail: com.example.audiotorrent.data.audiobookbay.AudioBookDetail): Result<String> = try {
    when (detail.source) {
      com.example.audiotorrent.data.audiobookbay.AudioBookSource.AUDIOBOOKBAY -> engine.addMagnet(detail.magnetUri, detail.coverUrl, detail.title)
      com.example.audiotorrent.data.audiobookbay.AudioBookSource.LIBRIVOX -> engine.addTorrentFile(librivox.torrent(detail), detail.title, detail.coverUrl, detail.catalogBook)
    }
  } catch (error: kotlinx.coroutines.CancellationException) { throw error }
    catch (error: Exception) { Result.failure(error) }

  /** Set when a torrent is added from an external intent; consumed by the library screen. */
  val pendingTorrent = MutableStateFlow<String?>(null)

  init {
    playback.analysisDataSource = { track -> engine.analysisDataSource(track.infoHash, track.fileIndex) }
    streamServer.start()
    playback.trackPreparer = { infoHash, fileIndex ->
      val stream = engine.prepareStream(infoHash, fileIndex)
      // The HTTP reader waits for verified ranges. Open Player/Text while audio buffers.
      PreparedPlayback(streamServer.streamUrl(infoHash, fileIndex)) { engine.abandonPreparation(stream) }
    }
    playback.queueProvider = { infoHash ->
      val torrent = engine.torrents.value.firstOrNull { it.infoHash == infoHash }
      torrent?.audioFiles?.map {
        val titleText = it.chapterTitle ?: if (it.directory.isNotBlank()) "${it.directory} - ${it.name}" else it.name
        com.example.audiotorrent.playback.QueueTrack(
          infoHash = torrent.infoHash,
          fileIndex = it.index,
          title = titleText,
          subtitle = torrent.name,
          coverUrl = torrent.coverUrl,
        )
      } ?: emptyList()
    }
  }
}

class AudioTorrentApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
  }
}

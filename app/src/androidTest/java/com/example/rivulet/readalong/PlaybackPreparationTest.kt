package com.example.rivulet.readalong

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PlaybackPreparationTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private suspend fun controller(db: TorrentDatabase) = withContext(Dispatchers.Main) {
    PlaybackController(object : ContextWrapper(context) { override fun startForegroundService(service: Intent): ComponentName? = null }, db)
  }

  @Test fun cancelledAndLatePreparationCannotChangePreviousTrackOrQueue() = runBlocking {
    val name = "prepare-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name)
    val c = controller(db); val server = StalledAudio()
    val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); var abandoned = 0
    val first = QueueTrack("a", 0, "First", "Book A")
    try {
      withContext(Dispatchers.Main) {
        c.setQueue(listOf(first), 0)
        c.play(server.url, first.title, first.subtitle, first.infoHash, 0, autoplay = false)
        c.trackPreparer = { _, _ ->
          entered.complete(Unit)
          // Emulates an uncooperative backend returning after cancellation.
          withContext(NonCancellable) { gate.await() }
          PreparedPlayback(server.url) { abandoned++ }
        }
      }
      val pending = launch(Dispatchers.Main) { c.requestPlayback("b", 1, queueOverride = listOf(QueueTrack("b", 1, "Second", "Book B"))) }
      entered.await(); pending.cancel(); gate.complete(Unit); pending.join()
      assertTrue(pending.isCancelled)
      withContext(Dispatchers.Main) {
        assertEquals("a", c.state.value.infoHash)
        assertEquals(listOf(first), c.currentQueue.value)
        assertNull(c.state.value.error)
        assertFalse(c.player.playWhenReady)
      }
      assertEquals(1, abandoned)
    } finally { withContext(Dispatchers.Main) { c.close() }; server.close(); db.close(); context.deleteDatabase(name) }
  }

  @Test fun newestRequestWinsAndPlayerCanBufferWithoutWaitingForAudioBytes() = runBlocking {
    val name = "latest-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name)
    val c = controller(db); val server = StalledAudio()
    val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); var abandoned = 0
    try {
      withContext(Dispatchers.Main) {
        c.trackPreparer = { hash, _ ->
          if (hash == "old") { entered.complete(Unit); withContext(NonCancellable) { gate.await() } }
          PreparedPlayback(server.url) { abandoned++ }
        }
      }
      val old = launch(Dispatchers.Main) { c.requestPlayback("old", 0) }
      entered.await()
      withTimeout(5000) { withContext(Dispatchers.Main) {
        assertTrue(c.requestPlayback("new", 4, queueOverride = listOf(QueueTrack("new", 4, "The Golden Spruce", "John Vaillant"))).isSuccess)
      } }
      gate.complete(Unit); old.join()
      withTimeout(5000) { while (!withContext(Dispatchers.Main) { c.state.value.isBuffering }) delay(20) }
      assertTrue(old.isCancelled); assertEquals(1, abandoned)
      withContext(Dispatchers.Main) {
        assertEquals("new", c.state.value.infoHash); assertEquals(4, c.readAlong.state.value.track!!.fileIndex)
        assertEquals("The Golden Spruce", c.state.value.title); assertNull(c.state.value.error)
      }
    } finally { gate.complete(Unit); withContext(Dispatchers.Main) { c.close() }; server.close(); db.close(); context.deleteDatabase(name) }
  }

  @Test fun restoredTrackRefreshesWhenSelectedFileMetadataArrives() = runBlocking {
    val name = "restore-title-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name)
    db.saveTorrent(com.example.rivulet.data.SavedTorrent("restore", "Simon Kurt Unsworth", 1, null, null))
    db.savePlaybackPosition("restore", 2, 10_000, 50_000)
    val c = controller(db)
    try {
      withTimeout(5000) { while (c.state.value.infoHash != "restore") delay(20) }
      withContext(Dispatchers.Main) { c.queueProvider = { listOf(QueueTrack("restore", 2, "The Devil's Detective - Part 03.mp3", "Simon Kurt Unsworth")) } }
      withTimeout(5000) { while (!c.state.value.title.startsWith("The Devil's Detective")) delay(20) }
      assertEquals(2, c.state.value.fileIndex)
      assertEquals(2, c.currentQueue.value.single().fileIndex)
    } finally { withContext(Dispatchers.Main) { c.close() }; db.close(); context.deleteDatabase(name) }
  }

  @Test fun failurePreservesCurrentQueueAndReportsActionableError() = runBlocking {
    val name = "prepare-error-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name)
    val c = controller(db)
    try {
      withContext(Dispatchers.Main) {
        val before = listOf(QueueTrack("a", 0, "First", "Book A"))
        c.setQueue(before, 0)
        c.trackPreparer = { _, _ -> error("Torrent metadata is unavailable. Retry this track.") }
        assertTrue(c.requestPlayback("b", 0).isFailure)
        assertEquals(before, c.currentQueue.value)
        assertEquals("Torrent metadata is unavailable. Retry this track.", c.state.value.error)
      }
    } finally { withContext(Dispatchers.Main) { c.close() }; db.close(); context.deleteDatabase(name) }
  }

  /** Accept HTTP but provide no audio bytes; verifies real ExoPlayer buffering without the swarm. */
  private class StalledAudio : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val release = CountDownLatch(1)
    private var client: java.net.Socket? = null
    val url = "http://127.0.0.1:${server.localPort}/public.wav"
    private val worker = thread(isDaemon = true, name = "StalledPublicAudio") {
      try {
        client = server.accept()
        client!!.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: 100000\r\n\r\n".toByteArray()); flush() }
        release.await(30, TimeUnit.SECONDS)
      } catch (_: java.io.IOException) { }
    }
    override fun close() { release.countDown(); client?.close(); server.close(); worker.join(1000) }
  }
}

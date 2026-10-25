package com.example.rivulet.readalong

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import com.example.rivulet.playback.PlaybackController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

class SyncPlaybackClockTest {
  @Test fun actualPlayerClockIsUsedAndCorrectionNeverSeeksPausedOrPlayingAudio() = runBlocking {
    val context: Context = ApplicationProvider.getApplicationContext()
    // This isolated controller owns no background playback service.
    val controllerContext = object : ContextWrapper(context) {
      override fun startForegroundService(service: Intent): ComponentName? = null
    }
    val name = "clock-${UUID.randomUUID()}.db"
    val database = TorrentDatabase(context, name)
    val file = File(context.cacheDir, "clock-${UUID.randomUUID()}.wav")
    InstrumentationRegistry.getInstrumentation().context.assets.open("readalong/jfk.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
    val server = WaveServer(file.readBytes())
    val controller = withContext(Dispatchers.Main) { PlaybackController(controllerContext, database) }
    val r = controller.readAlong
    val track = ReadAlongTrack("public-clock-fixture", 0)
    try {
      withContext(Dispatchers.Main) {
        controller.play(server.url, "Public fixture", "JFK", track.infoHash, track.fileIndex, autoplay = false)
        r.importBytes("[00:01.00]First fixture sentence.\n[00:04.00]Second fixture sentence.".toByteArray(), "clock.lrc", "text/plain", setOf(0))
      }
      withTimeout(15_000) {
        while (withContext(Dispatchers.Main) { controller.player.playbackState != Player.STATE_READY } || r.state.value.lines.isEmpty()) delay(20)
      }
      withContext(Dispatchers.Main) { controller.seekTo(8250) }
      delay(100)
      var seeks = 0
      withContext(Dispatchers.Main) {
        controller.player.addListener(object : Player.Listener {
          override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks++ }
        })
        r.playback(900, 11_000) // Deliberately stale repository snapshot.
        r.beginCorrection(1)
        controller.syncReadAlongNow(r.state.value.correction.target!!)
      }
      withTimeout(10_000) { r.state.first { it.correction.phase == SyncCorrectionPhase.APPLIED } }
      assertEquals(4250L, r.state.value.offsetMs)
      withContext(Dispatchers.Main) { assertEquals(8250L, controller.player.currentPosition); assertFalse(controller.player.playWhenReady) }
      assertEquals(0, seeks)
      withContext(Dispatchers.Main) { controller.setSpeed(1.5f); controller.player.play() }
      withTimeout(5000) { while (!withContext(Dispatchers.Main) { controller.player.isPlaying }) delay(10) }
      var expected = 0L
      withContext(Dispatchers.Main) {
        r.playback(0, 11_000)
        r.beginCorrection(0)
        expected = controller.player.currentPosition - 1000
        controller.syncReadAlongNow(r.state.value.correction.target!!)
      }
      withTimeout(10_000) { r.state.first { it.correction.phase == SyncCorrectionPhase.APPLIED } }
      assertTrue(kotlin.math.abs(expected - r.state.value.offsetMs) < 50)
      withContext(Dispatchers.Main) { assertTrue(controller.player.isPlaying); assertEquals(1.5f, controller.player.playbackParameters.speed) }
      assertEquals(0, seeks)
      withTimeout(5000) { while (ReadAlongStore(database).offset(track) != r.state.value.offsetMs) delay(10) }
    } finally { withContext(Dispatchers.Main) { controller.close() }; server.close(); delay(100); database.close(); context.deleteDatabase(name); file.delete() }
  }

  /** Range-capable loopback fixture; production playback uses the same HTTP transport. */
  private class WaveServer(private val bytes: ByteArray) : Closeable {
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}/public-fixture.wav"
    private val worker = thread(name = "SyncClockFixture", isDaemon = true) {
      while (!socket.isClosed) {
        try {
          socket.accept().use { client ->
            client.soTimeout = 5000
            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
            reader.readLine() ?: return@use
            var range: String? = null
            while (true) {
              val line = reader.readLine() ?: break
              if (line.isEmpty()) break
              if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim().removePrefix("bytes=")
            }
            val start = range?.substringBefore('-')?.toIntOrNull()?.coerceIn(0, bytes.lastIndex) ?: 0
            val end = range?.substringAfter('-')?.toIntOrNull()?.coerceIn(start, bytes.lastIndex) ?: bytes.lastIndex
            val status = if (range == null) "200 OK" else "206 Partial Content"
            val header = "HTTP/1.1 $status\r\nContent-Type: audio/wav\r\nContent-Length: ${end - start + 1}\r\nAccept-Ranges: bytes\r\nContent-Range: bytes $start-$end/${bytes.size}\r\nConnection: close\r\n\r\n"
            client.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes, start, end - start + 1); flush() }
          }
        } catch (_: IOException) { /* A seek can close the previous HTTP read. */ }
      }
    }
    override fun close() { socket.close(); worker.join(1000) }
  }
}

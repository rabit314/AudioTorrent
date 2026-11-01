package com.example.rivulet.readalong

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rivulet.data.*
import com.example.rivulet.stream.StreamServer
import com.example.rivulet.torrent.TorrentEngine
import com.example.rivulet.torrent.PublicWebseedBridge
import com.example.rivulet.data.catalog.PublicCatalogHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class WebseedDownloadTest {
  @Test fun multiFileAdapterVerifiesBoundaryPiecesAndExcludesAlternateAudioFromOfflineQueue() = runBlocking {
    val context: Context = ApplicationProvider.getApplicationContext()
    val name = "boundary-${UUID.randomUUID()}"
    val db = TorrentDatabase(context, "$name.db")
    val content = linkedMapOf("alternate.mp3" to ByteArray(13 * 1024) { (it % 241).toByte() },
      "cover.png" to ByteArray(1024) { (it % 97).toByte() }, "chapter.mp3" to ByteArray(80 * 1024) { (it % 251).toByte() })
    val requests = ConcurrentHashMap.newKeySet<String>()
    val transport = OkHttpClient.Builder().addInterceptor { chain ->
      val path = chain.request().url.pathSegments.last()
      val bytes = checkNotNull(content[path])
      requests += path
      val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(chain.request().header("Range").orEmpty())!!
      val from = range.groupValues[1].toInt(); val to = range.groupValues[2].toInt()
      val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture")
        .body(bytes.copyOfRange(from, to + 1).toResponseBody())
      // Some HTTP servers return200 when the requested range is the entire small file.
      if (from == 0 && to == bytes.lastIndex) response.code(200).build()
      else response.code(206).header("Content-Range", "bytes $from-$to/${bytes.size}").build()
    }.build()
    val adapter = PublicWebseedBridge(PublicCatalogHttp(setOf("archive.org"), transport))
    var engine = TorrentEngine(context, db, publicWebseeds = adapter)
    var playback = StreamServer(engine)
    var hash: String? = null
    try {
      val book = CatalogBook("fixture", "Boundary fixture", "Author", chapters = listOf(CatalogChapter("chapter.mp3", "Chapter")),
        webSeeds = listOf("https://archive.org/download/"))
      hash = engine.addTorrentFile(multiTorrent(name, content), catalogBook = book).getOrThrow()
      val lease = engine.prepareStream(hash, 2)
      playback.start()
      val result = withContext(Dispatchers.IO) {
        val connection = URL(playback.streamUrl(hash, 2)).openConnection() as HttpURLConnection
        try {
          connection.connectTimeout = 10_000; connection.readTimeout = 90_000
          connection.setRequestProperty("Range", "bytes=1000-4999")
          assertEquals(206, connection.responseCode)
          connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
      }
      assertArrayEquals(content.getValue("chapter.mp3").copyOfRange(1000, 5000), result)
      assertTrue(requests.containsAll(content.keys)) // The first verified piece crosses all three files.
      assertEquals(0, engine.find(hash)!!.peers)
      engine.setTorrentDownloadWanted(hash, true)
      withTimeout(90_000) { while (engine.find(hash)?.isFullyDownloaded != true) delay(100) }
      assertEquals(listOf(2), engine.find(hash)!!.audioFiles.map { it.index })
      assertEquals(mapOf(2 to true), db.getFilePriorities(hash))
      engine.abandonPreparation(lease)
      playback.stop(); engine.shutdown()
      assertTrue(java.io.File(context.filesDir, "torrents/$name/chapter.mp3").delete())
      requests.clear()
      engine = TorrentEngine(context, db, publicWebseeds = PublicWebseedBridge(PublicCatalogHttp(setOf("archive.org"), transport)))
      playback = StreamServer(engine)
      withTimeout(90_000) { while (engine.find(hash)?.hasMetadata != true) delay(50) }
      val restored = engine.prepareStream(hash, 2)
      playback.start()
      val afterRestart = withContext(Dispatchers.IO) {
        val connection = URL(playback.streamUrl(hash, 2)).openConnection() as HttpURLConnection
        try {
          connection.connectTimeout = 10_000; connection.readTimeout = 90_000
          connection.setRequestProperty("Range", "bytes=1000-4999")
          assertEquals(206, connection.responseCode)
          connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
      }
      assertArrayEquals(result, afterRestart)
      assertTrue(requests.contains("chapter.mp3"))
      engine.abandonPreparation(restored)
    } finally {
      playback.stop(); hash?.let { engine.removeTorrent(it, true) }
      engine.shutdown(); db.close(); context.deleteDatabase("$name.db")
      transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
    }
  }

  @Test fun adapterRejectsUnknownRoutesInvalidRangesAndUntrustedOrigins() {
    val calls = AtomicInteger()
    val transport = OkHttpClient.Builder().addInterceptor { chain ->
      calls.incrementAndGet()
      Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture").code(206)
        .header("Content-Range", "bytes 1-4/10").body(ByteArray(4) { 7 }.toResponseBody()).build()
    }.build()
    val adapter = PublicWebseedBridge(PublicCatalogHttp(setOf("archive.org"), transport))
    val hash = "0".repeat(40)
    try {
      assertThrows(IllegalArgumentException::class.java) { adapter.register(hash, listOf("https://evil.test/"), mapOf("root/file.mp3" to 10L)) }
      assertThrows(IllegalArgumentException::class.java) { adapter.register(hash, emptyList(), mapOf("root/../secret" to 10L)) }
      val base = adapter.register(hash, emptyList(), mapOf("root/file.mp3" to 10L))
      fun status(url: String, range: String? = null): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
          connection.connectTimeout = 3000; connection.readTimeout = 3000
          if (range != null) connection.setRequestProperty("Range", range)
          connection.responseCode
        } finally { connection.disconnect() }
      }
      assertEquals(404, status(base.replace(Regex("/[a-f0-9]{32}/"), "/wrong-token/") + "root/file.mp3"))
      assertEquals(404, status(base + "root/unknown.mp3"))
      assertEquals(404, status(base + "root/../secret"))
      assertEquals(416, status(base + "root/file.mp3", "bytes=999999999999999999999999999-"))
      assertEquals(416, status(base + "root/file.mp3", "bytes=20-30"))
      assertEquals(0, calls.get())
      assertEquals(206, status(base + "root/file.mp3", "bytes=1-4"))
      assertEquals(1, calls.get())
      adapter.remove(hash)
      assertEquals(404, status(base + "root/file.mp3"))
    } finally { adapter.close(); transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll() }
    assertThrows(IllegalStateException::class.java) { adapter.register(hash, emptyList(), mapOf("root/file.mp3" to 10L)) }
  }

  @Test fun privateTorrentDownloadsFromHttpWithoutPeersAndServesVerifiedSeekRange() = runBlocking {
    val context: Context = ApplicationProvider.getApplicationContext()
    val name = "webseed-${UUID.randomUUID()}"
    val db = TorrentDatabase(context, "$name.db")
    val engine = TorrentEngine(context, db)
    val bridge = StreamServer(engine)
    val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
    val source = SeedServer(bytes)
    var hash: String? = null
    try {
      val book = CatalogBook("fixture", "Public fixture", "Test Author", chapters = listOf(CatalogChapter("$name.mp3", "First")))
      hash = engine.addTorrentFile(torrent("$name.mp3", bytes, source.url), catalogBook = book).getOrThrow()
      val stream = engine.prepareStream(hash, 0)
      bridge.start()
      val result = withContext(Dispatchers.IO) {
        val connection = URL(bridge.streamUrl(hash, 0)).openConnection() as HttpURLConnection
        try {
          connection.connectTimeout = 10_000; connection.readTimeout = 90_000
          connection.setRequestProperty("Range", "bytes=1000-4999")
          assertEquals(206, connection.responseCode)
          connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
      }
      assertArrayEquals(bytes.copyOfRange(1000, 5000), result)
      assertTrue(source.requests.get() > 0)
      assertEquals(0, engine.find(hash)!!.peers)
      engine.setTorrentDownloadWanted(hash, true)
      withTimeout(90_000) { while (engine.find(hash)?.isFullyDownloaded != true) delay(100) }
      assertTrue(db.getFilePriorities(hash)[0] == true)
      engine.abandonPreparation(stream)
      assertEquals("First", engine.find(hash)!!.audioFiles.single().displayTitle)
    } finally {
      bridge.stop(); source.close()
      hash?.let { engine.removeTorrent(it, true) }
      engine.shutdown(); db.close(); context.deleteDatabase("$name.db")
    }
  }

  private fun torrent(name: String, bytes: ByteArray, seed: String): ByteArray = ByteArrayOutputStream().apply {
    val pieces = ByteArrayOutputStream()
    bytes.asList().chunked(16384).forEach { pieces.write(MessageDigest.getInstance("SHA-1").digest(it.toByteArray())) }
    write("d4:infod6:lengthi${bytes.size}e4:name${name.length}:$name".toByteArray())
    write("12:piece lengthi16384e6:pieces${pieces.size()}:".toByteArray()); write(pieces.toByteArray())
    write("7:privatei1ee8:url-listl${seed.length}:${seed}ee".toByteArray())
  }.toByteArray()

  private fun multiTorrent(name: String, files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
    val data = files.values.fold(ByteArray(0)) { all, bytes -> all + bytes }
    val pieces = ByteArrayOutputStream()
    data.asList().chunked(16384).forEach { pieces.write(MessageDigest.getInstance("SHA-1").digest(it.toByteArray())) }
    write("d4:infod5:filesl".toByteArray())
    files.forEach { (path, bytes) -> write("d6:lengthi${bytes.size}e4:pathl${path.length}:${path}ee".toByteArray()) }
    write("e4:name${name.length}:$name".toByteArray())
    write("12:piece lengthi16384e6:pieces${pieces.size()}:".toByteArray()); write(pieces.toByteArray())
    val oldSeed = "http://127.0.0.1:1/old/"
    write("7:privatei0ee8:url-listl${oldSeed.length}:$oldSeed".toByteArray())
    write("ee".toByteArray())
  }.toByteArray()

  /** No trackers and private=1: the only source is this loopback HTTP webseed. */
  private class SeedServer(private val bytes: ByteArray) : AutoCloseable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val requests = AtomicInteger()
    val url = "http://127.0.0.1:${socket.localPort}/fixture.mp3"
    private val worker = thread(isDaemon = true, name = "FixtureWebseed") {
      while (!socket.isClosed) try {
        socket.accept().use { client ->
          client.soTimeout = 5000
          val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
          val method = reader.readLine().orEmpty().substringBefore(' ')
          var range: String? = null
          while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
          }
          val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
          val start = match?.groupValues?.get(1)?.toInt() ?: 0
          val end = match?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
          val output = client.getOutputStream()
          val status = if (match == null) "200 OK" else "206 Partial Content"
          val contentRange = if (match == null) "" else "Content-Range: bytes $start-$end/${bytes.size}\r\n"
          output.write("HTTP/1.1 $status\r\nContent-Length: ${end - start + 1}\r\n${contentRange}Accept-Ranges: bytes\r\nConnection: close\r\n\r\n".toByteArray())
          if (method != "HEAD") { requests.incrementAndGet(); output.write(bytes, start, end - start + 1) }
          output.flush()
        }
      } catch (_: Exception) { /* A cancelled request closes its socket; the next range can continue. */ }
    }
    override fun close() { socket.close(); worker.join(1000) }
  }
}

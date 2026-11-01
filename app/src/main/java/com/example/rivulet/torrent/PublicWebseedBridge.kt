package com.example.rivulet.torrent

import com.example.rivulet.data.catalog.PublicCatalogHttp
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private data class PublicSeedRange(val origins: List<String>, val path: String, val size: Long, val from: Long, val to: Long)

/** BEP19 adapter only: Android handles HTTPS; libtorrent still hashes, stores and schedules every piece. */
class PublicWebseedBridge(private val http: PublicCatalogHttp = PublicCatalogHttp(setOf("archive.org"))) : AutoCloseable {
  private data class Recording(val origins: List<String>, val files: Map<String, Long>)
  private val token = UUID.randomUUID().toString().replace("-", "")
  private val recordings = ConcurrentHashMap<String, Recording>()
  private val clients = ConcurrentHashMap.newKeySet<Socket>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val permits = Semaphore(4)
  private val closed = AtomicBoolean(false)
  private var server: ServerSocket? = null

  @Synchronized fun register(hash: String, origins: List<String>, files: Map<String, Long>): String {
    check(!closed.get()) { "Public webseed bridge is closed." }
    require(hash.matches(Regex("[a-fA-F0-9]{40}")))
    val roots = (listOf("https://archive.org/download/") + origins).distinct().map { root ->
      val validated = http.validate(root).toHttpUrl()
      require(validated.encodedPath.endsWith('/') && validated.query == null && validated.fragment == null)
      validated.toString()
    }
    require(files.isNotEmpty() && files.all { (path, size) -> safePath(path) && size >= 0 })
    recordings[hash] = Recording(roots, files.toMap())
    Log.d("PublicWebseed", "Registered public recording (${files.size} paths)")
    if (server == null) {
      val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
      server = socket
      scope.launch {
        while (isActive) {
          permits.acquire()
          val client = try { socket.accept() } catch (_: IOException) { permits.release(); break }
          clients += client
          scope.launch {
            try { client.use(::serve) } catch (_: IOException) { /* A native cancellation closes the connection. */ }
          }.invokeOnCompletion { runCatching { client.close() }; clients -= client; permits.release() }
        }
      }
    }
    return "http://127.0.0.1:${server!!.localPort}/$token/$hash/"
  }

  fun remove(hash: String) { recordings.remove(hash) }
  @Synchronized override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { server?.close() }
    clients.forEach { runCatching { it.close() } }
    recordings.clear(); scope.cancel()
  }

  private fun serve(socket: Socket) {
    socket.soTimeout = 15_000
    val input = BufferedInputStream(socket.getInputStream())
    val output = BufferedOutputStream(socket.getOutputStream())
    while (!socket.isClosed && scope.isActive) {
      val request = line(input) ?: return
      val parts = request.split(' ')
      if (parts.size != 3 || parts[0] !in setOf("GET", "HEAD")) { error(output, "400 Bad Request"); return }
      val headers = mutableMapOf<String, String>()
      var headerBytes = 0
      while (true) {
        val header = line(input) ?: return
        if (header.isEmpty()) break
        headerBytes += header.length
        if (headerBytes > 16_384 || ':' !in header) { error(output, "400 Bad Request"); return }
        headers[header.substringBefore(':').trim().lowercase(Locale.ROOT)] = header.substringAfter(':').trim()
      }
      val rawPath = runCatching { URI(parts[1]).rawPath }.getOrNull().orEmpty()
      val prefix = "/$token/"
      if (!rawPath.startsWith(prefix)) { error(output, "404 Not Found"); return }
      val route = rawPath.removePrefix(prefix)
      val hash = route.substringBefore('/')
      val path = runCatching { URLDecoder.decode(route.substringAfter('/', "").replace("+", "%2B"), "UTF-8") }.getOrNull()
      val recording = recordings[hash]
      val size = path?.let { recording?.files?.get(it) }
      Log.d("PublicWebseed", "Public range request: registered=${size != null}")
      if (size == null || size <= 0) { error(output, "404 Not Found"); return }
      val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
      if (headers.containsKey("range") && range == null) { error(output, "416 Range Not Satisfiable"); return }
      val from = if (range == null) 0 else range.groupValues[1].toLongOrNull()
      val endText = range?.groupValues?.get(2).orEmpty()
      val to = if (endText.isBlank()) size - 1 else endText.toLongOrNull()?.coerceAtMost(size - 1)
      if (from == null || to == null) { error(output, "416 Range Not Satisfiable"); return }
      if (from !in 0 until size || to < from || (parts[0] != "HEAD" && to - from + 1 > MAX_RANGE)) {
        error(output, "416 Range Not Satisfiable"); return
      }
      var began = false
      val ready = {
        began = true
        val partial = if (range == null) "" else "Content-Range: bytes $from-$to/$size\r\n"
        output.write(("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\n" +
          "Content-Length: ${to - from + 1}\r\n${partial}Accept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\nConnection: keep-alive\r\n\r\n").toByteArray(Charsets.US_ASCII))
      }
      try {
        if (parts[0] == "HEAD") ready()
        else {
          val wanted = PublicSeedRange(recording!!.origins, path!!, size, from, to)
          transfer(wanted, output, ready)
        }
        output.flush()
      } catch (failure: Exception) {
        Log.w("PublicWebseed", "Public audio range failed: ${failure.message}")
        if (!began) error(output, "502 Bad Gateway")
        return
      }
      if (headers["connection"].equals("close", true)) return
    }
  }

  private fun transfer(request: PublicSeedRange, output: OutputStream, ready: () -> Unit) {
    var began = false
    var last: Exception? = null
    for (origin in request.origins) {
      try {
        val url = origin.toHttpUrl().newBuilder().addPathSegments(request.path).build().toString()
        http.range(url, request.from, request.to) { response ->
          val length = request.to - request.from + 1
          val body = response.body ?: throw IOException("Empty public webseed response.")
          val expected = "bytes ${request.from}-${request.to}/${request.size}"
          val partial = response.code == 206 && response.header("Content-Range") == expected
          val entire = response.code == 200 && request.from == 0L && request.to == request.size - 1
          if ((!partial && !entire) || body.contentLength().let { it >= 0 && it != length }) {
            throw IOException("Public webseed returned an incompatible range (HTTP ${response.code}).")
          }
          ready(); began = true
          body.byteStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            var remaining = length
            while (remaining > 0) {
              val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
              if (count < 0) throw EOFException("Incomplete public webseed range.")
              output.write(buffer, 0, count); remaining -= count
            }
          }
        }
        return
      } catch (failure: Exception) {
        if (began) throw failure
        last = failure
      }
    }
    throw IOException("No public webseed could serve this range.", last)
  }

  private fun error(output: OutputStream, status: String) {
    output.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.flush()
  }
  private fun line(input: InputStream): String? {
    val bytes = ByteArrayOutputStream()
    while (bytes.size() < 8192) {
      val value = input.read()
      if (value < 0) return if (bytes.size() == 0) null else throw EOFException()
      if (value == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
      bytes.write(value)
    }
    throw IOException("Oversized public webseed request.")
  }
  private fun safePath(path: String) = !path.startsWith('/') && '\\' !in path && path.none(Char::isISOControl) &&
    path.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") }
  companion object { private const val MAX_RANGE = 16L * 1024 * 1024 }
}

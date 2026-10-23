package com.example.rivulet.stream

import com.example.rivulet.torrent.TorrentEngine
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Minimal HTTP/1.1 range server bound to loopback, feeding ExoPlayer from the torrent.
 *
 * One connection = one read stream. Seeking makes ExoPlayer open a new range request, which is
 * exactly the behavior the torrent reader needs to re-arm its piece window.
 */
class StreamServer(private val engine: TorrentEngine) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val token = UUID.randomUUID().toString().replace("-", "")
  private val started = AtomicBoolean(false)
  private val permits = Semaphore(8)

  @Volatile private var serverSocket: ServerSocket? = null
  @Volatile private var port: Int = 0

  fun start(): Int {
    if (started.compareAndSet(false, true)) {
      try {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        scope.launch { acceptLoop(socket) }
      } catch (_: Throwable) {
        started.set(false)
        port = 0
      }
    }
    return port
  }

  fun streamUrl(infoHash: String, fileIndex: Int): String {
    check(port != 0) { "Stream server is not running." }
    return "http://127.0.0.1:$port/s/$infoHash/$fileIndex?t=$token"
  }

  fun stop() {
    runCatching { serverSocket?.close() }
    serverSocket = null
    port = 0
    scope.cancel()
  }

  private suspend fun acceptLoop(socket: ServerSocket) {
    while (scope.isActive) {
      val client = try {
        socket.accept()
      } catch (_: Throwable) {
        break
      }
      scope.launch {
        permits.withPermit { handleClient(client) }
      }
    }
  }

  private suspend fun handleClient(socket: Socket) {
    socket.use { client ->
      client.tcpNoDelay = true
      client.soTimeout = 30_000

      val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
      val requestLine = reader.readLine() ?: return
      var rangeHeader: String? = null
      var headerCount = 0
      while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) break
        if (++headerCount > 100) break
        if (line.startsWith("Range:", ignoreCase = true)) {
          rangeHeader = line.substringAfter(':').trim()
        }
      }

      val parts = requestLine.split(' ')
      if (parts.size < 3) {
        respondText(client, 400, "Bad Request")
        return
      }
      val method = parts[0].uppercase()
      if (method != "GET" && method != "HEAD") {
        respondText(client, 405, "Method Not Allowed")
        return
      }

      val uri = runCatching { URI(parts[1]) }.getOrNull()
      val segments = uri?.path?.trim('/')?.split('/').orEmpty()
      if (segments.size != 3 || segments[0] != "s") {
        respondText(client, 404, "Not Found")
        return
      }

      val hash = segments[1]
      val fileIndex = segments[2].toIntOrNull()
      if (fileIndex == null) {
        respondText(client, 404, "Not Found")
        return
      }

      val suppliedToken =
        uri?.query?.split('&')?.firstOrNull { it.startsWith("t=") }?.substringAfter("t=")
      if (suppliedToken != token) {
        respondText(client, 403, "Forbidden")
        return
      }

      val stream = try {
        engine.acquireStream(hash, fileIndex)
      } catch (t: Throwable) {
        Log.w(TAG, "acquireStream failed for $hash/$fileIndex", t)
        respondText(client, 404, t.message ?: "Not Found")
        return
      }

      try {
        Log.d(TAG, "Serving $hash/$fileIndex range=${rangeHeader ?: "full"}")
        serve(client, method, stream, rangeHeader)
      } catch (t: Throwable) {
        Log.w(TAG, "Stream $hash/$fileIndex failed: ${t.message}", t)
        // Client went away (seek/stop) or the piece wait timed out; dropping the socket is fine.
      } finally {
        try {
          engine.releaseStream(stream)
        } catch (t: Throwable) {
          Log.w(TAG, "releaseStream failed", t)
        }
      }
    }
  }

  private suspend fun serve(
    socket: Socket,
    method: String,
    stream: StreamSession,
    rangeHeader: String?,
  ) {
    val size = stream.fileSize
    if (size <= 0L) {
      respondText(socket, 404, "Empty file")
      return
    }

    val range = try {
      parseRange(rangeHeader, size)
    } catch (_: RangeNotSatisfiable) {
      writeHead(
        socket,
        "416 Range Not Satisfiable",
        mapOf("Content-Range" to "bytes */$size", "Content-Length" to "0", "Connection" to "close"),
      )
      return
    }

    val length = range.end - range.start + 1
    val headers = linkedMapOf(
      "Content-Type" to stream.mimeType,
      "Accept-Ranges" to "bytes",
      "Content-Length" to length.toString(),
      "Cache-Control" to "no-store",
      "Connection" to "close",
    )
    if (range.partial) {
      headers["Content-Range"] = "bytes ${range.start}-${range.end}/$size"
    }

    writeHead(socket, if (range.partial) "206 Partial Content" else "200 OK", headers)
    if (method == "HEAD") return

    val output = socket.getOutputStream()
    RandomAccessFile(stream.file, "r").use { file ->
      file.seek(range.start)
      var position = range.start
      val buffer = ByteArray(64 * 1024)

      while (position <= range.end) {
        stream.armWindow(position)
        val chunkEnd = min(range.end, position + buffer.size - 1)
        stream.awaitRange(position, chunkEnd)
        val count = (chunkEnd - position + 1).toInt()
        file.readFully(buffer, 0, count)
        output.write(buffer, 0, count)
        output.flush()
        position += count
      }
    }
  }

  private fun writeHead(socket: Socket, status: String, headers: Map<String, String>) {
    val builder = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
    for ((name, value) in headers) {
      builder.append(name).append(": ").append(value).append("\r\n")
    }
    builder.append("\r\n")
    val output = socket.getOutputStream()
    output.write(builder.toString().toByteArray(Charsets.US_ASCII))
    output.flush()
  }

  private fun respondText(socket: Socket, code: Int, message: String) {
    val body = message.toByteArray(Charsets.UTF_8)
    writeHead(
      socket,
      "$code ${reason(code)}",
      mapOf(
        "Content-Type" to "text/plain; charset=utf-8",
        "Content-Length" to body.size.toString(),
        "Connection" to "close",
      ),
    )
    runCatching {
      socket.getOutputStream().write(body)
      socket.getOutputStream().flush()
    }
  }

  private fun reason(code: Int): String =
    when (code) {
      200 -> "OK"
      206 -> "Partial Content"
      400 -> "Bad Request"
      403 -> "Forbidden"
      404 -> "Not Found"
      405 -> "Method Not Allowed"
      416 -> "Range Not Satisfiable"
      else -> "Error"
    }

  private data class ByteRange(val start: Long, val end: Long, val partial: Boolean)

  private class RangeNotSatisfiable : Exception()

  private fun parseRange(header: String?, size: Long): ByteRange {
    if (header.isNullOrBlank()) return ByteRange(0, size - 1, false)

    val value = header.trim()
    if (!value.startsWith("bytes=", ignoreCase = true)) throw RangeNotSatisfiable()
    val spec = value.substringAfter('=').substringBefore(',').trim()
    val dash = spec.indexOf('-')
    if (dash < 0) throw RangeNotSatisfiable()

    val startText = spec.substring(0, dash).trim()
    val endText = spec.substring(dash + 1).trim()

    var start: Long
    var end: Long
    if (startText.isEmpty()) {
      val suffix = endText.toLongOrNull() ?: throw RangeNotSatisfiable()
      if (suffix <= 0L) throw RangeNotSatisfiable()
      start = (size - suffix).coerceAtLeast(0L)
      end = size - 1
    } else {
      start = startText.toLongOrNull() ?: throw RangeNotSatisfiable()
      end = if (endText.isEmpty()) size - 1 else endText.toLongOrNull() ?: throw RangeNotSatisfiable()
    }

    if (start < 0L || start >= size || end < start) throw RangeNotSatisfiable()
    end = end.coerceAtMost(size - 1)
    return ByteRange(start, end, true)
  }

  private companion object {
    const val TAG = "RivuletServer"
  }
}

package com.example.rivulet.data.lyrics

import java.io.InputStream
import java.io.ByteArrayOutputStream

object BookImport {
  const val MAX_BYTES = 32 * 1024 * 1024
  fun read(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      require(result.size() + count <= limit) { "File exceeds the ${limit / 1024 / 1024} MB import limit." }
      result.write(buffer, 0, count)
    }
    require(result.size() > 0) { "The selected file is empty." }
    return result.toByteArray()
  }

  fun source(bytes: ByteArray, displayName: String, mime: String?): TextSyncSource {
    val name = displayName.lowercase()
    val prefix = bytes.take(256).toByteArray().toString(Charsets.UTF_8).trimStart().lowercase()
    require(!prefix.startsWith("<!doctype html") && !prefix.startsWith("<html")) { "The server returned a web page instead of an ebook. Check site access and try again." }
    if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) return TextSyncSource.NARRATION
    return when {
      name.endsWith(".lrc") -> TextSyncSource.LRC
      name.endsWith(".srt") || mime == "application/x-subrip" -> TextSyncSource.SRT
      name.endsWith(".txt") || mime == "text/plain" -> {
        require(bytes.none { it == 0.toByte() }) { "This is not a UTF-8 text file." }
        TextSyncSource.NARRATION
      }
      else -> error("Choose an EPUB, UTF-8 TXT, LRC or SRT file.")
    }
  }

  fun book(bytes: ByteArray, displayName: String): EpubBook =
    if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) EpubStructureParser.parse(bytes.inputStream())
    else EpubExtractor.extractBookFromBytes(bytes, displayName.substringBeforeLast('.') + ".txt")
}

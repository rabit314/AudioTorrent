package com.example.rivulet.data.lyrics

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream

object BookImport {
  const val MAX_BYTES = 32 * 1024 * 1024
  val SUPPORTED_FORMATS = setOf("EPUB", "PDF", "TXT", "FB2")
  private val SIZE_REGEX = Regex("(\\d+(?:\\.\\d+)?)\\s*(bytes?|b|kb|kib|mb|mib|gb|gib)\\b", RegexOption.IGNORE_CASE)

  @Volatile var pdfExtractor: PdfPageTextReader? = null

  fun init(context: Context) {
    if (pdfExtractor == null) {
      pdfExtractor = AndroidPdfPageReader(context.applicationContext)
    }
  }

  fun isZip(bytes: ByteArray): Boolean =
    bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()

  fun isPdf(bytes: ByteArray): Boolean =
    bytes.size >= 5 &&
      bytes[0] == 0x25.toByte() &&
      bytes[1] == 0x50.toByte() &&
      bytes[2] == 0x44.toByte() &&
      bytes[3] == 0x46.toByte() &&
      bytes[4] == 0x2d.toByte()

  fun isFb2Xml(bytes: ByteArray, displayName: String = ""): Boolean {
    if (displayName.endsWith(".fb2", ignoreCase = true)) return true
    val head = bytes.take(1024).toByteArray().toString(Charsets.UTF_8)
    return head.contains("<FictionBook", ignoreCase = true)
  }

  fun isSupportedFormat(rawFormat: String): Boolean {
    val norm = rawFormat.trim().uppercase()
    return norm in SUPPORTED_FORMATS
  }

  fun parseSizeBytes(raw: String): Long? {
    val match = SIZE_REGEX.find(raw) ?: return null
    val amount = match.groupValues[1].toDoubleOrNull() ?: return null
    val mult = when (match.groupValues[2].lowercase()) {
      "b", "byte", "bytes" -> 1.0
      "kb", "kib" -> 1024.0
      "mb", "mib" -> 1024.0 * 1024.0
      "gb", "gib" -> 1024.0 * 1024.0 * 1024.0
      else -> return null
    }
    return (amount * mult).toLong()
  }

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
    require(!prefix.startsWith("<!doctype html") && !prefix.startsWith("<html")) {
      "The server returned a web page instead of an ebook. Check site access and try again."
    }
    if (isZip(bytes)) return TextSyncSource.NARRATION
    if (isPdf(bytes)) return TextSyncSource.NARRATION
    if (isFb2Xml(bytes, name)) {
      require(bytes.none { it == 0.toByte() }) { "This FB2 file is invalid." }
      return TextSyncSource.NARRATION
    }
    return when {
      name.endsWith(".lrc") -> TextSyncSource.LRC
      name.endsWith(".srt") || mime == "application/x-subrip" -> TextSyncSource.SRT
      name.endsWith(".txt") || mime == "text/plain" -> {
        require(bytes.none { it == 0.toByte() }) { "This is not a UTF-8 text file." }
        TextSyncSource.NARRATION
      }
      else -> error("Choose an EPUB, PDF, FB2, UTF-8 TXT, LRC or SRT file.")
    }
  }

  fun book(bytes: ByteArray, displayName: String): EpubBook = when {
    isZip(bytes) -> EpubExtractor.extractZipBookFromBytes(bytes, displayName)
    isPdf(bytes) -> {
      val reader = pdfExtractor ?: error("PDF reader is not initialized.")
      PdfTextCleanup.toBook(reader.readPages(bytes), displayName)
    }
    isFb2Xml(bytes, displayName) -> EpubExtractor.parseFb2(String(bytes, Charsets.UTF_8), displayName)
    else -> EpubExtractor.extractBookFromBytes(bytes, displayName.substringBeforeLast('.') + ".txt")
  }
}

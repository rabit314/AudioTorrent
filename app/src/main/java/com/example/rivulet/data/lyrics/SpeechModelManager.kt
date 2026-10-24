package com.example.rivulet.data.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class SpeechModelManager(context: Context) {
  val file = File(context.noBackupFilesDir, "readalong/ggml-base.en-q5_1.bin")
  private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
  fun isInstalled() = file.isFile

  suspend fun download(progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
    file.parentFile!!.mkdirs()
    val partial = File(file.parentFile, "model.partial")
    try {
      client.newCall(Request.Builder().url(URL).build()).execute().use { response ->
        check(response.isSuccessful) { "Speech model download failed (${response.code}). Try again." }
        val body = response.body ?: error("Speech model download was empty.")
        val digest = MessageDigest.getInstance("SHA-256")
        val total = body.contentLength().takeIf { it > 0 } ?: 59_707_825L
        var downloaded = 0L
        body.byteStream().use { input -> partial.outputStream().use { output ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            downloaded += count
            check(downloaded <= 65_000_000) { "Unexpected speech model size." }
            digest.update(buffer, 0, count); output.write(buffer, 0, count)
            progress((downloaded * 100 / total).toInt().coerceIn(0, 99))
          }
        } }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == SHA256) { "Speech model checksum did not match. Retry the download." }
        check(partial.renameTo(file)) { "Could not save the speech model. Check available storage." }
        progress(100)
      }
    } finally { partial.delete() }
  }

  companion object {
    const val SHA256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
    const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/c521a4b02f422512d734391fdf08bb08c0862f68/ggml-base.en-q5_1.bin"
  }
}

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
  private val modelDir = File(context.noBackupFilesDir, "readalong")
  val fastFile = File(modelDir, SpeechModelTier.FAST.fileName)
  val accurateFile = File(modelDir, SpeechModelTier.ACCURATE.fileName)
  val legacyFile = File(modelDir, LEGACY_FILE_NAME)

  /** Preserves existing direct `models.file` callers and unit/androidTest fixtures. */
  val file: File
    get() = activeFile(SpeechModelTier.FAST)

  private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

  fun fileForTier(tier: SpeechModelTier): File = when (tier) {
    SpeechModelTier.FAST -> fastFile
    SpeechModelTier.ACCURATE -> accurateFile
  }

  /**
   * Resolves the best installed model for [preferred], honoring previously downloaded
   * legacy `base.en-q5_1` or sibling tier files so existing users never hit a forced re-download.
   */
  fun activeFile(preferred: SpeechModelTier = SpeechModelTier.FAST): File {
    val target = fileForTier(preferred)
    if (target.isFile) return target
    if (preferred == SpeechModelTier.ACCURATE && legacyFile.isFile) return legacyFile
    if (legacyFile.isFile) return legacyFile
    val fallback = if (preferred == SpeechModelTier.FAST) accurateFile else fastFile
    if (fallback.isFile) return fallback
    return target
  }

  fun isInstalled(preferred: SpeechModelTier = SpeechModelTier.FAST): Boolean = activeFile(preferred).isFile

  fun isDownloaded(tier: SpeechModelTier): Boolean = fileForTier(tier).isFile

  fun isUsingLegacyFallback(preferred: SpeechModelTier = SpeechModelTier.FAST): Boolean =
    !fileForTier(preferred).isFile && legacyFile.isFile && activeFile(preferred) == legacyFile

  fun activeModelTag(preferred: SpeechModelTier = SpeechModelTier.FAST): String = when (activeFile(preferred)) {
    fastFile -> SpeechModelTier.FAST.modelTag
    accurateFile -> SpeechModelTier.ACCURATE.modelTag
    legacyFile -> LEGACY_MODEL_TAG
    else -> preferred.modelTag
  }

  suspend fun download(progress: (Int) -> Unit) = download(SpeechModelTier.FAST, progress)

  suspend fun download(tier: SpeechModelTier, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
    val target = fileForTier(tier)
    target.parentFile!!.mkdirs()
    val partial = File(target.parentFile, "${tier.fileName}.partial")
    try {
      client.newCall(Request.Builder().url(tier.url).build()).execute().use { response ->
        check(response.isSuccessful) { "Speech model download failed (${response.code}). Try again." }
        val body = response.body ?: error("Speech model download was empty.")
        val digest = MessageDigest.getInstance("SHA-256")
        val total = body.contentLength().takeIf { it > 0 } ?: tier.exactBytes
        val maxAllowed = tier.exactBytes + 5_000_000L
        var downloaded = 0L
        body.byteStream().use { input -> partial.outputStream().use { output ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            downloaded += count
            check(downloaded <= maxAllowed) { "Unexpected speech model size." }
            digest.update(buffer, 0, count); output.write(buffer, 0, count)
            progress((downloaded * 100 / total).toInt().coerceIn(0, 99))
          }
        } }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == tier.sha256) { "Speech model checksum did not match. Retry the download." }
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Could not save the speech model. Check available storage." }
        progress(100)
      }
    } finally { partial.delete() }
  }

  companion object {
    const val LEGACY_FILE_NAME = "ggml-base.en-q5_1.bin"
    const val LEGACY_MODEL_TAG = "base.en-q5_1"
    const val LEGACY_SHA256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
    val SHA256: String get() = SpeechModelTier.FAST.sha256
    val URL: String get() = SpeechModelTier.FAST.url
  }
}

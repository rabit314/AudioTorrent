package com.example.rivulet.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface SpeechRecognizer {
  suspend fun transcribe(model: File, pcm: FloatArray): List<SpeechWord>
}

class WhisperEngine : SpeechRecognizer {
  private val mutex = Mutex()
  private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ReadAlongSpeech").apply { isDaemon = true } }
  private external fun nativeCreate(path: String): Long
  private external fun nativeTranscribe(handle: Long, pcm: FloatArray): Array<SpeechWord>
  private external fun nativeCancel(handle: Long)
  private external fun nativeDestroy(handle: Long)

  override suspend fun transcribe(model: File, pcm: FloatArray): List<SpeechWord> = mutex.withLock {
    withContext(Dispatchers.Default) {
      suspendCancellableCoroutine { continuation ->
        // Pointer lifetime and cancellation are serialized, including cancellation during loading.
        val lock = Any()
        var handle = 0L
        continuation.invokeOnCancellation { synchronized(lock) { if (handle != 0L) nativeCancel(handle) } }
        worker.execute {
          try {
            System.loadLibrary("rivulet_whisper")
            val created = nativeCreate(model.absolutePath)
            check(created != 0L) { "Speech model could not be loaded. Download it again." }
            synchronized(lock) { handle = created; if (!continuation.isActive) nativeCancel(created) }
            val words = nativeTranscribe(created, pcm).toList()
            if (continuation.isActive) continuation.resume(words)
          } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException(
              if (error is OutOfMemoryError) "Not enough memory for speech matching. Close other apps or use estimated timing."
              else "Speech matching failed: ${error.message ?: error.javaClass.simpleName}", error))
          } finally {
            synchronized(lock) { if (handle != 0L) { nativeDestroy(handle); handle = 0L } }
          }
        }
      }
    }
  }
}

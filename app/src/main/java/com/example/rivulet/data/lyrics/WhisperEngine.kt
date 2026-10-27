package com.example.rivulet.data.lyrics

import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface SpeechRecognizer {
  suspend fun transcribe(model: File, pcm: FloatArray): List<SpeechWord>
  fun release() {}
  fun close() {}
}

/** All model/context operations run on one worker. Only the atomic native abort is cross-thread. */
class WhisperEngine(private val idleMillis: Long = 60_000) : SpeechRecognizer {
  private val lock = Any()
  private val worker = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "ReadAlongSpeech").apply { isDaemon = true }
  }.apply { setExecuteExistingDelayedTasksAfterShutdownPolicy(false); removeOnCancelPolicy = true }
  private var handle = 0L
  private var modelKey: String? = null
  private var active: CancellableContinuation<List<SpeechWord>>? = null
  private var idleRelease: ScheduledFuture<*>? = null
  private var closed = false
  @Volatile var contextLoads = 0
    private set
  @Volatile var contextReleases = 0
    private set
  private external fun nativeCreate(path: String): Long
  private external fun nativeTranscribe(handle: Long, pcm: FloatArray): Array<SpeechWord>
  private external fun nativeResetCancel(handle: Long)
  private external fun nativeCancel(handle: Long)
  private external fun nativeDestroy(handle: Long)

  override suspend fun transcribe(model: File, pcm: FloatArray): List<SpeechWord> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
      synchronized(lock) { if (active === continuation && handle != 0L) nativeCancel(handle) }
    }
    synchronized(lock) {
      if (closed) {
        continuation.resumeWithException(IllegalStateException("Speech matcher is closed."))
        return@suspendCancellableCoroutine
      }
      idleRelease?.cancel(false)
      worker.execute {
        try {
          if (!continuation.isActive) return@execute
          check(!synchronized(lock) { closed }) { "Speech matcher is closed." }
          System.loadLibrary("rivulet_whisper")
          val key = "${model.absolutePath}:${model.length()}:${model.lastModified()}"
          if (modelKey != key || handle == 0L) {
            destroyContext()
            val began = android.os.SystemClock.elapsedRealtime()
            val created = nativeCreate(model.absolutePath)
            check(created != 0L) { "Speech model could not be loaded. Download it again." }
            synchronized(lock) { handle = created; modelKey = key }
            contextLoads++
            Log.d("ReadAlongPerf", "modelLoadMs=${android.os.SystemClock.elapsedRealtime()-began} loads=$contextLoads")
          }
          val run = synchronized(lock) {
            if (closed || !continuation.isActive) false else {
              nativeResetCancel(handle)
              active = continuation
              true
            }
          }
          if (!run) {
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Speech matcher is closed."))
            return@execute
          }
          val began = android.os.SystemClock.elapsedRealtime()
          val words = nativeTranscribe(handle, pcm).toList()
          Log.d("ReadAlongPerf", "nativeRecognitionMs=${android.os.SystemClock.elapsedRealtime()-began} samples=${pcm.size}")
          if (continuation.isActive) continuation.resume(words)
        } catch (error: Throwable) {
          if (continuation.isActive) continuation.resumeWithException(IllegalStateException(
            if (error is OutOfMemoryError) "Not enough memory for speech matching. Close other apps or use estimated timing."
            else "Speech matching failed: ${error.message ?: error.javaClass.simpleName}", error))
        } finally {
          synchronized(lock) {
            if (active === continuation) active = null
            idleRelease?.cancel(false)
            if (!closed) idleRelease = worker.schedule({ destroyContext() }, idleMillis, TimeUnit.MILLISECONDS)
          }
        }
      }
    }
  }

  // Called on the worker only; never frees an in-use native pointer.
  private fun destroyContext() {
    synchronized(lock) {
      if (handle != 0L) {
        nativeDestroy(handle); handle = 0L; modelKey = null; contextReleases++
        Log.d("ReadAlongPerf", "contextReleased=$contextReleases")
      }
    }
  }

  override fun release() {
    synchronized(lock) {
      if (!closed) {
        idleRelease?.cancel(false)
        worker.execute { destroyContext() }
      }
    }
  }

  override fun close() {
    synchronized(lock) {
      if (closed) return
      closed = true
      idleRelease?.cancel(false)
      if (active != null && handle != 0L) nativeCancel(handle)
      // Shut down only after cleanup executes: the executor drops delayed work on shutdown.
      worker.execute { destroyContext(); worker.shutdown() }
    }
  }
}

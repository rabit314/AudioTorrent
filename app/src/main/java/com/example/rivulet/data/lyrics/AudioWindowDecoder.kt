package com.example.rivulet.data.lyrics

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface ReadAlongAudioDecoder {
  suspend fun decode(track: ReadAlongTrack, startMs: Long, durationMs: Long): FloatArray
  fun release() {}
}

class AudioWindowDecoder(
  private val idleMillis: Long,
  private val dataSource: suspend (ReadAlongTrack) -> MediaDataSource,
) : ReadAlongAudioDecoder {
  constructor(dataSource: suspend (ReadAlongTrack) -> MediaDataSource) : this(15_000L, dataSource)
  private data class DecoderSession(
    val track: ReadAlongTrack,
    val extractor: MediaExtractor,
    val codec: MediaCodec,
    val source: MediaDataSource,
    var rate: Int,
    var channels: Int,
    var encoding: Int,
  ) {
    fun closeQuietly() {
      runCatching { codec.stop() }
      runCatching { codec.release() }
      runCatching { extractor.release() }
      runCatching { source.close() }
    }
  }

  private val mutex = Mutex()
  private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var session: DecoderSession? = null
  private var idleJob: Job? = null

  override suspend fun decode(track: ReadAlongTrack, startMs: Long, durationMs: Long): FloatArray = withContext(Dispatchers.IO) {
    require(durationMs in 1..30_000) { "Audio analysis windows must be bounded." }
    idleJob?.cancel()
    idleJob = null
    mutex.withLock {
      val reusable = session?.takeIf { it.track == track }
      if (reusable != null) {
        try {
          val pcm = decodeWithSession(reusable, startMs, durationMs, reused = true)
          scheduleIdleRelease()
          return@withLock pcm
        } catch (error: CancellationException) {
          closeSessionLocked()
          throw error
        } catch (_: Throwable) {
          closeSessionLocked()
        }
      } else if (session != null) {
        closeSessionLocked()
      }

      val fresh = openSession(track)
      session = fresh
      try {
        val pcm = decodeWithSession(fresh, startMs, durationMs, reused = false)
        scheduleIdleRelease()
        pcm
      } catch (error: Throwable) {
        closeSessionLocked()
        throw error
      }
    }
  }

  override fun release() {
    idleJob?.cancel()
    idleJob = null
    idleScope.launch {
      mutex.withLock { closeSessionLocked() }
    }
  }

  private fun scheduleIdleRelease() {
    idleJob?.cancel()
    if (idleMillis <= 0L) return
    idleJob = idleScope.launch {
      delay(idleMillis)
      mutex.withLock { closeSessionLocked() }
    }
  }

  private fun closeSessionLocked() {
    session?.closeQuietly()
    session = null
  }

  private suspend fun openSession(track: ReadAlongTrack): DecoderSession {
    val began = android.os.SystemClock.elapsedRealtime()
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    val source = dataSource(track)
    try {
      extractor.setDataSource(source)
      val audioIndex = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      } ?: error("This file has no decodable audio track.")
      val format = extractor.getTrackFormat(audioIndex)
      extractor.selectTrack(audioIndex)
      val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
      codec = decoder
      decoder.configure(format, null, null, 0)
      decoder.start()
      val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      val setupMs = android.os.SystemClock.elapsedRealtime() - began
      android.util.Log.d("ReadAlongPerf", "decodeOpenMs=$setupMs rate=$rate channels=$channels")
      return DecoderSession(track, extractor, decoder, source, rate, channels, AudioFormat.ENCODING_PCM_16BIT)
    } catch (error: Throwable) {
      codec?.let {
        runCatching { it.stop() }
        runCatching { it.release() }
      }
      runCatching { extractor.release() }
      runCatching { source.close() }
      throw error
    }
  }

  private suspend fun decodeWithSession(
    active: DecoderSession,
    startMs: Long,
    durationMs: Long,
    reused: Boolean,
  ): FloatArray {
    val began = android.os.SystemClock.elapsedRealtime()
    val extractor = active.extractor
    val decoder = active.codec
    if (reused) decoder.flush()
    extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    val seekReady = android.os.SystemClock.elapsedRealtime()
    val resampler = Pcm16kResampler(startMs, durationMs)
    val endUs = (startMs + durationMs) * 1000
    var inputEnded = false
    var outputEnded = false
    val info = MediaCodec.BufferInfo()
    var readNs = 0L
    var resampleNs = 0L
    var outputBuffers = 0
    val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
    while (!outputEnded && resampler.size < resampler.samples.size) {
      currentCoroutineContext().ensureActive()
      check(android.os.SystemClock.elapsedRealtime() < deadline) { "Audio decoding timed out. Try again after more audio has downloaded." }
      var queued = false
      if (!inputEnded) {
        // Fill all available input slots before draining output. Waiting 10ms after each AAC
        // packet serializes an otherwise asynchronous codec and costs seconds per passage.
        for (slot in 0 until 8) {
          val index = decoder.dequeueInputBuffer(0)
          if (index < 0) break
          val input = decoder.getInputBuffer(index)!!
          val reading = System.nanoTime()
          val size = extractor.readSampleData(input, 0)
          readNs += System.nanoTime() - reading
          val timeUs = extractor.sampleTime
          if (size < 0 || timeUs >= endUs + 200_000) {
            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEnded = true
          } else {
            decoder.queueInputBuffer(index, 0, size, timeUs, 0)
            extractor.advance()
          }
          queued = true
          if (inputEnded) break
        }
      }
      var waitUs = if (queued) 0L else 1000L
      while (!outputEnded && resampler.size < resampler.samples.size) {
        when (val index = decoder.dequeueOutputBuffer(info, waitUs)) {
          MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val output = decoder.outputFormat
            active.rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            active.channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            active.encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            check(active.encoding == AudioFormat.ENCODING_PCM_16BIT || active.encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported decoder PCM format." }
          }
          else -> if (index >= 0) {
            val output = decoder.getOutputBuffer(index)!!
            val sampling = System.nanoTime()
            resampler.append(output, info.offset, info.size, info.presentationTimeUs, active.rate, active.channels, active.encoding == AudioFormat.ENCODING_PCM_FLOAT)
            resampleNs += System.nanoTime() - sampling
            outputBuffers++
            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            decoder.releaseOutputBuffer(index, false)
          } else break
        }
        waitUs = 0
      }
    }
    check(resampler.size >= 1600) { "Not enough audio is available for speech matching yet." }
    android.util.Log.d(
      "ReadAlongPerf",
      "decodeSetupMs=${seekReady-began} decodeReused=$reused codecLoopMs=${android.os.SystemClock.elapsedRealtime()-seekReady} readMs=${readNs/1_000_000} resampleMs=${resampleNs/1_000_000} buffers=$outputBuffers rate=${active.rate} channels=${active.channels} samples=${resampler.size}",
    )
    return if (resampler.size == resampler.samples.size) resampler.samples else resampler.samples.copyOf(resampler.size)
  }
}

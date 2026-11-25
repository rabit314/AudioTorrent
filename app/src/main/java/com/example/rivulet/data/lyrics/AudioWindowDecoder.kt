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
    var cacheStartSample: Long = -1L,
    var cacheEndSample: Long = -1L,
    var cacheSamples: FloatArray = FloatArray(0),
    var nextUs: Double = 0.0,
    var outputEnded: Boolean = false,
  ) {
    fun resetCursor() {
      cacheStartSample = -1L
      cacheEndSample = -1L
      cacheSamples = FloatArray(0)
      nextUs = 0.0
      outputEnded = false
    }

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
    val reqStartSample = startMs * 16L
    val targetSamples = (durationMs * 16L).toInt()
    val slice = if (reused) sliceCachedPrefix(active, reqStartSample) else null

    if (slice != null && (slice.size >= targetSamples || active.outputEnded)) {
      val result = if (slice.size > targetSamples) slice.copyOf(targetSamples) else slice
      check(result.size >= 1600) { "Not enough audio is available for speech matching yet." }
      android.util.Log.d(
        "ReadAlongPerf",
        "decodeSetupMs=${android.os.SystemClock.elapsedRealtime()-began} decodeReused=true decodeSequential=true cacheHit=true codecLoopMs=0 samples=${result.size}",
      )
      return result
    }

    val sequentialContinue = slice != null && !active.outputEnded
    val extractor = active.extractor
    val decoder = active.codec
    val prefixCount = slice?.size ?: 0
    val neededFromCodec = (targetSamples - prefixCount).coerceAtLeast(0)
    val startNextUs: Double

    if (sequentialContinue) {
      startNextUs = active.nextUs
    } else {
      active.resetCursor()
      if (reused) decoder.flush()
      extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      startNextUs = startMs * 1000.0
    }

    val seekReady = android.os.SystemClock.elapsedRealtime()
    // Allow 500 ms headroom so the final codec packet completes cleanly and feeds the trailing overlap cache.
    val resampler = Pcm16kResampler(maxSamples = neededFromCodec + TAIL_HEADROOM_SAMPLES, initialNextUs = startNextUs)
    var feedStopUs = (startMs + durationMs) * 1000L + 60_000L
    var inputEnded = false
    var inputPaused = false
    var stallTicks = 0
    val info = MediaCodec.BufferInfo()
    var readNs = 0L
    var resampleNs = 0L
    var outputBuffers = 0
    val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
    while (!active.outputEnded && resampler.size < neededFromCodec) {
      currentCoroutineContext().ensureActive()
      check(android.os.SystemClock.elapsedRealtime() < deadline) { "Audio decoding timed out. Try again after more audio has downloaded." }
      var queued = false
      if (!inputEnded && !inputPaused) {
        // Check extractor.sampleTime before dequeueInputBuffer so we never hold an unqueued buffer across sequential windows.
        for (slot in 0 until 8) {
          val peekUs = extractor.sampleTime
          if (peekUs >= feedStopUs) {
            inputPaused = true
            break
          }
          val index = decoder.dequeueInputBuffer(0)
          if (index < 0) break
          val input = decoder.getInputBuffer(index)!!
          val reading = System.nanoTime()
          val size = extractor.readSampleData(input, 0)
          readNs += System.nanoTime() - reading
          val timeUs = extractor.sampleTime
          if (size < 0) {
            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEnded = true
            break
          } else {
            decoder.queueInputBuffer(index, 0, size, timeUs, 0)
            extractor.advance()
          }
          queued = true
        }
      }
      var waitUs = if (queued) 0L else 1000L
      var drainedAny = false
      while (!active.outputEnded && resampler.size < neededFromCodec) {
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
            drainedAny = true
            active.outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            decoder.releaseOutputBuffer(index, false)
          } else break
        }
        waitUs = 0
      }
      if (!drainedAny && inputPaused && !inputEnded) {
        stallTicks++
        if (stallTicks >= 5) {
          feedStopUs += 100_000L
          inputPaused = false
          stallTicks = 0
        }
      } else if (drainedAny) {
        stallTicks = 0
      }
    }

    val decodedCount = resampler.size
    val returnedFromCodec = minOf(decodedCount, neededFromCodec)
    val totalReturned = prefixCount + returnedFromCodec
    check(totalReturned >= 1600) { "Not enough audio is available for speech matching yet." }

    val result = if (prefixCount == 0 && returnedFromCodec == decodedCount) {
      resampler.samples.copyOf(returnedFromCodec)
    } else if (prefixCount == 0) {
      resampler.samples.copyOf(returnedFromCodec)
    } else {
      FloatArray(totalReturned).also { combined ->
        slice!!.copyInto(combined, destinationOffset = 0)
        if (returnedFromCodec > 0) {
          resampler.samples.copyInto(combined, destinationOffset = prefixCount, startIndex = 0, endIndex = returnedFromCodec)
        }
      }
    }

    updateTrailingCache(
      active = active,
      reqStartSample = reqStartSample,
      prefix = slice,
      decoded = resampler.samples,
      decodedSize = decodedCount,
      nextUs = resampler.nextUs,
    )

    android.util.Log.d(
      "ReadAlongPerf",
      "decodeSetupMs=${seekReady-began} decodeReused=$reused decodeSequential=$sequentialContinue prefixSamples=$prefixCount codecLoopMs=${android.os.SystemClock.elapsedRealtime()-seekReady} readMs=${readNs/1_000_000} resampleMs=${resampleNs/1_000_000} buffers=$outputBuffers rate=${active.rate} channels=${active.channels} samples=${result.size}",
    )
    return result
  }

  private fun sliceCachedPrefix(active: DecoderSession, reqStartSample: Long): FloatArray? {
    if (active.cacheStartSample < 0L || reqStartSample < active.cacheStartSample || reqStartSample > active.cacheEndSample) {
      return null
    }
    val offset = (reqStartSample - active.cacheStartSample).toInt()
    if (offset >= active.cacheSamples.size) return FloatArray(0)
    return active.cacheSamples.copyOfRange(offset, active.cacheSamples.size)
  }

  private fun updateTrailingCache(
    active: DecoderSession,
    reqStartSample: Long,
    prefix: FloatArray?,
    decoded: FloatArray,
    decodedSize: Int,
    nextUs: Double,
  ) {
    val combinedSize = (prefix?.size ?: 0) + decodedSize
    val tailKeep = minOf(combinedSize, MAX_TAIL_CACHE_SAMPLES)
    if (tailKeep <= 0) {
      active.resetCursor()
      return
    }
    val tail = stitchTrailingSamples(prefix, decoded, decodedSize, MAX_TAIL_CACHE_SAMPLES)
    val endSample = reqStartSample + combinedSize
    active.cacheStartSample = endSample - tail.size
    active.cacheEndSample = endSample
    active.cacheSamples = tail
    active.nextUs = nextUs
  }

  companion object {
    private const val MAX_TAIL_CACHE_SAMPLES = 8 * 16_000
    private const val TAIL_HEADROOM_SAMPLES = 8_000

    internal fun stitchTrailingSamples(
      prefix: FloatArray?,
      decoded: FloatArray,
      decodedSize: Int,
      maxTailSamples: Int = MAX_TAIL_CACHE_SAMPLES,
    ): FloatArray {
      val prefixLen = prefix?.size ?: 0
      val total = prefixLen + decodedSize
      val keep = minOf(total, maxTailSamples)
      if (keep <= 0) return FloatArray(0)
      val out = FloatArray(keep)
      val drop = total - keep
      var dest = 0
      if (prefix != null && drop < prefixLen) {
        val fromPrefix = prefixLen - drop
        prefix.copyInto(out, destinationOffset = 0, startIndex = drop, endIndex = prefixLen)
        dest = fromPrefix
      }
      val decodedStart = (drop - prefixLen).coerceAtLeast(0)
      if (decodedStart < decodedSize) {
        decoded.copyInto(out, destinationOffset = dest, startIndex = decodedStart, endIndex = decodedSize)
      }
      return out
    }
  }
}

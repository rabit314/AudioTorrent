package com.example.rivulet.data.lyrics

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

fun interface ReadAlongAudioDecoder {
  suspend fun decode(track: ReadAlongTrack, startMs: Long, durationMs: Long): FloatArray
}

class AudioWindowDecoder(private val dataSource: suspend (ReadAlongTrack) -> MediaDataSource) : ReadAlongAudioDecoder {
  override suspend fun decode(track: ReadAlongTrack, startMs: Long, durationMs: Long): FloatArray = withContext(Dispatchers.IO) {
    val began = android.os.SystemClock.elapsedRealtime()
    require(durationMs in 1..30_000) { "Audio analysis windows must be bounded." }
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
      extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
      codec = decoder
      decoder.configure(format, null, null, 0)
      decoder.start()
      val configured = android.os.SystemClock.elapsedRealtime()
      var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      var encoding = AudioFormat.ENCODING_PCM_16BIT
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
              rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
              channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
              encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
              check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported decoder PCM format." }
            }
            else -> if (index >= 0) {
              val output = decoder.getOutputBuffer(index)!!
              val sampling = System.nanoTime()
              resampler.append(output, info.offset, info.size, info.presentationTimeUs, rate, channels, encoding == AudioFormat.ENCODING_PCM_FLOAT)
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
      android.util.Log.d("ReadAlongPerf", "decodeSetupMs=${configured-began} codecLoopMs=${android.os.SystemClock.elapsedRealtime()-configured} readMs=${readNs/1_000_000} resampleMs=${resampleNs/1_000_000} buffers=$outputBuffers rate=$rate channels=$channels samples=${resampler.size}")
      resampler.samples.copyOf(resampler.size)
    } finally {
      codec?.let { runCatching { it.stop() }; it.release() }
      extractor.release()
      source.close()
    }
  }
}

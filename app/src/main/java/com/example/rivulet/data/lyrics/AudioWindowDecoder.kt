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
import java.nio.ByteOrder

fun interface ReadAlongAudioDecoder {
  suspend fun decode(track: ReadAlongTrack, startMs: Long, durationMs: Long): FloatArray
}

class AudioWindowDecoder(private val dataSource: suspend (ReadAlongTrack) -> MediaDataSource) : ReadAlongAudioDecoder {
  override suspend fun decode(track: ReadAlongTrack, startMs: Long, durationMs: Long): FloatArray = withContext(Dispatchers.IO) {
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
      var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      var encoding = AudioFormat.ENCODING_PCM_16BIT
      val result = FloatArray((durationMs * 16).toInt())
      var written = 0
      var nextSampleUs = startMs * 1000.0
      val endUs = (startMs + durationMs) * 1000
      var inputEnded = false
      var outputEnded = false
      val info = MediaCodec.BufferInfo()
      val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
      while (!outputEnded && written < result.size) {
        currentCoroutineContext().ensureActive()
        check(android.os.SystemClock.elapsedRealtime() < deadline) { "Audio decoding timed out. Try again after more audio has downloaded." }
        if (!inputEnded) {
          val index = decoder.dequeueInputBuffer(10_000)
          if (index >= 0) {
            val input = decoder.getInputBuffer(index)!!
            val size = extractor.readSampleData(input, 0)
            val timeUs = extractor.sampleTime
            if (size < 0 || timeUs >= endUs + 200_000) {
              decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputEnded = true
            } else {
              decoder.queueInputBuffer(index, 0, size, timeUs, 0)
              extractor.advance()
            }
          }
        }
        when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
          MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val output = decoder.outputFormat
            rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported decoder PCM format." }
          }
          else -> if (index >= 0) {
            val output = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
            output.position(info.offset); output.limit(info.offset + info.size)
            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val frames = info.size / (bytesPerSample * channels)
            for (frame in 0 until frames) {
              var mono = 0f
              repeat(channels) { mono += if (bytesPerSample == 4) output.float else output.short.toFloat() / 32768f }
              mono /= channels
              val timeUs = info.presentationTimeUs + frame * 1_000_000.0 / rate
              if (timeUs >= endUs) break
              if (timeUs >= nextSampleUs && written < result.size) {
                result[written++] = mono.coerceIn(-1f, 1f)
                nextSampleUs += 1_000_000.0 / 16_000
              }
            }
            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            decoder.releaseOutputBuffer(index, false)
          }
        }
      }
      check(written >= 1600) { "Not enough audio is available for speech matching yet." }
      result.copyOf(written)
    } finally {
      codec?.let { runCatching { it.stop() }; it.release() }
      extractor.release()
      source.close()
    }
  }
}

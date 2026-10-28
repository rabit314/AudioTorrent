package com.example.rivulet.data.lyrics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor

/** Visits only required 16kHz output samples, not every channel of every source frame. */
internal class Pcm16kResampler(startMs: Long, durationMs: Long) {
  val samples = FloatArray((durationMs * 16).toInt())
  var size = 0
    private set
  private var nextUs = startMs * 1000.0
  private val endUs = (startMs + durationMs) * 1000.0
  private var shorts = ShortArray(0)
  private var floats = FloatArray(0)

  fun append(buffer: ByteBuffer, offset: Int, byteCount: Int, timeUs: Long, rate: Int, channels: Int, floating: Boolean) {
    require(rate > 0 && channels > 0)
    val bytes = if (floating) 4 else 2
    val stride = bytes * channels
    val frames = byteCount / stride
    if (frames == 0) return
    val limitUs = minOf(endUs, timeUs + frames * 1_000_000.0 / rate)
    if (nextUs >= limitUs) return // Includes whole buffers before the requested seek position.
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.limit(offset + byteCount); buffer.position(offset)
    val count = frames * channels
    // Bulk-copy direct codec buffers once. Per-sample JNI memory reads are costly on Android.
    if (floating) {
      if (floats.size < count) floats = FloatArray(count)
      buffer.asFloatBuffer().get(floats, 0, count)
    } else {
      if (shorts.size < count) shorts = ShortArray(count)
      buffer.asShortBuffer().get(shorts, 0, count)
    }
    fun mono(frame: Int): Float {
      var value = 0f
      val base = frame * channels
      for (channel in 0 until channels) value += if (floating) floats[base + channel] else shorts[base + channel] / 32768f
      return value / channels
    }
    while (nextUs < limitUs && size < samples.size) {
      val position = ((nextUs - timeUs) * rate / 1_000_000.0).coerceAtLeast(0.0)
      val value = if (rate >= 16_000) {
        // Match the old first-source-frame-at/after-target policy without scanning discarded frames.
        val frame = ceil(position - 1e-8).toInt()
        if (frame >= frames) break
        mono(frame)
      } else {
        // Low-rate input needs actual upsampling to retain speech duration/pitch at 16kHz.
        val first = floor(position).toInt().coerceAtMost(frames - 1)
        val a = mono(first)
        a + (mono((first + 1).coerceAtMost(frames - 1)) - a) * (position - first).toFloat()
      }
      samples[size++] = value.coerceIn(-1f, 1f)
      nextUs += 62.5
    }
  }
}

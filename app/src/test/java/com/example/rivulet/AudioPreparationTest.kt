package com.example.rivulet

import com.example.rivulet.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPreparationTest {
  @Test fun requiredSampleAccessMatchesLegacyDownsamplingAndSkipsPreSeekAudio() {
    val buffer = ByteBuffer.allocate(44_100 * 4).order(ByteOrder.LITTLE_ENDIAN)
    repeat(44_100) { i -> buffer.putShort((i % 5000).toShort()); buffer.putShort((-i % 4000).toShort()) }
    val resampler = Pcm16kResampler(250, 500)
    resampler.append(buffer, 0, buffer.capacity(), 0, 44_100, 2, false)
    assertEquals(8_000, resampler.size)
    var nextUs = 250_000.0
    var at = 0
    repeat(44_100) { frame ->
      if (frame * 1_000_000.0 / 44_100 >= nextUs && at < 8_000) {
        val expected = (buffer.getShort(frame * 4) / 32768f + buffer.getShort(frame * 4 + 2) / 32768f) / 2
        assertEquals(expected, resampler.samples[at++], 0f)
        nextUs += 62.5
      }
    }
    assertEquals(8_000, at)
  }

  @Test fun lowRateStereoAndFloatInputRetainDurationAndPitch() {
    val buffer = ByteBuffer.allocate(8_000 * 4).order(ByteOrder.LITTLE_ENDIAN)
    repeat(8_000) { i -> buffer.putShort((i % 1000).toShort()); buffer.putShort((i % 1000).toShort()) }
    val pcm = Pcm16kResampler(0, 1000)
    pcm.append(buffer, 0, buffer.capacity(), 0, 8_000, 2, false)
    assertEquals(16_000, pcm.size)
    assertEquals(0.5f / 32768f, pcm.samples[1], 1e-8f)
    val floats = ByteBuffer.allocate(16_000 * 4).order(ByteOrder.LITTLE_ENDIAN)
    repeat(16_000) { floats.putFloat(0.25f) }
    val direct = Pcm16kResampler(0, 1000)
    direct.append(floats, 0, floats.capacity(), 0, 16_000, 1, true)
    assertEquals(16_000, direct.size); assertEquals(0.25f, direct.samples.last(), 0f)
  }

  @Test fun overlappingBatchesCoverTheWindowAndPrioritizeThePlayhead() {
    val batches = SpeechBatches.plan(30 * 16_000, 25_000)
    assertEquals(18_000, batches.first().startMs)
    assertEquals(listOf(0L, 9_000L, 18_000L), batches.map { it.startMs }.sorted())
    assertEquals(1, SpeechBatches.plan(11 * 16_000, 5_000).size)
    val a = batches.first { it.startMs == 0L }; val b = batches.first { it.startMs == 9_000L }
    val merged = SpeechBatches.merge(mapOf(a to listOf(SpeechWord("before", 8000, 8500), SpeechWord("overlap", 10_000, 10_400)),
      b to listOf(SpeechWord("overlap", 10_000, 10_400), SpeechWord("after", 11_000, 11_500))))
    assertEquals(listOf("before", "overlap", "after"), merged.map { it.text })
  }

  @Test fun timestampRoundingAcrossCodecBuffersDoesNotDropSamples() {
    val rate = 44_100
    val buffer = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(rate) { buffer.putShort(8192) }
    val pcm = Pcm16kResampler(0, 1000)
    var frame = 0
    while (frame < rate) {
      val count = minOf(1024, rate - frame)
      pcm.append(buffer, frame * 2, count * 2, kotlin.math.round(frame * 1_000_000.0 / rate).toLong(), rate, 1, false)
      frame += count
    }
    assertEquals(16_000, pcm.size)
    assertTrue(pcm.samples.all { it == 0.25f })
  }
}

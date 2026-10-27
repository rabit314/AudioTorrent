package com.example.rivulet.readalong

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rivulet.data.lyrics.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WarmSpeechEngineTest {
  // The upstream public-domain fixture is 16 kHz mono PCM16 WAV.
  private fun pcm(): FloatArray {
    val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("readalong/jfk.wav").use { it.readBytes() }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var at = 12
    while (at + 8 < bytes.size) {
      val size = buffer.getInt(at + 4)
      if (String(bytes, at, 4, Charsets.US_ASCII) == "data") {
        buffer.position(at + 8)
        return FloatArray(size / 2) { buffer.short / 32768f }
      }
      at += 8 + size + (size and 1)
    }
    error("Fixture has no PCM chunk")
  }

  @Test fun warmContextSurvivesCancellationAndReleasesOnRequestAndIdle() = runBlocking {
    val model = SpeechModelManager(ApplicationProvider.getApplicationContext()).file
    assertTrue(model.isFile)
    val pcm = pcm()
    val engine = WhisperEngine()
    try {
      val began = android.os.SystemClock.elapsedRealtime()
      val cold = engine.transcribe(model, pcm)
      val middle = android.os.SystemClock.elapsedRealtime()
      val warm = engine.transcribe(model, pcm)
      val ended = android.os.SystemClock.elapsedRealtime()
      Log.i("ReadAlongBenchmark", "coldMs=${middle-began} warmMs=${ended-middle} loads=${engine.contextLoads}")
      assertTrue(cold.isNotEmpty()); assertEquals(cold, warm); assertEquals(1, engine.contextLoads)
      val cancelled = launch(Dispatchers.Default) { engine.transcribe(model, pcm + pcm + pcm) }
      delay(100); cancelled.cancelAndJoin()
      assertEquals(warm, engine.transcribe(model, pcm))
      assertEquals(1, engine.contextLoads)
      engine.release()
      withTimeout(10_000) { while (engine.contextReleases != 1) delay(10) }
      assertEquals(warm, engine.transcribe(model, pcm)); assertEquals(2, engine.contextLoads)
    } finally { engine.close() }
    withTimeout(10_000) { while (engine.contextReleases != engine.contextLoads) delay(10) }
    val idle = WhisperEngine(idleMillis = 100)
    try {
      assertTrue(idle.transcribe(model, pcm).isNotEmpty())
      withTimeout(10_000) { while (idle.contextReleases != 1) delay(10) }
    } finally { idle.close() }
  }
}

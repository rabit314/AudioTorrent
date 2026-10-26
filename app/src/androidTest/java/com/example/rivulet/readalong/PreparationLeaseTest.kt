package com.example.rivulet.readalong

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.torrent.TorrentEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.libtorrent4j.Priority
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

class PreparationLeaseTest {
  @Test fun idleSelectedStreamCanResumeAndOldUrlCannotReplaceNewSelection() = runBlocking {
    val context: Context = ApplicationProvider.getApplicationContext()
    val name = "idle-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name)
    val engine = TorrentEngine(context, db, streamIdleTimeoutMs = 50)
    var a: String? = null; var b: String? = null
    try {
      a = engine.addTorrentFile(torrent("idle-a-${UUID.randomUUID()}.wav")).getOrThrow()
      b = engine.addTorrentFile(torrent("idle-b-${UUID.randomUUID()}.wav")).getOrThrow()
      val first = engine.prepareStream(a, 0)
      engine.acquireStream(a, 0); engine.releaseStream(first)
      withTimeout(5000) { while (!first.isClosed) delay(10) }
      val resumed = engine.acquireStream(a, 0)
      assertFalse(resumed.isClosed); assertNotSame(first, resumed)
      val next = engine.prepareStream(b, 0)
      assertTrue(runCatching { engine.acquireStream(a, 0) }.isFailure)
      engine.releaseStream(resumed) // Previous HTTP completion must not schedule cleanup of next.
      delay(100); assertFalse(next.isClosed)
      engine.abandonPreparation(next)
    } finally {
      a?.let { engine.removeTorrent(it, true) }; b?.let { engine.removeTorrent(it, true) }
      engine.shutdown(); db.close(); context.deleteDatabase(name)
    }
  }

  @Test fun abandonedPreparationRestoresOfflinePriorityAndCannotCloseNewerStream() = runBlocking {
    val context: Context = ApplicationProvider.getApplicationContext()
    val name = "lease-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name)
    val engine = TorrentEngine(context, db)
    var a: String? = null; var b: String? = null
    try {
      a = engine.addTorrentFile(torrent("public-a-${UUID.randomUUID()}.wav")).getOrThrow()
      b = engine.addTorrentFile(torrent("public-b-${UUID.randomUUID()}.wav")).getOrThrow()
      db.saveFileWanted(a, 0, true)
      val first = engine.prepareStream(a, 0)
      assertEquals(Priority.TOP_PRIORITY, engine.calculateEffectivePriority(a, 0))
      val second = engine.prepareStream(b, 0)
      engine.abandonPreparation(first)
      assertTrue(first.isClosed); assertFalse(second.isClosed)
      assertEquals(Priority.DEFAULT, engine.calculateEffectivePriority(a, 0))
      val acquired = engine.acquireStream(b, 0)
      engine.abandonPreparation(second)
      assertFalse(second.isClosed) // HTTP owns this lease now.
      engine.releaseStream(acquired)
      engine.abandonPreparation(second)
      assertTrue(second.isClosed)
      assertEquals(Priority.IGNORE, engine.calculateEffectivePriority(b, 0))
      assertTrue(runCatching { engine.acquireStream(a, 0) }.isFailure) // Obsolete URL cannot take ownership back.
      val wanted = engine.prepareStream(a, 0)
      engine.abandonPreparation(wanted)
      assertTrue(wanted.isClosed); assertEquals(Priority.DEFAULT, engine.calculateEffectivePriority(a, 0))
    } finally {
      a?.let { engine.removeTorrent(it, true) }; b?.let { engine.removeTorrent(it, true) }
      engine.shutdown(); db.close(); context.deleteDatabase(name)
    }
  }

  /** Generated, private one-piece torrent of invented zero bytes; no external media or trackers. */
  private fun torrent(name: String): ByteArray = ByteArrayOutputStream().apply {
    write("d4:infod6:lengthi16384e4:name${name.length}:${name}12:piece lengthi16384e6:pieces20:".toByteArray())
    write(MessageDigest.getInstance("SHA-1").digest(ByteArray(16384)))
    write("ee".toByteArray())
  }.toByteArray()
}

package com.example.audiotorrent.readalong

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import com.example.audiotorrent.data.TorrentDatabase
import com.example.audiotorrent.data.lyrics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class PdfAndMultiSourceAndroidTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Test fun androidPdfBoxExtractsRealPdfIntoReadAlongRepository() = runBlocking {
    val pdfBytes = ByteArrayOutputStream().use { out ->
      val doc = PdfDocument()
      val paint = Paint().apply { textSize = 12f }
      val page1 = doc.startPage(PdfDocument.PageInfo.Builder(400, 600, 1).create())
      page1.canvas.drawText("Chapter 1", 40f, 60f, paint)
      page1.canvas.drawText("Call me Ishmael. Some years ago having little money in my purse.", 40f, 90f, paint)
      doc.finishPage(page1)

      val page2 = doc.startPage(PdfDocument.PageInfo.Builder(400, 600, 2).create())
      page2.canvas.drawText("Chapter 2", 40f, 60f, paint)
      page2.canvas.drawText("I stuffed a shirt or two into my old carpet bag and started for Cape Horn.", 40f, 90f, paint)
      doc.finishPage(page2)

      doc.writeTo(out)
      doc.close()
      out.toByteArray()
    }

    val dbName = "pdf-import-${UUID.randomUUID()}.db"
    val db = TorrentDatabase(context, dbName)
    val repo = ReadAlongRepository(context, ReadAlongStore(db), AudioWindowDecoder { error("No audio needed") })
    val track = ReadAlongTrack("pdf-hash", 0)
    try {
      withContext(Dispatchers.Main) {
        repo.activate(track)
        repo.importBytes(pdfBytes, "Moby Dick.pdf", "application/pdf", setOf(0))
      }
      withTimeout(15_000) { repo.state.first { it.phase == ReadAlongPhase.READY } }
      val state = repo.state.value
      assertEquals("Moby Dick", state.title)
      assertTrue(state.lines.any { it.text.contains("Ishmael", ignoreCase = true) })
      assertTrue(state.lines.any { it.text.contains("carpet", ignoreCase = true) })
    } finally {
      withContext(Dispatchers.Main) { repo.close() }
      db.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test fun renderedWeLibAndAnnasArchivePagesPopulateEditionsCleanly() = runBlocking {
    val dbName = "rendered-sources-${UUID.randomUUID()}.db"
    val db = TorrentDatabase(context, dbName)
    val accessCatalog = object : BookCatalog {
      override fun search(query: String): List<BookEdition> = emptyList()
      override fun lookup(request: BookLookupRequest): BookLookupResult = when (request.provider) {
        TextProvider.WELIB -> throw SiteAccessRequired("https://welib.gl/search?q=${request.query}")
        TextProvider.ANNAS_ARCHIVE -> throw SiteAccessRequired("https://annas-archive.gl/search?q=${request.query}")
        else -> BookLookupResult(emptyList())
      }
      override fun download(edition: BookEdition, renderedHtml: String?) = error("unused")
    }
    val repo = ReadAlongRepository(context, ReadAlongStore(db), AudioWindowDecoder { error("unused") }, library = accessCatalog)
    val track = ReadAlongTrack("rendered-sources", 0)
    try {
      withContext(Dispatchers.Main) {
        repo.activate(track)
        repo.searchBooks("Moby Dick", provider = TextProvider.WELIB)
      }
      withTimeout(5000) { repo.state.first { it.accessUrl?.contains("welib.gl") == true } }

      val weLibHtml = """
        <div id="aarecord-list"><div class="book-list"><div class="book-card">
          <a href="/md5/024c0b8c27b919a25be0b9303f050405"><h2>Moby-Dick</h2></a>
          <a href="/search?q=Herman%20Melville">Herman Melville</a>
          <div class="mb-1"><span class="uppercase">epub</span><span>· English</span><span>· 1.5 MB</span></div>
        </div></div></div>
      """.trimIndent()
      withContext(Dispatchers.Main) { repo.resumeSiteAccess(weLibHtml, "https://welib.gl/search?q=Moby+Dick", track) }
      assertEquals(TextProvider.WELIB, repo.state.value.editions.single().provider)
      assertNull(repo.state.value.accessUrl)

      withContext(Dispatchers.Main) { repo.searchBooks("Moby Dick", provider = TextProvider.ANNAS_ARCHIVE) }
      withTimeout(5000) { repo.state.first { it.accessUrl?.contains("annas-archive.gl") == true } }

      val annasHtml = """
        <div class="js-aarecord-list-outer"><div class="flex"><div>
          <a href="/md5/077484a10743e5dd5d151013e8c732f4" class="js-vim-focus">Moby-Dick Or the Whale</a>
          <a href="/search?q=Melville"><span class="icon-[mdi--user-edit]"></span> Melville, Herman</a>
          <div class="text-gray-800">✅ English [en] · PDF · 2.1MB · 1851</div>
        </div></div></div>
      """.trimIndent()
      withContext(Dispatchers.Main) { repo.resumeSiteAccess(annasHtml, "https://annas-archive.gl/search?q=Moby+Dick&check=1", track) }
      assertEquals(TextProvider.ANNAS_ARCHIVE, repo.state.value.editions.single().provider)
      assertEquals("PDF", repo.state.value.editions.single().format)
    } finally {
      withContext(Dispatchers.Main) { repo.close() }
      db.close()
      context.deleteDatabase(dbName)
    }
  }
}

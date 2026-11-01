package com.example.rivulet.readalong

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rivulet.data.*
import com.example.rivulet.data.audiobookbay.*
import com.example.rivulet.data.catalog.*
import com.example.rivulet.data.lyrics.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl

class PublicCatalogIntegrationTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Test fun librivoxJsonAndPersistedManifestRetainExactBookAndChapterIdentity() {
    val json = """{"id":"253","title":"Pride and Prejudice (Version 2)","authors":[{"first_name":"Jane","last_name":"Austen"}],"language":"English","url_text_source":"https://www.gutenberg.org/etext/1342","num_sections":"2","sections":[{"section_number":"2","title":"Second","listen_url":"https://archive.org/download/public/two.mp3"},{"section_number":"1","title":"First","listen_url":"https://archive.org/download/public/one.mp3"}]}"""
    val published = JSONObject(json).put("url_zip_file", "https://archive.org/download/public/public.zip")
    val unfinished = """{"id":"22765","title":"Pride and Prejudice","url_zip_file":"","url_iarchive":"","url_librivox":""}"""
    val item = LibriVoxClient.parseBooks("""{"books":[$published,$unfinished]}""").single()
    // Availability filtering must not hide later pages when a whole raw page is unfinished.
    val emptyPage = LibriVoxClient.parsePage("""{"books":[${List(LibriVoxClient.PAGE_SIZE) { unfinished }.joinToString(",")}]}""")
    assertTrue(emptyPage.books.isEmpty()); assertTrue(emptyPage.hasMore)
    assertEquals(AudioBookSource.LIBRIVOX, item.source); assertEquals("Jane Austen", item.author)
    val seeds = LibriVoxClient.archiveWebSeeds(JSONObject("""{"d1":"dn601303.us.archive.org","d2":"evil.test","dir":"/0/items/public"}"""), "public")
    assertEquals(listOf("https://dn601303.us.archive.org/0/items/"), seeds)
    assertTrue(LibriVoxClient.archiveWebSeeds(JSONObject("""{"d1":"dn601303.us.archive.org","dir":"/0/items/wrong"}"""), "public").isEmpty())
    val book = LibriVoxClient.parseCatalogBook(JSONObject(json)).copy(webSeeds = seeds).resolve(listOf(9 to "root/one.mp3", 4 to "root/two.mp3"))
    assertEquals("Pride and Prejudice", book.title); assertEquals(listOf(9, 4), book.chapters.map { it.fileIndex })
    val hash = "catalog-${UUID.randomUUID()}"
    val first = BookCatalogMetadata(context); val second = BookCatalogMetadata(context)
    try {
      first.saveBook(hash, book)
      assertEquals(book, second.book(hash))
      first.remove(hash)
      assertNull(second.book(hash)) // A second instance must observe deletion, not retain stale provider hints.
    } finally { first.remove(hash) }
    assertThrows(IllegalArgumentException::class.java) {
      LibriVoxClient.parseCatalogBook(JSONObject(json).put("num_sections", "3"))
    }
  }

  @Test fun publicClientsRejectUnrelatedPrivateAndCredentialedLinks() {
    val client = PublicCatalogHttp(setOf("gutenberg.org"))
    assertEquals("https://www.gutenberg.org/ebooks/1342", client.validate("http://www.gutenberg.org/ebooks/1342"))
    for (url in listOf("https://gutenberg.org.evil.test/a", "http://127.0.0.1/a", "https://user:password@www.gutenberg.org/a", "https://www.gutenberg.org:1234/a")) {
      assertThrows(IllegalArgumentException::class.java) { client.validate(url) }
    }
  }

  @Test fun nonEnglishRecordingRequiresManualTextChoiceAndRetainsItsLanguage() = runBlocking {
    val name = "language-${UUID.randomUUID()}"; val db = TorrentDatabase(context, "$name.db")
    val metadata = BookCatalogMetadata(context); val calls = AtomicInteger()
    metadata.saveBook(name, CatalogBook("1", "Candide", "Voltaire", chapters = listOf(CatalogChapter("one.mp3", "One", 0)), language = "French"))
    val catalog = object : BookCatalog {
      override fun search(query: String): List<BookEdition> { calls.incrementAndGet(); return emptyList() }
      override fun download(edition: BookEdition, renderedHtml: String?) = error("unused")
    }
    val repository = ReadAlongRepository(context, ReadAlongStore(db), ReadAlongAudioDecoder { _, _, _ -> error("unused") }, library = catalog)
    try {
      assertEquals("French", BookCatalogMetadata(context).book(name)!!.language)
      withContext(Dispatchers.Main) { repository.activate(ReadAlongTrack(name, 0)); repository.setVisible(true) }
      withTimeout(5000) { repository.state.first { it.phase == ReadAlongPhase.EMPTY } }
      withContext(Dispatchers.Main) { repository.ensureBookText(BookIdentity("Candide", "Voltaire"), setOf(0)) }
      assertEquals(0, calls.get()); assertNull(repository.state.value.bookId)
      withContext(Dispatchers.Main) { repository.searchBooks("Candide", provider = TextProvider.GUTENBERG) }
      withTimeout(5000) { repository.state.first { !it.searching } }
      assertEquals(1, calls.get())
    } finally { withContext(Dispatchers.Main) { repository.close() }; metadata.remove(name); db.close(); context.deleteDatabase("$name.db") }
  }

  @Test fun livePublicRecordingAndLinkedGutenbergEpub() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("publicCatalogLive") == "true")
    val client = LibriVoxClient()
    val results = client.search("Pride and Prejudice", 1)
    assertTrue(results.isNotEmpty())
    assertTrue(client.search("Jane Austen", 1).any { it.author.contains("Jane Austen") })
    // A known public recording avoids choosing an arbitrary edition/translation for this fixture.
    val detail = client.detail(AudioBookItem("Pride and Prejudice", "Jane Austen", "https://librivox.org/pride-and-prejudice-by-jane-austen/",
      source = AudioBookSource.LIBRIVOX, providerId = "253"))
    val catalog = checkNotNull(detail.catalogBook)
    assertTrue(catalog.webSeeds.isNotEmpty())
    assertEquals("1342", GutenbergClient.bookId(catalog.textSourceUrl))
    val bytes = client.torrent(detail)
    assertTrue(bytes.isNotEmpty())
    val info = org.libtorrent4j.TorrentInfo(bytes)
    val resolved = catalog.resolve((0 until info.numFiles()).map { it to info.files().filePath(it) })
    assertEquals(37, resolved.chapters.size)
    // Verify actual Android HTTPS delivery of a piece spanning several Archive files.
    val seed = com.example.rivulet.torrent.PublicWebseedBridge()
    val piece = (info.files().fileOffset(resolved.chapters.first().fileIndex) / info.pieceLength()).toInt()
    val joined = java.io.ByteArrayOutputStream()
    try {
      val local = seed.register(info.infoHash().toHex(), catalog.webSeeds,
        (0 until info.numFiles()).associate { info.files().filePath(it) to info.files().fileSize(it) })
      for (slice in info.mapBlock(piece, 0, info.pieceSize(piece))) {
        val url = local.toHttpUrl().newBuilder().addPathSegments(info.files().filePath(slice.fileIndex())).build()
        val connection = url.toUrl().openConnection() as java.net.HttpURLConnection
        try {
          connection.connectTimeout = 15_000; connection.readTimeout = 90_000
          connection.setRequestProperty("Range", "bytes=${slice.offset()}-${slice.offset() + slice.size() - 1}")
          assertEquals(206, connection.responseCode)
          connection.inputStream.use { joined.write(it.readBytes()) }
        } finally { connection.disconnect() }
      }
    } finally { seed.close() }
    assertEquals(info.pieceSize(piece), joined.size())
    val digest = java.security.MessageDigest.getInstance("SHA-1").digest(joined.toByteArray())
    assertEquals(info.hashForPiece(piece).toHex(), digest.joinToString("") { "%02x".format(it.toInt() and 255) })
    val books = GutenbergFirstCatalog().lookup(BookLookupRequest("Pride and Prejudice Jane Austen", "Pride and Prejudice", "Jane Austen", resolved.textSourceUrl)).editions
    val edition = BookSearch.automaticEdition(books, BookIdentity("Pride and Prejudice", "Jane Austen"))
    assertNotNull(edition); assertEquals(TextProvider.GUTENBERG, edition!!.provider)
    val (epub, name, mime) = GutenbergClient().download(edition)
    assertEquals(TextSyncSource.NARRATION, BookImport.source(epub, name, mime))
    val book = BookImport.book(epub, name)
    assertTrue(book.chapters.isNotEmpty())
    assertTrue(book.chapters.sumOf { it.text.length } > 100_000)
  }
}

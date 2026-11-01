package com.example.rivulet

import com.example.rivulet.data.*
import com.example.rivulet.data.audiobookbay.*
import com.example.rivulet.data.catalog.*
import com.example.rivulet.data.lyrics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogIntegrationTest {
  private fun audio(source: AudioBookSource, id: String = "1", title: String = "Public Book") =
    AudioBookItem(title, "Test Author", "https://example.org/$id", source = source, providerId = id)
  private val identity = BookIdentity("Public Book", "Test Author")
  private fun edition(id: String = "1", provider: TextProvider = TextProvider.GUTENBERG) =
    BookEdition(identity.title, identity.author, "en", "EPUB", null, "https://www.gutenberg.org/ebooks/$id", provider = provider, providerId = id)
  private fun catalog(books: List<BookEdition>, download: String = "data") = object : BookCatalog {
    override fun search(query: String) = books
    override fun download(edition: BookEdition, renderedHtml: String?) = Triple(download.toByteArray(), "test.epub", "application/epub+zip")
  }

  @Test fun librivoxPublishesWithoutWaitingForBayAndSurvivesItsFailure() = runTest {
    val gate = CompletableDeferred<Unit>()
    val search = AudioCatalogSearch(this) { source, _, _ ->
      if (source == AudioBookSource.AUDIOBOOKBAY) { gate.await(); throw IOException("offline") }
      AudioCatalogPage(listOf(audio(source)), false)
    }
    search.search("Public Book"); runCurrent()
    assertEquals(AudioBookSource.LIBRIVOX, search.state.value.books.single().source)
    assertEquals(setOf(AudioBookSource.AUDIOBOOKBAY), search.state.value.loading)
    gate.complete(Unit); runCurrent()
    assertEquals(1, search.state.value.books.size)
    assertTrue(search.state.value.errors.containsKey(AudioBookSource.AUDIOBOOKBAY))
  }
  @Test fun emptyBayDoesNotPreventLibrivoxAndPaginationIsIndependent() = runTest {
    val calls = mutableListOf<Pair<AudioBookSource, Int>>()
    val search = AudioCatalogSearch(this) { source, _, page ->
      calls += source to page
      if (source == AudioBookSource.AUDIOBOOKBAY) AudioCatalogPage(emptyList(), false)
      else AudioCatalogPage(listOf(audio(source, page.toString())), page == 1)
    }
    search.search("Public Book"); runCurrent(); search.more(); runCurrent()
    assertEquals(2, search.state.value.books.size)
    assertEquals(1, calls.count { it.first == AudioBookSource.AUDIOBOOKBAY })
    assertEquals(listOf(1, 2), calls.filter { it.first == AudioBookSource.LIBRIVOX }.map { it.second })
  }
  @Test fun obsoleteSearchCannotReplaceNewQueryEvenWhenLoaderIgnoresCancellation() = runTest {
    val gate = CompletableDeferred<Unit>()
    val search = AudioCatalogSearch(this) { source, query, _ ->
      if (query == "old") withContext(NonCancellable) { gate.await() }
      AudioCatalogPage(listOf(audio(source, query, query)), false)
    }
    search.search("old", AudioBookSource.LIBRIVOX); runCurrent()
    search.search("new", AudioBookSource.LIBRIVOX); runCurrent()
    gate.complete(Unit); runCurrent()
    assertEquals("new", search.state.value.books.single().title)
    assertEquals("new", search.state.value.query)
  }
  @Test fun retryOnlyRepeatsFailedSourcePage() = runTest {
    var calls = 0
    val search = AudioCatalogSearch(this) { source, _, page ->
      if (source == AudioBookSource.LIBRIVOX && ++calls == 1) throw IOException("temporary")
      AudioCatalogPage(listOf(audio(source, page.toString())), false)
    }
    search.search("book"); runCurrent(); search.retry(AudioBookSource.LIBRIVOX); runCurrent()
    assertEquals(2, calls)
    assertEquals(2, search.state.value.books.size)
    assertTrue(search.state.value.errors.isEmpty())
  }
  @Test fun providerIdsDoNotCollapseDifferentRecordingsOrSources() {
    assertEquals(3, AudiobookCatalog.rank(listOf(audio(AudioBookSource.LIBRIVOX), audio(AudioBookSource.AUDIOBOOKBAY),
      audio(AudioBookSource.LIBRIVOX, "2"), audio(AudioBookSource.LIBRIVOX)), "Public Book").size)
  }
  @Test fun manifestUsesSectionOrderAndPreservesRealIndicesWhileExcludingOtherEncodings() {
    val manifest = CatalogBook("1", "Book", "Author", chapters = listOf(CatalogChapter("second_64kb.mp3", "Two"), CatalogChapter("first_64kb.mp3", "One")))
      .resolve(listOf(7 to "root/first_64kb.mp3", 3 to "root/first_128kb.mp3", 9 to "root/second_64kb.mp3"))
    val files = listOf(AudioFile(7, "first_64kb.mp3", "first", 10, 10, true), AudioFile(3, "first_128kb.mp3", "duplicate", 20, 0, true),
      AudioFile(9, "second_64kb.mp3", "second", 10, 10, true))
    val torrent = TorrentUi("hash", "root", "ready", 1f, 0, 0, 0, 0, 20, 20, true, files, catalogBook = manifest)
    assertEquals(listOf(9, 7), torrent.audioFiles.map { it.index })
    assertEquals(listOf("Two", "One"), torrent.audioFiles.map { it.displayTitle })
    assertTrue(torrent.isFullyDownloaded)
  }
  @Test fun missingAmbiguousAndDuplicateChaptersAreRejected() {
    val book = CatalogBook("1", "Book", "Author", chapters = listOf(CatalogChapter("one.mp3", "One")))
    for ((candidate, files) in listOf(book to emptyList(), book to listOf(1 to "a/one.mp3", 2 to "b/one.mp3"),
      book.copy(chapters = book.chapters + book.chapters) to listOf(1 to "one.mp3"))) {
      assertThrows(IllegalArgumentException::class.java) { candidate.resolve(files) }
    }
    assertEquals("a+b c.mp3", CatalogBook.fileName("https://archive.org/download/id/a+b%20c.mp3"))
  }
  @Test fun opdsNavigationAndAcquisitionMetadataAreParsedWithoutTreatingFormatsAsEditions() {
    val navigation = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>https://www.gutenberg.org/ebooks/1342.opds</id><title>Pride and Prejudice</title><content>Jane Austen</content></entry></feed>"""
    val first = GutenbergClient.parseSearch(navigation).single()
    assertEquals("1342", first.providerId); assertEquals("Jane Austen", first.author); assertNull(first.downloadUrl)
    val entry = """<entry><id>urn:gutenberg:1342:2</id><title>Pride and Prejudice</title><author><name>Austen, Jane</name></author><rights>Public domain in the USA.</rights><dcterms:language>en</dcterms:language><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/ebooks/1342.epub.noimages" /></entry>"""
    val details = GutenbergClient.parseDetails("""<feed xmlns:dcterms="http://purl.org/dc/terms/">$entry$entry</feed>""", "1342")
    assertEquals(1, details.size); assertEquals("en", details.single().language)
    assertEquals("https://www.gutenberg.org/ebooks/1342.epub.noimages", details.single().downloadUrl)
    assertEquals("1342", GutenbergClient.bookId("http://www.gutenberg.org/etext/1342"))
    assertEquals("1342", GutenbergClient.bookId("https://www.gutenberg.org/cache/epub/1342/pg1342-images.html"))
    assertEquals("1342", GutenbergClient.bookId("https://www.gutenberg.org/files/1342/1342-h/1342-h.htm"))
    assertNull(GutenbergClient.bookId("https://www.gutenberg.org.evil.test/ebooks/1342"))
    assertNull(GutenbergClient.bookId("https://user@www.gutenberg.org/ebooks/1342"))
  }
  @Test fun linkedGutenbergEditionAvoidsFallbackSearchAndDownloadRoutesByProvider() {
    var requested: String? = null
    val pg = object : GutenbergCatalog {
      override fun byId(id: String): List<BookEdition> { requested = id; return listOf(edition(id)) }
      override fun search(query: String): List<BookEdition> = error("Linked edition should be resolved directly")
      override fun download(edition: BookEdition, renderedHtml: String?) = Triple("pg".toByteArray(), "pg.epub", "application/epub+zip")
    }
    val client = GutenbergFirstCatalog(pg, catalog(emptyList(), "zlib"))
    val selected = client.lookup(BookLookupRequest(identity.query, identity.title, identity.author, "https://www.gutenberg.org/ebooks/1342")).editions.single()
    assertEquals("1342", requested); assertTrue(selected.linkedSource)
    assertEquals("pg", client.download(selected).first.toString(Charsets.UTF_8))
    assertEquals("zlib", client.download(edition(provider = TextProvider.ZLIBRARY)).first.toString(Charsets.UTF_8))
  }
  @Test fun gutenbergFirstFallbackAndAccessRecoveryRetainPublicResults() {
    val unrelated = edition().copy(title = "Another Book")
    val access = object : BookCatalog {
      override fun search(query: String): List<BookEdition> = throw SiteAccessRequired("https://zlib.li/s/book")
      override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> = error("unused")
    }
    val client = GutenbergFirstCatalog(catalog(listOf(unrelated)), access)
    val result = client.lookup(BookLookupRequest(identity.query, identity.title, identity.author))
    assertEquals(listOf(unrelated), result.editions); assertNotNull(result.accessUrl); assertFalse(result.complete)
    val offline = object : BookCatalog by catalog(emptyList()) { override fun search(query: String): List<BookEdition> = throw IOException("offline") }
    assertEquals(TextProvider.ZLIBRARY, GutenbergFirstCatalog(offline, catalog(listOf(edition(provider = TextProvider.ZLIBRARY))))
      .lookup(BookLookupRequest(identity.title)).editions.single().provider)
  }
  @Test fun ambiguityRequiresChoiceAndReversedAuthorsStillMatch() {
    val pg = edition().copy(author = "Author, Test")
    assertEquals(pg, BookSearch.automaticEdition(listOf(pg), identity))
    assertNull(BookSearch.automaticEdition(listOf(pg, pg.copy(providerId = "2", url = "https://www.gutenberg.org/ebooks/2")), identity))
    val linked = pg.copy(linkedSource = true)
    assertEquals(linked, BookSearch.automaticEdition(listOf(linked, edition("2")), identity))
    assertNull(BookSearch.automaticEdition(listOf(pg.copy(language = "fr")), identity))
    assertNull(BookSearch.automaticEdition(listOf(pg), identity.copy(author = "")))
  }
  @Test fun wrongAuthorDoesNotBlockFallbackAndManualProviderFilterIsRespected() {
    val z = edition("z", TextProvider.ZLIBRARY)
    val client = GutenbergFirstCatalog(catalog(listOf(edition().copy(author = "Different Author"))), catalog(listOf(z)))
    assertEquals(z, BookSearch.automaticEdition(client.lookup(BookLookupRequest(identity.query, identity.title, identity.author)).editions, identity))
    assertEquals(TextProvider.GUTENBERG, client.lookup(BookLookupRequest(identity.query, identity.title, identity.author,
      provider = TextProvider.GUTENBERG)).editions.single().provider)
    assertEquals(listOf(z), client.lookup(BookLookupRequest(identity.query, provider = TextProvider.ZLIBRARY)).editions)
  }
  @Test fun unavailableLinkedBookStillTriesGutenbergSearchBeforeFallback() {
    var searched = false
    val pg = object : GutenbergCatalog {
      override fun byId(id: String): List<BookEdition> = throw IOException("Not found")
      override fun search(query: String): List<BookEdition> { searched = true; return listOf(edition()) }
      override fun download(edition: BookEdition, renderedHtml: String?): Triple<ByteArray, String, String> = error("unused")
    }
    val client = GutenbergFirstCatalog(pg, object : BookCatalog by catalog(emptyList()) {
      override fun search(query: String): List<BookEdition> = error("Gutenberg search should succeed first")
    })
    assertEquals(edition(), client.lookup(BookLookupRequest(identity.query, identity.title, identity.author,
      "https://www.gutenberg.org/ebooks/999999")).editions.single())
    assertTrue(searched)
  }
  @Test fun cancellingPublicHttpRequestCancelsTheActualOkHttpCall() = runBlocking {
    val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
    val transport = OkHttpClient.Builder().addInterceptor { chain ->
      entered.countDown()
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
      if (chain.call().isCanceled()) cancelled.countDown()
      throw IOException("Cancelled fixture; no network is used")
    }.build()
    try {
      val client = PublicCatalogHttp(setOf("gutenberg.org"), transport)
      val request = launch { client.bytesAsync("https://www.gutenberg.org/fixture") }
      yield()
      assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
      request.cancelAndJoin()
      assertTrue(withContext(Dispatchers.IO) { cancelled.await(3, TimeUnit.SECONDS) })
    } finally { transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll() }
  }
  @Test fun librivox404MeansNoResultsRatherThanAnUnavailableSource() = runBlocking {
    val transport = OkHttpClient.Builder().addInterceptor { chain ->
      Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").body("{}".toResponseBody()).build()
    }.build()
    try {
      val client = LibriVoxClient(PublicCatalogHttp(setOf("librivox.org"), transport))
      val page = client.page("Nonexistent Book", 1)
      assertTrue(page.books.isEmpty()); assertFalse(page.hasMore)
    } finally { transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll() }
  }
  @Test fun authorNamesAndMixedTitleAuthorQueriesUseAllTerms() {
    assertEquals("Austen", LibriVoxClient.authorQuery("Jane Austen"))
    assertEquals("Austen", LibriVoxClient.authorQuery("Austen, Jane"))
    val book = audio(AudioBookSource.LIBRIVOX, title = "Pride and Prejudice").copy(author = "Jane Austen")
    assertTrue(LibriVoxClient.matches(book, "Jane Austen"))
    assertTrue(LibriVoxClient.matches(book, "Pride Prejudice Jane Austen"))
    assertFalse(LibriVoxClient.matches(book, "Pride Dickens"))
  }
}

package com.example.rivulet.readalong

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rivulet.data.BookCatalogMetadata
import com.example.rivulet.data.TorrentDatabase
import com.example.rivulet.data.lyrics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.Collections

class BookLookupRegressionTest {
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val exact = BookEdition("The Golden Spruce", "John Vaillant", "English", "EPUB", null, "https://zlib.li/book/1")

  @Test fun titleFallbackIsBoundedCachedAndRenderedResultsShareRanking() = runBlocking {
    val name = "lookup-plan-${UUID.randomUUID()}.db"
    val db = TorrentDatabase(context, name)
    val queries = Collections.synchronizedList(mutableListOf<String>())
    var challenge = false
    val catalog = object : BookCatalog {
      override fun search(query: String): List<BookEdition> {
        queries += query
        if (challenge) throw SiteAccessRequired("https://zlib.li/s/$query")
        return if (query == exact.title) listOf(exact.copy(title = "The Golden Spruce Workbook", url = "https://zlib.li/book/2"), exact) else emptyList()
      }
      override fun download(edition: BookEdition, renderedHtml: String?) = error("Never download from a search test")
    }
    val repo = ReadAlongRepository(context, ReadAlongStore(db), AudioWindowDecoder { error("Unused") }, library = catalog)
    val track = ReadAlongTrack("lookup", 0)
    try {
      withContext(Dispatchers.Main) { repo.activate(track) }
      withTimeout(5000) { repo.state.first { it.phase == ReadAlongPhase.EMPTY } }
      withContext(Dispatchers.Main) { repo.searchBooks("The Golden Spruce John Vaillant", title = exact.title, author = exact.author) }
      withTimeout(5000) { repo.state.first { !it.searching && it.editions.isNotEmpty() } }
      assertEquals(listOf("The Golden Spruce John Vaillant", "The Golden Spruce"), queries.toList())
      assertEquals(exact, repo.state.value.editions.first())
      withContext(Dispatchers.Main) { repo.searchBooks("The Golden Spruce John Vaillant", title = exact.title, author = exact.author) }
      assertEquals(2, queries.size)
      challenge = true
      withContext(Dispatchers.Main) { repo.searchBooks("The Golden Spruce John Vaillant", force = true, title = exact.title, author = exact.author) }
      withTimeout(5000) { repo.state.first { it.accessUrl != null } }
      val html = """<z-bookcard title="The Golden Spruce Workbook" href="/book/2"/><z-bookcard title="The Golden Spruce" author="John Vaillant" language="English" href="/book/1"/>"""
      withContext(Dispatchers.Main) { repo.acceptRenderedSearch(html, ZLibraryClient.BASE, track) }
      assertEquals(exact.title, repo.state.value.editions.first().title)
      withContext(Dispatchers.Main) { repo.activate(track.copy(fileIndex = 2)); repo.acceptRenderedSearch(html, ZLibraryClient.BASE, track) }
      assertTrue(repo.state.value.editions.isEmpty())
    } finally { withContext(Dispatchers.Main) { repo.close() }; db.close(); context.deleteDatabase(name) }
  }

  @Test fun browserEmptyResultCanResumeTitleOnlyFallbackAndManualQueryDoesNotBroaden() = runBlocking {
    val name = "browser-fallback-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name)
    val queries = Collections.synchronizedList(mutableListOf<String>())
    val catalog = object : BookCatalog {
      override fun search(query: String): List<BookEdition> {
        queries += query
        if (query.endsWith("John Vaillant")) throw SiteAccessRequired("https://zlib.li/s/query")
        return emptyList()
      }
      override fun download(edition: BookEdition, renderedHtml: String?) = error("Unused")
    }
    val repo = ReadAlongRepository(context, ReadAlongStore(db), AudioWindowDecoder { error("Unused") }, library = catalog)
    try {
      withContext(Dispatchers.Main) { repo.activate(ReadAlongTrack("browser", 0)) }
      withTimeout(5000) { repo.state.first { it.phase == ReadAlongPhase.EMPTY } }
      withContext(Dispatchers.Main) { repo.searchBooks("The Golden Spruce John Vaillant", title = exact.title, author = exact.author) }
      withTimeout(5000) { repo.state.first { it.accessUrl != null } }
      withContext(Dispatchers.Main) { repo.acceptRenderedSearch("<html>No matches</html>", ZLibraryClient.BASE) }
      withTimeout(5000) { repo.state.first { !it.searching && it.accessUrl == null } }
      assertEquals(listOf("The Golden Spruce John Vaillant", exact.title), queries.toList())
      withContext(Dispatchers.Main) { repo.searchBooks("manual phrase") }
      withTimeout(5000) { repo.state.first { !it.searching } }
      assertEquals(listOf("The Golden Spruce John Vaillant", exact.title, "manual phrase"), queries.toList())
    } finally { withContext(Dispatchers.Main) { repo.close() }; db.close(); context.deleteDatabase(name) }
  }

  @Test fun browserRecoveryForNewQueryCannotMergePreviousGutenbergResults() = runBlocking {
    val name = "provider-recovery-${UUID.randomUUID()}.db"; val db = TorrentDatabase(context, name)
    val old = exact.copy(title = "Old public book", provider = TextProvider.GUTENBERG, providerId = "1342", url = "https://www.gutenberg.org/ebooks/1342")
    val catalog = object : BookCatalog {
      override fun search(query: String): List<BookEdition> = if (query == "old") listOf(old) else throw SiteAccessRequired("https://zlib.li/s/new")
      override fun download(edition: BookEdition, renderedHtml: String?) = error("Unused")
    }
    val repo = ReadAlongRepository(context, ReadAlongStore(db), AudioWindowDecoder { error("Unused") }, library = catalog)
    try {
      withContext(Dispatchers.Main) { repo.activate(ReadAlongTrack("provider-recovery", 0)) }
      withTimeout(5000) { repo.state.first { it.phase == ReadAlongPhase.EMPTY } }
      withContext(Dispatchers.Main) { repo.searchBooks("old") }
      withTimeout(5000) { repo.state.first { !it.searching && it.editions.isNotEmpty() } }
      withContext(Dispatchers.Main) { repo.searchBooks("new", provider = TextProvider.ZLIBRARY) }
      withTimeout(5000) { repo.state.first { it.accessUrl != null } }
      withContext(Dispatchers.Main) { repo.acceptRenderedSearch("""<z-bookcard title="New book" href="/book/99" language="English"/>""", ZLibraryClient.BASE) }
      assertEquals("New book", repo.state.value.editions.single().title)
      assertEquals(TextProvider.ZLIBRARY, repo.state.value.editions.single().provider)
    } finally { withContext(Dispatchers.Main) { repo.close() }; db.close(); context.deleteDatabase(name) }
  }

  @Test fun catalogIdentityPersistsWithoutChangingTorrentDatabaseVersion() {
    val hash = "test-${UUID.randomUUID()}"
    val metadata = BookCatalogMetadata(context)
    try {
      metadata.save(hash, "The Golden Spruce - John Vaillant")
      assertEquals("The Golden Spruce - John Vaillant", BookCatalogMetadata(context).title(hash))
    } finally { metadata.remove(hash) }
    assertNull(BookCatalogMetadata(context).title(hash))
  }
}

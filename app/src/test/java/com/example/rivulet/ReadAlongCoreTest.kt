package com.example.rivulet

import com.example.rivulet.data.AudioFile
import com.example.rivulet.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReadAlongCoreTest {
  private fun epub(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
  }.toByteArray()

  @Test fun epub3SpineNavAndRelativePathsArePreserved() {
    val data = epub(mapOf(
      "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/package.opf'/></rootfiles></container>",
      "OPS/package.opf" to """<package><metadata><dc:title>Sample &amp; More</dc:title><dc:creator>Author</dc:creator></metadata><manifest><item id='b' href='text/b.xhtml'/><item id='a' href='text/a.xhtml'/><item id='nav' href='nav/toc.xhtml' properties='nav'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>""",
      "OPS/nav/toc.xhtml" to "<nav><a href='../text/a.xhtml#start'>First chapter</a><a href='../text/b.xhtml'>Second chapter</a></nav>",
      "OPS/text/a.xhtml" to "<html><body><p>Short text.</p><p>Another paragraph &mdash; with entities.</p></body></html>",
      "OPS/text/b.xhtml" to "<html><body><p>The second chapter has readable text.</p></body></html>",
    ))
    val book = EpubStructureParser.parse(data.inputStream())
    assertEquals("Sample & More", book.title)
    assertEquals(listOf("First chapter", "Second chapter"), book.chapters.map { it.title })
    assertEquals(listOf("a", "b"), book.chapters.map { it.id })
    assertTrue(book.chapters.first().text.contains("—"))
    assertTrue(book.chapters.first().text.contains("\n\n"))
  }

  @Test fun displayNameCanBeOpaqueWhenMimeAndMagicIdentifyEpub() {
    val data = epub(mapOf("META-INF/container.xml" to "<container/>"))
    assertEquals(TextSyncSource.NARRATION, BookImport.source(data, "document:927", "application/epub+zip"))
  }

  @Test fun htmlDownloadIsNeverImportedAsBookText() {
    assertThrows(IllegalArgumentException::class.java) { BookImport.source("<!doctype html><html>Sign in</html>".toByteArray(), "book.epub", "application/epub+zip") }
  }

  @Test fun unsupportedAndOversizeImportsFailClearly() {
    assertThrows(IllegalStateException::class.java) { BookImport.source("%PDF-1.4".toByteArray(), "book.pdf", "application/pdf") }
    assertThrows(IllegalArgumentException::class.java) { BookImport.read(ByteArray(10).inputStream(), 5) }
    assertThrows(IllegalArgumentException::class.java) { BookImport.read(ByteArray(0).inputStream()) }
  }

  @Test fun sentenceIdentitySurvivesReloadAndDiffersBetweenBooks() {
    val book = EpubBook("Test", "Author", listOf(EpubChapter(1, "one", "one.xhtml", "One", "First sentence is here. Second sentence follows.", 8)))
    val first = ReadAlongText.sentences(book, "book-a")
    assertEquals(first, ReadAlongText.sentences(book, "book-a"))
    assertEquals(2, first.size)
    assertNotEquals(first.first().id, ReadAlongText.sentences(book, "book-b").first().id)
  }

  @Test fun omnibusNamedFilesDoNotShareTextByDefault() {
    val files = listOf(audio(2, "Night Lords/Soul Hunter.m4b"), audio(5, "Night Lords/Blood Reaver.m4b"))
    assertEquals(setOf(2), ReadAlongText.defaultTracks(files, 2))
  }

  @Test fun chapterTracksShareOnlyTheirOwnDirectory() {
    val files = listOf(audio(2, "Omnibus/Book A/Chapter 1.mp3"), audio(5, "Omnibus/Book A/Chapter 2.mp3"), audio(9, "Omnibus/Book B/Chapter 1.mp3"))
    assertEquals(setOf(2, 5), ReadAlongText.defaultTracks(files, 2))
  }

  @Test fun correctionIsAppliedOnceToSelectionAndSeeking() {
    val line = TimedLine(1, 90_000, 95_000, "At a page boundary.")
    assertEquals(5_000, ReadAlongTimeline.calibration(line, 95_000))
    assertEquals(95_000, ReadAlongTimeline.seekPosition(line, 5_000))
    assertEquals(0, ReadAlongTimeline.activeIndex(listOf(line), 95_001, 5_000))
    assertEquals(-1, ReadAlongTimeline.activeIndex(listOf(line), 94_999, 5_000))
    assertEquals(5_000, ReadAlongTimeline.calibration(line, 95_000))
  }

  @Test fun untimedTextCannotBecomeActive() {
    assertEquals(-1, ReadAlongTimeline.activeIndex(listOf(TimedLine(1, -1, -1, "Unknown timing")), 0, 0))
  }

  @Test fun narrationFindsLaterChaptersAndIgnoresIntroductions() {
    val sentences = listOf(
      BookSentence(1, 1, "First", "A completely different chapter starts in the forest."),
      BookSentence(2, 2, "Second", "The little boat moved slowly across the quiet water."),
      BookSentence(3, 2, "Second", "A bright moon appeared above the distant hills."),
    )
    val words = timedWords("Welcome to this recording. The little boat moved slowly across the quiet water. A bright moon appeared above the distant hills.")
    val lines = NarrationMatcher(sentences).align(words)
    assertEquals(listOf(2L, 3L), lines.map { it.id })
    assertEquals(2_000, lines.first().startTimeMs)
  }

  @Test fun unrelatedNarrationHasNoConfidentHighlight() {
    val matcher = NarrationMatcher(listOf(BookSentence(1, 1, "Chapter", "The little boat moved slowly across the quiet water.")))
    assertTrue(matcher.align(timedWords("Please visit our website for the full list of available recordings and updates.")).isEmpty())
  }

  @Test fun missingSentenceBoundaryDoesNotInventItsStart() {
    val matcher = NarrationMatcher(listOf(BookSentence(1, 1, "Chapter", "The little boat moved slowly across the quiet water.")))
    assertTrue(matcher.align(timedWords("moved slowly across the quiet water")).isEmpty())
  }

  @Test fun repeatedPassagesNeedAnAnchorAndChapterHintDisambiguates() {
    val text = "The little boat moved slowly across the quiet water."
    val matcher = NarrationMatcher(listOf(BookSentence(1, 1, "One", text), BookSentence(2, 2, "Two", text)))
    assertTrue(matcher.align(timedWords(text)).isEmpty())
    assertEquals(listOf(2L), matcher.align(timedWords(text), hintSentence = 1).map { it.id })
  }

  @Test fun siteChallengeAndExpiredSessionRequireInteractiveAccess() {
    assertTrue(ZLibraryClient.needsAccess(503, "<title>Checking your browser ...</title>"))
    assertTrue(ZLibraryClient.needsAccess(200, "<input type='password'>"))
    assertFalse(ZLibraryClient.needsAccess(200, "<title>Search results</title>"))
  }

  @Test fun nativeLookupParsesModernAndClassicCardsWithoutDuplicates() {
    val html = """<z-bookcard title="The Sample Book" author="An Author" language="English" extension="EPUB" href="/book/123/sample" isbn="9780306406157"></z-bookcard><div class="resItemBox"><h3><a href="/book/124/other">Another Book</a></h3><div class="authors">Writer</div><div class="bookProperty_language"><span class="property_value">English</span></div></div>"""
    val books = ZLibraryClient.parseResults(html, "https://zlib.li/s/sample")
    assertEquals(2, books.size)
    assertEquals("The Sample Book", books.first().title)
    assertEquals("English", books.first().language)
    assertEquals("https://zlib.li/book/123/sample", books.first().url)
    assertNotNull(books.first().isbn)
  }

  @Test fun nativeLookupRejectsLookalikeDomains() {
    assertTrue(ZLibraryClient.parseResults("<a href='https://evilzlib.li/book/1'>Fake</a>", ZLibraryClient.BASE).isEmpty())
    assertThrows(IllegalArgumentException::class.java) { ZLibraryClient.validateUrl("http://zlib.li/book/1") }
  }

  @Test fun liveOctober2026CardShapeUsesSlotsAndIgnoresHiddenPromotions() {
    // Sanitized public markup inspected on zlib.li/s/Frankenstein on 2026-10-24.
    val html = """<z-bookcard href="/book/xK8evYX2Dj/frankenstein.html" download="/dl/vBzZ3O5p2w" extension="epub" language="English"><div slot="title">Frankenstein</div><div slot="author">Mary Shelley</div></z-bookcard><div hidden><z-bookcard href="/book/hidden/fake" extension="epub"><div slot="title">Hidden promotion</div></z-bookcard></div><z-bookcard href="/book/pdf/other" extension="pdf"><div slot="title">PDF edition</div></z-bookcard>"""
    val editions = ZLibraryClient.parseResults(html, ZLibraryClient.BASE)
    assertEquals(1, editions.size)
    assertEquals("Frankenstein", editions.first().title)
    assertEquals("Mary Shelley", editions.first().author)
    assertEquals("https://zlib.li/dl/vBzZ3O5p2w", editions.first().downloadUrl)
  }

  private fun timedWords(text: String) = ReadAlongText.tokens(text).mapIndexed { i, word -> SpeechWord(word, i * 500L, (i + 1) * 500L) }
  private fun audio(index: Int, path: String) = AudioFile(index, path, path.substringAfterLast('/'), 100, 100, true)
}

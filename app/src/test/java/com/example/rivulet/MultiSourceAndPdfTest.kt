package com.example.rivulet

import com.example.rivulet.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MultiSourceAndPdfTest {

  @Test fun libgenParsesCoversAndFiltersOversizeAndUnsupportedFormats() {
    val html = """
      <table id="tablelibgen">
        <thead><tr><th>Title</th><th>Author(s)</th><th>Publisher</th><th>Year</th><th>Language</th><th>Pages</th><th>Size</th><th>Ext.</th><th>Mirrors</th></tr></thead>
        <tbody>
          <tr>
            <td><img src="/covers/123/a5347daf5676bb9344812b5dee5a760b_small.jpg"></td>
            <td><a href="edition.php?id=1">Moby Dick</a><br><i>9780142437247</i></td>
            <td>Herman Melville</td>
            <td>Penguin</td>
            <td>2003</td>
            <td>English</td>
            <td>720</td>
            <td><a href="/file.php?id=1">1.5 MB</a></td>
            <td>epub</td>
            <td><a href="/ads.php?md5=a5347daf5676bb9344812b5dee5a760b">1</a></td>
          </tr>
          <tr>
            <td><img src="/covers/456/00112233445566778899aabbccddeeff_small.jpg"></td>
            <td><a href="edition.php?id=2">Moby Dick PDF</a></td>
            <td>Herman Melville</td>
            <td>Dover</td>
            <td>1999</td>
            <td>English</td>
            <td>500</td>
            <td><a href="/file.php?id=2">3 MB</a></td>
            <td>pdf</td>
            <td><a href="/ads.php?md5=00112233445566778899aabbccddeeff">1</a></td>
          </tr>
          <tr>
            <td></td>
            <td><a href="edition.php?id=3">Oversize PDF</a></td>
            <td>Herman Melville</td>
            <td>Big</td>
            <td>2020</td>
            <td>English</td>
            <td>500</td>
            <td><a href="/file.php?id=3">121 MB</a></td>
            <td>pdf</td>
            <td><a href="/ads.php?md5=11111111111111111111111111111111">1</a></td>
          </tr>
          <tr>
            <td></td>
            <td><a href="edition.php?id=4">Comic CBR</a></td>
            <td>Someone</td>
            <td>Press</td>
            <td>2000</td>
            <td>English</td>
            <td>50</td>
            <td><a href="/file.php?id=4">14 MB</a></td>
            <td>cbr</td>
            <td><a href="/ads.php?md5=22222222222222222222222222222222">1</a></td>
          </tr>
        </tbody>
      </table>
    """.trimIndent()

    val results = LibGenClient.parseResults(html, "https://libgen.li/index.php?req=Moby+Dick")
    assertEquals(2, results.size)
    assertEquals(TextProvider.LIBGEN, results[0].provider)
    assertEquals("Moby Dick", results[0].title)
    assertEquals("Herman Melville", results[0].author)
    assertEquals("English", results[0].language)
    assertEquals("EPUB", results[0].format)
    assertEquals("978-0-14243-724-7", results[0].isbn)
    assertEquals("a5347daf5676bb9344812b5dee5a760b", results[0].providerId)
    assertEquals("https://libgen.li/ads.php?md5=a5347daf5676bb9344812b5dee5a760b", results[0].url)
    assertEquals("https://libgen.li/covers/123/a5347daf5676bb9344812b5dee5a760b_small.jpg", results[0].coverUrl)
    assertEquals("PDF", results[1].format)
  }

  @Test fun libgenParsesLiveTenColumnTableWithCoverHeaderCorrectly() {
    val html = """
      <table id="tablelibgen">
        <thead><tr>
          <th scope="col"></th>
          <th scope="col" class="first_col">ID Time add. Title Series</th>
          <th scope="col">Author(s)</th>
          <th scope="col">Publisher</th>
          <th scope="col">Year</th>
          <th scope="col">Language</th>
          <th scope="col">Pages</th>
          <th scope="col">Size</th>
          <th scope="col">Ext.</th>
          <th scope="col">Mirrors</th>
        </tr></thead>
        <tbody>
          <tr>
            <td><a href="edition.php?id=149658933"><img src="/covers/6117000/11e4dbb395b3dfe50af6a199375705c1_small.jpg"></a></td>
            <td><a href="edition.php?id=149658933">The Caves of Steel</a></td>
            <td>Isaac Asimov</td>
            <td></td>
            <td>2010</td>
            <td>English</td>
            <td>224</td>
            <td><a href="/file.php?id=106971511">281 kB</a></td>
            <td>epub</td>
            <td><a href="/ads.php?md5=11e4dbb395b3dfe50af6a199375705c1">1</a></td>
          </tr>
        </tbody>
      </table>
    """.trimIndent()

    val results = LibGenClient.parseResults(html, "https://libgen.li/index.php?req=Caves+of+Steel")
    assertEquals(1, results.size)
    val item = results.single()
    assertEquals("The Caves of Steel", item.title)
    assertEquals("Isaac Asimov", item.author)
    assertEquals("English", item.language)
    assertEquals("EPUB", item.format)
    assertEquals("11e4dbb395b3dfe50af6a199375705c1", item.providerId)
  }

  @Test fun libgenExtractsGetLinkAndDetectsDbBusy500() {
    val adsHtml = """<table id="main"><tr><td><a href="get.php?md5=a5347daf5676bb9344812b5dee5a760b&key=KEY123"><h2>GET</h2></a></td></tr></table>"""
    assertEquals(
      "https://libgen.la/get.php?md5=a5347daf5676bb9344812b5dee5a760b&key=KEY123",
      LibGenClient.extractGetUrl(adsHtml, "https://libgen.la/ads.php?md5=a5347daf5676bb9344812b5dee5a760b"),
    )
    assertTrue(LibGenClient.isDbBusy(500, """<div class="alert">User 'libgen_get' has exceeded 'max_user_connections'</div>"""))
    assertFalse(LibGenClient.isDbBusy(200, "<html>OK</html>"))
  }

  @Test fun welibParsesVerifiedCardDomAndDetailDownloadLinks() {
    val searchHtml = """
      <div id="aarecord-list">
        <div class="book-list">
          <div class="book-card">
            <a href="/md5/05ff6931d11746bc699307f7a7595b6a">
              <div class="book-cover">
                <img src="https://img.y-cdn-y.com/covers/proxy?url=a" data-title="Moby Dick: Or, The Whale" data-author="Herman Melville">
              </div>
            </a>
            <a href="/md5/05ff6931d11746bc699307f7a7595b6a"><h2>Moby Dick: Or, The Whale</h2></a>
            <a href="/search?q=Herman%20Melville">Herman Melville</a>
            <div class="mb-1">
              <span class="uppercase">epub · PDF</span>
              <span>· English</span>
              <span>· 2009</span>
              <span>· 1.5 MB</span>
            </div>
            <a href="/auto_download/05ff6931d11746bc699307f7a7595b6a/0/0">Download</a>
          </div>
          <div class="book-card">
            <a href="/md5/6c55ed82eab2715f1b52375f8257ff5b"><h2>Moby-Dick, Norton</h2></a>
            <div class="mb-1"><span class="uppercase">pdf</span><span>· English</span><span>· 176.4 MB</span></div>
          </div>
        </div>
      </div>
    """.trimIndent()

    val results = WeLibClient.parseResults(searchHtml, "https://welib.gl/search?q=Moby+Dick")
    assertEquals(1, results.size)
    val item = results.single()
    assertEquals(TextProvider.WELIB, item.provider)
    assertEquals("Moby Dick: Or, The Whale", item.title)
    assertEquals("Herman Melville", item.author)
    assertEquals("EPUB", item.format)
    assertEquals("English", item.language)
    assertEquals("05ff6931d11746bc699307f7a7595b6a", item.providerId)
    assertEquals("https://welib.gl/md5/05ff6931d11746bc699307f7a7595b6a", item.url)
    assertEquals("https://welib.gl/auto_download/05ff6931d11746bc699307f7a7595b6a/0/0", item.downloadUrl)

    val detailHtml = """
      <div id="md5-panel-downloads">
        <a href="/fast_download/024c0b8c27b919a25be0b9303f050405/0/0">Fast</a>
        <a href="/slow_download/024c0b8c27b919a25be0b9303f050405/0/0">Slow</a>
        <span class="preview" data-book-url="/fast_preview/f4276438c8c994e5c43300c6b4df0cb2/0/0?viewer=1">Read</span>
      </div>
    """.trimIndent()
    assertEquals(
      "https://welib.gl/slow_download/024c0b8c27b919a25be0b9303f050405/0/0",
      WeLibClient.extractDownloadLink(detailHtml, "https://welib.gl/md5/024c0b8c27b919a25be0b9303f050405"),
    )
    assertEquals(
      "https://welib.gl/fast_preview/f4276438c8c994e5c43300c6b4df0cb2/0/0?viewer=1",
      WeLibClient.extractFastPreviewUrl(detailHtml, "https://welib.gl/md5/f4276438c8c994e5c43300c6b4df0cb2"),
    )

    val previewHtml = """
      <p><a href="/fast_view?url=https%3A//y-cdn-y.com/hs7/zlib3/f4276438c8c994e5c43300c6b4df0cb2/book.epub&amp;md5=f4276438c8c994e5c43300c6b4df0cb2">View</a></p>
      <img src="https://y-cdn-y.com/covers/proxy?url=cover.jpg">
    """.trimIndent()
    assertEquals(
      "https://y-cdn-y.com/hs7/zlib3/f4276438c8c994e5c43300c6b4df0cb2/book.epub",
      WeLibClient.extractPreviewFileUrl(previewHtml, "https://welib.gl/fast_preview/f4276438c8c994e5c43300c6b4df0cb2/0/0?viewer=1"),
    )
    assertNull(
      WeLibClient.extractFileUrlFromViewerSrc("https://welib.gl/fast_view?url=https%3A//img.y-cdn-y.com/covers/proxy%3Fkey%3D1"),
    )

    assertTrue(WeLibClient.needsAccess(403, "<title>Just a moment...</title>"))
    assertTrue(WeLibClient.needsAccess(200, "<html><head><title>Verify you are human by completing the action below. - WeLib</title></head><body><form id=\"captcha-form\"><div class=\"h-captcha\"></div></form></body></html>"))
    assertTrue(ZLibraryPrewarmer.isChallengeTitle("Verify you are human by completing the action below. - WeLib"))
  }

  @Test fun annasArchiveParsesVerifiedSearchCardsAndChallengeDetection() {
    val html = """
      <div class="js-aarecord-list-outer">
        <div class="flex">
          <a href="/md5/077484a10743e5dd5d151013e8c732f4">
            <img src="https://covers.z-lib.gd/cover.jpg">
          </a>
          <div>
            <a href="/md5/077484a10743e5dd5d151013e8c732f4" class="js-vim-focus">Moby-Dick Or the Whale</a>
            <a href="/search?q=Melville"><span class="icon-[mdi--user-edit]"></span> Melville, Herman</a>
            <div class="text-gray-800">✅ English [en] · EPUB · 1.5MB · 1851 · 📕 Book (fiction)</div>
          </div>
        </div>
        <div class="flex">
          <div>
            <a href="/md5/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" class="js-vim-focus">MOBI Only</a>
            <div class="text-gray-800">English [en] · MOBI · 2.1MB · 2010</div>
          </div>
        </div>
      </div>
    """.trimIndent()

    val results = AnnasArchiveClient.parseResults(html, "https://annas-archive.gl/search?q=Moby+Dick")
    assertEquals(1, results.size)
    val item = results.single()
    assertEquals(TextProvider.ANNAS_ARCHIVE, item.provider)
    assertEquals("Moby-Dick Or the Whale", item.title)
    assertEquals("Melville, Herman", item.author)
    assertEquals("EPUB", item.format)
    assertEquals("English", item.language)
    assertEquals("077484a10743e5dd5d151013e8c732f4", item.providerId)
    assertEquals("https://annas-archive.gl/md5/077484a10743e5dd5d151013e8c732f4", item.url)
    assertTrue(AnnasArchiveClient.needsAccess(403, "<title>DDoS-Guard</title>"))
  }

  @Test fun downloadBridgePreservesCaptureMetadataSafely() {
    val encoded = CatalogDownloadBridge.encode(
      downloadUrl = "https://cdn.example.com/dl?a=1&b=2",
      referer = "https://welib.gl/slow_download/abc/0/0",
      userAgent = "CustomAgent/1.0",
      filename = "attachment; filename=\"moby.pdf\"",
      mimeType = "application/pdf",
    )
    assertTrue(CatalogDownloadBridge.isDownloadCapture(encoded))
    val parsed = CatalogDownloadBridge.parse(encoded)!!
    assertEquals("https://cdn.example.com/dl?a=1&b=2", parsed.url)
    assertEquals("https://welib.gl/slow_download/abc/0/0", parsed.referer)
    assertEquals("CustomAgent/1.0", parsed.userAgent)
    assertEquals("application/pdf", parsed.mimeType)
  }

  @Test fun pdfCleanupDehyphenatesStripsRepeatedHeadersAndRejectsScannedPages() {
    val repeatedHeader = "Moby-Dick · Herman Melville"
    val pages = listOf(
      "$repeatedHeader\n\nChapter 1\nCall me Ishmael. Some years ago—never mind how long pre-\ncisely—having little money.\n\n1",
      "$repeatedHeader\n\nI thought I would sail about a little and see the wa-\ntery part of the world.\n\n2",
      "$repeatedHeader\n\nChapter 2\nThe carpet-bag had plenty of old clothes packed carefully inside.\n\n3",
    )
    val book = PdfTextCleanup.toBook(PdfExtraction("Moby-Dick", "Herman Melville", pages), "moby.pdf")
    assertEquals("Moby-Dick", book.title)
    assertEquals("Herman Melville", book.author)
    assertEquals(2, book.chapters.size)
    assertTrue(book.chapters[0].text.contains("precisely"))
    assertTrue(book.chapters[0].text.contains("watery"))
    assertFalse(book.chapters[0].text.contains(repeatedHeader))

    val scanned = PdfExtraction(null, null, listOf("", "  4  "))
    assertThrows(IllegalStateException::class.java) { PdfTextCleanup.toBook(scanned, "scan.pdf") }
  }

  @Test fun bookImportSupportsFb2XmlFb2ZipAndPluggedPdfExtractor() {
    val fb2 = """
      <?xml version="1.0" encoding="utf-8"?>
      <FictionBook>
        <description><title-info><book-title>Sample FB2</book-title><author><first-name>Jane</first-name><last-name>Doe</last-name></author></title-info></description>
        <body>
          <section><title><p>Opening</p></title><p>First sentence in chapter one.</p></section>
          <section><title><p>Second</p></title><p>Second sentence in chapter two.</p></section>
        </body>
        <body name="notes"><section><p>Footnote ignored</p></section></body>
      </FictionBook>
    """.trimIndent().toByteArray()

    assertEquals(TextSyncSource.NARRATION, BookImport.source(fb2, "sample.fb2", null))
    val fb2Book = BookImport.book(fb2, "sample.fb2")
    assertEquals("Sample FB2", fb2Book.title)
    assertEquals("Jane Doe", fb2Book.author)
    assertEquals(listOf("Opening", "Second"), fb2Book.chapters.map { it.title })

    val fb2Zip = ByteArrayOutputStream().also { out ->
      ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry("book.fb2"))
        zip.write(fb2)
        zip.closeEntry()
      }
    }.toByteArray()
    assertEquals(TextSyncSource.NARRATION, BookImport.source(fb2Zip, "sample.fb2.zip", "application/zip"))
    assertEquals("Sample FB2", BookImport.book(fb2Zip, "sample.fb2.zip").title)

    val previous = BookImport.pdfExtractor
    try {
      BookImport.pdfExtractor = PdfPageTextReader {
        PdfExtraction("Test PDF", "Author Name", listOf((1..25).joinToString(" ") { "word$it" }))
      }
      val pdfBytes = "%PDF-1.4\n1 0 obj\n".toByteArray()
      assertEquals(TextSyncSource.NARRATION, BookImport.source(pdfBytes, "book.pdf", "application/pdf"))
      val pdfBook = BookImport.book(pdfBytes, "book.pdf")
      assertEquals("Test PDF", pdfBook.title)
      assertEquals("Page 1", pdfBook.chapters.single().title)
    } finally {
      BookImport.pdfExtractor = previous
    }
  }

  @Test fun gutenbergFirstCatalogKeepsLibGenResultsWhenZLibraryNeedsAccess() {
    val emptyPg = object : BookCatalog {
      override fun search(query: String): List<BookEdition> = emptyList()
      override fun download(edition: BookEdition, renderedHtml: String?) = error("unused")
    }
    val accessZlib = object : BookCatalog {
      override fun search(query: String): List<BookEdition> = throw SiteAccessRequired("https://1lib.sk/s/book")
      override fun download(edition: BookEdition, renderedHtml: String?) = error("unused")
    }
    val libgenEdition = BookEdition("Test Book", "Test Author", "English", "PDF", null, "https://libgen.li/ads.php?md5=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", provider = TextProvider.LIBGEN, providerId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    val libgen = object : BookCatalog {
      override fun search(query: String): List<BookEdition> = listOf(libgenEdition)
      override fun download(edition: BookEdition, renderedHtml: String?) = error("unused")
    }
    val multi = GutenbergFirstCatalog(emptyPg, accessZlib, libgen, null, null)
    val auto = multi.lookup(BookLookupRequest("Test Book Author", "Test Book", "Test Author"))
    assertEquals(listOf(libgenEdition), auto.editions)
    assertNull(auto.accessUrl)
    assertTrue(auto.complete)
  }
}

package com.example.rivulet

import com.example.rivulet.data.lyrics.BookIdentityResolver
import com.example.rivulet.data.lyrics.BookTitleCleaner
import com.example.rivulet.data.lyrics.EpubExtractor
import com.example.rivulet.data.lyrics.EpubStructureParser
import com.example.rivulet.data.lyrics.IsbnValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubAndAlignmentTest {

  @Test
  fun testEpubStructureParserWithMockEpub() {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
      zos.putNextEntry(ZipEntry("META-INF/container.xml"))
      val containerXml = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
      """.trimIndent()
      zos.write(containerXml.toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
      val opfXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>The Adventures of Sherlock Holmes</dc:title>
            <dc:creator>Arthur Conan Doyle</dc:creator>
          </metadata>
          <manifest>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="c1" href="ch01.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="ch02.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine toc="ncx">
            <itemref idref="c1"/>
            <itemref idref="c2"/>
          </spine>
        </package>
      """.trimIndent()
      zos.write(opfXml.toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
      val ncxXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
          <navMap>
            <navPoint id="np1">
              <navLabel><text>A Scandal in Bohemia</text></navLabel>
              <content src="ch01.xhtml"/>
            </navPoint>
            <navPoint id="np2">
              <navLabel><text>The Red-Headed League</text></navLabel>
              <content src="ch02.xhtml"/>
            </navPoint>
          </navMap>
        </ncx>
      """.trimIndent()
      zos.write(ncxXml.toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("OEBPS/ch01.xhtml"))
      val ch1Html = """
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>Chapter 1</title></head>
          <body>
            <h1>A Scandal in Bohemia</h1>
            <p>To Sherlock Holmes she is always THE woman. I have seldom heard him mention her under any other name.</p>
            <p>In his eyes she eclipses and predominates the whole of her sex.</p>
          </body>
        </html>
      """.trimIndent()
      zos.write(ch1Html.toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("OEBPS/ch02.xhtml"))
      val ch2Html = """
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>Chapter 2</title></head>
          <body>
            <h1>The Red-Headed League</h1>
            <p>I had called upon my friend, Mr. Sherlock Holmes, one day in the autumn of last year and found him in deep conversation.</p>
          </body>
        </html>
      """.trimIndent()
      zos.write(ch2Html.toByteArray(Charsets.UTF_8))
      zos.closeEntry()
    }

    val epubBytes = baos.toByteArray()
    val book = EpubStructureParser.parse(ByteArrayInputStream(epubBytes))

    assertEquals("The Adventures of Sherlock Holmes", book.title)
    assertEquals("Arthur Conan Doyle", book.author)
    assertEquals(2, book.chapters.size)

    val ch1 = book.chapters[0]
    assertEquals(1, ch1.index)
    assertEquals("A Scandal in Bohemia", ch1.title)
    assertTrue("Should extract body text", ch1.text.contains("To Sherlock Holmes she is always THE woman"))
    assertTrue("Should compute word count", ch1.wordCount > 10)

    val ch2 = book.chapters[1]
    assertEquals(2, ch2.index)
    assertEquals("The Red-Headed League", ch2.title)
    assertTrue(ch2.text.contains("Mr. Sherlock Holmes"))
  }

  @Test
  fun testTxtExtractorChapterParsing() {
    val sampleTxt = """
      Chapter 1. The Boy Who Lived
      Mr. and Mrs. Dursley, of number four, Privet Drive, were proud to say that they were perfectly normal, thank you very much.

      Chapter 2. The Vanishing Glass
      Nearly ten years had passed since the Dursleys had woken up to find their nephew on the front step.
    """.trimIndent()

    val chapters = EpubExtractor.parseTextIntoChapters(sampleTxt)
    assertEquals(2, chapters.size)
    assertEquals("Chapter 1. The Boy Who Lived", chapters[0].title)
    assertEquals("Chapter 2. The Vanishing Glass", chapters[1].title)
    assertTrue(chapters[0].wordCount > 10)
  }

  @Test
  fun testIsbnValidatorCalculations() {
    assertTrue("0-306-40615-2 should be valid ISBN-10", IsbnValidator.isValid("0-306-40615-2"))
    assertTrue("080442957X should be valid ISBN-10 with check digit X", IsbnValidator.isValid("080442957X"))
    assertTrue("1844168603 should be valid ISBN-10", IsbnValidator.isValid("1844168603"))
    assertFalse("0-306-40615-3 has bad check digit", IsbnValidator.isValid("0-306-40615-3"))
    assertTrue("978-0-306-40615-7 should be valid ISBN-13", IsbnValidator.isValid("978-0-306-40615-7"))
    assertTrue("978-1-84416-860-6 should be valid ISBN-13", IsbnValidator.isValid("978-1-84416-860-6"))
    assertFalse("978-0-306-40615-0 has bad check digit", IsbnValidator.isValid("978-0-306-40615-0"))
    assertFalse("Empty string is not valid", IsbnValidator.isValid(""))
    assertFalse("Random characters are not valid", IsbnValidator.isValid("abcdefghijk"))

    val extracted = IsbnValidator.extractFirstValidIsbn("urn:isbn:978-1-84416-860-6 (pbk.)")
    assertEquals("978-1-84416-860-6", extracted)

    val extractedRaw = IsbnValidator.extractFirstValidIsbn("Book ID: 080442957X in catalog")
    assertNotNull(extractedRaw)
  }

  @Test
  fun testBookTitleCleanerResolution() {
    val book1 = BookIdentityResolver.resolve("[01] Soul Hunter.m4b", container = "Night Lords").title
    assertEquals("Soul Hunter", book1)

    val book2 = BookIdentityResolver.resolve("02 - Blood_Reaver.m4b", container = "Night Lords Omnibus").title
    assertEquals("Blood Reaver", book2)

    val book3 = BookIdentityResolver.resolve("01 - Chapter 1.mp3", container = "Ray Bradbury - Fahrenheit 451").title
    assertEquals("Fahrenheit 451", book3)

    val book4 = BookIdentityResolver.resolve("Aaron Dembski-Bowden - Void Stalker [Audiobook].mp3", container = "Warhammer").title
    assertEquals("Aaron Dembski-Bowden - Void Stalker", book4)

    val book5 = BookIdentityResolver.resolve("Audiobooks/Night Lords/[01] Soul Hunter.m4b", container = "Night Lords").title
    assertEquals("Soul Hunter", book5)

    assertTrue(BookTitleCleaner.isGenericTrackName("Chapter 1"))
    assertTrue(BookTitleCleaner.isGenericTrackName("Track 02"))
    assertTrue(BookTitleCleaner.isGenericTrackName("01"))
    assertTrue(BookTitleCleaner.isGenericTrackName("Part 3"))
    assertTrue(BookTitleCleaner.isGenericTrackName("Disc 1"))
    assertFalse(BookTitleCleaner.isGenericTrackName("Soul Hunter"))
    assertFalse(BookTitleCleaner.isGenericTrackName("The Hobbit"))
  }

  @Test
  fun testEpubStructureParserWithIsbnMetadata() {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
      zos.putNextEntry(ZipEntry("META-INF/container.xml"))
      zos.write("""<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""".toByteArray())
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("content.opf"))
      val opf = """
        <package>
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Soul Hunter</dc:title>
            <dc:creator>Aaron Dembski-Bowden</dc:creator>
            <dc:identifier id="BookId">urn:isbn:978-1-84416-860-6</dc:identifier>
          </metadata>
          <manifest>
            <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine>
            <itemref idref="c1"/>
          </spine>
        </package>
      """.trimIndent()
      zos.write(opf.toByteArray())
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("ch1.xhtml"))
      zos.write("""<html><body><h1>Prologue</h1><p>The stars were cold and unforgiving in the depths of space, watching silently over the fleet.</p></body></html>""".toByteArray())
      zos.closeEntry()
    }

    val book = EpubStructureParser.parse(ByteArrayInputStream(baos.toByteArray()))
    assertEquals("Soul Hunter", book.title)
    assertEquals("Aaron Dembski-Bowden", book.author)
    assertEquals("978-1-84416-860-6", book.isbn)
    assertEquals(1, book.chapters.size)
  }
}

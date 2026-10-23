package com.example.rivulet

import com.example.rivulet.data.AudioFile
import com.example.rivulet.data.lyrics.AlignmentStrategy
import com.example.rivulet.data.lyrics.AudioBookAligner
import com.example.rivulet.data.lyrics.EpubBook
import com.example.rivulet.data.lyrics.EpubChapter
import com.example.rivulet.data.lyrics.EpubExtractor
import com.example.rivulet.data.lyrics.EpubStructureParser
import com.example.rivulet.data.lyrics.PacedTimingEngine
import org.junit.Assert.assertEquals
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
      // container.xml
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

      // content.opf
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

      // toc.ncx
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

      // ch01.xhtml
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

      // ch02.xhtml
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
  fun testAudioBookAlignerTitleAndNumberMatching() {
    val audioFiles = listOf(
      AudioFile(index = 0, path = "Sherlock/01_A_Scandal_in_Bohemia.mp3", name = "01_A_Scandal_in_Bohemia.mp3", size = 5000L, downloadedBytes = 5000L, isAudio = true),
      AudioFile(index = 1, path = "Sherlock/02_The_Red_Headed_League.mp3", name = "02_The_Red_Headed_League.mp3", size = 6000L, downloadedBytes = 6000L, isAudio = true),
    )

    val chapters = listOf(
      EpubChapter(index = 1, id = "c1", href = "c1.xhtml", title = "Chapter 1: A Scandal in Bohemia", text = "Content 1", wordCount = 100),
      EpubChapter(index = 2, id = "c2", href = "c2.xhtml", title = "Chapter 2: The Red-Headed League", text = "Content 2", wordCount = 120),
    )

    val book = EpubBook("Sherlock Holmes", "Conan Doyle", chapters)
    val result = AudioBookAligner.align(audioFiles, book)

    assertEquals(AlignmentStrategy.EXACT_TITLE_MATCH, result.strategy)
    assertTrue("Confidence should be high", result.confidence >= 0.8f)
    assertEquals(2, result.trackToChapterMap.size)
    assertEquals("Chapter 1: A Scandal in Bohemia", result.trackToChapterMap[0]?.title)
    assertEquals("Chapter 2: The Red-Headed League", result.trackToChapterMap[1]?.title)
  }

  @Test
  fun testAudioBookAlignerSingleFileMultiChapter() {
    val audioFiles = listOf(
      AudioFile(index = 0, path = "Sherlock.m4b", name = "Sherlock.m4b", size = 50000L, downloadedBytes = 50000L, isAudio = true),
    )
    val chapters = listOf(
      EpubChapter(index = 1, id = "c1", href = "c1.xhtml", title = "Chapter 1", text = "Text 1", wordCount = 100),
      EpubChapter(index = 2, id = "c2", href = "c2.xhtml", title = "Chapter 2", text = "Text 2", wordCount = 100),
    )
    val book = EpubBook("Sherlock Holmes", "Conan Doyle", chapters)
    val result = AudioBookAligner.align(audioFiles, book)

    assertEquals(AlignmentStrategy.SINGLE_FILE_MULTI_CHAPTER, result.strategy)
    assertNotNull(result.trackToChapterMap[0])
    assertEquals("Chapter 1", result.trackToChapterMap[0]?.title)
  }

  @Test
  fun testPacedTimingEngineSimilarityAndAnchor() {
    val textA = "To Sherlock Holmes she is always the woman."
    val textB = "To Sherlock Holmes she was always the woman."
    val similarity = PacedTimingEngine.calculateSimilarity(textA, textB)
    assertTrue("High token overlap similarity expected", similarity > 0.7f)

    val lines = PacedTimingEngine.paceChapter(
      chapterText = "Mr and Mrs Dursley were proud to say they were normal. They were the last people you would expect to be involved in anything strange.",
      totalDurationMs = 20_000L,
    )
    assertTrue(lines.size >= 2)

    val matchIdx = PacedTimingEngine.findBestMatchingLineIndex(lines, "last people you would expect")
    assertEquals(1, matchIdx)
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
}

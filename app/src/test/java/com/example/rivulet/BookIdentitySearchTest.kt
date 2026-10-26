package com.example.rivulet

import com.example.rivulet.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test

class BookIdentitySearchTest {
  @Test fun selectedFileRecoversBookFromAnAuthorOnlyTorrent() {
    val identity = BookIdentityResolver.resolve("The Devil's Detective - Part 01.mp3", "Simon Kurt Unsworth", "Simon Kurt Unsworth", chapterTrack = true)
    assertEquals(BookIdentity("The Devil's Detective", "Simon Kurt Unsworth"), identity)
    assertEquals("The Devil's Detective Simon Kurt Unsworth", identity.query)
    assertEquals(identity, BookIdentityResolver.resolve("01 - The Devil's Detective-Part01.mp3", "Thomas Fool", "Simon Kurt Unsworth", chapterTrack = true))
    assertEquals(identity, BookIdentityResolver.resolve("The Devils Detective - 001.mp3", "Thomas Fool/01 - The Devil's Detective", "Simon Kurt Unsworth", chapterTrack = true))
  }

  @Test fun catalogTitleSurvivesEitherFilenameOrientationAndGenericChapters() {
    for (file in listOf("John Vaillant - The Golden Spruce.m4b", "The Golden Spruce - John Vaillant.m4b", "Chapter 01.mp3", "Chapter 2 - The Forest.mp3")) {
      assertEquals(BookIdentity("The Golden Spruce", "John Vaillant"), BookIdentityResolver.resolve(file, container = "John Vaillant", catalogTitle = "The Golden Spruce - John Vaillant"))
    }
  }

  @Test fun omnibusUsesSpecificBookFolderForNamedAndNumberedChapters() {
    for (file in listOf("Chapter 01.mp3", "01 - The Opening.mp3", "A Named Chapter.mp3")) {
      assertEquals("Soul Hunter", BookIdentityResolver.resolve(file, "Night Lords/[01] Soul Hunter", "Night Lords Omnibus", "Night Lords Omnibus - Aaron Dembski-Bowden", true).title)
    }
    assertEquals("Blood Reaver", BookIdentityResolver.resolve("02 - Blood_Reaver.m4b", container = "Night Lords Omnibus").title)
    assertEquals("Soul Hunter", BookIdentityResolver.resolve("[01] Soul Hunter.m4b", container = "Night Lords").query)
  }

  @Test fun ordinaryMultiTrackBookPrefersBookContextToNamedChapter() {
    assertEquals("The Golden Spruce", BookIdentityResolver.resolve("01 - The Forest.mp3", container = "The Golden Spruce", chapterTrack = true).title)
    assertEquals("The Hobbit", BookIdentityResolver.resolve("Concerning Hobbits.mp3", "The Hobbit", "Tolkien", chapterTrack = true).title)
    assertEquals("Last Wish Wedding: Widow's Point Island Trilogy, Book 1", BookIdentityResolver.resolve("Chapter 01.mp3", container = "Jeulia Hesse", catalogTitle = "Last Wish Wedding: Widow's Point Island Trilogy, Book 1 - Jeulia Hesse", chapterTrack = true).title)
  }

  @Test fun meaningfulNumbersSubtitlesAndAmbiguousHyphensAreRetained() {
    for (title in listOf("1984", "11.22.63", "2001 - A Space Odyssey", "The Road - A Novel", "Fahrenheit 451")) {
      assertEquals(title, BookIdentityResolver.resolve("$title.m4b").title)
    }
    assertEquals("Fahrenheit 451", BookIdentityResolver.resolve("Chapter 01.mp3", container = "Ray Bradbury - Fahrenheit 451").title)
    assertEquals("Soul Hunter", BookIdentityResolver.resolve("[01] Soul Hunter.m4b", container = "Night Lords").title)
  }

  @Test fun authorAndEnglishRankExactTitleBeforeBroadTokenMatches() {
    fun edition(title: String, author: String = "John Vaillant", language: String = "English", id: String = title) =
      BookEdition(title, author, language, "EPUB", null, "https://zlib.li/book/$id")
    val exact = edition("The Golden Spruce")
    val wrongAuthor = edition("The Golden Spruce", "Someone Else", id = "wrong")
    val foreign = edition("The Golden Spruce", language = "French", id = "fr")
    val anthology = edition("The Golden Spruce and Other Stories")
    val unrelated = edition("A Golden Opportunity")
    assertEquals(listOf(exact, foreign, wrongAuthor, anthology, unrelated), BookSearch.rank(listOf(unrelated, anthology, wrongAuthor, foreign, exact, exact.copy(url = exact.url + "#details")), exact.title, exact.title, exact.author))
    assertTrue(BookSearch.matchesTitle(exact, "The Golden Spruce"))
    assertFalse(BookSearch.matchesTitle(unrelated, "The Golden Spruce"))
  }

  @Test fun parsedAndRenderedCardsShareRankingAndUrlDeduplication() {
    val html = """<z-bookcard title="The Golden Spruce Workbook" href="/book/2" author="Other"/><z-bookcard title="The Golden Spruce" href="/book/1" author="John Vaillant"/>"""
    val cards = ZLibraryClient.parseResults(html, ZLibraryClient.BASE)
    assertEquals("The Golden Spruce", BookSearch.rank(cards, "The Golden Spruce John Vaillant", "The Golden Spruce", "John Vaillant").first().title)
  }
}

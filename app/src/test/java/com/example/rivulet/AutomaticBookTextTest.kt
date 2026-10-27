package com.example.rivulet

import com.example.rivulet.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticBookTextTest {
  private val identity = BookIdentity("The Case Study", "Nicole Lundrigan")
  private val edition = BookEdition("The Case Study", "Nicole Lundrigan", "English", "EPUB", null, "https://zlib.li/book/1")

  @Test fun autoAttachRequiresFullTitleAuthorLanguageAndFormat() {
    assertEquals(edition, BookSearch.automaticEdition(listOf(edition), identity))
    assertEquals(edition, BookSearch.automaticEdition(listOf(edition), identity.copy(title = "The Case Study!")))
    assertNull(BookSearch.automaticEdition(listOf(edition), identity.copy(author = "")))
    for (other in listOf(edition.copy(title = "The Case Study Workbook"), edition.copy(author = "Another Author"),
      edition.copy(language = "French"), edition.copy(language = ""), edition.copy(format = "PDF"))) {
      assertNull(BookSearch.automaticEdition(listOf(other), identity))
    }
  }

  @Test fun clearMatchBeatsUnrelatedSearchResultsAndAllowsReversedAuthorOrder() {
    val reversed = edition.copy(author = "Lundrigan, Nicole")
    assertEquals(reversed, BookSearch.automaticEdition(listOf(edition.copy(title = "A Different Book", url = "https://zlib.li/book/2"), reversed), identity))
    val resolved = BookIdentityResolver.resolve("Nicole Lundrigan - The Case Study.m4b")
    assertEquals(identity, resolved)
  }

  @Test fun futurePreparationDoesNotReplaceCurrentReadyStatus() {
    assertNull(ReadAlongState(phase = ReadAlongPhase.READY, preparingAhead = true).progressLabel)
    assertEquals("Importing companion text…", ReadAlongState(phase = ReadAlongPhase.LOADING, message = "Importing companion text…").progressLabel)
  }
}

package com.example.audiotorrent

import androidx.compose.ui.graphics.luminance
import com.example.audiotorrent.data.AudioFile
import com.example.audiotorrent.data.lyrics.*
import com.example.audiotorrent.theme.ReaderThemePreset
import com.example.audiotorrent.theme.appColorSchemeFor
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
    assertEquals(TextSyncSource.NARRATION, BookImport.source("%PDF-1.4".toByteArray(), "book.pdf", "application/pdf"))
    assertThrows(IllegalStateException::class.java) { BookImport.source("AT&TFORM".toByteArray(), "book.djvu", "image/vnd.djvu") }
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
    assertTrue(ZLibraryClient.needsAccess(513, "<title>Verifying your browser | DiamWall</title>"))
    assertTrue(ZLibraryClient.needsAccess(200, "<title>Sign in</title><a href='/login'>Sign in to download</a>"))
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

    val mirrorBooks = ZLibraryClient.parseResults(html, "https://z-library.sk/s/sample")
    assertEquals("https://z-library.sk/book/123/sample", mirrorBooks.first().url)
  }

  @Test fun nativeLookupRejectsLookalikeDomains() {
    assertTrue(ZLibraryClient.parseResults("<a href='https://evilzlib.li/book/1'>Fake</a>", ZLibraryClient.BASE).isEmpty())
    assertThrows(IllegalArgumentException::class.java) { ZLibraryClient.validateUrl("http://zlib.li/book/1") }
  }

  @Test fun liveOctober2026CardShapeUsesSlotsAndIgnoresHiddenPromotions() {
    // Sanitized public markup inspected on zlib.li/s/Frankenstein on 2026-10-24.
    val html = """<z-bookcard href="/book/xK8evYX2Dj/frankenstein.html" download="/dl/vBzZ3O5p2w" extension="epub" language="English"><div slot="title">Frankenstein</div><div slot="author">Mary Shelley</div></z-bookcard><div hidden><z-bookcard href="/book/hidden/fake" extension="epub"><div slot="title">Hidden promotion</div></z-bookcard></div><z-bookcard href="/book/pdf/other" extension="pdf"><div slot="title">PDF edition</div></z-bookcard><z-bookcard href="/book/azw3/skip" extension="azw3"><div slot="title">AZW3 edition</div></z-bookcard>"""
    val editions = ZLibraryClient.parseResults(html, ZLibraryClient.BASE)
    assertEquals(2, editions.size)
    assertEquals("Frankenstein", editions.first().title)
    assertEquals("Mary Shelley", editions.first().author)
    assertEquals("${ZLibraryClient.BASE}/dl/vBzZ3O5p2w", editions.first().downloadUrl)
    assertEquals("PDF", editions[1].format)
  }

  @Test fun textProviderLabelsMatchUserFacingIntent() {
    assertEquals("Automatic (All Sources)", TextProvider.AUTOMATIC.label)
    assertEquals("Public Domain (Gutenberg)", TextProvider.GUTENBERG.label)
    assertEquals("LibGen", TextProvider.LIBGEN.label)
    assertEquals("Z-Library", TextProvider.ZLIBRARY.label)
    assertEquals("Anna's Archive", TextProvider.ANNAS_ARCHIVE.label)
    assertEquals("WeLib", TextProvider.WELIB.label)
  }

  @Test fun zlibraryPrewarmerHandlesClearanceCheckSafely() {
    // In headless JVM unit test environment, CookieManager/WebView gracefully handles absent webview runtime
    assertFalse(ZLibraryPrewarmer.hasClearanceCookie())
  }

  @Test fun binarySearchStickyTrackingKeepsSentenceActiveDuringPauses() {
    val line1 = TimedLine(1, 10_000, 14_000, "First sentence here.")
    val line2 = TimedLine(2, 16_000, 20_000, "Second sentence follows after pause.")
    val lines = listOf(line1, line2)

    // Prior to first sentence
    assertEquals(-1, ReadAlongTimeline.activeIndex(lines, 9_999, 0))

    // During first sentence
    assertEquals(0, ReadAlongTimeline.activeIndex(lines, 10_000, 0))
    assertEquals(0, ReadAlongTimeline.activeIndex(lines, 12_000, 0))
    assertEquals(0, ReadAlongTimeline.activeIndex(lines, 13_999, 0))

    // In the pause between first and second sentence (14_000 to 16_000): sticky line 0
    assertEquals(0, ReadAlongTimeline.activeIndex(lines, 14_500, 0))
    assertEquals(0, ReadAlongTimeline.activeIndex(lines, 15_999, 0))

    // Exactly at second sentence start
    assertEquals(1, ReadAlongTimeline.activeIndex(lines, 16_000, 0))
    assertEquals(1, ReadAlongTimeline.activeIndex(lines, 18_000, 0))

    // Within 3 seconds after last sentence ends: sticky line 1
    assertEquals(1, ReadAlongTimeline.activeIndex(lines, 21_000, 0))
    // Past 3 seconds after last sentence: inactive
    assertEquals(-1, ReadAlongTimeline.activeIndex(lines, 24_000, 0))
  }

  @Test fun withTimingAlwaysPopulatesCanonicalText() {
    val sentence = BookSentence(42L, 1, "Chapter 1", "The true canonical text from epub.")
    // Database returns empty text to save 75% space
    val dbTimedLine = TimedLine(42L, 5_000, 8_000, "")
    val hydrated = sentence.withTiming(dbTimedLine)

    assertEquals(42L, hydrated.id)
    assertEquals(5_000L, hydrated.startTimeMs)
    assertEquals(8_000L, hydrated.endTimeMs)
    assertEquals("The true canonical text from epub.", hydrated.text)
    assertTrue(hydrated.isTimed)
  }

  @Test fun syncCoverageComputesContiguousAheadBufferAndTrackRatio() {
    val windows = listOf(0L..20_000L, 20_000L..40_000L, 40_000L..60_000L, 120_000L..140_000L)
    val merged = ReadAlongTimeline.mergeWindows(windows, 200_000L)
    assertEquals(listOf(0L..60_000L, 120_000L..140_000L), merged)

    val stats = ReadAlongTimeline.computeSyncCoverage(
      windows = windows,
      positionMs = 15_000L,
      durationMs = 200_000L,
      matchedSentences = 30,
      totalBookSentences = 1000,
      isSyncingNow = false,
      syncPausedReason = "Balanced (+3m limit)",
    )
    assertEquals(45_000L, stats.aheadSyncedMs)
    assertEquals(0.4f, stats.trackTimedRatio, 0.001f)
    assertEquals(30, stats.matchedSentences)
    assertEquals(1000, stats.totalBookSentences)
    assertEquals("Balanced (+3m limit)", stats.syncPausedReason)
    assertNull(stats.syncSpeedRatio)
    assertEquals(1.0f, stats.playbackSpeed, 0.001f)

    val withSpeed = ReadAlongTimeline.computeSyncCoverage(
      windows = windows,
      positionMs = 15_000L,
      durationMs = 200_000L,
      matchedSentences = 30,
      totalBookSentences = 1000,
      isSyncingNow = true,
      syncPausedReason = null,
      syncSpeedRatio = 2.4f,
      playbackSpeed = 1.5f,
    )
    assertEquals(2.4f, withSpeed.syncSpeedRatio!!, 0.001f)
    assertEquals(1.5f, withSpeed.playbackSpeed, 0.001f)
  }

  @Test fun syncSchedulerSprintsBelowWallRunwayAndAccountsForPlaybackSpeed() {
    assertEquals(SyncBudget(4, 0L), SyncScheduler.computeBudget(ahead = false, isImmediate = true, effectiveAheadWallMs = 0L, transcribeFullTrack = false, isPlaying = true, syncMode = SyncMode.BALANCED))
    // 60s ahead audio at 2.0x speed is only 30s wall runway (< 45s) -> still sprints with 4 threads, 0ms cooldown.
    val runway2x = SyncScheduler.effectiveWallRunwayMs(60_000L, 2.0f)
    assertEquals(30_000L, runway2x)
    assertEquals(SyncBudget(4, 0L), SyncScheduler.computeBudget(ahead = true, isImmediate = false, effectiveAheadWallMs = runway2x, transcribeFullTrack = false, isPlaying = true, syncMode = SyncMode.BALANCED))
    // 60s ahead audio at 1.0x speed is 60s wall runway (45s..90s) -> tapers to 3 threads, 300ms cooldown.
    val runway1x = SyncScheduler.effectiveWallRunwayMs(60_000L, 1.0f)
    assertEquals(60_000L, runway1x)
    assertEquals(SyncBudget(3, 300L), SyncScheduler.computeBudget(ahead = true, isImmediate = false, effectiveAheadWallMs = runway1x, transcribeFullTrack = false, isPlaying = true, syncMode = SyncMode.BALANCED))
    // >= 90s runway drops to 2 threads + SyncMode cooldown.
    assertEquals(SyncBudget(2, SyncMode.BALANCED.lookaheadCooldownMs), SyncScheduler.computeBudget(ahead = true, isImmediate = false, effectiveAheadWallMs = 95_000L, transcribeFullTrack = false, isPlaying = true, syncMode = SyncMode.BALANCED))
    // Paused buffer building sprints to build a 1m..3m cushion instead of starving at 3000ms sleeps.
    assertEquals(SyncBudget(4, 0L), SyncScheduler.computeBudget(ahead = true, isImmediate = false, effectiveAheadWallMs = 20_000L, transcribeFullTrack = true, isPlaying = false, syncMode = SyncMode.FULL_TRACK))
    assertEquals(SyncBudget(3, 300L), SyncScheduler.computeBudget(ahead = true, isImmediate = false, effectiveAheadWallMs = 120_000L, transcribeFullTrack = false, isPlaying = false, syncMode = SyncMode.EXTENDED))
    assertEquals(SyncBudget(2, SyncMode.EXTENDED.lookaheadCooldownMs), SyncScheduler.computeBudget(ahead = true, isImmediate = false, effectiveAheadWallMs = 200_000L, transcribeFullTrack = false, isPlaying = false, syncMode = SyncMode.EXTENDED))
    // Decode bounds: 23s narration vs 24s script.
    assertEquals(18_500L to 23_000L, SyncScheduler.windowDecodeBounds(20_000L, 40_000L, 100_000L, isScript = false))
    assertEquals(18_000L to 24_000L, SyncScheduler.windowDecodeBounds(20_000L, 40_000L, 100_000L, isScript = true))
  }

  @Test fun hintScopedMatcherFastPathMatchesSequentialPassages() {
    val sentences = (1..20).map { i ->
      BookSentence(i.toLong(), 1, "Chapter 1", "Sentence number $i describes a distinct voyage across the quiet northern ocean.")
    }
    val matcher = NarrationMatcher(sentences)
    val words = timedWords(sentences[9].text + " " + sentences[10].text)
    val matched = matcher.align(words, hintSentence = 8)
    assertEquals(listOf(10L, 11L), matched.map { it.id })
  }

  @Test fun appColorSchemesPreservePolarityAndContrastAcrossAllPresets() {
    for (preset in ReaderThemePreset.entries) {
      val scheme = appColorSchemeFor(preset)
      val bgLum = scheme.background.luminance()
      val fgLum = scheme.onBackground.luminance()
      val containerLum = scheme.primaryContainer.luminance()
      val onContainerLum = scheme.onPrimaryContainer.luminance()
      if (preset.isLight) {
        assertTrue("${preset.name} background should be light", bgLum > 0.5f)
        assertTrue("${preset.name} text should contrast darker than background", fgLum < 0.1f)
        assertTrue("${preset.name} onPrimaryContainer should contrast darker than primaryContainer", onContainerLum < containerLum)
      } else {
        assertTrue("${preset.name} background should be dark", bgLum < 0.05f)
        assertTrue("${preset.name} text should contrast lighter than background", fgLum > 0.5f)
        assertTrue("${preset.name} onPrimaryContainer should contrast lighter than primaryContainer", onContainerLum > containerLum)
      }
      assertNotEquals(scheme.primary, scheme.background)
      assertNotEquals(scheme.surfaceContainerLowest, scheme.primary)
    }
  }

  @Test fun syncModesExposeFourTiersAndCorrectLookaheadHorizons() {
    assertEquals(
      listOf(SyncMode.PLAYHEAD_ONLY, SyncMode.BALANCED, SyncMode.EXTENDED, SyncMode.FULL_TRACK),
      SyncMode.entries,
    )
    val oneHourMs = 3_600_000L
    assertEquals(3, SyncMode.PLAYHEAD_ONLY.horizonWindows(0L, oneHourMs))
    assertEquals(15, SyncMode.BALANCED.horizonWindows(0L, oneHourMs))
    assertEquals(45, SyncMode.EXTENDED.horizonWindows(0L, oneHourMs))
    assertEquals(180, SyncMode.FULL_TRACK.horizonWindows(0L, oneHourMs))

    assertEquals(60_000L, SyncMode.PLAYHEAD_ONLY.horizonEndMs(0L, oneHourMs))
    assertEquals(80_000L, SyncMode.PLAYHEAD_ONLY.horizonEndMs(20_000L, oneHourMs))
    assertEquals(300_000L, SyncMode.BALANCED.horizonEndMs(0L, oneHourMs))
    assertEquals(420_000L, SyncMode.BALANCED.horizonEndMs(120_000L, oneHourMs))
    assertEquals(900_000L, SyncMode.EXTENDED.horizonEndMs(0L, oneHourMs))
    assertEquals(1_200_000L, SyncMode.EXTENDED.horizonEndMs(300_000L, oneHourMs))
    assertEquals(oneHourMs, SyncMode.FULL_TRACK.horizonEndMs(0L, oneHourMs))

    assertEquals(SyncMode.FULL_TRACK, SyncMode.fromPersisted("CHARGING_FULL"))
    assertEquals(SyncMode.EXTENDED, SyncMode.fromPersisted("EXTENDED"))
    assertEquals(SyncMode.BALANCED, SyncMode.fromPersisted("UNKNOWN"))
  }

  @Test fun hysteresisSyncSchedulerRestsUntilLowWaterAndRefillsToHighWater() {
    val dur = 3_600_000L
    // Playhead: 1m (60s) top, rests until 40s remain while playing, paused syncs current passage only.
    assertFalse(
      SyncScheduler.shouldSyncWithHysteresis(
        restingAtHorizon = true, mode = SyncMode.PLAYHEAD_ONLY, fullTrack = false,
        isPlaying = true, positionMs = 15_000L, durationMs = dur,
        aheadSyncedMs = 45_000L, activeWindowSynced = true, hasUnsyncedAheadWithinHorizon = true,
      ),
    )
    assertTrue(
      SyncScheduler.shouldSyncWithHysteresis(
        restingAtHorizon = true, mode = SyncMode.PLAYHEAD_ONLY, fullTrack = false,
        isPlaying = true, positionMs = 20_000L, durationMs = dur,
        aheadSyncedMs = 40_000L, activeWindowSynced = true, hasUnsyncedAheadWithinHorizon = true,
      ),
    )
    assertFalse(
      SyncScheduler.shouldSyncWithHysteresis(
        restingAtHorizon = false, mode = SyncMode.PLAYHEAD_ONLY, fullTrack = false,
        isPlaying = false, positionMs = 5_000L, durationMs = dur,
        aheadSyncedMs = 15_000L, activeWindowSynced = true, hasUnsyncedAheadWithinHorizon = true,
      ),
    )

    // Balanced: 5m (300s) top, rests until 3m (180s) remain.
    assertFalse(
      SyncScheduler.shouldSyncWithHysteresis(
        restingAtHorizon = true, mode = SyncMode.BALANCED, fullTrack = false,
        isPlaying = false, positionMs = 100_000L, durationMs = dur,
        aheadSyncedMs = 200_000L, activeWindowSynced = true, hasUnsyncedAheadWithinHorizon = true,
      ),
    )
    assertTrue(
      SyncScheduler.shouldSyncWithHysteresis(
        restingAtHorizon = true, mode = SyncMode.BALANCED, fullTrack = false,
        isPlaying = false, positionMs = 120_000L, durationMs = dur,
        aheadSyncedMs = 180_000L, activeWindowSynced = true, hasUnsyncedAheadWithinHorizon = true,
      ),
    )

    // Extended: 15m (900s) top, rests until 10m (600s) remain, or stops at track end.
    assertFalse(
      SyncScheduler.shouldSyncWithHysteresis(
        restingAtHorizon = true, mode = SyncMode.EXTENDED, fullTrack = false,
        isPlaying = true, positionMs = 250_000L, durationMs = dur,
        aheadSyncedMs = 650_000L, activeWindowSynced = true, hasUnsyncedAheadWithinHorizon = true,
      ),
    )
    assertTrue(
      SyncScheduler.shouldSyncWithHysteresis(
        restingAtHorizon = true, mode = SyncMode.EXTENDED, fullTrack = false,
        isPlaying = true, positionMs = 300_000L, durationMs = dur,
        aheadSyncedMs = 600_000L, activeWindowSynced = true, hasUnsyncedAheadWithinHorizon = true,
      ),
    )
    assertFalse(
      SyncScheduler.shouldSyncWithHysteresis(
        restingAtHorizon = true, mode = SyncMode.EXTENDED, fullTrack = false,
        isPlaying = true, positionMs = 3_300_000L, durationMs = dur,
        aheadSyncedMs = 300_000L, activeWindowSynced = true, hasUnsyncedAheadWithinHorizon = false,
      ),
    )
  }

  @Test fun maxStickyGapPreventsStaleHighlightAcrossUntranscribedSeekGaps() {
    val lines = listOf(
      TimedLine(1, 1_000, 4_000, "Opening sentence."),
      TimedLine(2, 300_000, 304_000, "Sentence five minutes later."),
    )
    // Within 3s trailing window: sticky holds
    assertEquals(0, ReadAlongTimeline.activeIndex(lines, 6_500, 0, maxStickyGapMs = 6_000L))
    // Far into untranscribed gap: highlight clears instead of sticking for 5 minutes
    assertEquals(-1, ReadAlongTimeline.activeIndex(lines, 120_000, 0, maxStickyGapMs = 6_000L))
    // Default unbounded sticky mode preserves existing NARRATION behavior
    assertEquals(0, ReadAlongTimeline.activeIndex(lines, 120_000, 0))
  }

  private fun timedWords(text: String) = ReadAlongText.tokens(text).mapIndexed { i, word -> SpeechWord(word, i * 500L, (i + 1) * 500L) }
  private fun audio(index: Int, path: String) = AudioFile(index, path, path.substringAfterLast('/'), 100, 100, true)
}

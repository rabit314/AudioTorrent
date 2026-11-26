package com.example.rivulet.data.lyrics

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.rivulet.ui.player.ReaderParagraph
import com.example.rivulet.ui.player.readerParagraphs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

data class ReaderSearchHit(
  val sentenceId: Long,
  val chapterIndex: Int,
  val chapterTitle: String,
  val text: String,
  val startTimeMs: Long,
  val isTimed: Boolean,
)

/** App-scoped source of truth. Player snapshots enter on Main; no player is accessed by IO work. */
class ReadAlongRepository(
  private val context: Context,
  private val store: ReadAlongStore,
  private val decoder: ReadAlongAudioDecoder,
  private val speech: SpeechRecognizer = WhisperEngine(),
  private val models: SpeechModelManager = SpeechModelManager(context),
  private val library: BookCatalog = GutenbergFirstCatalog(),
  private val skipSilentScriptWindows: Boolean = speech is WhisperEngine,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val mutable = MutableStateFlow(ReadAlongState())
  val state = mutable.asStateFlow()
  val readerPreferences = ReaderPreferences(context)
  private val catalogMetadata = com.example.rivulet.data.BookCatalogMetadata(context)
  private var generation = 0L
  private var loadJob: Job? = null
  private var importJob: Job? = null
  private var analysisJob: Job? = null
  private var modelJob: Job? = null
  private var lookupJob: Job? = null
  private var offsetJob: Job? = null
  private var bufferRetryJob: Job? = null
  private var exhaustedBase: Long? = null
  private var searchKey: String? = null
  private var searchSucceeded = false
  private var searchResults: List<BookEdition> = emptyList()
  private data class Lookup(val track: ReadAlongTrack, val version: Long, val request: Long,
    val query: String, val title: String, val author: String, var titleOnly: Boolean = false,
    val automaticKey: String? = null, val selectedTracks: Set<Int> = emptySet(), val provider: TextProvider = TextProvider.AUTOMATIC,
    var prewarmAttempted: Boolean = false)
  private data class AutomaticResult(val editions: List<BookEdition>, val message: String?, val accessUrl: String? = null)
  private val automaticAttempts = mutableMapOf<String, AutomaticResult?>()
  private data class Download(val track: ReadAlongTrack, val version: Long, val edition: BookEdition, val indices: Set<Int>)
  private var pendingDownload: Download? = null
  private var lookup: Lookup? = null
  private var lookupSequence = 0L
  private var recoveryAction: (() -> Unit)? = null
  private var source: StoredReadAlongBook? = null
  private var sentences: List<BookSentence> = emptyList()
  private var sentenceTokenCounts = IntArray(0)
  private var cachedParagraphs: List<ReaderParagraph> = emptyList()
  private var cachedWindows: MutableList<LongRange> = ArrayList()
  private var matcher: NarrationMatcher? = null
  private var timings = LinkedHashMap<Long, TimedLine>()
  private var positionMs = 0L
  private var durationMs = 0L
  private var playbackSpeed = 1.0f
  private var measuredSyncSpeedRatio: Float? = null
  private var playbackReady = false
  private var isPlaying = true
  private var visible = false
  private var estimated = false
  private var chapterHint: Int? = null
  private var lastMatchedSentenceHint: Int? = null
  private var transcribeFullTrack = false
  private var inFlightWindowStartMs: Long? = null
  private var inFlightWindowEndMs: Long? = null
  private var inFlightWindowLines: List<TimedLine> = emptyList()
  private var correctionJob: Job? = null
  private var retiredAnalysis: Job? = null
  private var correctionSequence = 0L
  private var pendingReference: SyncReference? = null
  private data class UndoCorrection(val target: SyncTarget, val offset: Long, val chapter: Int?, val sentenceHint: Int?)
  private var undoCorrection: UndoCorrection? = null
  @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
  private val memoryCallbacks = object : ComponentCallbacks2 {
    override fun onConfigurationChanged(newConfig: Configuration) {}
    override fun onLowMemory() {
      speech.release()
      decoder.release()
      if (transcribeFullTrack) setTranscribeFullTrack(false)
    }
    override fun onTrimMemory(level: Int) {
      if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
        speech.release()
        decoder.release()
      }
      if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && transcribeFullTrack) {
        setTranscribeFullTrack(false)
      }
    }
  }
  init {
    BookImport.init(context)
    context.applicationContext.registerComponentCallbacks(memoryCallbacks)
  }

  fun activate(track: ReadAlongTrack, initialPositionMs: Long = 0, initialDurationMs: Long = 0) {
    if (mutable.value.track == track) return
    invalidateCorrection()
    generation++
    lookup?.automaticKey?.let { if (lookupJob?.isActive == true && automaticAttempts[it] == null) automaticAttempts.remove(it) }
    loadJob?.cancel(); importJob?.cancel(); analysisJob?.cancel(); lookupJob?.cancel(); bufferRetryJob?.cancel()
    decoder.release()
    pendingDownload = null
    source = null; sentences = emptyList(); sentenceTokenCounts = IntArray(0); cachedParagraphs = emptyList()
    cachedWindows.clear(); matcher = null; timings.clear(); estimated = false
    measuredSyncSpeedRatio = null
    transcribeFullTrack = false; inFlightWindowStartMs = null; inFlightWindowEndMs = null; inFlightWindowLines = emptyList()
    searchKey = null; searchSucceeded = false; searchResults = emptyList(); lookupSequence++; lookup = null; recoveryAction = null
    positionMs = initialPositionMs.coerceAtLeast(0); durationMs = initialDurationMs.coerceAtLeast(0); playbackReady = false
    selectedHint = null; chapterHint = null; lastMatchedSentenceHint = null; exhaustedBase = null
    mutable.value = ReadAlongState(track = track, phase = ReadAlongPhase.LOADING)
    load(track, generation)
  }

  private fun load(track: ReadAlongTrack, version: Long) {
    exhaustedBase = null
    loadJob?.cancel()
    loadJob = scope.launch {
      try {
        offsetJob?.join()
        var saved = withContext(Dispatchers.IO) {
          Triple(store.book(track), store.offset(track), store.chapterHint(track))
        }
        if (!current(track, version)) return@launch
        if (saved.first == null &&
          catalogMetadata.isScriptModeEnabled(track.infoHash) &&
          catalogMetadata.book(track.infoHash)?.canAutoMatchText != false
        ) {
          val scriptBook = createScriptBook(
            track = track,
            title = catalogMetadata.title(track.infoHash).orEmpty(),
            trackTitle = "Part ${track.fileIndex + 1}",
          )
          withContext(Dispatchers.IO) { store.attach(track, scriptBook, setOf(track.fileIndex)) }
          if (!current(track, version)) return@launch
          saved = Triple(scriptBook, 0L, null)
        }
        val book = saved.first
        if (book == null) { mutable.value = ReadAlongState(track = track); return@launch }
        val existing = withContext(Dispatchers.IO) { store.timings(track, book.id) }
        val storedWindows = withContext(Dispatchers.IO) { store.windows(track, book.id) }
        if (!current(track, version)) return@launch
        val indices = withContext(Dispatchers.IO) { store.associatedTracks(book.id) }
        if (!current(track, version)) return@launch

        if (book.source == TextSyncSource.WHISPER_SCRIPT) {
          val structured = withContext(Dispatchers.Default) {
            val valid = existing.filter { it.text.isNotBlank() && it.isTimed }.sortedBy { it.startTimeMs }
            TranscriptAssembler.applyParagraphBreaks(valid)
          }
          val chapterTitle = book.book.chapters.firstOrNull()?.title ?: book.book.title
          val (loadedSentences, tokenCounts, initialParagraphs) = withContext(Dispatchers.Default) {
            val s = structured.map { BookSentence(it.id, 1, chapterTitle, it.text, it.isParagraphStart) }
            val counts = IntArray(s.size) { i -> ReadAlongText.tokens(s[i].text).size.coerceAtLeast(1) }
            Triple(s, counts, readerParagraphs(structured, book.source))
          }
          if (!current(track, version)) return@launch
          source = book
          sentences = loadedSentences
          sentenceTokenCounts = tokenCounts
          cachedWindows = storedWindows.toMutableList()
          matcher = null
          chapterHint = null
          selectedHint = null
          timings = structured.associateByTo(LinkedHashMap()) { it.id }
          cachedParagraphs = initialParagraphs
          transcribeFullTrack = (readerPreferences.syncMode.value == SyncMode.FULL_TRACK)
          val matched = structured.size
          val coverage = buildCoverageStats(matched, isSyncingNow = false)
          val syncProg = coverage.trackTimedRatio.takeIf { it > 0f }
          mutable.value = ReadAlongState(
            track = track, bookId = book.id, title = book.book.title, isbn = null,
            chapters = book.book.chapters, offsetMs = saved.second, phase = ReadAlongPhase.READY,
            source = book.source, trackIndices = indices, lines = structured, paragraphs = cachedParagraphs,
            matchedSentences = matched,
            activeLineIndex = ReadAlongTimeline.activeIndex(structured, positionMs, saved.second, maxStickyGapMs = SCRIPT_STICKY_GAP_MS),
            syncProgress = syncProg, syncCoverage = coverage, transcribingFullTrack = transcribeFullTrack,
          )
          loadJob = null
          scheduleAnalysis()
          return@launch
        }

        val loaded = withContext(Dispatchers.Default) { ReadAlongText.sentences(book.book, book.id) }
        if (!current(track, version)) return@launch
        val (loadedMatcher, tokenCounts, initialParagraphs) = withContext(Dispatchers.Default) {
          val counts = IntArray(loaded.size) { i -> ReadAlongText.tokens(loaded[i].text).size.coerceAtLeast(1) }
          val linesForParagraphs = if (book.source == TextSyncSource.LRC || book.source == TextSyncSource.SRT) {
            LrcParser.detectAndParse(book.book.chapters.joinToString("\n") { it.text })
          } else {
            loaded.map { it.withTiming(null) }
          }
          Triple(
            NarrationMatcher(loaded),
            counts,
            readerParagraphs(linesForParagraphs, book.source),
          )
        }
        if (!current(track, version)) return@launch
        source = book; sentences = loaded; sentenceTokenCounts = tokenCounts
        cachedWindows = storedWindows.toMutableList(); matcher = loadedMatcher
        chapterHint = saved.third
        selectedHint = saved.third?.let { hint -> loaded.indexOfFirst { it.chapterIndex == hint }.takeIf { it >= 0 } }
        timings = existing.associateByTo(LinkedHashMap()) { it.id }
        if (book.source == TextSyncSource.LRC || book.source == TextSyncSource.SRT) {
          timings = LrcParser.detectAndParse(book.book.chapters.joinToString("\n") { it.text }).associateByTo(LinkedHashMap()) { it.id }
          sentences = timings.values.map { BookSentence(it.id, 1, book.book.title, it.text) }
          sentenceTokenCounts = IntArray(sentences.size) { i -> ReadAlongText.tokens(sentences[i].text).size.coerceAtLeast(1) }
        }
        cachedParagraphs = initialParagraphs
        interpolateGaps()
        val lines = sentences.map { it.withTiming(timings[it.id]) }
        val matched = lines.count { it.isTimed }
        val coverage = buildCoverageStats(matched, isSyncingNow = false)
        val syncProg = coverage.trackTimedRatio.takeIf { it > 0f }
          ?: if (sentences.isNotEmpty() && matched > 0) (matched.toFloat() / sentences.size).coerceIn(0f, 1f) else null
        mutable.value = ReadAlongState(track = track, bookId = book.id, title = book.book.title, isbn = book.book.isbn,
          chapters = book.book.chapters, offsetMs = saved.second, phase = ReadAlongPhase.READY, source = book.source, trackIndices = indices,
          lines = lines, paragraphs = cachedParagraphs, matchedSentences = matched,
          activeLineIndex = ReadAlongTimeline.activeIndex(lines, positionMs, saved.second),
          syncProgress = syncProg, syncCoverage = coverage)
        loadJob = null
        scheduleAnalysis()
      } catch (error: Exception) {
        if (current(track, version)) loadJob = null
        fail(error, track, version)
        offerRecovery(track, version, "Retry loading text") { load(track, version) }
      }
    }
  }

  fun setVisible(value: Boolean) {
    visible = value
    // Let a bounded in-flight window finish so returning to Text doesn't discard useful work.
    if (value) {
      scheduleAnalysis()
    }
  }

  fun setSyncMode(mode: SyncMode) {
    readerPreferences.setSyncMode(mode)
    if (source?.source == TextSyncSource.WHISPER_SCRIPT) {
      transcribeFullTrack = (mode == SyncMode.FULL_TRACK)
      mutable.update { it.copy(transcribingFullTrack = transcribeFullTrack) }
    }
    exhaustedBase = null
    bufferRetryJob?.cancel()
    if (analysisJob?.isActive == true) {
      analysisJob?.cancel()
      analysisJob = null
      analysisSequence++
    }
    publishCoverageOnly(isSyncingNow = false)
    scheduleAnalysis()
  }

  fun isSpeechModelDownloaded(tier: SpeechModelTier): Boolean = models.isDownloaded(tier)
  fun activeSpeechModelTag(): String = models.activeModelTag(readerPreferences.speechModelTier.value)
  fun isUsingLegacySpeechModel(): Boolean = models.isUsingLegacyFallback(readerPreferences.speechModelTier.value)

  fun setSpeechModelTier(tier: SpeechModelTier) {
    if (models.isDownloaded(tier)) {
      readerPreferences.setSpeechModelTier(tier)
      measuredSyncSpeedRatio = null
      exhaustedBase = null
      bufferRetryJob?.cancel()
      if (analysisJob?.isActive == true) {
        analysisJob?.cancel()
        analysisJob = null
        analysisSequence++
      }
      publishCoverageOnly(isSyncingNow = false)
      scheduleAnalysis()
    } else {
      downloadModelTier(tier)
    }
  }

  fun playback(
    position: Long,
    duration: Long,
    track: ReadAlongTrack? = mutable.value.track,
    prepared: Boolean = true,
    isPlaying: Boolean = true,
    speed: Float = playbackSpeed,
  ) {
    if (track != mutable.value.track) return
    val prevPlaying = this.isPlaying
    val clampedSpeed = speed.coerceIn(0.5f, 4.0f)
    val speedChanged = kotlin.math.abs(clampedSpeed - playbackSpeed) >= 0.05f
    playbackSpeed = clampedSpeed
    playbackReady = prepared
    this.isPlaying = isPlaying
    val clampedPos = position.coerceAtLeast(0)
    val clampedDur = duration.coerceAtLeast(0)
    val posMoved = kotlin.math.abs(clampedPos - positionMs) >= 1000L
    positionMs = clampedPos
    durationMs = clampedDur
    val snapshot = mutable.value
    val stickyGapMs = if (snapshot.source == TextSyncSource.WHISPER_SCRIPT) SCRIPT_STICKY_GAP_MS else Long.MAX_VALUE
    val nextActive = ReadAlongTimeline.activeIndex(snapshot.lines, positionMs, snapshot.offsetMs, stickyGapMs)
    val shouldRefreshCoverage = posMoved || prevPlaying != isPlaying || speedChanged ||
      (snapshot.syncCoverage?.trackDurationMs ?: 0L) != durationMs
    if (nextActive != snapshot.activeLineIndex || shouldRefreshCoverage) {
      val coverage = if (snapshot.bookId != null) {
        buildCoverageStats(snapshot.matchedSentences, isSyncingNow = analysisJob?.isActive == true)
      } else null
      mutable.update {
        it.copy(
          activeLineIndex = nextActive,
          syncCoverage = coverage ?: it.syncCoverage,
          syncProgress = coverage?.trackTimedRatio?.takeIf { ratio -> ratio > 0f } ?: it.syncProgress,
        )
      }
    }
    if (estimated && durationMs > 0 && snapshot.lines.none { it.isTimed }) useEstimatedTiming()
    // A seek cancels work far from the new playhead, then prioritizes that passage.
    if (analysisJob?.isActive == true && analysisStartMs >= 0 && kotlin.math.abs(positionMs - analysisStartMs) > 30_000) {
      analysisJob?.cancel(); analysisJob = null
    }
    if (prevPlaying != isPlaying && isPlaying) exhaustedBase = null
    scheduleAnalysis()
  }

  fun importDocument(uri: Uri, selectedTracks: Set<Int>) {
    val track = mutable.value.track ?: return
    startImport(track, selectedTracks) {
      val resolver = context.contentResolver
      val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Imported document"
      val bytes = resolver.openInputStream(uri)?.use { BookImport.read(it) } ?: error("Cannot open the selected document.")
      Triple(bytes, name, resolver.getType(uri))
    }
  }

  fun importBytes(bytes: ByteArray, name: String, mime: String?, selectedTracks: Set<Int>) {
    val track = mutable.value.track ?: return
    startImport(track, selectedTracks) { Triple(bytes, name, mime) }
  }

  fun ensureBookText(identity: BookIdentity, selectedTracks: Set<Int>, expectedTrack: ReadAlongTrack? = mutable.value.track) {
    val snapshot = mutable.value
    val track = snapshot.track ?: return
    if (track != expectedTrack || !visible || snapshot.bookId != null || snapshot.phase != ReadAlongPhase.EMPTY || snapshot.searching ||
      identity.title.isBlank() || identity.title.equals("Audiobook", true)) return
    if (catalogMetadata.book(track.infoHash)?.canAutoMatchText == false) {
      mutable.update { it.copy(lookupMessage = "Automatic narration matching supports English. Choose or import text for this recording.") }
      return
    }
    val key = listOf(track.infoHash, selectedTracks.sorted().joinToString(","),
      ReadAlongText.tokens(identity.title).joinToString(" "), ReadAlongText.tokens(identity.author).sorted().joinToString(" ")).joinToString("\n")
    if (automaticAttempts.containsKey(key)) {
      val saved = automaticAttempts[key]
      if (saved != null) {
        if (saved.accessUrl != null && (library is GutenbergFirstCatalog || library is ZLibraryClient) &&
          ZLibraryPrewarmer.hasClearanceCookie(saved.accessUrl)
        ) {
          automaticAttempts.remove(key)
        } else {
          mutable.update {
            it.copy(
              editions = saved.editions,
              choosingEdition = saved.editions.isNotEmpty(),
              lookupMessage = saved.message,
              accessUrl = saved.accessUrl,
            )
          }
          return
        }
      } else {
        return
      }
    }
    automaticAttempts[key] = null
    searchBooks(identity.query, title = identity.title, author = identity.author, automaticKey = key, selectedTracks = selectedTracks)
  }

  fun searchBooks(query: String, force: Boolean = false, title: String = query, author: String = "",
    automaticKey: String? = null, selectedTracks: Set<Int> = emptySet(), provider: TextProvider = TextProvider.AUTOMATIC) {
    val track = mutable.value.track ?: return
    pendingDownload = null
    fun normalize(value: String) = value.trim().replace(WHITESPACE_COLLAPSE_REGEX, " ").lowercase(java.util.Locale.ROOT)
    if (query.isBlank()) { mutable.update { it.copy(lookupMessage = "Enter a book title.") }; return }
    val normalized = listOf(query, title, author, provider.name).joinToString("\n") { normalize(it) }
    if (!force && searchKey == normalized && searchSucceeded) {
      lookupJob?.cancel()
      val search = Lookup(track, generation, ++lookupSequence, query.trim(), title.trim(), author.trim(), automaticKey = automaticKey, selectedTracks = selectedTracks, provider = provider)
      lookup = search
      clearRecovery()
      finishLookup(search, searchResults)
      return
    }
    if (!force && searchKey == normalized && lookupJob?.isActive == true && mutable.value.searching) return
    searchKey = normalized; searchSucceeded = false
    searchResults = emptyList() // Public results merged after browser recovery belong only to this lookup.
    val version = generation
    val request = ++lookupSequence
    lookupJob?.cancel()
    clearRecovery()
    mutable.update { it.copy(searching = true, editions = emptyList(), accessUrl = null, lookupMessage = null, choosingEdition = false) }
    val search = Lookup(track, version, request, query.trim(), title.trim(), author.trim(), automaticKey = automaticKey, selectedTracks = selectedTracks, provider = provider)
    lookup = search
    if (provider == TextProvider.ZLIBRARY) ZLibraryPrewarmer.warmUp(context)
    lookupJob = scope.launch { runLookup(search) }
  }

  private fun lookupCurrent(search: Lookup) = current(search.track, search.version) && search.request == lookupSequence

  private suspend fun runLookup(search: Lookup) {
      try {
        val outcome = withContext(Dispatchers.IO) { library.lookup(BookLookupRequest(if (search.titleOnly) search.title else search.query,
          search.title, search.author, catalogMetadata.book(search.track.infoHash)?.textSourceUrl.takeIf { search.automaticKey != null }, search.provider)) }
        val results = outcome.editions
        if (lookupCurrent(search)) {
          if (outcome.accessUrl == null && shouldBroaden(search, results)) {
            search.titleOnly = true
            mutable.update { it.copy(lookupMessage = "Trying the book title without the author…") }
            runLookup(search)
            return
          }
          finishLookup(search, results, outcome)
          outcome.accessUrl?.let { targetUrl ->
            triggerPrewarmRetry(search, targetUrl)
          }
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (lookupCurrent(search)) {
          val accessUrl = (error as? SiteAccessRequired)?.url
          search.automaticKey?.let { automaticAttempts[it] = AutomaticResult(emptyList(), error.message, accessUrl) }
          mutable.update { it.copy(searching = false, accessUrl = accessUrl, lookupMessage = error.message) }
          if (error is SiteAccessRequired) {
            triggerPrewarmRetry(search, error.url)
          } else {
            offerRecovery(search.track, search.version, "Retry search") {
              searchBooks(search.query, force = true, title = search.title, author = search.author,
                automaticKey = search.automaticKey, selectedTracks = search.selectedTracks, provider = search.provider)
            }
          }
        }
      }
  }

  private fun triggerPrewarmRetry(search: Lookup, targetUrl: String) {
    if (library !is GutenbergFirstCatalog && library !is ZLibraryClient) return
    val targetHost = runCatching { targetUrl.toHttpUrl().host }.getOrNull().orEmpty()
    if (!ZLibraryClient.isAllowedHost(targetHost)) return
    if (search.prewarmAttempted) return
    search.prewarmAttempted = true
    ZLibraryPrewarmer.warmUp(context, forceRefresh = true, targetUrl = targetUrl) { success ->
      if (success && lookupCurrent(search) && mutable.value.accessUrl != null) {
        search.automaticKey?.let { automaticAttempts.remove(it) }
        mutable.update { it.copy(accessUrl = null, searching = true, lookupMessage = "Site access verified. Loading books…") }
        lookupJob = scope.launch { runLookup(search) }
      }
    }
  }

  private fun finishLookup(search: Lookup, results: List<BookEdition>, outcome: BookLookupResult = BookLookupResult(results)) {
    if (!lookupCurrent(search)) return
    searchSucceeded = outcome.complete
    searchResults = BookSearch.rank(results, search.query, search.title, search.author)
    val message = outcome.message ?: if (results.isEmpty()) "No editions found. Try a different title or import your ebook."
      else if (search.automaticKey != null) "Choose the edition that matches your audiobook."
      else if (search.titleOnly) "Results for ${search.title} (title only)." else null
    search.automaticKey?.let { automaticAttempts[it] = AutomaticResult(searchResults, message, outcome.accessUrl) }
    mutable.update { it.copy(searching = false, accessUrl = outcome.accessUrl, editions = searchResults,
      choosingEdition = search.automaticKey != null && results.isNotEmpty(), lookupMessage = message) }
    if (search.automaticKey != null) {
      BookSearch.automaticEdition(searchResults, BookIdentity(search.title, search.author))?.let {
        downloadEdition(it, search.selectedTracks)
        return
      }
      if (results.isEmpty() && outcome.accessUrl == null && outcome.complete &&
        readerPreferences.autoGenerateScript.value && models.isInstalled(readerPreferences.speechModelTier.value) &&
        catalogMetadata.book(search.track.infoHash)?.canAutoMatchText != false
      ) {
        generateScriptFromAudio(search.title.ifBlank { search.query }, search.title, search.track)
        return
      }
    }
    if (!outcome.complete && outcome.accessUrl == null) offerRecovery(search.track, search.version, "Retry search") {
      searchBooks(search.query, force = true, title = search.title, author = search.author, provider = search.provider)
    }
  }

  private fun createScriptBook(track: ReadAlongTrack, title: String, trackTitle: String): StoredReadAlongBook {
    val cleanTitle = title.trim().ifBlank { trackTitle.trim() }.ifBlank { "Audio Script" }
    val chapterTitle = trackTitle.trim().ifBlank { "Track ${track.fileIndex + 1}" }
    return StoredReadAlongBook(
      id = "script:${track.infoHash}:${track.fileIndex}",
      book = EpubBook(
        title = cleanTitle,
        author = "Transcribed by Whisper",
        chapters = listOf(EpubChapter(1, "script_1", "script", chapterTitle, "", 0)),
      ),
      fileName = "generated-script.txt",
      source = TextSyncSource.WHISPER_SCRIPT,
    )
  }

  fun generateScriptFromAudio(
    title: String,
    trackTitle: String = title,
    expectedTrack: ReadAlongTrack? = mutable.value.track,
  ) {
    val track = mutable.value.track ?: return
    if (track != expectedTrack) return
    invalidateCorrection()
    generation++
    val version = generation
    pendingDownload = null
    loadJob?.cancel(); importJob?.cancel(); analysisJob?.cancel(); lookupJob?.cancel(); lookupSequence++
    clearRecovery()
    transcribeFullTrack = false
    inFlightWindowStartMs = null; inFlightWindowEndMs = null; inFlightWindowLines = emptyList()
    catalogMetadata.setScriptModeEnabled(track.infoHash, true)
    if (catalogMetadata.title(track.infoHash).isNullOrBlank() && title.isNotBlank()) {
      catalogMetadata.save(track.infoHash, title)
    }
    mutable.update {
      it.copy(
        phase = ReadAlongPhase.LOADING,
        searching = false,
        accessUrl = null,
        editions = emptyList(),
        choosingEdition = false,
        lookupMessage = null,
        message = "Preparing audio script…",
      )
    }
    importJob = scope.launch {
      try {
        val scriptBook = createScriptBook(track, title, trackTitle)
        withContext(Dispatchers.IO) { store.attach(track, scriptBook, setOf(track.fileIndex)) }
        if (current(track, version)) {
          importJob = null
          load(track, version)
        }
      } catch (error: Exception) {
        if (current(track, version)) importJob = null
        fail(error, track, version)
      }
    }
  }

  fun clearGeneratedScript() {
    val track = mutable.value.track ?: return
    val book = source ?: return
    if (book.source != TextSyncSource.WHISPER_SCRIPT) return
    invalidateCorrection()
    generation++
    val version = generation
    loadJob?.cancel(); importJob?.cancel(); analysisJob?.cancel(); lookupJob?.cancel(); lookupSequence++
    clearRecovery()
    transcribeFullTrack = false
    inFlightWindowStartMs = null; inFlightWindowEndMs = null; inFlightWindowLines = emptyList()
    catalogMetadata.setScriptModeEnabled(track.infoHash, false)
    source = null; sentences = emptyList(); sentenceTokenCounts = IntArray(0); cachedParagraphs = emptyList()
    cachedWindows.clear(); matcher = null; timings.clear(); estimated = false; exhaustedBase = null
    scope.launch {
      withContext(Dispatchers.IO) { store.clearScriptBook(track, book.id) }
      if (current(track, version)) mutable.value = ReadAlongState(track = track)
    }
  }

  fun setTranscribeFullTrack(enabled: Boolean) {
    val currentSource = source?.source
    if (enabled && currentSource != TextSyncSource.WHISPER_SCRIPT) return
    transcribeFullTrack = enabled
    if (!enabled && readerPreferences.syncMode.value == SyncMode.FULL_TRACK) {
      readerPreferences.setSyncMode(SyncMode.BALANCED)
    }
    exhaustedBase = null
    mutable.update { it.copy(transcribingFullTrack = enabled) }
    if (!enabled) {
      if (analysisJob?.isActive == true && (!visible || (!isPlaying && readerPreferences.syncMode.value == SyncMode.PLAYHEAD_ONLY))) {
        analysisJob?.cancel()
        analysisJob = null
        analysisSequence++
      }
      publishCoverageOnly(isSyncingNow = analysisJob?.isActive == true)
    } else {
      scheduleAnalysis()
    }
  }

  private fun shouldBroaden(search: Lookup, results: List<BookEdition>) = !search.titleOnly &&
    search.author.isNotBlank() && !search.title.equals(search.query, true) &&
    results.none { BookSearch.matchesTitle(it, search.title) }

  fun downloadEdition(edition: BookEdition, selectedTracks: Set<Int>, renderedHtml: String? = null, prewarmed: Boolean = false) {
    val track = mutable.value.track ?: return
    val version = generation
    val request = ++lookupSequence
    lookupJob?.cancel()
    pendingDownload = Download(track, version, edition, selectedTracks)
    clearRecovery()
    mutable.update { it.copy(searching = true, lookupMessage = "Downloading ${edition.title}…", accessUrl = null, choosingEdition = false) }
    lookupJob = scope.launch {
      try {
        val (bytes, name, mime) = withContext(Dispatchers.IO) { library.download(edition, renderedHtml) }
        if (current(track, version) && request == lookupSequence) {
          mutable.update { it.copy(searching = false, editions = emptyList(), lookupMessage = null) }
          importBytes(bytes, name, mime, selectedTracks)
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (current(track, version) && request == lookupSequence) {
          mutable.update { it.copy(searching = false, accessUrl = (error as? SiteAccessRequired)?.url, lookupMessage = error.message) }
          if (error is SiteAccessRequired && !prewarmed && (library is GutenbergFirstCatalog || library is ZLibraryClient) &&
            runCatching { ZLibraryClient.isAllowedHost(error.url.toHttpUrl().host) }.getOrDefault(false)
          ) {
            ZLibraryPrewarmer.warmUp(context, forceRefresh = true, targetUrl = edition.url) { success ->
              if (success && current(track, version) && request == lookupSequence && mutable.value.accessUrl != null) {
                mutable.update { it.copy(accessUrl = null, searching = true, lookupMessage = "Site access verified. Downloading…") }
                downloadEdition(edition, selectedTracks, renderedHtml, prewarmed = true)
              }
            }
          } else if (error !is SiteAccessRequired) {
            offerRecovery(track, version, "Retry ebook download") { downloadEdition(edition, selectedTracks, renderedHtml) }
          }
        }
      }
    }
  }

  fun resumeSiteAccess(html: String, url: String, expectedTrack: ReadAlongTrack?) {
    if (expectedTrack != mutable.value.track) return
    val download = pendingDownload
    if (download != null && current(download.track, download.version)) {
      val editionPage = CatalogDownloadBridge.isDownloadCapture(html) ||
        url.substringBefore('?').trimEnd('/') == download.edition.url.substringBefore('?').trimEnd('/') ||
        html.contains("/dl/", ignoreCase = true) ||
        html.contains("/download/", ignoreCase = true) ||
        html.contains("/slow_download/", ignoreCase = true) ||
        html.contains("/fast_download/", ignoreCase = true) ||
        html.contains("/auto_download/", ignoreCase = true) ||
        html.contains("get.php", ignoreCase = true)
      downloadEdition(download.edition, download.indices, html.takeIf { editionPage })
    } else {
      val search = lookup ?: return
      if (!lookupCurrent(search)) return
      val hasCards = html.contains("<z-bookcard", ignoreCase = true) ||
        html.contains("resItemBox", ignoreCase = true) ||
        html.contains("book-card", ignoreCase = true) ||
        html.contains("js-aarecord-list-outer", ignoreCase = true) ||
        html.contains("tablelibgen", ignoreCase = true)
      val isSearchPage = url.contains("/s/") || url.contains("/search") || url.contains("index.php")
      if (isSearchPage || hasCards) {
        acceptRenderedSearch(html, url, expectedTrack)
      } else if (needsBrowserChallenge(url, html)) {
        mutable.update { it.copy(accessUrl = url, searching = false, lookupMessage = "Complete the browser verification or sign in to continue.") }
      } else {
        mutable.update { it.copy(accessUrl = null, searching = true) }
        lookupJob = scope.launch { runLookup(search) }
      }
    }
  }

  private fun needsBrowserChallenge(url: String, html: String): Boolean {
    val host = runCatching { url.toHttpUrl().host }.getOrNull().orEmpty()
    return when {
      WeLibClient.isAllowedHost(host) -> WeLibClient.needsAccess(200, html)
      AnnasArchiveClient.isAllowedHost(host) -> AnnasArchiveClient.needsAccess(200, html)
      LibGenClient.isAllowedHost(host) -> CatalogHttpHelpers.isChallengeHtml(200, html)
      else -> ZLibraryClient.needsAccess(200, html)
    }
  }

  fun acceptRenderedSearch(html: String, url: String, expectedTrack: ReadAlongTrack? = mutable.value.track) {
    if (expectedTrack != mutable.value.track) return
    val search = lookup ?: return
    if (!lookupCurrent(search)) return
    val host = runCatching { url.toHttpUrl().host }.getOrNull().orEmpty()
    val renderedResults = when {
      WeLibClient.isAllowedHost(host) || html.contains("id=\"aarecord-list\"", ignoreCase = true) ->
        WeLibClient.parseResults(html, url)
      AnnasArchiveClient.isAllowedHost(host) || html.contains("js-aarecord-list-outer", ignoreCase = true) ->
        AnnasArchiveClient.parseResults(html, url)
      LibGenClient.isAllowedHost(host) || html.contains("id=\"tablelibgen\"", ignoreCase = true) ->
        LibGenClient.parseResults(html, url)
      else -> ZLibraryClient.parseResults(html, url)
    }
    if (renderedResults.isEmpty() && needsBrowserChallenge(url, html)) {
      mutable.update {
        it.copy(
          searching = false,
          accessUrl = url,
          lookupMessage = "Complete the browser verification or sign in on the page first.",
        )
      }
      return
    }
    val retained = if (search.provider == TextProvider.AUTOMATIC) {
      searchResults.filter { it.provider in setOf(TextProvider.GUTENBERG, TextProvider.LIBGEN) }
    } else emptyList()
    val parsed = retained + renderedResults
    if (shouldBroaden(search, parsed)) {
      search.titleOnly = true
      mutable.update { it.copy(accessUrl = null, searching = true, lookupMessage = "Trying the book title without the author…") }
      lookupJob = scope.launch { runLookup(search) }
      return
    }
    finishLookup(search, parsed)
  }

  fun reportImportError(message: String) {
    recoveryAction = null
    mutable.update { it.copy(phase = ReadAlongPhase.ERROR, message = message, recoveryLabel = "Choose another file", recovery = ReadAlongRecovery.PICK_FILE) }
  }

  fun updateTrackSelection(indices: Set<Int>) {
    val track = mutable.value.track ?: return
    val book = source ?: return
    val version = generation
    scope.launch {
      try {
        withContext(Dispatchers.IO) { store.setTrackSelection(track, book.id, indices) }
        if (current(track, version)) mutable.update { it.copy(trackIndices = indices + track.fileIndex) }
      } catch (error: Exception) { fail(error, track, version) }
    }
  }

  private fun startImport(track: ReadAlongTrack, selectedTracks: Set<Int>, read: () -> Triple<ByteArray, String, String?>) {
    invalidateCorrection()
    generation++
    pendingDownload = null
    val version = generation
    loadJob?.cancel(); importJob?.cancel(); analysisJob?.cancel(); lookupJob?.cancel(); lookupSequence++
    clearRecovery()
    transcribeFullTrack = false
    inFlightWindowStartMs = null; inFlightWindowEndMs = null; inFlightWindowLines = emptyList()
    catalogMetadata.setScriptModeEnabled(track.infoHash, false)
    mutable.update { it.copy(phase = ReadAlongPhase.LOADING, searching = false, accessUrl = null, message = "Importing companion text…", transcribingFullTrack = false) }
    importJob = scope.launch {
      try {
        withContext(Dispatchers.IO) {
          val (bytes, name, mime) = read()
          require(bytes.size <= BookImport.MAX_BYTES) { "The selected ebook is too large." }
          val type = BookImport.source(bytes, name, mime)
          val book = if (type == TextSyncSource.LRC || type == TextSyncSource.SRT) {
            val text = bytes.toString(Charsets.UTF_8)
            require(LrcParser.detectAndParse(text).isNotEmpty()) { "No valid timestamps were found in this attachment." }
            EpubBook(name, "Attached transcript", listOf(EpubChapter(1, "transcript", name, name, text, ReadAlongText.tokens(text).size)))
          } else BookImport.book(bytes, name)
          require(book.chapters.isNotEmpty()) { "The selected file has no readable book text." }
          ensureActive()
          store.attach(track, StoredReadAlongBook(track.infoHash + ":" + ReadAlongText.digest(bytes), book, name, type), selectedTracks + track.fileIndex)
        }
        if (current(track, version)) {
          importJob = null
          load(track, version)
        }
      } catch (error: Exception) {
        if (current(track, version)) importJob = null
        fail(error, track, version)
        if (current(track, version)) {
          if (error is java.io.IOException) offerRecovery(track, version, "Retry import") { startImport(track, selectedTracks, read) }
          else reportImportError(error.message ?: "Choose a different ebook file.")
        }
      }
    }
  }

  fun retry() {
    val action = recoveryAction ?: return
    clearRecovery()
    action()
  }

  private fun clearRecovery() {
    recoveryAction = null
    mutable.update { it.copy(recoveryLabel = null, recovery = null) }
  }

  fun dismissLookupStatus() {
    val state = mutable.value
    val isLookupRecovery = state.recovery == ReadAlongRecovery.RETRY &&
      state.recoveryLabel in setOf("Retry search", "Retry ebook download")
    if (isLookupRecovery) recoveryAction = null
    mutable.update {
      it.copy(
        lookupMessage = null,
        accessUrl = null,
        recoveryLabel = if (isLookupRecovery) null else it.recoveryLabel,
        recovery = if (isLookupRecovery) null else it.recovery,
      )
    }
  }

  private fun offerRecovery(track: ReadAlongTrack, version: Long, label: String, action: () -> Unit) {
    if (!current(track, version)) return
    recoveryAction = { if (current(track, version)) action() }
    mutable.update { it.copy(recoveryLabel = label, recovery = ReadAlongRecovery.RETRY) }
  }

  fun downloadModel() = downloadModelTier(null)

  fun downloadModelTier(tier: SpeechModelTier?) {
    if (modelJob?.isActive == true) return
    modelJob = scope.launch {
      val track = mutable.value.track
      val version = generation
      val targetTier = tier ?: readerPreferences.speechModelTier.value
      try {
        mutable.update { it.copy(modelProgress = 0, message = "Downloading the English speech model (${targetTier.label})…") }
        models.download(targetTier) { progress -> mutable.update { it.copy(modelProgress = progress) } }
        readerPreferences.setSpeechModelTier(targetTier)
        measuredSyncSpeedRatio = null
        exhaustedBase = null
        if (analysisJob?.isActive == true) {
          analysisJob?.cancel()
          analysisJob = null
          analysisSequence++
        }
        mutable.update { it.copy(modelProgress = null, phase = if (it.phase == ReadAlongPhase.NEEDS_MODEL) ReadAlongPhase.READY else it.phase, message = it.message?.takeUnless { text -> text.startsWith("Downloading the English") }) }
        if (mutable.value.correction.needsModel) mutable.update { it.copy(correction = it.correction.copy(phase = SyncCorrectionPhase.SELECTING, needsModel = false, message = "Model ready. Press Sync now when this sentence begins.")) }
        publishCoverageOnly(isSyncingNow = false)
        scheduleAnalysis()
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutable.update { it.copy(modelProgress = null) }
        if (track != null && current(track, version)) {
          if (models.isInstalled(readerPreferences.speechModelTier.value)) {
            mutable.update { it.copy(message = error.message ?: "Model download failed.") }
          } else {
            fail(error, track, version)
            offerRecovery(track, version, "Retry model download") { downloadModelTier(targetTier) }
          }
        }
      }
    }
  }

  private var analysisStartMs = -1L
  private var analysisSequence = 0L
  private fun windowBase() = (positionMs / 20_000) * 20_000

  fun retryCurrentPassage() {
    val track = mutable.value.track ?: return
    val book = source ?: return
    val version = generation
    analysisJob?.cancel(); analysisJob = null; analysisSequence++
    val base = windowBase()
    val windowEnd = base + 20_000L
    val isScript = book.source == TextSyncSource.WHISPER_SCRIPT
    scope.launch {
      withContext(Dispatchers.IO) {
        store.retryWindow(track, book.id, base, windowEnd, clearTimingsInWindow = isScript)
      }
      if (current(track, version)) {
        cachedWindows.removeAll { base in it.first until it.last }
        if (isScript) {
          val overlapFloor = (base - 500L).coerceAtLeast(0L)
          timings.values.removeAll {
            val mid = (it.startTimeMs + it.endTimeMs) / 2L
            it.startTimeMs in base until windowEnd || (it.startTimeMs >= overlapFloor && mid in base until windowEnd)
          }
          inFlightWindowStartMs = null; inFlightWindowEndMs = null; inFlightWindowLines = emptyList()
          publishLines(isSyncingNow = false)
        }
        exhaustedBase = null; clearRecovery()
        mutable.update { it.copy(phase = ReadAlongPhase.READY, message = null) }
        scheduleAnalysis()
      }
    }
  }

  private fun isFullTrackMode(bookSource: TextSyncSource? = source?.source): Boolean =
    readerPreferences.syncMode.value == SyncMode.FULL_TRACK ||
      (transcribeFullTrack && bookSource == TextSyncSource.WHISPER_SCRIPT)

  private suspend fun nextPendingWindow(
    track: ReadAlongTrack,
    bookId: String,
    base: Long,
    minStartMs: Long = 0L,
  ): Long? {
    val mode = readerPreferences.syncMode.value
    val fullTrack = isFullTrackMode()
    if (!isPlaying && !fullTrack && mode == SyncMode.PLAYHEAD_ONLY) {
      if (minStartMs > base) return null
      // Playhead mode syncs only the active window when paused so CPU returns to idle.
      val ready = if (base < durationMs) withContext(Dispatchers.IO) { store.windowReady(track, bookId, base) } else true
      return if (ready) null else base
    }
    if (fullTrack) {
      return withContext(Dispatchers.IO) {
        val alignedBase = base.coerceAtLeast(0L)
        val scanStart = maxOf(alignedBase, minStartMs)
        val forward = generateSequence(scanStart) { it + 20_000L }
          .takeWhile { it < durationMs }
          .firstOrNull { !store.windowReady(track, bookId, it) }
        if (forward != null || minStartMs > 0L) forward
        else generateSequence(0L) { it + 20_000L }
          .takeWhile { it < alignedBase && it < durationMs }
          .firstOrNull { !store.windowReady(track, bookId, it) }
      }
    }
    val horizonCount = mode.horizonWindows(base, durationMs)
    if (horizonCount <= 0) return null
    val nextMissing = withContext(Dispatchers.IO) {
      (0 until horizonCount)
        .asSequence()
        .map { base + it * 20_000L }
        .filter { it >= minStartMs }
        .takeWhile { it < durationMs }
        .firstOrNull { !store.windowReady(track, bookId, it) }
    }
    if (minStartMs == 0L) {
      Log.d("ReadAlongPerf", "mode=$mode horizon=$horizonCount nextMissing=$nextMissing playing=$isPlaying speed=$playbackSpeed")
    }
    return nextMissing
  }

  private fun scheduleAnalysis() {
    val book = source ?: return
    val track = mutable.value.track ?: return
    val isScript = book.source == TextSyncSource.WHISPER_SCRIPT
    val keepRunningBackground = isFullTrackMode(book.source)
    if ((!visible && !keepRunningBackground) || !playbackReady || estimated || mutable.value.correction.isEditing ||
      correctionJob?.isActive == true || (book.source != TextSyncSource.NARRATION && !isScript) || durationMs <= 0 ||
      loadJob?.isActive == true || importJob?.isActive == true || analysisJob?.isActive == true) return
    if (mutable.value.phase == ReadAlongPhase.ERROR && mutable.value.recovery != null) return
    if (exhaustedBase == windowBase() && !keepRunningBackground) return
    bufferRetryJob?.cancel()
    val version = generation
    val request = ++analysisSequence
    analysisJob = scope.launch {
      fun valid() = current(track, version) && request == analysisSequence
      var prefetchedStart: Long? = null
      var prefetchJob: Deferred<Pair<Long, Result<FloatArray>>>? = null
      try {
        while (isActive && (visible || isFullTrackMode(book.source)) && valid()) {
          val base = windowBase()
          val start = nextPendingWindow(track, book.id, base)
          if (!valid()) return@launch
          if (start == null) {
            prefetchJob?.cancel(); prefetchJob = null; prefetchedStart = null
            exhaustedBase = base
            if (transcribeFullTrack && isScript) {
              transcribeFullTrack = false
              mutable.update { it.copy(transcribingFullTrack = false) }
            }
            publishCoverageOnly(isSyncingNow = false)
            break
          }
          if (prefetchJob != null && prefetchedStart != start) {
            prefetchJob.cancel()
            prefetchJob = null
            prefetchedStart = null
          }
          val ahead = start != base
          val isImmediate = !ahead || (start > base && start <= base + 20_000L)
          val preferredTier = readerPreferences.speechModelTier.value
          if (!models.isInstalled(preferredTier)) {
            exhaustedBase = base
            if (transcribeFullTrack) {
              transcribeFullTrack = false
              mutable.update { it.copy(transcribingFullTrack = false) }
            }
            if (!ahead && modelJob?.isActive != true) {
              val sizeMb = preferredTier.approximateMb
              mutable.update {
                it.copy(
                  phase = ReadAlongPhase.NEEDS_MODEL,
                  message = if (isScript) "Download the English speech model ($sizeMb MB) to generate a script from audio."
                  else "Download the English speech model ($sizeMb MB) to match the narrator on this phone.",
                )
              }
            }
            break
          }
          val modelFile = models.activeFile(preferredTier)
          analysisStartMs = positionMs
          mutable.update { it.copy(phase = if (ahead || (isScript && it.lines.isNotEmpty())) ReadAlongPhase.READY else ReadAlongPhase.PREPARING,
            preparingAhead = ahead, message = if (ahead) null else it.message,
            syncCoverage = buildCoverageStats(it.matchedSentences, isSyncingNow = true)) }
          val began = android.os.SystemClock.elapsedRealtime()
          val windowEnd = minOf(start + 20_000L, durationMs)
          val (decodeStart, decodeDur) = SyncScheduler.windowDecodeBounds(start, windowEnd, durationMs, isScript)
          val activePrefetch = prefetchJob
          val pcm = if (activePrefetch != null && prefetchedStart == start) {
            val (_, prefetchedResult) = activePrefetch.await()
            prefetchJob = null
            prefetchedStart = null
            prefetchedResult.getOrElse { decoder.decode(track, decodeStart, decodeDur) }
          } else {
            decoder.decode(track, decodeStart, decodeDur)
          }
          val decoded = android.os.SystemClock.elapsedRealtime()
          val currentCov = buildCoverageStats(timings.size, isSyncingNow = true)
          val effectiveAheadWallMs = SyncScheduler.effectiveWallRunwayMs(currentCov.aheadSyncedMs, playbackSpeed)
          val budget = SyncScheduler.computeBudget(
            ahead = ahead,
            isImmediate = isImmediate,
            effectiveAheadWallMs = effectiveAheadWallMs,
            transcribeFullTrack = isFullTrackMode(book.source),
            isPlaying = isPlaying,
            syncMode = readerPreferences.syncMode.value,
          )
          if (ahead && budget.cooldownMs <= 500L && valid()) {
            val nextStart = nextPendingWindow(track, book.id, base, minStartMs = start + 20_000L)
            if (nextStart != null && valid()) {
              val nextEnd = minOf(nextStart + 20_000L, durationMs)
              val (nextDecodeStart, nextDecodeDur) = SyncScheduler.windowDecodeBounds(nextStart, nextEnd, durationMs, isScript)
              prefetchedStart = nextStart
              prefetchJob = async(Dispatchers.IO) {
                val res = runCatching { decoder.decode(track, nextDecodeStart, nextDecodeDur) }
                  .onFailure { if (it is CancellationException) throw it }
                nextStart to res
              }
            }
          }
          var matchedCount = 0

          if (isScript) {
            if (skipSilentScriptWindows && TranscriptAssembler.isSilent(pcm)) {
              withContext(Dispatchers.IO) {
                ensureActive()
                store.saveWindow(track, book.id, start, windowEnd, emptyList(), complete = true, persistText = true)
              }
              if (!valid()) return@launch
              cachedWindows += start..windowEnd
            } else {
              val historyLines = timings.values
                .asSequence()
                .filter { it.startTimeMs < start && it.text.isNotBlank() }
                .sortedBy { it.startTimeMs }
                .toList()
              val previousTail = historyLines.maxByOrNull { it.endTimeMs }
              val prevTokens = historyLines
                .takeLast(3)
                .flatMap { ReadAlongText.tokens(it.text) }
                .map(TranscriptAssembler::normalizedToken)
                .filter { it.isNotEmpty() }
                .takeLast(8)
              val prevToken = prevTokens.lastOrNull()
              val prevEndMs = previousTail?.endTimeMs ?: Long.MIN_VALUE
              val prevEndedSentence = previousTail?.let { TranscriptAssembler.endsSentence(it.text) } ?: true
              val scriptPrompt = buildScriptPrompt(track, book, historyLines)
              val effectiveWindowStart = if (previousTail != null && previousTail.endTimeMs > start + 500L) {
                previousTail.endTimeMs.coerceAtMost(windowEnd)
              } else {
                start
              }

              val recognized = if (!ahead) {
                speech.transcribeProgressiveWithPrompt(
                  modelFile,
                  pcm,
                  (positionMs - decodeStart).coerceAtLeast(0),
                  scriptPrompt,
                  budget.threads,
                ) { partial ->
                  ensureActive()
                  if (!valid()) return@transcribeProgressiveWithPrompt
                  val shifted = partial.map { it.copy(startMs = it.startMs + decodeStart, endMs = it.endMs + decodeStart) }
                  val draft = withContext(Dispatchers.Default) {
                    TranscriptAssembler.assembleWindow(
                      words = shifted,
                      bookId = book.id,
                      windowStartMs = effectiveWindowStart,
                      windowEndMs = windowEnd,
                      previousTailToken = prevToken,
                      previousTailEndMs = prevEndMs,
                      idNamespace = "draft",
                      previousTailTokens = prevTokens,
                      previousEndedSentence = prevEndedSentence,
                    )
                  }
                  if (!valid()) return@transcribeProgressiveWithPrompt
                  inFlightWindowStartMs = start
                  inFlightWindowEndMs = windowEnd
                  inFlightWindowLines = draft
                  if (draft.isNotEmpty()) {
                    mutable.update { it.copy(phase = ReadAlongPhase.READY, preparingAhead = true, message = null) }
                    publishLines(isSyncingNow = true)
                  }
                }
              } else {
                speech.transcribeScriptWindow(modelFile, pcm, scriptPrompt, budget.threads)
              }

              if (!valid()) return@launch
              val shiftedFinal = recognized.map { it.copy(startMs = it.startMs + decodeStart, endMs = it.endMs + decodeStart) }
              val assembledLines = withContext(Dispatchers.Default) {
                TranscriptAssembler.assembleWindow(
                  words = shiftedFinal,
                  bookId = book.id,
                  windowStartMs = effectiveWindowStart,
                  windowEndMs = windowEnd,
                  previousTailToken = prevToken,
                  previousTailEndMs = prevEndMs,
                  previousTailTokens = prevTokens,
                  previousEndedSentence = prevEndedSentence,
                )
              }
              var persistedLines = assembledLines
              if (previousTail != null &&
                previousTail.endTimeMs <= start + 500L &&
                !prevEndedSentence &&
                previousTail.text.length < 220 &&
                assembledLines.isNotEmpty()
              ) {
                val firstNew = assembledLines.first()
                val seamGapMs = firstNew.startTimeMs - previousTail.endTimeMs
                val firstChar = firstNew.text.firstOrNull { it.isLetterOrDigit() }
                val isLowerCont = firstChar?.isLowerCase() == true
                val isPronounI = firstNew.text == "I" || firstNew.text.startsWith("I ") || firstNew.text.startsWith("I'")
                if (seamGapMs < 1_200L && (isLowerCont || isPronounI)) {
                  val merged = previousTail.copy(
                    endTimeMs = maxOf(previousTail.endTimeMs, firstNew.endTimeMs),
                    text = "${previousTail.text.trimEnd()} ${firstNew.text.trimStart()}",
                  )
                  persistedLines = listOf(merged) + assembledLines.drop(1)
                }
              }
              inFlightWindowStartMs = null; inFlightWindowEndMs = null; inFlightWindowLines = emptyList()
              withContext(Dispatchers.IO) {
                ensureActive()
                store.saveWindow(track, book.id, start, windowEnd, persistedLines, complete = true, persistText = true)
              }
              if (!valid()) return@launch
              val overlapFloor = (start - 500L).coerceAtLeast(0L)
              timings.values.removeAll {
                val mid = (it.startTimeMs + it.endTimeMs) / 2L
                it.startTimeMs in start until windowEnd || (it.startTimeMs >= overlapFloor && mid in start until windowEnd)
              }
              persistedLines.forEach { timings[it.id] = it }
              matchedCount = assembledLines.size
              cachedWindows += start..windowEnd
            }
            Log.d("ReadAlongPerf", "scriptWindow=$start..$windowEnd decodeMs=${decoded-began} speechMs=${android.os.SystemClock.elapsedRealtime()-decoded} threads=${budget.threads} model=${models.activeModelTag(preferredTier)} lines=$matchedCount ahead=$ahead")
            mutable.update {
              it.copy(
                alignmentAttempted = true,
                phase = ReadAlongPhase.READY,
                preparingAhead = false,
                analysisMillis = android.os.SystemClock.elapsedRealtime() - began,
                message = if (!ahead && matchedCount == 0 && timings.isEmpty()) "No clear speech detected yet. Keep playing or wait for the next passage." else null,
              )
            }
          } else {
            suspend fun applyWords(recognized: List<SpeechWord>, publishNow: Boolean) {
              ensureActive()
              if (!valid()) return
              val words = recognized.map { it.copy(startMs = it.startMs + decodeStart, endMs = it.endMs + decodeStart) }
              val activeMatcher = matcher
              val wrappedBack = start < windowBase()
              val hint = if (wrappedBack) null else selectedHint ?: lastMatchedSentenceHint ?: mutable.value.activeLineIndex.takeIf { it >= 0 }
              val matched = withContext(Dispatchers.Default) { activeMatcher?.align(words, hint) ?: emptyList() }
              if (!valid()) return
              if (!wrappedBack && matched.isNotEmpty()) {
                val lastId = matched.last().id
                val matchedIdx = sentences.indexOfFirst { it.id == lastId }
                if (matchedIdx >= 0) lastMatchedSentenceHint = matchedIdx
              }
              val lines = matched.filter { it.id !in timings }
              withContext(Dispatchers.IO) { ensureActive(); store.saveWindow(track, book.id, start, windowEnd, lines, complete = false) }
              if (valid()) {
                lines.forEach { timings[it.id] = it }
                matchedCount = maxOf(matchedCount, matched.size)
                if (publishNow && lines.isNotEmpty()) {
                  mutable.update { it.copy(phase = ReadAlongPhase.READY, preparingAhead = true, message = null) }
                  publishLines(isSyncingNow = true)
                  Log.d("ReadAlongPerf", "usableUpdateMs=${android.os.SystemClock.elapsedRealtime()-began} added=${lines.size}")
                }
              }
            }

            if (!ahead) {
              // Immediate playhead window: use progressive balanced focus-first slices for low time-to-first-highlight.
              speech.transcribeProgressive(modelFile, pcm, (positionMs - decodeStart).coerceAtLeast(0), budget.threads) { partial ->
                applyWords(partial, publishNow = true)
              }
            } else {
              // Lookahead windows: single Whisper pass eliminates redundant neural overlap.
              val words = speech.transcribe(modelFile, pcm, budget.threads)
              applyWords(words, publishNow = false)
            }

            if (!valid()) return@launch
            withContext(Dispatchers.IO) { ensureActive(); store.saveWindow(track, book.id, start, windowEnd, emptyList()) }
            if (!valid()) return@launch
            cachedWindows += start..windowEnd
            Log.d("ReadAlongPerf", "decodeMs=${decoded-began} speechMs=${android.os.SystemClock.elapsedRealtime()-decoded} threads=${budget.threads} model=${models.activeModelTag(preferredTier)} matched=$matchedCount ahead=$ahead")
            mutable.update { it.copy(alignmentAttempted = true, phase = ReadAlongPhase.READY, preparingAhead = false,
              analysisMillis = android.os.SystemClock.elapsedRealtime() - began,
              message = if (!ahead && matchedCount == 0) "No confident text match in this passage. Check the edition or choose a chapter." else it.message.takeIf { _ -> ahead }) }
          }

          val windowElapsedMs = (android.os.SystemClock.elapsedRealtime() - began).coerceAtLeast(1L)
          val coveredSpanMs = (windowEnd - start).coerceAtLeast(1L)
          val sampleRatio = (coveredSpanMs.toFloat() / windowElapsedMs.toFloat()).coerceIn(0.1f, 20f)
          measuredSyncSpeedRatio = measuredSyncSpeedRatio?.let { prev -> prev * 0.4f + sampleRatio * 0.6f } ?: sampleRatio

          val morePending = nextPendingWindow(track, book.id, windowBase()) != null
          publishLines(isSyncingNow = morePending)
          if (!morePending) {
            prefetchJob?.cancel(); prefetchJob = null; prefetchedStart = null
            exhaustedBase = windowBase()
            if (transcribeFullTrack && isScript) {
              transcribeFullTrack = false
              mutable.update { it.copy(transcribingFullTrack = false) }
              publishCoverageOnly(isSyncingNow = false)
            }
            break
          }
          if (budget.cooldownMs > 0L) delay(budget.cooldownMs)
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        inFlightWindowStartMs = null; inFlightWindowEndMs = null; inFlightWindowLines = emptyList()
        if (valid()) {
          val retryBase = windowBase()
          exhaustedBase = retryBase
          val waitingForBuffer = mutable.value.preparingAhead && error.message?.startsWith("Waiting for verified audio data") == true
          if (waitingForBuffer) {
            transcribeFullTrack = false
            mutable.update {
              it.copy(
                phase = ReadAlongPhase.READY,
                preparingAhead = false,
                transcribingFullTrack = false,
                syncCoverage = buildCoverageStats(it.matchedSentences, isSyncingNow = false, overrideReason = "Waiting for torrent download ahead"),
              )
            }
            if (visible || isFullTrackMode(book.source)) {
              bufferRetryJob?.cancel()
              bufferRetryJob = scope.launch {
                delay(5_000L)
                if (current(track, version) && exhaustedBase == retryBase) {
                  exhaustedBase = null
                  scheduleAnalysis()
                }
              }
            }
          } else {
            transcribeFullTrack = false
            // A failed future passage must not hide already usable current text.
            mutable.update { it.copy(phase = if (it.preparingAhead) ReadAlongPhase.READY else ReadAlongPhase.ERROR,
              preparingAhead = false, transcribingFullTrack = false, message = error.message ?: "Could not prepare speech synchronization.",
              syncCoverage = buildCoverageStats(it.matchedSentences, isSyncingNow = false)) }
            offerRecovery(track, version, "Retry matching", ::retryCurrentPassage)
          }
        }
      } finally {
        prefetchJob?.cancel()
        decoder.release()
        if (request == analysisSequence) {
          analysisStartMs = -1
          inFlightWindowStartMs = null; inFlightWindowEndMs = null; inFlightWindowLines = emptyList()
          mutable.update {
            it.copy(
              preparingAhead = false,
              transcribingFullTrack = transcribeFullTrack,
              phase = if (it.phase == ReadAlongPhase.PREPARING) ReadAlongPhase.READY else it.phase,
              syncCoverage = if (it.bookId != null) buildCoverageStats(it.matchedSentences, isSyncingNow = false) else it.syncCoverage,
            )
          }
        }
      }
    }
  }

  fun adjustOffset(deltaMs: Long) {
    invalidateCorrection()
    saveOffset(mutable.value.offsetMs + deltaMs)
    scheduleAnalysis()
  }
  private fun saveOffset(value: Long) {
    val track = mutable.value.track ?: return
    val bookId = mutable.value.bookId ?: return
    mutable.update {
      val stickyGap = if (it.isScriptSource) SCRIPT_STICKY_GAP_MS else Long.MAX_VALUE
      it.copy(offsetMs = value, activeLineIndex = ReadAlongTimeline.activeIndex(it.lines, positionMs, value, stickyGap))
    }
    if (estimated || mutable.value.source == TextSyncSource.EPUB_PACED) return
    val previous = offsetJob
    offsetJob = scope.launch { previous?.join(); withContext(Dispatchers.IO) { store.setOffset(track, value, bookId) } }
  }

  fun chooseChapter(chapter: EpubChapter) {
    if (!mutable.value.canChooseChapter || mutable.value.phase == ReadAlongPhase.LOADING) return
    invalidateCorrection()
    val track = mutable.value.track ?: return
    val book = source ?: return
    generation++
    val version = generation
    val index = sentences.indexOfFirst { it.chapterIndex == chapter.index }
    analysisJob?.cancel(); analysisJob = null; lookupJob?.cancel(); lookupSequence++
    clearRecovery()
    exhaustedBase = null
    lastMatchedSentenceHint = index.takeIf { it >= 0 }
    mutable.update { it.copy(activeLineIndex = -1, alignmentAttempted = false, searching = false, accessUrl = null, message = "Chapter selected: ${chapter.title}. Preparing narration match…") }
    scope.launch {
      try {
        withContext(Dispatchers.IO) { store.setChapterHint(track, chapter.index); store.resetAlignment(track, book.id) }
        if (current(track, version)) {
          selectedHint = index.takeIf { it >= 0 }
          chapterHint = chapter.index
          cachedWindows.clear()
          timings.clear(); publishLines(isSyncingNow = false); scheduleAnalysis()
        }
      } catch (error: Exception) { fail(error, track, version) }
    }
  }
  private var selectedHint: Int? = null

  fun useEstimatedTiming() {
    if (!mutable.value.canEstimate) return
    invalidateCorrection()
    val book = source ?: return
    if (durationMs <= 0) { mutable.update { it.copy(message = "Wait until the audio duration is known.") }; return }
    analysisJob?.cancel(); analysisJob = null; estimated = true
    clearRecovery()
    val text = book.book.chapters.joinToString("\n\n") { it.text }
    val paced = PacedTimingEngine.paceText(text, durationMs)
    sentences = paced.map { BookSentence(it.id, 1, book.book.title, it.text) }
    sentenceTokenCounts = IntArray(sentences.size) { i -> ReadAlongText.tokens(sentences[i].text).size.coerceAtLeast(1) }
    timings = paced.associateByTo(LinkedHashMap()) { it.id }
    cachedParagraphs = readerParagraphs(paced, TextSyncSource.EPUB_PACED)
    mutable.update { it.copy(source = TextSyncSource.EPUB_PACED, phase = ReadAlongPhase.READY, paragraphs = cachedParagraphs, message = "Estimated timing — use Correct sync to correct drift.") }
    publishLines(isSyncingNow = false)
  }

  fun useNarrationTiming() {
    if (!estimated) return
    invalidateCorrection()
    estimated = false
    val track = mutable.value.track ?: return
    generation++; analysisJob?.cancel()
    mutable.update { it.copy(phase = ReadAlongPhase.LOADING, message = "Restoring narration timing…") }
    load(track, generation)
  }

  fun beginCorrection(sentenceId: Long? = null) {
    if (mutable.value.bookId == null || mutable.value.phase == ReadAlongPhase.LOADING) return
    stopCorrectionWork()
    retiredAnalysis = analysisJob ?: retiredAnalysis
    analysisJob?.cancel(); analysisJob = null
    mutable.update { it.copy(
      phase = if (it.phase == ReadAlongPhase.PREPARING) ReadAlongPhase.READY else it.phase,
      correction = SyncCorrectionState(SyncCorrectionPhase.SELECTING, sentenceId?.let(it::syncTarget), event = correctionSequence),
    ) }
  }

  fun selectCorrectionSentence(sentenceId: Long) {
    if (!mutable.value.correction.isEditing) return
    val target = mutable.value.syncTarget(sentenceId) ?: return
    stopCorrectionWork()
    mutable.update { it.copy(correction = SyncCorrectionState(SyncCorrectionPhase.SELECTING, target, event = correctionSequence)) }
  }

  fun cancelCorrection() {
    stopCorrectionWork()
    mutable.update { it.copy(correction = SyncCorrectionState(event = correctionSequence)) }
    scheduleAnalysis()
  }

  /** Called for an actual player seek, never for ordinary playback progression or speed changes. */
  fun playbackDiscontinuity() {
    playbackReady = false
    analysisJob?.cancel(); analysisJob = null; analysisSequence++; exhaustedBase = null
    mutable.update { it.copy(preparingAhead = false, phase = if (it.phase == ReadAlongPhase.PREPARING) ReadAlongPhase.READY else it.phase) }
    if (pendingReference == null && correctionJob?.isActive != true) return
    val target = mutable.value.correction.target
    stopCorrectionWork()
    mutable.update { it.copy(correction = SyncCorrectionState(SyncCorrectionPhase.SELECTING, target,
      "Audio position changed. Press Sync now when the sentence begins.", event = correctionSequence)) }
  }

  fun correctionUnavailable(message: String) {
    if (!mutable.value.correction.isEditing) return
    mutable.update { it.copy(correction = it.correction.copy(phase = SyncCorrectionPhase.ERROR, message = message, canRetry = false)) }
  }

  fun syncNow(reference: SyncReference) {
    if (!mutable.value.accepts(reference.target) || mutable.value.correction.target != reference.target || !mutable.value.correction.isEditing || correctionJob?.isActive == true) return
    if (reference.durationMs <= 0 || reference.positionMs !in 0 until reference.durationMs) {
      correctionUnavailable("Start or prepare the audio before correcting sync."); return
    }
    positionMs = reference.positionMs; durationMs = reference.durationMs
    pendingReference = reference
    runCorrection(reference)
  }

  fun retryCorrection() {
    val reference = pendingReference ?: return
    if (mutable.value.correction.canRetry && mutable.value.accepts(reference.target) && correctionJob?.isActive != true) runCorrection(reference)
  }

  private fun runCorrection(reference: SyncReference) {
    val target = reference.target
    val version = generation
    val request = ++correctionSequence
    val oldAnalysis = analysisJob ?: retiredAnalysis
    analysisJob?.cancel(); analysisJob = null
    mutable.update { it.copy(correction = SyncCorrectionState(SyncCorrectionPhase.MATCHING, target, event = request)) }
    correctionJob = scope.launch {
      fun valid() = current(target.track, version) && request == correctionSequence && mutable.value.accepts(target)
      try {
        oldAnalysis?.join(); offsetJob?.join()
        if (!valid()) return@launch
        var line = mutable.value.lines.first { it.id == target.sentenceId }
        val index = sentences.indexOfFirst { it.id == target.sentenceId }
        if (!line.isTimed) {
          check(target.source == TextSyncSource.NARRATION && index >= 0) { "This sentence has no usable timing. Choose another sentence." }
          val preferredTier = readerPreferences.speechModelTier.value
          if (!models.isInstalled(preferredTier)) {
            pendingReference = null
            mutable.update { it.copy(correction = it.correction.copy(phase = SyncCorrectionPhase.ERROR, needsModel = true,
              message = "Download the speech model, then press Sync now again when this sentence begins.")) }
            return@launch
          }
          val start = reference.windowStartMs
          val pcm = decoder.decode(target.track, start, reference.windowDurationMs)
          val words = speech.transcribe(models.activeFile(preferredTier), pcm).map { it.copy(startMs = it.startMs + start, endMs = it.endMs + start) }
          val activeMatcher = matcher
          val matched = withContext(Dispatchers.Default) { activeMatcher?.align(words, index) ?: emptyList() }
          if (!valid()) return@launch
          val selected = matched.firstOrNull { it.id == target.sentenceId }
            ?: error("Could not confidently match this sentence. Try a longer sentence or check the ebook edition.")
          val additions = matched.filter { it.id !in timings }
          withContext(Dispatchers.IO) { ensureActive(); store.saveWindow(target.track, target.bookId, start, start + reference.windowDurationMs, additions) }
          if (!valid()) return@launch
          cachedWindows += start..(start + reference.windowDurationMs)
          additions.forEach { timings[it.id] = it }
          line = selected
        }
        if (!valid()) return@launch
        undoCorrection = UndoCorrection(target, mutable.value.offsetMs, chapterHint, selectedHint)
        if (target.source == TextSyncSource.NARRATION && index >= 0) {
          selectedHint = index; lastMatchedSentenceHint = index; chapterHint = sentences[index].chapterIndex
        }
        val value = ReadAlongTimeline.calibration(line, reference.positionMs)
        val lines = if (target.source == TextSyncSource.WHISPER_SCRIPT) {
          mutable.value.lines
        } else {
          interpolateGaps()
          sentences.map { it.withTiming(timings[it.id]) }
        }
        val matchedTotal = lines.count { item -> item.isTimed }
        val coverage = buildCoverageStats(matchedTotal, isSyncingNow = false)
        val syncProg = coverage.trackTimedRatio.takeIf { it > 0f }
          ?: if (sentences.isNotEmpty() && matchedTotal > 0) (matchedTotal.toFloat() / sentences.size).coerceIn(0f, 1f) else null
        val recoveredMatching = mutable.value.recoveryLabel in setOf("Retry matching", "Retry model download")
        if (recoveredMatching) recoveryAction = null
        val stickyGap = if (target.source == TextSyncSource.WHISPER_SCRIPT) SCRIPT_STICKY_GAP_MS else Long.MAX_VALUE
        // Playback may have advanced during matching; align this historical reference without seeking.
        mutable.update { it.copy(offsetMs = value, lines = lines, paragraphs = cachedParagraphs, matchedSentences = matchedTotal,
          activeLineIndex = ReadAlongTimeline.activeIndex(lines, positionMs, value, stickyGap), message = null,
          phase = if (recoveredMatching) ReadAlongPhase.READY else it.phase,
          recovery = if (recoveredMatching) null else it.recovery, recoveryLabel = if (recoveredMatching) null else it.recoveryLabel,
          correction = SyncCorrectionState(SyncCorrectionPhase.APPLIED, target, "Text aligned.", event = request), canUndoCorrection = true,
          syncProgress = syncProg, syncCoverage = coverage) }
        persistCorrection(target, value, chapterHint)
        pendingReference = null; exhaustedBase = null
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (valid()) mutable.update { it.copy(correction = it.correction.copy(phase = SyncCorrectionPhase.ERROR,
          message = error.message ?: "Could not correct sync. Retry this passage.", canRetry = true)) }
      } finally {
        if (request == correctionSequence) { correctionJob = null; retiredAnalysis = null; scheduleAnalysis() }
      }
    }
  }

  fun undoSyncCorrection() {
    val previous = undoCorrection ?: return
    if (!mutable.value.accepts(previous.target)) return
    stopCorrectionWork()
    selectedHint = previous.sentenceHint; lastMatchedSentenceHint = previous.sentenceHint; chapterHint = previous.chapter
    undoCorrection = null
    mutable.update { it.copy(offsetMs = previous.offset, canUndoCorrection = false, correction = SyncCorrectionState(event = correctionSequence)) }
    publishLines(isSyncingNow = false); persistCorrection(previous.target, previous.offset, previous.chapter)
  }

  fun resetSyncCorrection() {
    val target = mutable.value.lines.firstOrNull()?.let { mutable.value.syncTarget(it.id) } ?: return
    invalidateCorrection()
    mutable.update { it.copy(offsetMs = 0) }; publishLines(isSyncingNow = false)
    persistCorrection(target, 0, chapterHint)
    scheduleAnalysis()
  }

  fun searchInBook(query: String, limit: Int = 50): List<ReaderSearchHit> {
    val q = query.trim()
    if (q.length < 2) return emptyList()
    val timedById = mutable.value.lines.associateBy { it.id }
    val results = ArrayList<ReaderSearchHit>(minOf(limit, 16))
    for (sentence in sentences) {
      if (sentence.text.contains(q, ignoreCase = true)) {
        val line = timedById[sentence.id]
        results += ReaderSearchHit(
          sentenceId = sentence.id,
          chapterIndex = sentence.chapterIndex,
          chapterTitle = sentence.chapterTitle,
          text = sentence.text,
          startTimeMs = line?.startTimeMs ?: -1L,
          isTimed = line?.isTimed == true,
        )
        if (results.size >= limit) break
      }
    }
    return results
  }

  fun firstSentenceIdForChapter(chapterIndex: Int): Long? =
    sentences.firstOrNull { it.chapterIndex == chapterIndex }?.id

  private fun persistCorrection(target: SyncTarget, value: Long, chapter: Int?) {
    if (target.source == TextSyncSource.EPUB_PACED || estimated) return
    val previous = offsetJob
    offsetJob = scope.launch {
      try {
        previous?.join()
        withContext(Dispatchers.IO) { store.setCorrection(target.track, target.bookId, value, chapter) }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (mutable.value.accepts(target)) mutable.update { it.copy(message = "Could not save sync correction. Try correcting again.") }
      }
    }
  }

  private fun stopCorrectionWork() {
    correctionSequence++
    correctionJob?.cancel(); correctionJob = null; pendingReference = null
  }

  private fun invalidateCorrection() {
    stopCorrectionWork(); undoCorrection = null
    mutable.update { it.copy(correction = SyncCorrectionState(event = correctionSequence), canUndoCorrection = false) }
  }

  private fun interpolateGaps() {
    if (sentences.isEmpty() || timings.isEmpty()) return
    var i = 0
    while (i < sentences.size) {
      val startSentence = sentences[i]
      val startTiming = timings[startSentence.id]
      if (startTiming != null && startTiming.isTimed) {
        var j = i + 1
        while (j < sentences.size && sentences[j].chapterIndex == startSentence.chapterIndex && timings[sentences[j].id] == null) {
          j++
        }
        if (j < sentences.size && sentences[j].chapterIndex == startSentence.chapterIndex) {
          val endSentence = sentences[j]
          val endTiming = timings[endSentence.id]
          val gapCount = j - i - 1
          if (endTiming != null && endTiming.isTimed && gapCount in 1..6) {
            val gapDurationMs = endTiming.startTimeMs - startTiming.endTimeMs
            if (gapDurationMs in 300L..25_000L) {
              var totalWords = 0
              for (k in i + 1 until j) totalWords += sentenceTokenCounts.getOrElse(k) { 1 }
              totalWords = totalWords.coerceAtLeast(1)
              var cursorMs = startTiming.endTimeMs
              for (k in i + 1 until j) {
                val s = sentences[k]
                val w = sentenceTokenCounts.getOrElse(k) { 1 }
                val dur = (gapDurationMs * w) / totalWords
                val lineStart = cursorMs
                val lineEnd = (cursorMs + dur).coerceAtMost(endTiming.startTimeMs)
                timings[s.id] = TimedLine(s.id, lineStart, lineEnd, "")
                cursorMs = lineEnd
              }
            }
          }
        }
        i = j
      } else {
        i++
      }
    }
  }

  private fun buildCoverageStats(
    matched: Int,
    isSyncingNow: Boolean,
    overrideReason: String? = null,
  ): SyncCoverageStats {
    val mode = readerPreferences.syncMode.value
    val fullTrack = isFullTrackMode()
    val reason = overrideReason ?: when {
      isSyncingNow -> null
      !isPlaying && !fullTrack && mode == SyncMode.PLAYHEAD_ONLY -> "Playhead mode (~40s) · Paused"
      !isPlaying && fullTrack -> "Full track synced · Paused"
      !isPlaying && mode == SyncMode.BALANCED -> "Buffered ahead (+3m) · Paused"
      !isPlaying && mode == SyncMode.EXTENDED -> "Buffered ahead (+15m) · Paused"
      fullTrack -> "Full track synced"
      mode == SyncMode.PLAYHEAD_ONLY -> "Playhead mode (~40s)"
      mode == SyncMode.BALANCED -> "Buffered ahead (+3m limit)"
      mode == SyncMode.EXTENDED -> "Buffered ahead (+15m limit)"
      else -> null
    }
    return ReadAlongTimeline.computeSyncCoverage(
      windows = cachedWindows,
      positionMs = positionMs,
      durationMs = durationMs,
      matchedSentences = matched,
      totalBookSentences = sentences.size,
      isSyncingNow = isSyncingNow,
      syncPausedReason = reason,
      syncSpeedRatio = measuredSyncSpeedRatio,
      playbackSpeed = playbackSpeed,
    )
  }

  private fun publishCoverageOnly(isSyncingNow: Boolean) {
    mutable.update {
      if (it.bookId == null) it
      else {
        val coverage = buildCoverageStats(it.matchedSentences, isSyncingNow)
        it.copy(
          syncCoverage = coverage,
          syncProgress = coverage.trackTimedRatio.takeIf { ratio -> ratio > 0f } ?: it.syncProgress,
        )
      }
    }
  }

  private fun publishLines(isSyncingNow: Boolean) {
    val book = source
    if (book?.source == TextSyncSource.WHISPER_SCRIPT) {
      val draftStart = inFlightWindowStartMs
      val draftEnd = inFlightWindowEndMs
      val persisted = if (draftStart != null && draftEnd != null) {
        timings.values.filter { it.startTimeMs !in draftStart until draftEnd }
      } else {
        timings.values.toList()
      }
      val merged = (persisted + inFlightWindowLines)
        .filter { it.text.isNotBlank() && it.isTimed }
        .sortedBy { it.startTimeMs }
      val structured = TranscriptAssembler.applyParagraphBreaks(merged)
      val chapterTitle = book.book.chapters.firstOrNull()?.title ?: book.book.title
      sentences = structured.map { BookSentence(it.id, 1, chapterTitle, it.text, it.isParagraphStart) }
      sentenceTokenCounts = IntArray(sentences.size) { i -> ReadAlongText.tokens(sentences[i].text).size.coerceAtLeast(1) }
      cachedParagraphs = readerParagraphs(structured, book.source)
      val matched = structured.size
      val coverage = buildCoverageStats(matched, isSyncingNow)
      val syncProg = coverage.trackTimedRatio.takeIf { it > 0f }
      mutable.update {
        it.copy(
          lines = structured,
          paragraphs = cachedParagraphs,
          matchedSentences = matched,
          activeLineIndex = ReadAlongTimeline.activeIndex(structured, positionMs, it.offsetMs, SCRIPT_STICKY_GAP_MS),
          syncProgress = syncProg,
          syncCoverage = coverage,
        )
      }
      return
    }
    interpolateGaps()
    val lines = sentences.map { it.withTiming(timings[it.id]) }
    val matched = lines.count { line -> line.isTimed }
    val coverage = buildCoverageStats(matched, isSyncingNow)
    val syncProg = coverage.trackTimedRatio.takeIf { it > 0f }
      ?: if (sentences.isNotEmpty() && matched > 0) (matched.toFloat() / sentences.size).coerceIn(0f, 1f) else null
    mutable.update { it.copy(
      lines = lines,
      paragraphs = cachedParagraphs,
      matchedSentences = matched,
      activeLineIndex = ReadAlongTimeline.activeIndex(lines, positionMs, it.offsetMs),
      syncProgress = syncProg,
      syncCoverage = coverage,
    ) }
  }

  suspend fun hasMultiChapterScripts(): Boolean {
    val hash = mutable.value.track?.infoHash ?: return false
    return withContext(Dispatchers.IO) { store.scriptChaptersForTorrent(hash).size > 1 }
  }

  suspend fun buildExportText(bookTitle: String, trackTitles: Map<Int, String>, fullBook: Boolean): String {
    val snapshot = mutable.value
    val hash = snapshot.track?.infoHash ?: return ""
    return if (fullBook) {
      val chapters = withContext(Dispatchers.IO) { store.scriptChaptersForTorrent(hash) }
      ScriptExporter.toBookPlainText(
        bookTitle,
        chapters.map { (idx, lines) -> (trackTitles[idx] ?: "Chapter ${idx + 1}") to lines },
      )
    } else {
      ScriptExporter.toPlainText(bookTitle.ifBlank { snapshot.title.orEmpty() }, snapshot.lines)
    }
  }

  fun buildExportSrt(): String = ScriptExporter.toSrt(mutable.value.lines)

  private fun buildScriptPrompt(
    track: ReadAlongTrack,
    book: StoredReadAlongBook,
    historyLines: List<TimedLine>,
  ): String? {
    val meta = catalogMetadata.book(track.infoHash)
    val rawTitle = meta?.title?.takeIf { it.isNotBlank() } ?: book.book.title
    val cleanTitle = rawTitle.replace(WHITESPACE_COLLAPSE_REGEX, " ").trim().take(70)
    val cleanAuthor = meta?.author?.replace(WHITESPACE_COLLAPSE_REGEX, " ")?.trim()?.take(40).orEmpty()
    val header = when {
      cleanTitle.isNotEmpty() && cleanAuthor.isNotEmpty() -> "$cleanTitle by $cleanAuthor"
      cleanTitle.isNotEmpty() -> cleanTitle
      else -> ""
    }
    val tailWords = historyLines
      .takeLast(3)
      .joinToString(" ") { it.text.trim() }
      .replace(WHITESPACE_COLLAPSE_REGEX, " ")
      .trim()
      .split(' ')
      .filter { it.isNotEmpty() }
      .takeLast(28)
      .joinToString(" ")
    return when {
      header.isNotEmpty() && tailWords.isNotEmpty() -> "$header. $tailWords"
      tailWords.isNotEmpty() -> tailWords
      header.isNotEmpty() -> header
      else -> null
    }?.take(240)
  }

  private fun current(track: ReadAlongTrack, version: Long) = mutable.value.track == track && generation == version
  private fun fail(error: Exception, track: ReadAlongTrack, version: Long) {
    if (error is CancellationException) throw error
    if (current(track, version)) mutable.update { it.copy(phase = ReadAlongPhase.ERROR, message = error.message ?: "Unable to load companion text.") }
  }
  fun close() {
    scope.cancel()
    context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
    decoder.release()
    speech.close()
  }

  private companion object {
    val WHITESPACE_COLLAPSE_REGEX = Regex("\\s+")
    const val SCRIPT_STICKY_GAP_MS = 6_000L
  }
}

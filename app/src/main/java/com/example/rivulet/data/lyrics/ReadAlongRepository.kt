package com.example.rivulet.data.lyrics

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-scoped source of truth. Player snapshots enter on Main; no player is accessed by IO work. */
class ReadAlongRepository(
  private val context: Context,
  private val store: ReadAlongStore,
  private val decoder: ReadAlongAudioDecoder,
  private val speech: SpeechRecognizer = WhisperEngine(),
  private val models: SpeechModelManager = SpeechModelManager(context),
  private val library: BookCatalog = ZLibraryClient { url -> android.webkit.CookieManager.getInstance().getCookie(url) },
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val mutable = MutableStateFlow(ReadAlongState())
  val state = mutable.asStateFlow()
  val readerPreferences = ReaderPreferences(context)
  private var generation = 0L
  private var loadJob: Job? = null
  private var importJob: Job? = null
  private var analysisJob: Job? = null
  private var modelJob: Job? = null
  private var lookupJob: Job? = null
  private var offsetJob: Job? = null
  private var exhaustedBase: Long? = null
  private var searchKey: String? = null
  private var searchSucceeded = false
  private var searchResults: List<BookEdition> = emptyList()
  private data class Lookup(val track: ReadAlongTrack, val version: Long, val request: Long,
    val query: String, val title: String, val author: String, var titleOnly: Boolean = false,
    val automaticKey: String? = null, val selectedTracks: Set<Int> = emptySet())
  private data class AutomaticResult(val editions: List<BookEdition>, val message: String?)
  private val automaticAttempts = mutableMapOf<String, AutomaticResult?>()
  private data class Download(val track: ReadAlongTrack, val version: Long, val edition: BookEdition, val indices: Set<Int>)
  private var pendingDownload: Download? = null
  private var lookup: Lookup? = null
  private var lookupSequence = 0L
  private var recoveryAction: (() -> Unit)? = null
  private var source: StoredReadAlongBook? = null
  private var sentences: List<BookSentence> = emptyList()
  private var matcher: NarrationMatcher? = null
  private var timings = LinkedHashMap<Long, TimedLine>()
  private var positionMs = 0L
  private var durationMs = 0L
  private var playbackReady = false
  private var visible = false
  private var estimated = false
  private var chapterHint: Int? = null
  private var correctionJob: Job? = null
  private var retiredAnalysis: Job? = null
  private var correctionSequence = 0L
  private var pendingReference: SyncReference? = null
  private data class UndoCorrection(val target: SyncTarget, val offset: Long, val chapter: Int?, val sentenceHint: Int?)
  private var undoCorrection: UndoCorrection? = null
  @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
  private val memoryCallbacks = object : ComponentCallbacks2 {
    override fun onConfigurationChanged(newConfig: Configuration) {}
    override fun onLowMemory() { speech.release() }
    override fun onTrimMemory(level: Int) {
      if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) speech.release()
    }
  }
  init { context.applicationContext.registerComponentCallbacks(memoryCallbacks) }

  fun activate(track: ReadAlongTrack, initialPositionMs: Long = 0, initialDurationMs: Long = 0) {
    if (mutable.value.track == track) return
    invalidateCorrection()
    generation++
    lookup?.automaticKey?.let { if (lookupJob?.isActive == true && automaticAttempts[it] == null) automaticAttempts.remove(it) }
    loadJob?.cancel(); importJob?.cancel(); analysisJob?.cancel(); lookupJob?.cancel()
    pendingDownload = null
    source = null; sentences = emptyList(); matcher = null; timings.clear(); estimated = false
    searchKey = null; searchSucceeded = false; searchResults = emptyList(); lookupSequence++; lookup = null; recoveryAction = null
    positionMs = initialPositionMs.coerceAtLeast(0); durationMs = initialDurationMs.coerceAtLeast(0); playbackReady = false
    selectedHint = null; chapterHint = null; exhaustedBase = null
    mutable.value = ReadAlongState(track = track, phase = ReadAlongPhase.LOADING)
    load(track, generation)
  }

  private fun load(track: ReadAlongTrack, version: Long) {
    exhaustedBase = null
    loadJob?.cancel()
    loadJob = scope.launch {
      try {
        offsetJob?.join()
        val saved = withContext(Dispatchers.IO) {
          Triple(store.book(track), store.offset(track), store.chapterHint(track))
        }
        if (!current(track, version)) return@launch
        val book = saved.first
        if (book == null) { mutable.value = ReadAlongState(track = track); return@launch }
        val loaded = withContext(Dispatchers.Default) { ReadAlongText.sentences(book.book, book.id) }
        val existing = withContext(Dispatchers.IO) { store.timings(track, book.id) }
        if (!current(track, version)) return@launch
        val loadedMatcher = withContext(Dispatchers.Default) { NarrationMatcher(loaded) }
        val indices = withContext(Dispatchers.IO) { store.associatedTracks(book.id) }
        if (!current(track, version)) return@launch
        source = book; sentences = loaded; matcher = loadedMatcher
        chapterHint = saved.third
        selectedHint = saved.third?.let { hint -> loaded.indexOfFirst { it.chapterIndex == hint }.takeIf { it >= 0 } }
        timings = existing.associateByTo(LinkedHashMap()) { it.id }
        if (book.source == TextSyncSource.LRC || book.source == TextSyncSource.SRT) {
          timings = LrcParser.detectAndParse(book.book.chapters.joinToString("\n") { it.text }).associateByTo(LinkedHashMap()) { it.id }
          sentences = timings.values.map { BookSentence(it.id, 1, book.book.title, it.text) }
        }
        val lines = sentences.map { it.withTiming(timings[it.id]) }
        mutable.value = ReadAlongState(track = track, bookId = book.id, title = book.book.title, isbn = book.book.isbn,
          chapters = book.book.chapters, offsetMs = saved.second, phase = ReadAlongPhase.READY, source = book.source, trackIndices = indices,
          lines = lines, matchedSentences = lines.count { it.isTimed }, activeLineIndex = ReadAlongTimeline.activeIndex(lines, positionMs, saved.second))
        scheduleAnalysis()
      } catch (error: Exception) {
        fail(error, track, version)
        offerRecovery(track, version, "Retry loading text") { load(track, version) }
      }
    }
  }

  fun setVisible(value: Boolean) {
    visible = value
    // Let a bounded in-flight window finish so returning to Text doesn't discard useful work.
    if (value) scheduleAnalysis()
  }

  fun playback(position: Long, duration: Long, track: ReadAlongTrack? = mutable.value.track, prepared: Boolean = true) {
    if (track != mutable.value.track) return
    playbackReady = prepared
    positionMs = position.coerceAtLeast(0)
    durationMs = duration.coerceAtLeast(0)
    val snapshot = mutable.value
    mutable.update { it.copy(activeLineIndex = ReadAlongTimeline.activeIndex(it.lines, positionMs, it.offsetMs)) }
    if (estimated && durationMs > 0 && snapshot.lines.none { it.isTimed }) useEstimatedTiming()
    // A seek cancels work far from the new playhead, then prioritizes that passage.
    if (analysisJob?.isActive == true && analysisStartMs >= 0 && kotlin.math.abs(positionMs - analysisStartMs) > 30_000) {
      analysisJob?.cancel(); analysisJob = null
    }
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
    val key = listOf(track.infoHash, selectedTracks.sorted().joinToString(","),
      ReadAlongText.tokens(identity.title).joinToString(" "), ReadAlongText.tokens(identity.author).sorted().joinToString(" ")).joinToString("\n")
    if (automaticAttempts.containsKey(key)) {
      automaticAttempts[key]?.let { saved -> mutable.update { it.copy(editions = saved.editions,
        choosingEdition = saved.editions.isNotEmpty(), lookupMessage = saved.message) } }
      return
    }
    automaticAttempts[key] = null
    searchBooks(identity.query, title = identity.title, author = identity.author, automaticKey = key, selectedTracks = selectedTracks)
  }

  fun searchBooks(query: String, force: Boolean = false, title: String = query, author: String = "",
    automaticKey: String? = null, selectedTracks: Set<Int> = emptySet()) {
    val track = mutable.value.track ?: return
    pendingDownload = null
    fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
    if (query.isBlank()) { mutable.update { it.copy(lookupMessage = "Enter a book title.") }; return }
    val normalized = listOf(query, title, author).joinToString("\n") { normalize(it) }
    if (!force && searchKey == normalized && searchSucceeded) {
      lookupJob?.cancel()
      val search = Lookup(track, generation, ++lookupSequence, query.trim(), title.trim(), author.trim(), automaticKey = automaticKey, selectedTracks = selectedTracks)
      lookup = search
      clearRecovery()
      finishLookup(search, searchResults)
      return
    }
    if (!force && searchKey == normalized && lookupJob?.isActive == true && mutable.value.searching) return
    searchKey = normalized; searchSucceeded = false
    val version = generation
    val request = ++lookupSequence
    lookupJob?.cancel()
    clearRecovery()
    mutable.update { it.copy(searching = true, editions = emptyList(), accessUrl = null, lookupMessage = null, choosingEdition = false) }
    val search = Lookup(track, version, request, query.trim(), title.trim(), author.trim(), automaticKey = automaticKey, selectedTracks = selectedTracks)
    lookup = search
    lookupJob = scope.launch { runLookup(search) }
  }

  private fun lookupCurrent(search: Lookup) = current(search.track, search.version) && search.request == lookupSequence

  private suspend fun runLookup(search: Lookup) {
      try {
        val results = withContext(Dispatchers.IO) { library.search(if (search.titleOnly) search.title else search.query) }
        if (lookupCurrent(search)) {
          if (shouldBroaden(search, results)) {
            search.titleOnly = true
            mutable.update { it.copy(lookupMessage = "Trying the book title without the author…") }
            runLookup(search)
            return
          }
          finishLookup(search, results)
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (lookupCurrent(search)) {
          search.automaticKey?.let { automaticAttempts[it] = AutomaticResult(emptyList(), error.message) }
          mutable.update { it.copy(searching = false, accessUrl = (error as? SiteAccessRequired)?.url, lookupMessage = error.message) }
          if (error !is SiteAccessRequired) offerRecovery(search.track, search.version, "Retry search") {
            searchBooks(search.query, force = true, title = search.title, author = search.author,
              automaticKey = search.automaticKey, selectedTracks = search.selectedTracks)
          }
        }
      }
  }

  private fun finishLookup(search: Lookup, results: List<BookEdition>) {
    if (!lookupCurrent(search)) return
    searchSucceeded = true
    searchResults = BookSearch.rank(results, search.query, search.title, search.author)
    val message = if (results.isEmpty()) "No editions found. Try a different title or import your ebook."
      else if (search.automaticKey != null) "Choose the edition that matches your audiobook."
      else if (search.titleOnly) "Results for ${search.title} (title only)." else null
    search.automaticKey?.let { automaticAttempts[it] = AutomaticResult(searchResults, message) }
    mutable.update { it.copy(searching = false, accessUrl = null, editions = searchResults,
      choosingEdition = search.automaticKey != null && results.isNotEmpty(), lookupMessage = message) }
    if (search.automaticKey != null) {
      BookSearch.automaticEdition(searchResults, BookIdentity(search.title, search.author))?.let {
        downloadEdition(it, search.selectedTracks)
      }
    }
  }

  private fun shouldBroaden(search: Lookup, results: List<BookEdition>) = !search.titleOnly &&
    search.author.isNotBlank() && !search.title.equals(search.query, true) &&
    results.none { BookSearch.matchesTitle(it, search.title) }

  fun downloadEdition(edition: BookEdition, selectedTracks: Set<Int>, renderedHtml: String? = null) {
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
          if (error !is SiteAccessRequired) offerRecovery(track, version, "Retry ebook download") { downloadEdition(edition, selectedTracks, renderedHtml) }
        }
      }
    }
  }

  fun resumeSiteAccess(html: String, url: String, expectedTrack: ReadAlongTrack?) {
    if (expectedTrack != mutable.value.track) return
    val download = pendingDownload
    if (download != null && current(download.track, download.version)) {
      // A login flow can finish on a different page. Re-fetch the edition unless this is its page.
      val editionPage = url.substringBefore('?').trimEnd('/') == download.edition.url.substringBefore('?').trimEnd('/')
      downloadEdition(download.edition, download.indices, html.takeIf { editionPage })
    } else {
      val search = lookup ?: return
      if (!lookupCurrent(search)) return
      if (url.contains("/s/")) acceptRenderedSearch(html, url, expectedTrack)
      else {
        mutable.update { it.copy(accessUrl = null, searching = true) }
        lookupJob = scope.launch { runLookup(search) }
      }
    }
  }

  fun acceptRenderedSearch(html: String, url: String, expectedTrack: ReadAlongTrack? = mutable.value.track) {
    if (expectedTrack != mutable.value.track) return
    val search = lookup ?: return
    if (!lookupCurrent(search)) return
    val parsed = ZLibraryClient.parseResults(html, url)
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
    mutable.update { it.copy(phase = ReadAlongPhase.LOADING, searching = false, accessUrl = null, message = "Importing companion text…") }
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
        if (current(track, version)) load(track, version)
      } catch (error: Exception) {
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

  private fun offerRecovery(track: ReadAlongTrack, version: Long, label: String, action: () -> Unit) {
    if (!current(track, version)) return
    recoveryAction = { if (current(track, version)) action() }
    mutable.update { it.copy(recoveryLabel = label, recovery = ReadAlongRecovery.RETRY) }
  }

  fun downloadModel() {
    if (modelJob?.isActive == true) return
    modelJob = scope.launch {
      val track = mutable.value.track
      val version = generation
      try {
        mutable.update { it.copy(modelProgress = 0, message = "Downloading the English speech model…") }
        models.download { progress -> mutable.update { it.copy(modelProgress = progress) } }
        exhaustedBase = null
        mutable.update { it.copy(modelProgress = null, phase = if (it.phase == ReadAlongPhase.NEEDS_MODEL) ReadAlongPhase.READY else it.phase, message = it.message?.takeUnless { text -> text.startsWith("Downloading the English") }) }
        if (mutable.value.correction.needsModel) mutable.update { it.copy(correction = it.correction.copy(phase = SyncCorrectionPhase.SELECTING, needsModel = false, message = "Model ready. Press Sync now when this sentence begins.")) }
        scheduleAnalysis()
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutable.update { it.copy(modelProgress = null) }
        if (track != null && current(track, version)) {
          fail(error, track, version)
          offerRecovery(track, version, "Retry model download") { downloadModel() }
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
    scope.launch {
      withContext(Dispatchers.IO) { store.retryWindow(track, book.id, base) }
      if (current(track, version)) {
        exhaustedBase = null; clearRecovery()
        mutable.update { it.copy(phase = ReadAlongPhase.READY, message = null) }
        scheduleAnalysis()
      }
    }
  }

  private fun scheduleAnalysis() {
    val book = source ?: return
    val track = mutable.value.track ?: return
    if (!visible || !playbackReady || estimated || mutable.value.correction.isEditing || correctionJob?.isActive == true ||
      book.source != TextSyncSource.NARRATION || durationMs <= 0 || loadJob?.isActive == true ||
      importJob?.isActive == true || analysisJob?.isActive == true) return
    if (mutable.value.phase == ReadAlongPhase.ERROR && mutable.value.recovery != null) return
    if (exhaustedBase == windowBase()) return
    val version = generation
    val request = ++analysisSequence
    analysisJob = scope.launch {
      fun valid() = current(track, version) && request == analysisSequence
      try {
        while (isActive && visible && valid()) {
          val base = windowBase()
          val starts = listOf(base, base + 20_000).filter { it < durationMs }
          val pending = withContext(Dispatchers.IO) { starts.filter { !store.windowReady(track, book.id, it) } }
          if (!valid()) return@launch
          Log.d("ReadAlongPerf", "cacheHits=${starts.size - pending.size} requestedWindows=${starts.size}")
          val start = pending.firstOrNull()
          if (start == null) { exhaustedBase = base; break }
          val ahead = start > base
          if (!models.isInstalled()) {
            exhaustedBase = base
            if (!ahead && modelJob?.isActive != true) mutable.update { it.copy(phase = ReadAlongPhase.NEEDS_MODEL,
              message = "Download the English speech model (60 MB) to match the narrator on this phone.") }
            break
          }
          analysisStartMs = positionMs
          mutable.update { it.copy(phase = if (ahead) ReadAlongPhase.READY else ReadAlongPhase.PREPARING,
            preparingAhead = ahead, message = if (ahead) null else "Preparing sync… Audio can keep playing.") }
          val began = android.os.SystemClock.elapsedRealtime()
          val decodeStart = (start - 5_000).coerceAtLeast(0)
          val pcm = decoder.decode(track, decodeStart, minOf(30_000, durationMs - decodeStart))
          val decoded = android.os.SystemClock.elapsedRealtime()
          var matchedCount = 0
          speech.transcribeProgressive(models.file, pcm, (positionMs - decodeStart).coerceAtLeast(0)) { partial ->
            ensureActive()
            if (valid()) {
              val words = partial.map { it.copy(startMs = it.startMs + decodeStart, endMs = it.endMs + decodeStart) }
              val activeMatcher = matcher
              val hint = selectedHint ?: mutable.value.activeLineIndex.takeIf { it >= 0 }
              val matched = withContext(Dispatchers.Default) { activeMatcher?.align(words, hint) ?: emptyList() }
              if (valid()) {
                val lines = matched.filter { it.id !in timings }
                withContext(Dispatchers.IO) { ensureActive(); store.saveWindow(track, book.id, start, minOf(start + 20_000, durationMs), lines, complete = false) }
                if (valid()) {
                  lines.forEach { timings[it.id] = it }
                  matchedCount = maxOf(matchedCount, matched.size)
                  if (lines.isNotEmpty()) {
                    mutable.update { it.copy(phase = ReadAlongPhase.READY, preparingAhead = true, message = null) }
                    publishLines()
                    Log.d("ReadAlongPerf", "usableUpdateMs=${android.os.SystemClock.elapsedRealtime()-began} added=${lines.size}")
                  }
                }
              }
            }
          }
          if (!valid()) return@launch
          withContext(Dispatchers.IO) { ensureActive(); store.saveWindow(track, book.id, start, minOf(start + 20_000, durationMs), emptyList()) }
          if (!valid()) return@launch
          Log.d("ReadAlongPerf", "decodeMs=${decoded-began} progressiveMs=${android.os.SystemClock.elapsedRealtime()-decoded} matched=$matchedCount ahead=$ahead")
          mutable.update { it.copy(alignmentAttempted = true, phase = ReadAlongPhase.READY, preparingAhead = false,
            analysisMillis = android.os.SystemClock.elapsedRealtime() - began,
            message = if (!ahead && matchedCount == 0) "No confident text match in this passage. Check the edition or choose a chapter." else it.message.takeIf { _ -> ahead }) }
          publishLines()
          delay(100)
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (valid()) {
          exhaustedBase = windowBase()
          // A failed future passage must not hide already usable current text.
          mutable.update { it.copy(phase = if (it.preparingAhead) ReadAlongPhase.READY else ReadAlongPhase.ERROR,
            preparingAhead = false, message = error.message ?: "Could not prepare speech synchronization.") }
          offerRecovery(track, version, "Retry matching", ::retryCurrentPassage)
        }
      } finally {
        if (request == analysisSequence) {
          analysisStartMs = -1
          mutable.update { it.copy(preparingAhead = false, phase = if (it.phase == ReadAlongPhase.PREPARING) ReadAlongPhase.READY else it.phase) }
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
    mutable.update { it.copy(offsetMs = value, activeLineIndex = ReadAlongTimeline.activeIndex(it.lines, positionMs, value)) }
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
    mutable.update { it.copy(activeLineIndex = -1, alignmentAttempted = false, searching = false, accessUrl = null, message = "Chapter selected: ${chapter.title}. Preparing narration match…") }
    scope.launch {
      try {
        withContext(Dispatchers.IO) { store.setChapterHint(track, chapter.index); store.resetAlignment(track, book.id) }
        if (current(track, version)) {
          selectedHint = index.takeIf { it >= 0 }
          chapterHint = chapter.index
          timings.clear(); publishLines(); scheduleAnalysis()
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
    timings = paced.associateByTo(LinkedHashMap()) { it.id }
    mutable.update { it.copy(source = TextSyncSource.EPUB_PACED, phase = ReadAlongPhase.READY, message = "Estimated timing — use Correct sync to correct drift.") }
    publishLines()
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
          if (!models.isInstalled()) {
            pendingReference = null
            mutable.update { it.copy(correction = it.correction.copy(phase = SyncCorrectionPhase.ERROR, needsModel = true,
              message = "Download the speech model, then press Sync now again when this sentence begins.")) }
            return@launch
          }
          val start = reference.windowStartMs
          val pcm = decoder.decode(target.track, start, reference.windowDurationMs)
          val words = speech.transcribe(models.file, pcm).map { it.copy(startMs = it.startMs + start, endMs = it.endMs + start) }
          val activeMatcher = matcher
          val matched = withContext(Dispatchers.Default) { activeMatcher?.align(words, index) ?: emptyList() }
          if (!valid()) return@launch
          val selected = matched.firstOrNull { it.id == target.sentenceId }
            ?: error("Could not confidently match this sentence. Try a longer sentence or check the ebook edition.")
          val additions = matched.filter { it.id !in timings }
          withContext(Dispatchers.IO) { ensureActive(); store.saveWindow(target.track, target.bookId, start, start + reference.windowDurationMs, additions) }
          if (!valid()) return@launch
          additions.forEach { timings[it.id] = it }
          line = selected
        }
        if (!valid()) return@launch
        undoCorrection = UndoCorrection(target, mutable.value.offsetMs, chapterHint, selectedHint)
        if (target.source == TextSyncSource.NARRATION && index >= 0) {
          selectedHint = index; chapterHint = sentences[index].chapterIndex
        }
        val value = ReadAlongTimeline.calibration(line, reference.positionMs)
        val lines = sentences.map { it.withTiming(timings[it.id]) }
        val recoveredMatching = mutable.value.recoveryLabel in setOf("Retry matching", "Retry model download")
        if (recoveredMatching) recoveryAction = null
        // Playback may have advanced during matching; align this historical reference without seeking.
        mutable.update { it.copy(offsetMs = value, lines = lines, matchedSentences = lines.count { item -> item.isTimed },
          activeLineIndex = ReadAlongTimeline.activeIndex(lines, positionMs, value), message = null,
          phase = if (recoveredMatching) ReadAlongPhase.READY else it.phase,
          recovery = if (recoveredMatching) null else it.recovery, recoveryLabel = if (recoveredMatching) null else it.recoveryLabel,
          correction = SyncCorrectionState(SyncCorrectionPhase.APPLIED, target, "Text aligned.", event = request), canUndoCorrection = true) }
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
    selectedHint = previous.sentenceHint; chapterHint = previous.chapter
    undoCorrection = null
    mutable.update { it.copy(offsetMs = previous.offset, canUndoCorrection = false, correction = SyncCorrectionState(event = correctionSequence)) }
    publishLines(); persistCorrection(previous.target, previous.offset, previous.chapter)
  }

  fun resetSyncCorrection() {
    val target = mutable.value.lines.firstOrNull()?.let { mutable.value.syncTarget(it.id) } ?: return
    invalidateCorrection()
    mutable.update { it.copy(offsetMs = 0) }; publishLines()
    persistCorrection(target, 0, chapterHint)
    scheduleAnalysis()
  }

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

  private fun publishLines() {
    val lines = sentences.map { it.withTiming(timings[it.id]) }
    mutable.update { it.copy(lines = lines, matchedSentences = lines.count { line -> line.isTimed }, activeLineIndex = ReadAlongTimeline.activeIndex(lines, positionMs, it.offsetMs)) }
  }
  private fun current(track: ReadAlongTrack, version: Long) = mutable.value.track == track && generation == version
  private fun fail(error: Exception, track: ReadAlongTrack, version: Long) {
    if (error is CancellationException) throw error
    if (current(track, version)) mutable.update { it.copy(phase = ReadAlongPhase.ERROR, message = error.message ?: "Unable to load companion text.") }
  }
  fun close() {
    scope.cancel()
    context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
    speech.close()
  }
}

package com.example.rivulet.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
  private val decoder: AudioWindowDecoder,
  private val speech: WhisperEngine = WhisperEngine(),
  private val models: SpeechModelManager = SpeechModelManager(context),
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val mutable = MutableStateFlow(ReadAlongState())
  val state = mutable.asStateFlow()
  private var generation = 0L
  private var loadJob: Job? = null
  private var importJob: Job? = null
  private var analysisJob: Job? = null
  private var modelJob: Job? = null
  private var lookupJob: Job? = null
  private var offsetJob: Job? = null
  private var exhaustedBase: Long? = null
  private val library = ZLibraryClient { url -> android.webkit.CookieManager.getInstance().getCookie(url) }
  private var source: StoredReadAlongBook? = null
  private var sentences: List<BookSentence> = emptyList()
  private var matcher: NarrationMatcher? = null
  private var timings = LinkedHashMap<Long, TimedLine>()
  private var positionMs = 0L
  private var durationMs = 0L
  private var visible = false
  private var estimated = false

  fun activate(track: ReadAlongTrack) {
    if (mutable.value.track == track) return
    generation++
    loadJob?.cancel(); importJob?.cancel(); analysisJob?.cancel(); lookupJob?.cancel()
    source = null; sentences = emptyList(); matcher = null; timings.clear(); estimated = false
    positionMs = 0; durationMs = 0; selectedHint = null; exhaustedBase = null
    mutable.value = ReadAlongState(track = track, phase = ReadAlongPhase.LOADING)
    load(track, generation)
  }

  private fun load(track: ReadAlongTrack, version: Long) {
    exhaustedBase = null
    loadJob?.cancel()
    loadJob = scope.launch {
      try {
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
        selectedHint = saved.third?.let { hint -> loaded.indexOfFirst { it.chapterIndex == hint }.takeIf { it >= 0 } }
        timings = existing.associateByTo(LinkedHashMap()) { it.id }
        if (book.source == TextSyncSource.LRC || book.source == TextSyncSource.SRT) {
          timings = LrcParser.detectAndParse(book.book.chapters.joinToString("\n") { it.text }).associateByTo(LinkedHashMap()) { it.id }
          sentences = timings.values.map { BookSentence(it.id, 1, book.book.title, it.text) }
        }
        mutable.value = ReadAlongState(track = track, bookId = book.id, title = book.book.title, isbn = book.book.isbn,
          chapters = book.book.chapters, offsetMs = saved.second, phase = ReadAlongPhase.READY, source = book.source, trackIndices = indices)
        publishLines()
        scheduleAnalysis()
      } catch (error: Exception) { fail(error, track, version) }
    }
  }

  fun setVisible(value: Boolean) {
    visible = value
    if (value) scheduleAnalysis() else { analysisJob?.cancel(); analysisJob = null }
  }

  fun playback(position: Long, duration: Long) {
    positionMs = position.coerceAtLeast(0)
    durationMs = duration.coerceAtLeast(0)
    val snapshot = mutable.value
    mutable.update { it.copy(activeLineIndex = ReadAlongTimeline.activeIndex(it.lines, positionMs, it.offsetMs)) }
    if (estimated && durationMs > 0 && snapshot.lines.none { it.isTimed }) useEstimatedTiming()
    // A seek cancels work far from the new playhead, then prioritizes that passage.
    if (analysisJob?.isActive == true && analysisStartMs >= 0 && kotlin.math.abs(positionMs - analysisStartMs) > 90_000) {
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

  fun searchBooks(query: String) {
    val track = mutable.value.track ?: return
    val version = generation
    lookupJob?.cancel()
    mutable.update { it.copy(searching = true, editions = emptyList(), accessUrl = null, lookupMessage = null) }
    lookupJob = scope.launch {
      try {
        val results = withContext(Dispatchers.IO) { library.search(query) }
        if (current(track, version)) mutable.update { it.copy(searching = false, editions = results, lookupMessage = if (results.isEmpty()) "No editions found. Try a different title or import your ebook." else null) }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (current(track, version)) mutable.update { it.copy(searching = false, accessUrl = (error as? SiteAccessRequired)?.url, lookupMessage = error.message) }
      }
    }
  }

  fun downloadEdition(edition: BookEdition, selectedTracks: Set<Int>, renderedHtml: String? = null) {
    val track = mutable.value.track ?: return
    val version = generation
    lookupJob?.cancel()
    mutable.update { it.copy(searching = true, lookupMessage = "Downloading ${edition.title}…", accessUrl = null) }
    lookupJob = scope.launch {
      try {
        val (bytes, name, mime) = withContext(Dispatchers.IO) { library.download(edition, renderedHtml) }
        if (current(track, version)) {
          mutable.update { it.copy(searching = false, editions = emptyList(), lookupMessage = null) }
          importBytes(bytes, name, mime, selectedTracks)
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (current(track, version)) mutable.update { it.copy(searching = false, accessUrl = (error as? SiteAccessRequired)?.url, lookupMessage = error.message) }
      }
    }
  }

  fun dismissAccess() { mutable.update { it.copy(accessUrl = null) } }

  fun acceptRenderedSearch(html: String, url: String) {
    val editions = ZLibraryClient.parseResults(html, url)
    mutable.update { it.copy(accessUrl = null, searching = false, editions = editions, lookupMessage = if (editions.isEmpty()) "No book editions were found on this page. Search again or import an ebook." else null) }
  }

  fun reportImportError(message: String) { mutable.update { it.copy(phase = ReadAlongPhase.ERROR, message = message) } }

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
    generation++
    val version = generation
    loadJob?.cancel(); importJob?.cancel(); analysisJob?.cancel()
    mutable.update { it.copy(phase = ReadAlongPhase.LOADING, message = "Importing companion text…") }
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
      } catch (error: Exception) { fail(error, track, version) }
    }
  }

  fun retry() {
    val track = mutable.value.track ?: return
    if (source == null) load(track, generation) else { mutable.update { it.copy(phase = ReadAlongPhase.READY, message = null) }; scheduleAnalysis() }
  }

  fun downloadModel() {
    if (modelJob?.isActive == true) return
    modelJob = scope.launch {
      try {
        mutable.update { it.copy(modelProgress = 0, message = "Downloading the English speech model…") }
        models.download { progress -> mutable.update { it.copy(modelProgress = progress) } }
        mutable.update { it.copy(modelProgress = null, phase = if (source == null) ReadAlongPhase.EMPTY else ReadAlongPhase.READY, message = null) }
        scheduleAnalysis()
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutable.update { it.copy(modelProgress = null, phase = ReadAlongPhase.ERROR, message = error.message ?: "Model download failed.") }
      }
    }
  }

  private var analysisStartMs = -1L
  private fun scheduleAnalysis() {
    val book = source ?: return
    val track = mutable.value.track ?: return
    if (!visible || estimated || book.source != TextSyncSource.NARRATION || durationMs <= 0 || loadJob?.isActive == true || importJob?.isActive == true || analysisJob?.isActive == true) return
    val requestedBase = ((positionMs - 5_000).coerceAtLeast(0) / 20_000) * 20_000
    if (exhaustedBase == requestedBase) return
    if (!models.isInstalled()) {
      if (modelJob?.isActive != true) mutable.update { it.copy(phase = ReadAlongPhase.NEEDS_MODEL, message = "Download the English speech model (60 MB) to match the narrator on this phone.") }
      return
    }
    val version = generation
    analysisJob = scope.launch {
      try {
        while (isActive && visible && current(track, version)) {
          val base = ((positionMs - 5_000).coerceAtLeast(0) / 20_000) * 20_000
          val starts = (0..5).map { base + it * 20_000 }.filter { it < durationMs }
          val start = withContext(Dispatchers.IO) { starts.firstOrNull { !store.windowReady(track, book.id, it) } }
          if (start == null) { exhaustedBase = base; break }
          // Measure distance from the initiating playhead, not from speculative look-ahead work.
          analysisStartMs = positionMs
          mutable.update { it.copy(phase = ReadAlongPhase.PREPARING, message = "Preparing sync… Audio can keep playing.") }
          val began = android.os.SystemClock.elapsedRealtime()
          val pcm = decoder.decode(track, start, minOf(30_000, durationMs - start))
          val words = speech.transcribe(models.file, pcm).map { it.copy(startMs = it.startMs + start, endMs = it.endMs + start) }
          val activeMatcher = matcher
          val hint = selectedHint ?: mutable.value.activeLineIndex.takeIf { it >= 0 }
          val lines = withContext(Dispatchers.Default) { activeMatcher?.align(words, hint) ?: emptyList() }
          if (!current(track, version)) return@launch
          withContext(Dispatchers.IO) { ensureActive(); store.saveWindow(track, book.id, start, minOf(start + 20_000, durationMs), lines) }
          if (!current(track, version)) return@launch
          lines.forEach { timings[it.id] = it }
          mutable.update { it.copy(analysisMillis = android.os.SystemClock.elapsedRealtime() - began, message = if (lines.isEmpty()) "No confident text match in this passage. Check the edition or choose a chapter." else null) }
          publishLines()
          delay(100)
        }
        if (current(track, version)) mutable.update { it.copy(phase = ReadAlongPhase.READY) }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (current(track, version)) {
          mutable.update { it.copy(phase = ReadAlongPhase.ERROR, message = error.message ?: "Could not prepare speech synchronization.") }
          // Missing pieces are retried without disturbing the player's download priorities.
          delay(5_000)
        }
      } finally { analysisStartMs = -1 }
    }
  }

  fun adjustOffset(deltaMs: Long) = saveOffset(mutable.value.offsetMs + deltaMs)
  fun calibrate(line: TimedLine) { if (line.isTimed) saveOffset(ReadAlongTimeline.calibration(line, positionMs)) }
  private fun saveOffset(value: Long) {
    val track = mutable.value.track ?: return
    val bookId = mutable.value.bookId ?: return
    mutable.update { it.copy(offsetMs = value, activeLineIndex = ReadAlongTimeline.activeIndex(it.lines, positionMs, value)) }
    val previous = offsetJob
    offsetJob = scope.launch { previous?.join(); withContext(Dispatchers.IO) { store.setOffset(track, value, bookId) } }
  }

  fun chooseChapter(chapter: EpubChapter) {
    val track = mutable.value.track ?: return
    val book = source ?: return
    generation++
    val version = generation
    val index = sentences.indexOfFirst { it.chapterIndex == chapter.index }
    analysisJob?.cancel(); analysisJob = null
    exhaustedBase = null
    mutable.update { it.copy(activeLineIndex = -1, message = "Chapter selected: ${chapter.title}. Preparing narration match…") }
    scope.launch {
      try {
        withContext(Dispatchers.IO) { store.setChapterHint(track, chapter.index); store.resetAlignment(track, book.id) }
        if (current(track, version)) {
          selectedHint = index.takeIf { it >= 0 }
          timings.clear(); publishLines(); scheduleAnalysis()
        }
      } catch (error: Exception) { fail(error, track, version) }
    }
  }
  private var selectedHint: Int? = null

  fun useEstimatedTiming() {
    val book = source ?: return
    if (durationMs <= 0) { mutable.update { it.copy(message = "Wait until the audio duration is known.") }; return }
    analysisJob?.cancel(); analysisJob = null; estimated = true
    val text = book.book.chapters.joinToString("\n\n") { it.text }
    val paced = PacedTimingEngine.paceText(text, durationMs)
    sentences = paced.map { BookSentence(it.id, 1, book.book.title, it.text) }
    timings = paced.associateByTo(LinkedHashMap()) { it.id }
    mutable.update { it.copy(source = TextSyncSource.EPUB_PACED, phase = ReadAlongPhase.READY, message = "Estimated timing — use Sync here to correct drift.") }
    publishLines()
  }

  fun useNarrationTiming() {
    estimated = false
    val track = mutable.value.track ?: return
    generation++; analysisJob?.cancel(); load(track, generation)
  }

  private fun publishLines() {
    val lines = sentences.map { sentence -> timings[sentence.id] ?: TimedLine(sentence.id, -1, -1, sentence.text) }
    mutable.update { it.copy(lines = lines, matchedSentences = lines.count { line -> line.isTimed }, activeLineIndex = ReadAlongTimeline.activeIndex(lines, positionMs, it.offsetMs)) }
  }
  private fun current(track: ReadAlongTrack, version: Long) = mutable.value.track == track && generation == version
  private fun fail(error: Exception, track: ReadAlongTrack, version: Long) {
    if (error is CancellationException) throw error
    if (current(track, version)) mutable.update { it.copy(phase = ReadAlongPhase.ERROR, message = error.message ?: "Unable to load companion text.") }
  }
  fun close() { scope.cancel() }
}

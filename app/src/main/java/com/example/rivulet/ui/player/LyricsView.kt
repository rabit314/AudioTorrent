package com.example.rivulet.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import com.example.rivulet.data.AudioFile
import com.example.rivulet.data.lyrics.*
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LyricsView(
  repository: ReadAlongRepository,
  audioFiles: List<AudioFile>,
  cleanBookTitle: String,
  onSeekToLine: (TimedLine) -> Unit,
  modifier: Modifier = Modifier,
  onSyncNow: (SyncTarget) -> Unit = { repository.correctionUnavailable("Start or prepare the audio before correcting sync.") },
  searchAuthor: String = "",
  bookIdentityReady: Boolean = true,
  bookIdentityTrack: ReadAlongTrack? = null,
  isVisible: Boolean = true,
) {
  val state by repository.state.collectAsStateWithLifecycle()
  val textSize by repository.readerPreferences.textSize.collectAsStateWithLifecycle()
  val list = key(state.track, state.bookId, state.source) { rememberLazyListState() }
  val paragraphs = remember(state.lines, state.source) { readerParagraphs(state.lines, state.source) }
  val dragged by list.interactionSource.collectIsDraggedAsState()
  var following by rememberSaveable(state.track, state.bookId, state.source) { mutableStateOf(true) }
  var followingBeforeCorrection by rememberSaveable(state.track, state.bookId, state.source) { mutableStateOf(true) }
  var handledCorrectionEvent by rememberSaveable(state.track, state.bookId, state.source) { mutableLongStateOf(-1L) }
  val suggestedQuery = BookIdentity(cleanBookTitle, searchAuthor).query
  var previousSuggestion by rememberSaveable(state.track) { mutableStateOf(suggestedQuery) }
  var query by rememberSaveable(state.track) { mutableStateOf(suggestedQuery) }
  LaunchedEffect(suggestedQuery) {
    if (query == previousSuggestion) query = suggestedQuery
    previousSuggestion = suggestedQuery
  }
  var showSearch by rememberSaveable(state.track) { mutableStateOf(false) }
  var showOptions by remember { mutableStateOf(false) }
  var showTracks by remember(state.track) { mutableStateOf(false) }
  var showChapters by remember(state.track) { mutableStateOf(false) }
  var showAdjustment by remember(state.track) { mutableStateOf(false) }
  var showTextSize by rememberSaveable { mutableStateOf(false) }
  var showAccess by remember(state.track) { mutableStateOf(false) }
  var selectedTracks by remember(state.track, audioFiles) {
    mutableStateOf(ReadAlongText.defaultTracks(audioFiles, state.track?.fileIndex ?: -1))
  }
  var pickedFor by remember { mutableStateOf<ReadAlongTrack?>(null) }
  var pickedTracks by remember { mutableStateOf<Set<Int>>(emptySet()) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      if (repository.state.value.track == pickedFor) repository.importDocument(uri, pickedTracks)
      else repository.reportImportError("The audio track changed while the picker was open. Select the file again for the current book.")
    }
  }
  val importFile = {
    pickedFor = state.track; pickedTracks = selectedTracks
    picker.launch(arrayOf("application/epub+zip", "text/*", "application/x-subrip", "application/octet-stream"))
  }
  fun search() = repository.searchBooks(query, title = if (query == suggestedQuery) cleanBookTitle else query,
    author = if (query == suggestedQuery) searchAuthor else "")
  val findText = { showSearch = true; search() }
  val beginCorrection: (Long?) -> Unit = { sentenceId ->
    if (!state.correction.isEditing) followingBeforeCorrection = following
    following = false
    repository.beginCorrection(sentenceId)
  }
  DisposableEffect(repository, isVisible) {
    repository.setVisible(isVisible)
    onDispose { repository.setVisible(false) }
  }
  LaunchedEffect(state.track, state.phase, state.bookId, cleanBookTitle, searchAuthor, bookIdentityReady, bookIdentityTrack, isVisible) {
    if (bookIdentityReady && isVisible) repository.ensureBookText(BookIdentity(cleanBookTitle, searchAuthor), selectedTracks, bookIdentityTrack ?: state.track)
    if (state.bookId != null && state.phase != ReadAlongPhase.LOADING && !state.searching) showSearch = false
  }
  LaunchedEffect(state.choosingEdition) { if (state.choosingEdition) showSearch = true }
  LaunchedEffect(state.bookId, state.trackIndices) { if (state.trackIndices.isNotEmpty()) selectedTracks = state.trackIndices }
  LaunchedEffect(dragged) { if (dragged) following = false }
  LaunchedEffect(state.correction.phase, state.correction.event) {
    if (state.correction.phase == SyncCorrectionPhase.APPLIED && handledCorrectionEvent != state.correction.event) {
      following = true
      handledCorrectionEvent = state.correction.event
    }
  }

  Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
      if (state.correction.isEditing) Text("Correct sync", style = MaterialTheme.typography.labelLarge)
      if (state.bookId != null && !state.correction.isEditing) TextButton(onClick = { beginCorrection(null) }, modifier = Modifier.testTag("correct-sync")) { Text("Correct sync") }
      Box {
        TextButton(onClick = { showOptions = true }) {
          if (state.preparingAhead && state.lines.isNotEmpty()) {
            CircularProgressIndicator(Modifier.size(12.dp).semantics { contentDescription = "Preparing the next passage" }, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp))
          }
          Text("Text options")
        }
        DropdownMenu(showOptions, onDismissRequest = { showOptions = false }) {
          DropdownMenuItem(text = { Text("Text size") }, onClick = { showOptions = false; showTextSize = true })
          DropdownMenuItem(text = { Text(if (state.bookId == null) "Find book text" else "Replace edition") }, onClick = { showOptions = false; findText() })
          DropdownMenuItem(text = { Text("Import EPUB / TXT / LRC / SRT") }, onClick = { showOptions = false; importFile() })
          DropdownMenuItem(text = { Text("Select audio tracks") }, onClick = { showOptions = false; showTracks = true })
          if (state.canChooseChapter) DropdownMenuItem(text = { Text("Choose chapter hint") }, onClick = { showOptions = false; showChapters = true })
          if (state.bookId != null) DropdownMenuItem(text = { Text("Synchronization adjustment") }, onClick = { showOptions = false; showAdjustment = true })
          if (state.canEstimate) DropdownMenuItem(text = { Text("Use estimated timing…") }, onClick = { showOptions = false; showAdjustment = true })
          if (state.source == TextSyncSource.EPUB_PACED) DropdownMenuItem(text = { Text("Return to narration matching") }, onClick = { showOptions = false; repository.useNarrationTiming() })
          if (state.source == TextSyncSource.NARRATION) DropdownMenuItem(text = { Text("Retry current passage") }, onClick = { showOptions = false; repository.retryCurrentPassage() })
        }
      }
    }
    if (!showSearch) ReadAlongStatus(state, repository::retry, importFile, repository::downloadModel, { showAccess = true }, showAhead = false)
    if (state.correction.isEditing) {
      val correction = state.correction
      Text(correction.message ?: "Select a sentence. Press Sync now when it begins.", style = MaterialTheme.typography.bodySmall)
      correction.target?.let { target -> state.lines.firstOrNull { it.id == target.sentenceId }?.let { line ->
        Text("Selected: ${line.text}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      } }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (correction.needsModel) TextButton(onClick = repository::downloadModel, enabled = state.modelProgress == null) { Text("Download speech model") }
        else FilledTonalButton(onClick = { correction.target?.let(onSyncNow) },
          enabled = correction.target != null && correction.phase != SyncCorrectionPhase.MATCHING, modifier = Modifier.testTag("sync-now")) { Text("Sync now") }
        if (correction.canRetry) TextButton(onClick = repository::retryCorrection) { Text("Retry matching") }
        TextButton(onClick = { repository.cancelCorrection(); following = followingBeforeCorrection }) { Text("Cancel") }
      }
    } else if (state.canUndoCorrection) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("Text aligned", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
      TextButton(onClick = repository::undoSyncCorrection) { Text("Undo correction") }
    }
    if (state.bookId == null && state.phase != ReadAlongPhase.LOADING && !state.searching) {
      Text("Find your edition or import a book to read along.", style = MaterialTheme.typography.bodyMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = findText) { Text("Find book text") }
        TextButton(onClick = importFile) { Text("Import file") }
      }
    }
    if (!following && !state.correction.isEditing) FilledTonalButton(onClick = { following = true }, modifier = Modifier.fillMaxWidth()) { Text("Follow narration") }
    key(state.track, state.bookId, state.source) {
      FlowingReader(paragraphs, list, textSize, state.lines.getOrNull(state.activeLineIndex)?.id,
      state.correction.target?.sentenceId.takeIf { state.correction.isEditing }, following, state.correction.isEditing, isVisible,
      onTap = { id ->
        val line = state.lines.firstOrNull { it.id == id }
        if (state.correction.isEditing) { repository.selectCorrectionSentence(id); line != null }
        else if (line?.isTimed == true) { onSeekToLine(line); following = true; true } else false
      }, onLongPress = { beginCorrection(it) }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
  }

  if (showSearch && isVisible) AlertDialog(
    onDismissRequest = { showSearch = false }, title = { Text("Find book text") },
    text = { Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
      OutlinedTextField(query, { query = it }, label = { Text("Book title or author") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      TextButton(onClick = { search() }) { Text("Search") }
      ReadAlongStatus(state, repository::retry, importFile, repository::downloadModel, { showAccess = true })
      LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        items(state.editions, key = { it.url }) { edition ->
          BookEditionRow(edition, onSelect = { repository.downloadEdition(edition, selectedTracks) }, enabled = !state.searching)
        }
      }
    } }, confirmButton = { TextButton(onClick = { showSearch = false }) { Text("Done") } },
  )
  if (showTracks && isVisible) AlertDialog(
    onDismissRequest = { showTracks = false }, title = { Text("Attach text to audio tracks") },
    text = { LazyColumn(Modifier.heightIn(max = 380.dp)) { items(audioFiles, key = { it.index }) { file ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(file.index in selectedTracks, onCheckedChange = { checked -> selectedTracks = if (checked) selectedTracks + file.index else selectedTracks - file.index }, enabled = file.index != state.track?.fileIndex)
        Text(file.displayTitle, style = MaterialTheme.typography.bodySmall)
      }
    } } }, confirmButton = { TextButton(onClick = { repository.updateTrackSelection(selectedTracks); showTracks = false }) { Text("Save") } },
  )
  if (showChapters && state.canChooseChapter && isVisible) AlertDialog(
    onDismissRequest = { showChapters = false }, title = { Text("Choose a chapter hint") },
    text = { LazyColumn(Modifier.heightIn(max = 380.dp)) { items(state.chapters, key = { it.index }) { chapter ->
      TextButton(onClick = { repository.chooseChapter(chapter); showChapters = false; following = false }, modifier = Modifier.fillMaxWidth()) { Text(chapter.title) }
    } } }, confirmButton = { TextButton(onClick = { showChapters = false }) { Text("Close") } },
  )
  if (showAdjustment && isVisible) AlertDialog(
    onDismissRequest = { showAdjustment = false }, title = { Text("Synchronization") },
    text = { Column {
      Text("Correction: ${state.offsetMs / 1000.0}s")
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { repository.adjustOffset(-500) }) { Text("−0.5s") }
        TextButton(onClick = { repository.adjustOffset(500) }) { Text("+0.5s") }
      }
      TextButton(onClick = repository::resetSyncCorrection) { Text("Reset correction") }
      if (state.canEstimate) {
        Text("Narration matching is unavailable for this passage. Estimated timing uses reading pace and may drift. Its adjustments are temporary.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { repository.useEstimatedTiming(); showAdjustment = false }) { Text("Use estimated timing") }
      }
      if (state.source == TextSyncSource.EPUB_PACED) TextButton(onClick = { repository.useNarrationTiming(); showAdjustment = false }) { Text("Return to narration matching") }
      state.isbn?.let { Text("ISBN $it · checksum valid. This validates the ISBN, not the narration match.", style = MaterialTheme.typography.bodySmall) }
      Text("${state.matchedSentences} sentences timed · ${state.source?.displayName ?: "No text"}", style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(onClick = { showAdjustment = false }) { Text("Done") } },
  )
  if (showTextSize && isVisible) AlertDialog(
    onDismissRequest = { showTextSize = false }, title = { Text("Text size") },
    text = { Column {
      Text("$textSize sp", modifier = Modifier.testTag("reader-text-size"))
      Slider(value = textSize.toFloat(), onValueChange = { repository.readerPreferences.setTextSize(it.roundToInt()) },
        valueRange = 14f..26f, steps = 5, modifier = Modifier.testTag("reader-text-size-slider"))
      Text("The words follow the narration at your reading size.", fontSize = textSize.sp, lineHeight = (textSize + 8).sp)
      TextButton(onClick = { repository.readerPreferences.setTextSize(ReaderPreferences.DEFAULT_SIZE) }) { Text("Reset") }
    } }, confirmButton = { TextButton(onClick = { showTextSize = false }) { Text("Done") } },
  )
  if (showAccess && isVisible) state.accessUrl?.let { url ->
    ZLibraryAccessDialog(url, onDismiss = { showAccess = false }, onReady = { html, loadedUrl ->
      showAccess = false
      repository.resumeSiteAccess(html, loadedUrl, state.track)
    })
  }
}

/** One status area for both acquisition and synchronization, including operation-specific recovery. */
@Composable
fun ReadAlongStatus(state: ReadAlongState, onRetry: () -> Unit, onImport: () -> Unit, onDownloadModel: () -> Unit, onOpenAccess: () -> Unit = {}, showAhead: Boolean = true) {
  Column(Modifier.fillMaxWidth()) {
    state.progressLabel?.let { label ->
      LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("readalong-progress"))
      Text(label, style = MaterialTheme.typography.bodySmall)
    }
    if (state.progressLabel == null) listOfNotNull(state.lookupMessage, state.message.takeUnless { state.correction.isEditing }).distinct().forEach {
      Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
    }
    state.recoveryLabel?.let { label ->
      TextButton(onClick = if (state.recovery == ReadAlongRecovery.PICK_FILE) onImport else onRetry) { Text(label) }
    }
    if (state.accessUrl != null) TextButton(onClick = onOpenAccess) { Text("Open site access") }
    if (state.preparingAhead && showAhead) Text("Preparing the next passage…", style = MaterialTheme.typography.labelSmall)
    if (state.phase == ReadAlongPhase.NEEDS_MODEL && state.modelProgress == null && !state.correction.isEditing) TextButton(onClick = onDownloadModel) { Text("Download speech model · 60 MB") }
  }
}

@Composable
fun BookEditionRow(edition: BookEdition, onSelect: () -> Unit, enabled: Boolean = true, imageLoader: ImageLoader? = null) {
  OutlinedCard(onClick = onSelect, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("edition-row")) {
    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      val placeholder: @Composable () -> Unit = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.MenuBook, "Cover unavailable", tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
      SubcomposeAsyncImage(model = edition.coverUrl, imageLoader = imageLoader ?: LocalContext.current.imageLoader,
        contentDescription = "Cover of ${edition.title}", contentScale = ContentScale.Fit,
        loading = { placeholder() }, error = { placeholder() },
        modifier = Modifier.size(width = 56.dp, height = 84.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)).testTag("edition-cover"))
      Column(Modifier.weight(1f)) {
        Text(edition.title, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(listOf(edition.author, edition.language, edition.format).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        edition.isbn?.let { Text("ISBN $it", style = MaterialTheme.typography.labelSmall) }
      }
    }
  }
}

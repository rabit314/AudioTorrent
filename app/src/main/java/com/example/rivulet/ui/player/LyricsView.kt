package com.example.rivulet.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rivulet.data.AudioFile
import com.example.rivulet.data.lyrics.*

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LyricsView(
  repository: ReadAlongRepository,
  audioFiles: List<AudioFile>,
  cleanBookTitle: String,
  onSeekToLine: (TimedLine) -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by repository.state.collectAsStateWithLifecycle()
  val list = rememberLazyListState()
  val dragged by list.interactionSource.collectIsDraggedAsState()
  var following by rememberSaveable(state.track) { mutableStateOf(true) }
  var query by rememberSaveable(state.track) { mutableStateOf(cleanBookTitle) }
  var showSearch by rememberSaveable(state.track) { mutableStateOf(false) }
  var showTracks by remember { mutableStateOf(false) }
  var showChapters by remember { mutableStateOf(false) }
  var selectedTracks by remember(state.track, audioFiles) {
    mutableStateOf(ReadAlongText.defaultTracks(audioFiles, state.track?.fileIndex ?: -1))
  }
  var pickedFor by remember { mutableStateOf<ReadAlongTrack?>(null) }
  var pickedTracks by remember { mutableStateOf<Set<Int>>(emptySet()) }
  var pendingEdition by remember(state.track) { mutableStateOf<BookEdition?>(null) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      if (repository.state.value.track == pickedFor) repository.importDocument(uri, pickedTracks)
      else repository.reportImportError("The audio track changed while the picker was open. Select the file again for the current book.")
    }
  }
  DisposableEffect(repository) {
    repository.setVisible(true)
    onDispose { repository.setVisible(false) }
  }
  LaunchedEffect(state.track, state.phase) {
    if (state.bookId == null && state.phase == ReadAlongPhase.EMPTY) {
      showSearch = true
      repository.searchBooks(query)
    }
  }
  LaunchedEffect(state.bookId) { if (state.bookId != null) showSearch = false }
  LaunchedEffect(state.bookId, state.trackIndices) { if (state.trackIndices.isNotEmpty()) selectedTracks = state.trackIndices }
  LaunchedEffect(dragged) { if (dragged) following = false }
  LaunchedEffect(state.track, state.activeLineIndex, following) {
    if (following && state.activeLineIndex in state.lines.indices) list.animateScrollToItem((state.activeLineIndex - 2).coerceAtLeast(0))
  }

  Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    Text(state.title ?: cleanBookTitle.ifBlank { "Read Along" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    val activeChapter = state.lines.getOrNull(state.activeLineIndex)?.let { line ->
      state.chapters.firstOrNull { chapter -> chapter.text.contains(line.text) }?.title
    }
    if (activeChapter != null) Text(activeChapter, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    state.isbn?.let { Text("ISBN $it · checksum valid", style = MaterialTheme.typography.labelSmall) }
    if (state.source != null) Text("${state.source!!.displayName} · ${state.matchedSentences} sentences timed", style = MaterialTheme.typography.labelSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      TextButton(onClick = { showSearch = !showSearch; if (showSearch) repository.searchBooks(query) }) { Text("Find text") }
      TextButton(onClick = {
        pickedFor = state.track; pickedTracks = selectedTracks
        picker.launch(arrayOf("application/epub+zip", "text/*", "application/x-subrip", "application/octet-stream"))
      }) { Text("Import") }
      TextButton(onClick = { showTracks = true }) { Text("Tracks (${selectedTracks.size})") }
      if (state.chapters.isNotEmpty()) TextButton(onClick = { showChapters = true }) { Text("Chapters") }
    }

    if (showSearch) {
      OutlinedTextField(query, { query = it }, label = { Text("Book title or author") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        trailingIcon = { TextButton(onClick = { pendingEdition = null; repository.searchBooks(query) }, enabled = !state.searching) { Text("Search") } })
      if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
      state.lookupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp)) }
      if (state.editions.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 230.dp)) {
        items(state.editions, key = { it.url }) { edition ->
          OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = {
            pendingEdition = edition; repository.downloadEdition(edition, selectedTracks)
          }, enabled = !state.searching) {
            Column(Modifier.padding(12.dp)) {
              Text(edition.title, fontWeight = FontWeight.Bold)
              Text(listOf(edition.author, edition.language, edition.format).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
              edition.isbn?.let { Text("ISBN $it", style = MaterialTheme.typography.labelSmall) }
            }
          }
        }
      }
    }

    if (state.phase == ReadAlongPhase.LOADING || state.phase == ReadAlongPhase.PREPARING || state.modelProgress != null) {
      LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
    }
    state.message?.let { Text(it + (state.modelProgress?.let { p -> " $p%" } ?: ""), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp)) }
    if (state.phase == ReadAlongPhase.ERROR) TextButton(onClick = { repository.retry() }) { Text("Retry") }
    if (state.phase == ReadAlongPhase.NEEDS_MODEL || (state.phase == ReadAlongPhase.ERROR && state.bookId != null && state.matchedSentences == 0)) {
      FilledTonalButton(onClick = { repository.downloadModel() }, enabled = state.modelProgress == null) { Text("Download speech model · 60 MB") }
    }
    if (state.bookId != null) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { repository.adjustOffset(-500) }) { Text("−0.5s") }
        Text("${state.offsetMs / 1000.0}s", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { repository.adjustOffset(500) }) { Text("+0.5s") }
        TextButton(onClick = { if (state.source == TextSyncSource.EPUB_PACED) repository.useNarrationTiming() else repository.useEstimatedTiming() }) {
          Text(if (state.source == TextSyncSource.EPUB_PACED) "Match narrator" else "Estimate timing")
        }
      }
    }
    if (!following) FilledTonalButton(onClick = { following = true }, modifier = Modifier.fillMaxWidth()) { Text("Follow narration") }

    LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f).testTag("readalong-lines"), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      items(state.lines, key = { it.id }) { line ->
        val active = state.lines.getOrNull(state.activeLineIndex)?.id == line.id
        Column(Modifier.fillMaxWidth().background(
          if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
          RoundedCornerShape(12.dp),
        ).combinedClickable(
          onClick = { if (line.isTimed) { onSeekToLine(line); following = true } },
          onLongClick = { if (line.isTimed) repository.calibrate(line) },
        ).padding(12.dp)) {
          Text(line.text, fontSize = if (active) 23.sp else 20.sp, lineHeight = 30.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
          if (active) TextButton(onClick = { repository.calibrate(line) }) { Text("Sync here") }
        }
      }
      if (state.lines.isEmpty() && state.phase == ReadAlongPhase.EMPTY) item { Text("Find an edition above or import your EPUB, TXT, LRC or SRT file.") }
    }
  }

  if (showTracks) AlertDialog(
    onDismissRequest = { showTracks = false }, title = { Text("Attach text to audio tracks") },
    text = { LazyColumn(Modifier.heightIn(max = 380.dp)) { items(audioFiles, key = { it.index }) { file ->
      Row { Checkbox(file.index in selectedTracks, onCheckedChange = { checked ->
        selectedTracks = if (checked) selectedTracks + file.index else selectedTracks - file.index
      }, enabled = file.index != state.track?.fileIndex); Text(file.displayTitle, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) }
    } } },
    confirmButton = { TextButton(onClick = { repository.updateTrackSelection(selectedTracks); showTracks = false }) { Text("Save") } },
  )
  if (showChapters) AlertDialog(
    onDismissRequest = { showChapters = false }, title = { Text("Choose a chapter hint") },
    text = { LazyColumn(Modifier.heightIn(max = 380.dp)) { items(state.chapters, key = { it.index }) { chapter ->
      TextButton(onClick = { repository.chooseChapter(chapter); showChapters = false; following = false }, modifier = Modifier.fillMaxWidth()) { Text(chapter.title) }
    } } }, confirmButton = { TextButton(onClick = { showChapters = false }) { Text("Close") } },
  )
  state.accessUrl?.let { url ->
    ZLibraryAccessDialog(url, onDismiss = { repository.dismissAccess() }, onReady = { html, loadedUrl ->
      repository.dismissAccess()
      val edition = pendingEdition
      if (edition == null) repository.acceptRenderedSearch(html, loadedUrl) else repository.downloadEdition(edition, selectedTracks, html)
    })
  }
}

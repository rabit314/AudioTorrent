package com.example.rivulet.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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
import com.example.rivulet.theme.ReaderFontFamily
import com.example.rivulet.theme.ReaderLineSpacing
import com.example.rivulet.theme.ReaderPalette
import com.example.rivulet.theme.ReaderThemePreset
import com.example.rivulet.theme.readerPaletteFor
import com.example.rivulet.ui.formatDuration
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
  val readerTheme by repository.readerPreferences.readerTheme.collectAsStateWithLifecycle()
  val fontPref by repository.readerPreferences.fontFamily.collectAsStateWithLifecycle()
  val spacingPref by repository.readerPreferences.lineSpacing.collectAsStateWithLifecycle()
  val keepScreenOn by repository.readerPreferences.keepScreenOn.collectAsStateWithLifecycle()
  val syncMode by repository.readerPreferences.syncMode.collectAsStateWithLifecycle()

  val palette = readerPaletteFor(readerTheme, MaterialTheme.colorScheme)
  val composeFont = if (fontPref == ReaderFontFamily.SERIF) FontFamily.Serif else FontFamily.SansSerif
  val view = LocalView.current
  DisposableEffect(view, keepScreenOn, isVisible) {
    if (keepScreenOn && isVisible) {
      view.keepScreenOn = true
    }
    onDispose { view.keepScreenOn = false }
  }

  val list = key(state.track, state.bookId, state.source) { rememberLazyListState() }
  val paragraphs = remember(state.paragraphs, state.bookId, state.source, state.lines.isEmpty()) {
    state.paragraphs.ifEmpty { readerParagraphs(state.lines, state.source) }
  }
  val dragged by list.interactionSource.collectIsDraggedAsState()
  var following by rememberSaveable(state.track, state.bookId, state.source) { mutableStateOf(true) }
  var followingBeforeCorrection by rememberSaveable(state.track, state.bookId, state.source) { mutableStateOf(true) }
  var handledCorrectionEvent by rememberSaveable(state.track, state.bookId, state.source) { mutableLongStateOf(-1L) }
  val suggestedQuery = BookIdentity(cleanBookTitle, searchAuthor).query
  var previousSuggestion by rememberSaveable(state.track) { mutableStateOf(suggestedQuery) }
  var query by rememberSaveable(state.track) { mutableStateOf(suggestedQuery) }
  var textProvider by rememberSaveable(state.track) { mutableStateOf(TextProvider.AUTOMATIC) }
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
  var showThemeSheet by rememberSaveable { mutableStateOf(false) }
  var showSyncSheet by rememberSaveable { mutableStateOf(false) }
  var showBookNav by rememberSaveable { mutableStateOf(false) }
  var showAccess by remember(state.track) { mutableStateOf(false) }
  var scrollTargetSentenceId by remember { mutableStateOf<Long?>(null) }
  var scrollTargetNonce by remember { mutableLongStateOf(0L) }
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
    author = if (query == suggestedQuery) searchAuthor else "", provider = textProvider)
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

  Column(modifier.fillMaxSize().background(palette.background).padding(horizontal = 12.dp, vertical = 4.dp)) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
      if (state.correction.isEditing) {
        Text("Correct sync", style = MaterialTheme.typography.labelLarge, color = palette.activeText)
      }
      if (state.bookId != null && !state.correction.isEditing) {
        TextButton(onClick = { beginCorrection(null) }, modifier = Modifier.testTag("correct-sync")) {
          Text("Correct sync", color = palette.accent)
        }
      }
      syncBadgeLabel(state)?.let { label ->
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = palette.chipContainer,
          modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { showSyncSheet = true }
            .testTag("sync-status-pill"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            if (state.syncCoverage?.isSyncingNow == true || state.preparingAhead) {
              CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = palette.accent,
              )
            }
            Text(
              text = label,
              style = MaterialTheme.typography.labelSmall,
              color = palette.mutedText,
            )
          }
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.bookId != null) {
          IconButton(onClick = { showBookNav = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Search, contentDescription = "Search or jump chapters", tint = palette.accent, modifier = Modifier.size(18.dp))
          }
        }
        IconButton(onClick = { showThemeSheet = true }, modifier = Modifier.size(36.dp)) {
          Icon(Icons.Default.Palette, contentDescription = "Reading theme and style", tint = palette.accent, modifier = Modifier.size(18.dp))
        }
        Box {
          TextButton(onClick = { showOptions = true }) {
            if ((state.preparingAhead || state.phase == ReadAlongPhase.PREPARING) && state.lines.isNotEmpty()) {
              CircularProgressIndicator(
                Modifier.size(12.dp).semantics { contentDescription = "Preparing the next passage" },
                strokeWidth = 1.5.dp,
                color = palette.accent,
              )
              Spacer(Modifier.width(6.dp))
            }
            Text("Text options", color = palette.accent)
          }
          DropdownMenu(showOptions, onDismissRequest = { showOptions = false }) {
            DropdownMenuItem(text = { Text("Text size") }, onClick = { showOptions = false; showTextSize = true })
            DropdownMenuItem(text = { Text("Reader theme & style") }, onClick = { showOptions = false; showThemeSheet = true })
            if (state.bookId != null) DropdownMenuItem(text = { Text("Search book & chapters") }, onClick = { showOptions = false; showBookNav = true })
            if (state.bookId != null) DropdownMenuItem(text = { Text("Sync details & mode") }, onClick = { showOptions = false; showSyncSheet = true })
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
    }
    if (!showSearch) ReadAlongStatus(state, repository::retry, importFile, repository::downloadModel, { showAccess = true }, showAhead = false, textColor = palette.mutedText)
    if (state.correction.isEditing) {
      val correction = state.correction
      Text(correction.message ?: "Select a sentence. Press Sync now when it begins.", style = MaterialTheme.typography.bodySmall, color = palette.bodyText)
      correction.target?.let { target -> state.lines.firstOrNull { it.id == target.sentenceId }?.let { line ->
        Text("Selected: ${line.text}", style = MaterialTheme.typography.labelSmall, color = palette.mutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
      } }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (correction.needsModel) TextButton(onClick = repository::downloadModel, enabled = state.modelProgress == null) { Text("Download speech model") }
        else FilledTonalButton(onClick = { correction.target?.let(onSyncNow) },
          enabled = correction.target != null && correction.phase != SyncCorrectionPhase.MATCHING, modifier = Modifier.testTag("sync-now")) { Text("Sync now") }
        if (correction.canRetry) TextButton(onClick = repository::retryCorrection) { Text("Retry matching") }
        TextButton(onClick = { repository.cancelCorrection(); following = followingBeforeCorrection }) { Text("Cancel") }
      }
    } else if (state.canUndoCorrection) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("Text aligned", style = MaterialTheme.typography.labelSmall, color = palette.mutedText, modifier = Modifier.weight(1f))
      TextButton(onClick = repository::undoSyncCorrection) { Text("Undo correction") }
    }
    if (state.bookId == null && state.phase != ReadAlongPhase.LOADING && !state.searching) {
      Text("Find your edition or import a book to read along.", style = MaterialTheme.typography.bodyMedium, color = palette.bodyText)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = findText) { Text("Find book text") }
        TextButton(onClick = importFile) { Text("Import file") }
      }
    }
    if (!following && !state.correction.isEditing) {
      FilledTonalButton(onClick = { following = true }, modifier = Modifier.fillMaxWidth()) { Text("Follow narration") }
    }
    key(state.track, state.bookId, state.source) {
      FlowingReader(
        paragraphs = paragraphs,
        list = list,
        textSize = textSize,
        activeId = state.lines.getOrNull(state.activeLineIndex)?.id,
        selectedId = state.correction.target?.sentenceId.takeIf { state.correction.isEditing },
        following = following,
        editing = state.correction.isEditing,
        visible = isVisible,
        onTap = { id ->
          val line = state.lines.firstOrNull { it.id == id }
          if (state.correction.isEditing) { repository.selectCorrectionSentence(id); line != null }
          else if (line?.isTimed == true) { onSeekToLine(line); following = true; true } else false
        },
        onLongPress = { beginCorrection(it) },
        modifier = Modifier.fillMaxWidth().weight(1f),
        palette = palette,
        fontFamily = composeFont,
        lineSpacingExtraSp = spacingPref.extraSp,
        scrollTargetSentenceId = scrollTargetSentenceId,
        scrollTargetNonce = scrollTargetNonce,
      )
    }
  }

  if (showSearch && isVisible) AlertDialog(
    onDismissRequest = { showSearch = false }, title = { Text("Find book text") },
    text = { Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
      OutlinedTextField(query, { query = it }, label = { Text("Book title or author") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextProvider.entries.forEach { provider -> FilterChip(textProvider == provider, { textProvider = provider; search() }, label = { Text(provider.label) }) }
      }
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
      Text("The words follow the narration at your reading size.", fontSize = textSize.sp, lineHeight = (textSize + spacingPref.extraSp).sp, fontFamily = composeFont)
      TextButton(onClick = { repository.readerPreferences.setTextSize(ReaderPreferences.DEFAULT_SIZE) }) { Text("Reset") }
    } }, confirmButton = { TextButton(onClick = { showTextSize = false }) { Text("Done") } },
  )
  if (showThemeSheet && isVisible) {
    ReaderAppearanceDialog(
      readerTheme = readerTheme,
      fontFamily = fontPref,
      lineSpacing = spacingPref,
      textSize = textSize,
      keepScreenOn = keepScreenOn,
      onThemeChange = repository.readerPreferences::setReaderTheme,
      onFontChange = repository.readerPreferences::setFontFamily,
      onSpacingChange = repository.readerPreferences::setLineSpacing,
      onSizeChange = repository.readerPreferences::setTextSize,
      onKeepScreenChange = repository.readerPreferences::setKeepScreenOn,
      onDismiss = { showThemeSheet = false },
    )
  }
  if (showSyncSheet && isVisible) {
    SyncCoverageDialog(
      state = state,
      syncMode = syncMode,
      onSelectMode = repository::setSyncMode,
      onRetryCurrent = { repository.retryCurrentPassage(); showSyncSheet = false },
      onDismiss = { showSyncSheet = false },
    )
  }
  if (showBookNav && isVisible) {
    BookNavigationDialog(
      state = state,
      repository = repository,
      onJumpToChapter = { chapter ->
        repository.firstSentenceIdForChapter(chapter.index)?.let { id ->
          following = false
          scrollTargetSentenceId = id
          scrollTargetNonce++
        }
        showBookNav = false
      },
      onJumpToHit = { hit ->
        if (hit.isTimed) {
          onSeekToLine(TimedLine(hit.sentenceId, hit.startTimeMs, hit.startTimeMs + 1000L, hit.text))
          following = true
        } else {
          following = false
          scrollTargetSentenceId = hit.sentenceId
          scrollTargetNonce++
        }
        showBookNav = false
      },
      onDismiss = { showBookNav = false },
    )
  }
  if (showAccess && isVisible) state.accessUrl?.let { url ->
    ZLibraryAccessDialog(url, onDismiss = { showAccess = false }, onReady = { html, loadedUrl ->
      showAccess = false
      repository.resumeSiteAccess(html, loadedUrl, state.track)
    })
  }
}

private fun syncBadgeLabel(state: ReadAlongState): String? {
  if (state.bookId == null) return null
  return when (state.source) {
    TextSyncSource.LRC, TextSyncSource.SRT -> "✓ Transcript synced"
    TextSyncSource.EPUB_PACED -> "~ Estimated pace"
    else -> {
      val cov = state.syncCoverage
      if (cov != null) {
        when {
          cov.trackTimedRatio >= 0.99f -> "✓ Track synced"
          cov.aheadSyncedMs >= 60_000L -> "Synced +${cov.aheadSyncedMs / 60_000}m ahead"
          cov.aheadSyncedMs > 0L -> "Synced +${cov.aheadSyncedMs / 1000}s ahead"
          cov.isSyncingNow || state.preparingAhead -> "Syncing…"
          cov.trackDurationMs > 0L && cov.trackTimedRatio > 0f -> "${(cov.trackTimedRatio * 100).toInt()}% of track"
          cov.matchedSentences > 0 -> "${cov.matchedSentences} lines synced"
          else -> null
        }
      } else {
        state.syncProgress?.let { prog -> if (prog >= 0.99f) "✓ Track synced" else "${(prog * 100).toInt()}% synced" }
      }
    }
  }
}

@Composable
private fun ReaderAppearanceDialog(
  readerTheme: ReaderThemePreset,
  fontFamily: ReaderFontFamily,
  lineSpacing: ReaderLineSpacing,
  textSize: Int,
  keepScreenOn: Boolean,
  onThemeChange: (ReaderThemePreset) -> Unit,
  onFontChange: (ReaderFontFamily) -> Unit,
  onSpacingChange: (ReaderLineSpacing) -> Unit,
  onSizeChange: (Int) -> Unit,
  onKeepScreenChange: (Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  val previewPalette = readerPaletteFor(readerTheme, MaterialTheme.colorScheme)
  val previewFont = if (fontFamily == ReaderFontFamily.SERIF) FontFamily.Serif else FontFamily.SansSerif
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Reading appearance") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Live mini-reader preview card
        Surface(
          color = previewPalette.background,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        ) {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              "The pages rustled gently in the quiet room.",
              color = previewPalette.bodyText,
              fontSize = textSize.coerceAtMost(20).sp,
              lineHeight = (textSize.coerceAtMost(20) + lineSpacing.extraSp).sp,
              fontFamily = previewFont,
            )
            Box(Modifier.background(previewPalette.activeHighlightFill, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
              Text(
                "This active sentence stands out without harsh glare.",
                color = previewPalette.activeText,
                fontSize = textSize.coerceAtMost(20).sp,
                lineHeight = (textSize.coerceAtMost(20) + lineSpacing.extraSp).sp,
                fontFamily = previewFont,
              )
            }
          }
        }

        Text("Reader theme", style = MaterialTheme.typography.labelLarge)
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          ReaderThemePreset.entries.forEach { preset ->
            val p = readerPaletteFor(preset, MaterialTheme.colorScheme)
            FilterChip(
              selected = readerTheme == preset,
              onClick = { onThemeChange(preset) },
              leadingIcon = {
                Box(
                  Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(p.background)
                    .border(1.dp, p.accent, CircleShape)
                )
              },
              label = { Text(preset.label) },
            )
          }
        }

        Text("Text size · $textSize sp", style = MaterialTheme.typography.labelLarge)
        Slider(
          value = textSize.toFloat(),
          onValueChange = { onSizeChange(it.roundToInt()) },
          valueRange = 14f..26f,
          steps = 5,
        )

        Text("Font style", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ReaderFontFamily.entries.forEach { family ->
            FilterChip(
              selected = fontFamily == family,
              onClick = { onFontChange(family) },
              label = { Text(family.label) },
            )
          }
        }

        Text("Line spacing", style = MaterialTheme.typography.labelLarge)
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          ReaderLineSpacing.entries.forEach { spacing ->
            FilterChip(
              selected = lineSpacing == spacing,
              onClick = { onSpacingChange(spacing) },
              label = { Text(spacing.label) },
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f)) {
            Text("Keep screen on", style = MaterialTheme.typography.bodyMedium)
            Text("Prevents screen timeout while reading.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Switch(checked = keepScreenOn, onCheckedChange = onKeepScreenChange)
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
  )
}

@Composable
private fun SyncCoverageDialog(
  state: ReadAlongState,
  syncMode: SyncMode,
  onSelectMode: (SyncMode) -> Unit,
  onRetryCurrent: () -> Unit,
  onDismiss: () -> Unit,
) {
  val cov = state.syncCoverage
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Synchronization details") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (cov != null) {
          val aheadText = when {
            cov.aheadSyncedMs >= 60_000L -> "${cov.aheadSyncedMs / 60_000}m ${(cov.aheadSyncedMs % 60_000) / 1000}s ahead of playhead"
            cov.aheadSyncedMs > 0L -> "${cov.aheadSyncedMs / 1000}s ahead of playhead"
            else -> "Waiting to sync around playhead"
          }
          Text(aheadText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
          Text(
            "Current track synced: ${(cov.trackTimedRatio * 100).toInt()}% (${cov.syncedWindowsMs.size} cached span${if (cov.syncedWindowsMs.size == 1) "" else "s"})",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            "Book sentences timed: ${cov.matchedSentences} / ${cov.totalBookSentences}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          cov.syncPausedReason?.let { reason ->
            Text("Status: $reason", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          if (cov.trackDurationMs > 0L) {
            val barBg = MaterialTheme.colorScheme.surfaceVariant
            val barFg = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
              drawRect(barBg, size = size)
              val dur = cov.trackDurationMs.toFloat()
              cov.syncedWindowsMs.forEach { range ->
                val x = (range.first / dur).coerceIn(0f, 1f) * size.width
                val w = ((range.last - range.first) / dur).coerceIn(0f, 1f) * size.width
                drawRect(barFg, topLeft = Offset(x, 0f), size = Size(w.coerceAtLeast(2f), size.height))
              }
            }
          }
        } else {
          Text("${state.matchedSentences} sentences timed · ${state.source?.displayName ?: ""}", style = MaterialTheme.typography.bodySmall)
        }

        if (state.source == TextSyncSource.NARRATION) {
          HorizontalDivider()
          Text("Background sync mode", style = MaterialTheme.typography.labelLarge)
          SyncMode.entries.forEach { mode ->
            Row(
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSelectMode(mode) }.padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = syncMode == mode, onClick = { onSelectMode(mode) })
              Spacer(Modifier.width(6.dp))
              Column(Modifier.weight(1f)) {
                Text(mode.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(mode.summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
          TextButton(onClick = onRetryCurrent) { Text("Re-sync current passage") }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookNavigationDialog(
  state: ReadAlongState,
  repository: ReadAlongRepository,
  onJumpToChapter: (EpubChapter) -> Unit,
  onJumpToHit: (ReaderSearchHit) -> Unit,
  onDismiss: () -> Unit,
) {
  var tab by remember { mutableIntStateOf(0) }
  var searchQuery by remember { mutableStateOf("") }
  val hits = remember(searchQuery, state.lines) { repository.searchInBook(searchQuery) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Book navigation") },
    text = {
      Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        PrimaryTabRow(selectedTabIndex = tab) {
          Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chapters (${state.chapters.size})") })
          Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Find text") })
        }
        Spacer(Modifier.height(8.dp))
        if (tab == 0) {
          LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.chapters, key = { it.index }) { chapter ->
              OutlinedCard(onClick = { onJumpToChapter(chapter) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(Modifier.weight(1f)) {
                    Text(chapter.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${chapter.wordCount} words", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
              }
            }
          }
        } else {
          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search phrases or names") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(8.dp))
          if (searchQuery.trim().length < 2) {
            Text("Type at least 2 characters to search the loaded book.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          } else if (hits.isEmpty()) {
            Text("No matching passages found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              items(hits, key = { it.sentenceId }) { hit ->
                OutlinedCard(onClick = { onJumpToHit(hit) }, modifier = Modifier.fillMaxWidth()) {
                  Column(Modifier.padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                      Text(hit.chapterTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                      if (hit.isTimed) {
                        Text("▶ ${formatDuration(hit.startTimeMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                      }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(hit.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}

/** One status area for both acquisition and synchronization, including operation-specific recovery. */
@Composable
fun ReadAlongStatus(
  state: ReadAlongState,
  onRetry: () -> Unit,
  onImport: () -> Unit,
  onDownloadModel: () -> Unit,
  onOpenAccess: () -> Unit = {},
  showAhead: Boolean = true,
  textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  Column(Modifier.fillMaxWidth()) {
    state.progressLabel?.let { label ->
      LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("readalong-progress"))
      Text(label, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
    if (state.progressLabel == null) listOfNotNull(state.lookupMessage, state.message.takeUnless { state.correction.isEditing }).distinct().forEach {
      Text(it, style = MaterialTheme.typography.bodySmall, color = textColor, modifier = Modifier.padding(vertical = 4.dp))
    }
    state.recoveryLabel?.let { label ->
      TextButton(onClick = if (state.recovery == ReadAlongRecovery.PICK_FILE) onImport else onRetry) { Text(label) }
    }
    if (state.accessUrl != null) TextButton(onClick = onOpenAccess) { Text("Open site access") }
    if (state.preparingAhead && showAhead) Text("Preparing the next passage…", style = MaterialTheme.typography.labelSmall, color = textColor)
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
        Text(listOf(edition.provider.label, edition.author, edition.language, edition.format).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        edition.isbn?.let { Text("ISBN $it", style = MaterialTheme.typography.labelSmall) }
      }
    }
  }
}

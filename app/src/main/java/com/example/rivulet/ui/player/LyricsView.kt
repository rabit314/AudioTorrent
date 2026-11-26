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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
  trackTitle: String = "",
  trackTitlesByIndex: Map<Int, String> = emptyMap(),
  onPrepareAudioForScript: () -> Unit = {},
) {
  val state by repository.state.collectAsStateWithLifecycle()
  val textSize by repository.readerPreferences.textSize.collectAsStateWithLifecycle()
  val readerTheme by repository.readerPreferences.readerTheme.collectAsStateWithLifecycle()
  val fontPref by repository.readerPreferences.fontFamily.collectAsStateWithLifecycle()
  val spacingPref by repository.readerPreferences.lineSpacing.collectAsStateWithLifecycle()
  val keepScreenOn by repository.readerPreferences.keepScreenOn.collectAsStateWithLifecycle()
  val syncMode by repository.readerPreferences.syncMode.collectAsStateWithLifecycle()
  val autoGenerateScript by repository.readerPreferences.autoGenerateScript.collectAsStateWithLifecycle()
  val speechModelTier by repository.readerPreferences.speechModelTier.collectAsStateWithLifecycle()
  val savedAnnasKey by repository.readerPreferences.annasSecretKey.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

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
  var interactiveAccessNonce by remember(state.track) { mutableIntStateOf(0) }
  var handledInteractiveAccessNonce by remember(state.track) { mutableIntStateOf(0) }
  var showReplaceScriptConfirm by remember(state.track) { mutableStateOf(false) }
  var exportFullBookMode by remember { mutableStateOf(false) }
  var hasMultiChapterScripts by remember { mutableStateOf(false) }
  var exportFeedback by remember(state.track) { mutableStateOf<String?>(null) }
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
  val exportTxtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
    if (uri != null) {
      scope.launch {
        val text = repository.buildExportText(cleanBookTitle, trackTitlesByIndex, exportFullBookMode)
        val ok = withContext(Dispatchers.IO) {
          runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Cannot write file")
          }.isSuccess
        }
        exportFeedback = if (ok) "Script saved as .txt" else "Could not save the script file."
      }
    }
  }
  val exportSrtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
    if (uri != null) {
      scope.launch {
        val srt = repository.buildExportSrt()
        val ok = withContext(Dispatchers.IO) {
          runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(srt.toByteArray(Charsets.UTF_8)) } ?: error("Cannot write file")
          }.isSuccess
        }
        exportFeedback = if (ok) "Timestamps saved as .srt" else "Could not save the .srt file."
      }
    }
  }
  val importFile = {
    pickedFor = state.track; pickedTracks = selectedTracks
    picker.launch(arrayOf("application/epub+zip", "application/pdf", "text/*", "application/x-subrip", "application/octet-stream"))
  }
  fun search() {
    if (textProvider in setOf(TextProvider.WELIB, TextProvider.ANNAS_ARCHIVE, TextProvider.ZLIBRARY)) {
      interactiveAccessNonce++
    }
    repository.searchBooks(
      query,
      title = if (query == suggestedQuery) cleanBookTitle else query,
      author = if (query == suggestedQuery) searchAuthor else "",
      provider = textProvider,
    )
  }
  val findText = { showSearch = true; search() }
  val startScriptGeneration = {
    showSearch = false
    showAccess = false
    exportFeedback = null
    onPrepareAudioForScript()
    repository.generateScriptFromAudio(
      title = cleanBookTitle,
      trackTitle = trackTitle,
      expectedTrack = bookIdentityTrack ?: state.track,
    )
  }
  val launchTxtExport: (Boolean) -> Unit = { fullBook ->
    exportFullBookMode = fullBook
    val name = ScriptExporter.suggestedFileName(
      cleanBookTitle.ifBlank { state.title.orEmpty() },
      if (fullBook) "Full Script" else trackTitle.takeIf { it.isNotBlank() },
      "txt",
    )
    exportTxtLauncher.launch(name)
  }
  val launchSrtExport = {
    val name = ScriptExporter.suggestedFileName(
      cleanBookTitle.ifBlank { state.title.orEmpty() },
      trackTitle.takeIf { it.isNotBlank() },
      "srt",
    )
    exportSrtLauncher.launch(name)
  }
  LaunchedEffect(state.track, state.isScriptSource, state.lines.size) {
    hasMultiChapterScripts = if (state.isScriptSource) repository.hasMultiChapterScripts() else false
  }
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
  LaunchedEffect(isVisible, bookIdentityReady, state.track, state.bookId, state.source, state.phase) {
    if (isVisible && bookIdentityReady && state.bookId != null &&
      (state.source == TextSyncSource.NARRATION || state.isScriptSource) &&
      state.phase != ReadAlongPhase.LOADING
    ) {
      onPrepareAudioForScript()
    }
  }
  LaunchedEffect(state.choosingEdition) { if (state.choosingEdition) showSearch = true }
  LaunchedEffect(state.accessUrl, interactiveAccessNonce) {
    if (state.accessUrl != null && isVisible && interactiveAccessNonce > handledInteractiveAccessNonce) {
      handledInteractiveAccessNonce = interactiveAccessNonce
      showAccess = true
    }
  }
  LaunchedEffect(state.bookId, state.trackIndices) { if (state.trackIndices.isNotEmpty()) selectedTracks = state.trackIndices }
  LaunchedEffect(dragged) { if (dragged) following = false }
  LaunchedEffect(state.correction.phase, state.correction.event) {
    if (state.correction.phase == SyncCorrectionPhase.APPLIED && handledCorrectionEvent != state.correction.event) {
      following = true
      handledCorrectionEvent = state.correction.event
    }
  }

  Column(modifier.fillMaxSize().background(palette.background).padding(horizontal = 12.dp, vertical = 4.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(top = 2.dp, bottom = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (state.correction.isEditing) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = palette.activeHighlightFill,
          ) {
            Text(
              "Correct sync",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              color = palette.activeText,
              maxLines = 1,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
        } else {
          syncBadgeLabel(state)?.let { label ->
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = palette.chipContainer.copy(alpha = 0.85f),
              modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { showSyncSheet = true }
                .testTag("sync-status-pill"),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
              ) {
                if (state.syncCoverage?.isSyncingNow == true || state.preparingAhead || state.phase == ReadAlongPhase.PREPARING) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(10.dp).semantics { contentDescription = "Preparing the next passage" },
                    strokeWidth = 1.5.dp,
                    color = palette.accent,
                  )
                }
                Text(
                  text = label,
                  style = MaterialTheme.typography.labelSmall,
                  color = palette.mutedText,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
      }
      Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (state.bookId != null) {
          IconButton(onClick = { showBookNav = true }, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Default.Search, contentDescription = "Search or jump chapters", tint = palette.mutedText, modifier = Modifier.size(18.dp))
          }
        }
        IconButton(onClick = { showThemeSheet = true }, modifier = Modifier.size(34.dp)) {
          Icon(Icons.Default.Palette, contentDescription = "Reading theme and style", tint = palette.mutedText, modifier = Modifier.size(18.dp))
        }
        Box {
          IconButton(
            onClick = { showOptions = true },
            modifier = Modifier
              .size(34.dp)
              .testTag("text-settings-button")
              .semantics { contentDescription = "Text and sync settings" },
          ) {
            TextSettingsGlyph(tint = palette.accent)
          }
          DropdownMenu(showOptions, onDismissRequest = { showOptions = false }) {
            DropdownMenuItem(text = { Text("Text size") }, onClick = { showOptions = false; showTextSize = true })
            if (state.bookId != null) {
              DropdownMenuItem(
                text = { Text("Correct sync") },
                onClick = { showOptions = false; beginCorrection(null) },
                modifier = Modifier.testTag("menu-correct-sync"),
              )
            }
            if (state.isScriptSource) {
              DropdownMenuItem(
                text = { Text(if (state.transcribingFullTrack) "Stop full transcription" else "Transcribe entire track") },
                onClick = { showOptions = false; repository.setTranscribeFullTrack(!state.transcribingFullTrack) },
              )
              DropdownMenuItem(
                text = { Text("Export script (.txt)") },
                enabled = state.lines.any { it.text.isNotBlank() },
                onClick = { showOptions = false; launchTxtExport(false) },
              )
              if (hasMultiChapterScripts) {
                DropdownMenuItem(
                  text = { Text("Export book script (.txt)") },
                  onClick = { showOptions = false; launchTxtExport(true) },
                )
              }
              DropdownMenuItem(
                text = { Text("Export timestamps (.srt)") },
                enabled = state.lines.any { it.isTimed },
                onClick = { showOptions = false; launchSrtExport() },
              )
            }
            DropdownMenuItem(
              text = { Text(if (state.bookId == null) "Find book text" else if (state.isScriptSource) "Replace with ebook" else "Replace edition") },
              onClick = { showOptions = false; findText() },
            )
            DropdownMenuItem(text = { Text("Import EPUB / PDF / TXT / FB2") }, onClick = { showOptions = false; importFile() })
            if (!state.isScriptSource) {
              DropdownMenuItem(
                text = { Text("Generate script from audio") },
                onClick = {
                  showOptions = false
                  if (state.bookId == null) startScriptGeneration() else showReplaceScriptConfirm = true
                },
                modifier = Modifier.testTag("menu-generate-script"),
              )
              DropdownMenuItem(text = { Text("Select audio tracks") }, onClick = { showOptions = false; showTracks = true })
            } else {
              DropdownMenuItem(
                text = { Text("Clear generated script") },
                onClick = { showOptions = false; repository.clearGeneratedScript() },
              )
            }
          }
        }
      }
    }
    HorizontalDivider(thickness = 0.5.dp, color = palette.mutedText.copy(alpha = 0.16f))
    if (!showSearch) {
      val isLookupRecovery = state.recovery == ReadAlongRecovery.RETRY &&
        state.recoveryLabel in setOf("Retry search", "Retry ebook download")
      val readerStatusState = if (state.bookId != null) {
        state.copy(
          lookupMessage = null,
          accessUrl = null,
          recoveryLabel = if (isLookupRecovery) null else state.recoveryLabel,
          recovery = if (isLookupRecovery) null else state.recovery,
        )
      } else {
        state
      }
      ReadAlongStatus(
        state = readerStatusState,
        onRetry = repository::retry,
        onImport = importFile,
        onDownloadModel = repository::downloadModel,
        onOpenAccess = { showAccess = true },
        onDismissLookup = repository::dismissLookupStatus,
        showAhead = false,
        compactLookup = state.bookId == null,
        modelSizeMb = speechModelTier.approximateMb,
        onDownloadModelTier = repository::downloadModelTier,
        textColor = palette.mutedText,
      )
    }
    exportFeedback?.let { feedback ->
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(feedback, style = MaterialTheme.typography.labelSmall, color = palette.accent, modifier = Modifier.weight(1f))
        TextButton(onClick = { exportFeedback = null }) { Text("Dismiss") }
      }
    }
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
        if (state.bookId != null) TextButton(onClick = { showAdjustment = true }) { Text("Fine-tune & tools") }
        TextButton(onClick = { repository.cancelCorrection(); following = followingBeforeCorrection }) { Text("Cancel") }
      }
    } else if (state.canUndoCorrection) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("Text aligned", style = MaterialTheme.typography.labelSmall, color = palette.mutedText, modifier = Modifier.weight(1f))
      TextButton(onClick = repository::undoSyncCorrection) { Text("Undo correction") }
    }
    if (state.bookId == null && state.phase != ReadAlongPhase.LOADING) {
      if (!state.searching) {
        Text("Find your edition, import a book, or generate a script from English audio.", style = MaterialTheme.typography.bodyMedium, color = palette.bodyText)
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.searching) {
          FilledTonalButton(onClick = findText) { Text("Find book text") }
          TextButton(onClick = importFile) { Text("Import file") }
        }
        FilledTonalButton(
          onClick = startScriptGeneration,
          modifier = Modifier.testTag("generate-script-button"),
        ) { Text("Generate script") }
      }
    }
    if (!following && !state.correction.isEditing) {
      FilledTonalButton(onClick = { following = true }, modifier = Modifier.fillMaxWidth()) { Text("Follow narration") }
    }
    if (state.isScriptSource && state.lines.isEmpty() && state.phase != ReadAlongPhase.NEEDS_MODEL && state.modelProgress == null) {
      Box(
        modifier = Modifier.fillMaxWidth().weight(1f).testTag("script-listening-placeholder"),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(24.dp)) {
          CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp, color = palette.accent)
          Text(
            "Listening to audio and writing script…",
            style = MaterialTheme.typography.titleSmall,
            color = palette.bodyText,
          )
          Text(
            "First lines appear in a few seconds · Audio keeps playing",
            style = MaterialTheme.typography.bodySmall,
            color = palette.mutedText,
          )
          if (!state.transcribingFullTrack) {
            TextButton(onClick = { repository.setTranscribeFullTrack(true) }) {
              Text("Transcribe entire track")
            }
          }
        }
      }
    } else {
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
  }

  if (showSearch && isVisible) AlertDialog(
    onDismissRequest = { showSearch = false; if (state.bookId != null) repository.dismissLookupStatus() }, title = { Text("Find book text") },
    text = { Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
      OutlinedTextField(query, { query = it }, label = { Text("Book title or author") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextProvider.entries.forEach { provider -> FilterChip(textProvider == provider, { textProvider = provider; search() }, label = { Text(provider.label) }) }
      }
      if (textProvider == TextProvider.ANNAS_ARCHIVE) {
        var keyDraft by remember(savedAnnasKey) { mutableStateOf(savedAnnasKey) }
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("Optional Anna's member key") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = { repository.readerPreferences.setAnnasSecretKey(keyDraft) }) {
            Text(if (keyDraft.isBlank()) "Clear" else "Save")
          }
        }
      }
      TextButton(onClick = { search() }) { Text("Search") }
      ReadAlongStatus(
        state,
        repository::retry,
        importFile,
        repository::downloadModel,
        { showAccess = true },
        modelSizeMb = speechModelTier.approximateMb,
        onDownloadModelTier = repository::downloadModelTier,
      )
      if (state.editions.isEmpty() || state.accessUrl != null) {
        TextButton(onClick = startScriptGeneration, modifier = Modifier.testTag("dialog-generate-script")) {
          Text("No ebook found? Generate script from audio")
        }
      }
      LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        items(state.editions, key = { "${it.provider.name}:${it.url}" }) { edition ->
          BookEditionRow(
            edition = edition,
            onSelect = {
              if (edition.provider in setOf(TextProvider.WELIB, TextProvider.ANNAS_ARCHIVE, TextProvider.ZLIBRARY)) {
                interactiveAccessNonce++
              }
              repository.downloadEdition(edition, selectedTracks)
            },
            enabled = !state.searching,
          )
        }
      }
    } }, confirmButton = { TextButton(onClick = { showSearch = false; if (state.bookId != null) repository.dismissLookupStatus() }) { Text("Done") } },
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
    onDismissRequest = { showAdjustment = false }, title = { Text("Correct sync & timing tools") },
    text = { Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("Offset correction: ${state.offsetMs / 1000.0}s", style = MaterialTheme.typography.titleSmall)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { repository.adjustOffset(-500) }) { Text("−0.5s") }
        TextButton(onClick = repository::resetSyncCorrection) { Text("Reset") }
        TextButton(onClick = { repository.adjustOffset(500) }) { Text("+0.5s") }
      }
      if (state.source == TextSyncSource.NARRATION || state.isScriptSource) {
        TextButton(onClick = { repository.retryCurrentPassage(); showAdjustment = false }) {
          Text(if (state.isScriptSource) "Re-transcribe passage" else "Retry current passage")
        }
      }
      if (state.canChooseChapter) {
        TextButton(onClick = { showAdjustment = false; showChapters = true }) { Text("Choose chapter hint") }
      }
      TextButton(onClick = { showAdjustment = false; showSyncSheet = true }) { Text("Sync coverage & mode") }
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
      autoGenerateScript = autoGenerateScript,
      speechModelTier = speechModelTier,
      activeModelTag = repository.activeSpeechModelTag(),
      isFastDownloaded = repository.isSpeechModelDownloaded(SpeechModelTier.FAST),
      isAccurateDownloaded = repository.isSpeechModelDownloaded(SpeechModelTier.ACCURATE),
      isUsingLegacyModel = repository.isUsingLegacySpeechModel(),
      onSelectMode = repository::setSyncMode,
      onSelectModelTier = repository::setSpeechModelTier,
      onDownloadModelTier = repository::downloadModelTier,
      onToggleFullTrack = repository::setTranscribeFullTrack,
      onToggleAutoScript = repository.readerPreferences::setAutoGenerateScript,
      onRetryCurrent = { repository.retryCurrentPassage(); showSyncSheet = false },
      onOpenCorrectSync = { showSyncSheet = false; beginCorrection(null) },
      onExportTxt = { showSyncSheet = false; launchTxtExport(false) },
      onClearScript = { showSyncSheet = false; repository.clearGeneratedScript() },
      onDismiss = { showSyncSheet = false },
    )
  }
  if (showReplaceScriptConfirm && isVisible) {
    AlertDialog(
      onDismissRequest = { showReplaceScriptConfirm = false },
      title = { Text("Generate audio script?") },
      text = { Text("This replaces the current companion text on this track with a transcript generated from English audio.") },
      confirmButton = {
        TextButton(onClick = { showReplaceScriptConfirm = false; startScriptGeneration() }) { Text("Generate") }
      },
      dismissButton = {
        TextButton(onClick = { showReplaceScriptConfirm = false }) { Text("Cancel") }
      },
    )
  }
  if (showBookNav && isVisible) {
    BookNavigationDialog(
      state = state,
      repository = repository,
      onJumpToSentence = { id ->
        following = false
        scrollTargetSentenceId = id
        scrollTargetNonce++
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
    SiteAccessDialog(
      url = url,
      onDismiss = { showAccess = false },
      onReady = { html, loadedUrl ->
        showAccess = false
        repository.resumeSiteAccess(html, loadedUrl, state.track)
      },
      onImportLocalFile = importFile,
    )
  }
}

private fun syncBadgeLabel(state: ReadAlongState): String? {
  if (state.bookId == null) return null
  return when (state.source) {
    TextSyncSource.LRC, TextSyncSource.SRT -> "✓ Transcript synced"
    TextSyncSource.EPUB_PACED -> "~ Estimated pace"
    TextSyncSource.WHISPER_SCRIPT -> {
      val cov = state.syncCoverage
      when {
        cov != null && cov.trackTimedRatio >= 0.99f -> "✓ Script ready"
        cov != null && cov.aheadSyncedMs >= 60_000L -> "Script · +${cov.aheadSyncedMs / 60_000}m"
        cov != null && cov.aheadSyncedMs > 0L -> "Script · +${cov.aheadSyncedMs / 1000}s"
        cov?.isSyncingNow == true || state.preparingAhead || state.transcribingFullTrack -> "Writing script…"
        cov != null && cov.trackDurationMs > 0L && cov.trackTimedRatio > 0f -> "Script · ${(cov.trackTimedRatio * 100).toInt()}%"
        cov != null && cov.matchedSentences > 0 -> "Script · ${cov.matchedSentences} lines"
        else -> "Audio script"
      }
    }
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

        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Text(
          "Applies across the whole app and reading view.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    .border(1.2.dp, p.accent, CircleShape)
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
  autoGenerateScript: Boolean,
  speechModelTier: SpeechModelTier,
  activeModelTag: String,
  isFastDownloaded: Boolean,
  isAccurateDownloaded: Boolean,
  isUsingLegacyModel: Boolean,
  onSelectMode: (SyncMode) -> Unit,
  onSelectModelTier: (SpeechModelTier) -> Unit,
  onDownloadModelTier: (SpeechModelTier) -> Unit,
  onToggleFullTrack: (Boolean) -> Unit,
  onToggleAutoScript: (Boolean) -> Unit,
  onRetryCurrent: () -> Unit,
  onOpenCorrectSync: () -> Unit,
  onExportTxt: () -> Unit,
  onClearScript: () -> Unit,
  onDismiss: () -> Unit,
) {
  val cov = state.syncCoverage
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (state.isScriptSource) "Generated script details" else "Synchronization details") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (state.isScriptSource) {
          Text(
            "Generated automatically from English speech (on-device Whisper). Proper nouns and rare terms may vary.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (cov != null) {
          val aheadText = when {
            cov.aheadSyncedMs >= 60_000L -> "${cov.aheadSyncedMs / 60_000}m ${(cov.aheadSyncedMs % 60_000) / 1000}s ahead of playhead"
            cov.aheadSyncedMs > 0L -> "${cov.aheadSyncedMs / 1000}s ahead of playhead"
            else -> if (state.isScriptSource) "Waiting to transcribe around playhead" else "Waiting to sync around playhead"
          }
          Text(aheadText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
          cov.syncSpeedRatio?.let { ratio ->
            val syncLabel = String.format(java.util.Locale.US, "%.1f×", ratio)
            val playLabel = String.format(java.util.Locale.US, "%.1f×", cov.playbackSpeed)
            val speedSummary = if (cov.playbackSpeed > 1.05f || cov.playbackSpeed < 0.95f) {
              "Sync speed: $syncLabel real-time (playback $playLabel)"
            } else {
              "Sync speed: $syncLabel real-time"
            }
            Text(
              speedSummary,
              style = MaterialTheme.typography.bodySmall,
              color = if (ratio >= cov.playbackSpeed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
          }
          Text(
            if (state.isScriptSource) "Track transcribed: ${(cov.trackTimedRatio * 100).toInt()}% (${cov.syncedWindowsMs.size} cached span${if (cov.syncedWindowsMs.size == 1) "" else "s"})"
            else "Current track synced: ${(cov.trackTimedRatio * 100).toInt()}% (${cov.syncedWindowsMs.size} cached span${if (cov.syncedWindowsMs.size == 1) "" else "s"})",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            if (state.isScriptSource) "Sentences generated: ${cov.matchedSentences}"
            else "Book sentences timed: ${cov.matchedSentences} / ${cov.totalBookSentences}",
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

        if (state.isScriptSource) {
          HorizontalDivider()
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(Modifier.weight(1f)) {
              Text("Transcribe entire track", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
              Text(
                if ((cov?.trackDurationMs ?: 0L) >= 3_600_000L) "Runs across the full track while paused or on Cover with thermal cooldowns."
                else "Runs across the entire track while paused or on Cover; synced with Full track mode.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(checked = state.transcribingFullTrack || syncMode == SyncMode.FULL_TRACK, onCheckedChange = onToggleFullTrack)
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(Modifier.weight(1f)) {
              Text("Auto-generate when no book found", style = MaterialTheme.typography.bodyMedium)
              Text("Starts script automatically if search finds no match.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = autoGenerateScript, onCheckedChange = onToggleAutoScript)
          }
        }

        if (state.source == TextSyncSource.NARRATION || state.isScriptSource) {
          HorizontalDivider()
          Text("Speech model", style = MaterialTheme.typography.labelLarge)
          Text(
            if (isUsingLegacyModel) "Active engine: $activeModelTag (legacy model — get Fast 44 MB for >1× speed or Accurate v2 82 MB)"
            else "Active engine: $activeModelTag",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          state.modelProgress?.let { progress ->
            LinearProgressIndicator(progress = { (progress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
          }
          SpeechModelTier.entries.forEach { tier ->
            val downloaded = if (tier == SpeechModelTier.FAST) isFastDownloaded else isAccurateDownloaded
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onSelectModelTier(tier) }
                .padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = speechModelTier == tier, onClick = { onSelectModelTier(tier) })
              Spacer(Modifier.width(6.dp))
              Column(Modifier.weight(1f)) {
                Text(tier.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                  if (downloaded) "${tier.modelTag} · Installed · ${tier.summary}"
                  else "${tier.modelTag} · ${tier.approximateMb} MB download · ${tier.summary}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (!downloaded && state.modelProgress == null) {
                TextButton(onClick = { onDownloadModelTier(tier) }) { Text("Get") }
              }
            }
          }
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
          FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onRetryCurrent) { Text(if (state.isScriptSource) "Re-transcribe passage" else "Re-sync passage") }
            TextButton(onClick = onOpenCorrectSync) { Text("Correct sync") }
            if (state.isScriptSource && state.lines.any { it.text.isNotBlank() }) {
              TextButton(onClick = onExportTxt) { Text("Export .txt") }
            }
            if (state.isScriptSource) {
              TextButton(onClick = onClearScript) { Text("Clear script") }
            }
          }
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
  onJumpToSentence: (Long) -> Unit,
  onJumpToHit: (ReaderSearchHit) -> Unit,
  onDismiss: () -> Unit,
) {
  var tab by remember { mutableIntStateOf(0) }
  var searchQuery by remember { mutableStateOf("") }
  val hits = remember(searchQuery, state.lines) { repository.searchInBook(searchQuery) }
  val sections = remember(state.lines, state.isScriptSource) {
    if (state.isScriptSource) TranscriptAssembler.buildTimeSections(state.lines) else emptyList()
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (state.isScriptSource) "Script navigation" else "Book navigation") },
    text = {
      Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        PrimaryTabRow(selectedTabIndex = tab) {
          Tab(
            selected = tab == 0,
            onClick = { tab = 0 },
            text = { Text(if (state.isScriptSource) "Sections (${sections.size})" else "Chapters (${state.chapters.size})") },
          )
          Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Find text") })
        }
        Spacer(Modifier.height(8.dp))
        if (tab == 0) {
          if (state.isScriptSource) {
            if (sections.isEmpty()) {
              Text("Script sections appear as audio is transcribed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sections, key = { it.index }) { section ->
                  OutlinedCard(onClick = { onJumpToSentence(section.firstSentenceId) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(section.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text("${section.wordCount} words", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                      }
                      if (section.previewText.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(section.previewText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                      }
                    }
                  }
                }
              }
            }
          } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              items(state.chapters, key = { it.index }) { chapter ->
                OutlinedCard(
                  onClick = { repository.firstSentenceIdForChapter(chapter.index)?.let(onJumpToSentence) },
                  modifier = Modifier.fillMaxWidth(),
                ) {
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadAlongStatus(
  state: ReadAlongState,
  onRetry: () -> Unit,
  onImport: () -> Unit,
  onDownloadModel: () -> Unit,
  onOpenAccess: () -> Unit = {},
  onDismissLookup: (() -> Unit)? = null,
  showAhead: Boolean = true,
  compactLookup: Boolean = false,
  modelSizeMb: Int = SpeechModelTier.FAST.approximateMb,
  onDownloadModelTier: ((SpeechModelTier) -> Unit)? = null,
  textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  val hasLookupNotice = state.lookupMessage != null || state.accessUrl != null ||
    (state.recovery == ReadAlongRecovery.RETRY && state.recoveryLabel in setOf("Retry search", "Retry ebook download"))
  val showCompactCard = compactLookup && hasLookupNotice && state.progressLabel == null
  Column(Modifier.fillMaxWidth()) {
    state.progressLabel?.let { label ->
      LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("readalong-progress"))
      Text(label, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
    if (showCompactCard) {
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("compact-lookup-status"),
      ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
          state.lookupMessage?.let { msg ->
            Text(msg, style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
          state.message.takeUnless { state.correction.isEditing || it == state.lookupMessage }?.let { msg ->
            Text(msg, style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (state.accessUrl != null) {
              TextButton(onClick = onOpenAccess, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.defaultMinSize(minHeight = 32.dp)) {
                Text("Open site access", style = MaterialTheme.typography.labelMedium)
              }
            }
            state.recoveryLabel?.let { label ->
              TextButton(
                onClick = if (state.recovery == ReadAlongRecovery.PICK_FILE) onImport else onRetry,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.defaultMinSize(minHeight = 32.dp),
              ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
            if (onDismissLookup != null) {
              TextButton(onClick = onDismissLookup, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.defaultMinSize(minHeight = 32.dp)) {
                Text("Dismiss", style = MaterialTheme.typography.labelMedium)
              }
            }
          }
        }
      }
    } else {
      if (state.progressLabel == null) {
        listOfNotNull(state.lookupMessage, state.message.takeUnless { state.correction.isEditing }).distinct().forEach {
          Text(it, style = MaterialTheme.typography.bodySmall, color = textColor, modifier = Modifier.padding(vertical = 4.dp))
        }
      }
      state.recoveryLabel?.let { label ->
        TextButton(onClick = if (state.recovery == ReadAlongRecovery.PICK_FILE) onImport else onRetry) { Text(label) }
      }
      if (state.accessUrl != null) TextButton(onClick = onOpenAccess) { Text("Open site access") }
    }
    if (state.preparingAhead && showAhead) Text("Preparing the next passage…", style = MaterialTheme.typography.labelSmall, color = textColor)
    if (state.phase == ReadAlongPhase.NEEDS_MODEL && state.modelProgress == null && !state.correction.isEditing) {
      if (onDownloadModelTier != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { onDownloadModelTier(SpeechModelTier.FAST) }) {
            Text("Download Fast · ${SpeechModelTier.FAST.approximateMb} MB")
          }
          TextButton(onClick = { onDownloadModelTier(SpeechModelTier.ACCURATE) }) {
            Text("Accurate · ${SpeechModelTier.ACCURATE.approximateMb} MB")
          }
        }
      } else {
        TextButton(onClick = onDownloadModel) { Text("Download speech model · $modelSizeMb MB") }
      }
    }
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

/** Crisp custom typographic T + mini settings gear vector matching compact header icon weight. */
@Composable
private fun TextSettingsGlyph(tint: Color, modifier: Modifier = Modifier) {
  Canvas(modifier.size(20.dp)) {
    val stroke = 1.65.dp.toPx()
    val fine = 1.25.dp.toPx()
    val w = size.width
    val h = size.height

    // Serif-styled typographic 'T' in upper-left
    val tLeft = w * 0.14f
    val tRight = w * 0.58f
    val tTop = h * 0.18f
    val tCenterX = (tLeft + tRight) * 0.5f
    val tBottom = h * 0.72f
    drawLine(tint, Offset(tLeft, tTop), Offset(tRight, tTop), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(tLeft, tTop), Offset(tLeft, tTop + h * 0.08f), strokeWidth = fine, cap = StrokeCap.Round)
    drawLine(tint, Offset(tRight, tTop), Offset(tRight, tTop + h * 0.08f), strokeWidth = fine, cap = StrokeCap.Round)
    drawLine(tint, Offset(tCenterX, tTop), Offset(tCenterX, tBottom), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(tCenterX - w * 0.10f, tBottom), Offset(tCenterX + w * 0.10f, tBottom), strokeWidth = fine, cap = StrokeCap.Round)

    // Minimalist 6-tooth gear badge in lower-right
    val cx = w * 0.75f
    val cy = h * 0.73f
    val ringR = w * 0.145f
    val toothOut = w * 0.215f
    drawCircle(tint, radius = ringR, center = Offset(cx, cy), style = Stroke(width = fine))
    for (i in 0 until 6) {
      val angle = i * PI / 3.0
      val cosA = cos(angle).toFloat()
      val sinA = sin(angle).toFloat()
      drawLine(
        color = tint,
        start = Offset(cx + cosA * ringR, cy + sinA * ringR),
        end = Offset(cx + cosA * toothOut, cy + sinA * toothOut),
        strokeWidth = fine,
        cap = StrokeCap.Round,
      )
    }
  }
}

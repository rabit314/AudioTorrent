package com.example.rivulet.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.rivulet.AppContainer
import com.example.rivulet.data.lyrics.BookTitleCleaner
import com.example.rivulet.ui.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
  container: AppContainer,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by container.playback.state.collectAsStateWithLifecycle()
  val queueTracks by container.playback.currentQueue.collectAsStateWithLifecycle()
  val currentQueueIndex by container.playback.currentQueueIndex.collectAsStateWithLifecycle()
  val bookmarks by container.playback.bookmarks.collectAsStateWithLifecycle()
  val activeWindowLines by container.playback.activeWindowLines.collectAsStateWithLifecycle()
  val activeLineIndex by container.playback.activeLineIndex.collectAsStateWithLifecycle()
  val syncOffsetMs by container.playback.currentSyncOffsetMs.collectAsStateWithLifecycle()
  val isLyricsAvailable by container.playback.isLyricsAvailable.collectAsStateWithLifecycle()
  val lyricsSource by container.playback.lyricsSource.collectAsStateWithLifecycle()
  val companionBookTitle by container.playback.companionBookTitle.collectAsStateWithLifecycle()
  val companionBookIsbn by container.playback.companionBookIsbn.collectAsStateWithLifecycle()
  val activeChapterTitle by container.playback.activeChapterTitle.collectAsStateWithLifecycle()
  val alignmentConfidence by container.playback.alignmentConfidence.collectAsStateWithLifecycle()
  val alignmentStrategy by container.playback.alignmentStrategy.collectAsStateWithLifecycle()
  val allCompanionChapters by container.playback.allCompanionChapters.collectAsStateWithLifecycle()

  var dragValue by remember { mutableStateOf<Float?>(null) }
  var showSpeedDialog by remember { mutableStateOf(false) }
  var showSleepDialog by remember { mutableStateOf(false) }
  var showLyricsSheet by remember { mutableStateOf(false) }
  var showChaptersSheet by remember { mutableStateOf(false) }
  var showAddBookmarkDialog by remember { mutableStateOf(false) }
  var newBookmarkNote by remember { mutableStateOf("") }
  var selectedChaptersTab by remember { mutableStateOf(0) }
  var jumpIntervalMode by remember { mutableStateOf(0) }
  val coroutineScope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

  Scaffold(
    modifier = modifier.safeDrawingPadding(),
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Now playing", style = MaterialTheme.typography.titleMedium)
            if (queueTracks.isNotEmpty() && currentQueueIndex >= 0) {
              Text(
                "Chapter ${currentQueueIndex + 1} of ${queueTracks.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(onClick = { showAddBookmarkDialog = true }) {
            Icon(
              Icons.Default.BookmarkBorder,
              contentDescription = "Add Bookmark",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = { showChaptersSheet = true }) {
            Icon(
              Icons.Default.FormatListNumbered,
              contentDescription = "Chapters & Queue",
              tint = if (queueTracks.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = { showLyricsSheet = true }) {
            Icon(
              Icons.Default.FormatQuote,
              contentDescription = "Read Along (Lyrics)",
              tint = if (isLyricsAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Shift upper block down slightly for balanced screen proportions and thumb reach
      Spacer(Modifier.height(38.dp))

      Box(
        modifier =
          Modifier.size(240.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        if (!state.coverUrl.isNullOrBlank()) {
          AsyncImage(
            model = state.coverUrl,
            contentDescription = state.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      }

      Spacer(Modifier.height(20.dp))

      Text(
        state.title.ifBlank { "Nothing playing" },
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(6.dp))
      Text(
        state.subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )

      Spacer(Modifier.height(20.dp))

      val duration = state.durationMs.coerceAtLeast(1L)
      val sliderValue =
        (dragValue ?: (state.positionMs.toFloat() / duration.toFloat())).coerceIn(0f, 1f)

      Slider(
        value = sliderValue,
        onValueChange = { dragValue = it },
        onValueChangeFinished = {
          dragValue?.let { container.playback.seekTo((it * duration).toLong()) }
          dragValue = null
        },
        enabled = state.hasMedia && state.durationMs > 0L,
        modifier = Modifier.fillMaxWidth(),
      )

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelMedium)
        if (state.bufferedMs > state.positionMs) {
          val bufferSec = (state.bufferedMs - state.positionMs) / 1000L
          Text(
            "+${bufferSec}s buffer",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelMedium)
      }

      Spacer(Modifier.height(16.dp))

      // Playback Controls Row: Prev Track | -10s | Play/Pause | +30s | Next Track
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
      ) {
        TextButton(
          onClick = { container.playback.playPrevious() },
          enabled = state.hasPrevious || state.positionMs > 3000L,
        ) {
          Text("⏮")
        }
        Spacer(Modifier.width(8.dp))

        val backMs = if (jumpIntervalMode == 1) 15_000L else 10_000L
        val backLabel = if (jumpIntervalMode == 1) "−15s" else "−10s"
        TextButton(onClick = { container.playback.seekBy(-backMs) }) { Text(backLabel) }
        Spacer(Modifier.width(12.dp))

        FilledIconButton(
          onClick = { container.playback.togglePlayPause() },
          modifier = Modifier.size(68.dp),
        ) {
          if (state.isPlaying) {
            PauseGlyph(
              modifier = Modifier.size(28.dp),
              color = MaterialTheme.colorScheme.onPrimary,
            )
          } else {
            Icon(
              Icons.Default.PlayArrow,
              contentDescription = "Play",
              modifier = Modifier.size(38.dp),
            )
          }
        }

        Spacer(Modifier.width(12.dp))
        TextButton(onClick = { container.playback.seekBy(30_000L) }) { Text("+30s") }
        Spacer(Modifier.width(8.dp))

        TextButton(
          onClick = { container.playback.playNext() },
          enabled = state.hasNext,
        ) {
          Text("⏭")
        }
      }

      Spacer(Modifier.height(20.dp))

      // Speed, Sleep Timer, Voice Boost & Silence Skip Row
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(onClick = { showSpeedDialog = true }) {
          val speedLabel = if (state.speed == 1.0f) "1.0×" else "${state.speed}×"
          Text(speedLabel)
        }

        OutlinedButton(onClick = { showSleepDialog = true }) {
          val sleepLabel = when {
            state.isSleepTimerEndOfTrack -> "Track end"
            state.sleepTimerRemainingSec != null -> {
              val sec = state.sleepTimerRemainingSec ?: 0
              val m = sec / 60
              val s = sec % 60
              String.format(java.util.Locale.US, "%d:%02d", m, s)
            }
            else -> "Sleep"
          }
          Text(sleepLabel)
        }

        FilterChip(
          selected = state.isVoiceBoost,
          onClick = { container.playback.toggleVoiceBoost() },
          label = { Text(if (state.isVoiceBoost) "Boost On" else "Boost") },
        )

        FilterChip(
          selected = state.isSkipSilence,
          onClick = { container.playback.toggleSkipSilence() },
          label = { Text(if (state.isSkipSilence) "Trim On" else "Trim Silence") },
        )

        FilterChip(
          selected = jumpIntervalMode == 1,
          onClick = { jumpIntervalMode = if (jumpIntervalMode == 0) 1 else 0 },
          label = { Text(if (jumpIntervalMode == 1) "−15s/+30s" else "−10s/+30s") },
        )
      }

      Spacer(Modifier.height(20.dp))

      // Spotify-style Expandable Lyrics Card
      Card(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { showLyricsSheet = true },
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
          ),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            Icons.Default.FormatQuote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
          )
          Spacer(Modifier.width(10.dp))
          Column(modifier = Modifier.weight(1f)) {
            val cleanBook = BookTitleCleaner.resolveCleanBookTitle(state.title, state.subtitle)
            Text(
              if (!companionBookTitle.isNullOrBlank()) "Read Along · $companionBookTitle" else "Read Along · $cleanBook",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            val activeLine = activeWindowLines.getOrNull(activeLineIndex)
            Text(
              activeLine?.text ?: if (isLyricsAvailable) "Tap to open synced text" else "Tap to attach companion book (EPUB / TXT)",
              style = MaterialTheme.typography.bodySmall,
              color = if (activeLine != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Spacer(Modifier.width(8.dp))
          Icon(
            Icons.Default.KeyboardArrowUp,
            contentDescription = "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(Modifier.height(16.dp))

      if (state.isBuffering) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(10.dp))
          Text(
            "Buffering from peers…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      state.error?.let {
        Spacer(Modifier.height(12.dp))
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          textAlign = TextAlign.Center,
        )
      }

      Spacer(Modifier.height(32.dp))
    }
  }

  // Fine-grained playback speed selection dialog
  if (showSpeedDialog) {
    var customSpeed by remember { mutableStateOf(state.speed) }
    AlertDialog(
      onDismissRequest = { showSpeedDialog = false },
      title = { Text("Playback speed") },
      text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            String.format(java.util.Locale.US, "%.2f×", customSpeed),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.height(8.dp))
          Slider(
            value = customSpeed,
            onValueChange = {
              val rounded = (Math.round(it * 20.0) / 20.0).toFloat()
              customSpeed = rounded
              container.playback.setSpeed(rounded)
            },
            valueRange = 0.5f..3.0f,
            steps = 49,
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(4.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedButton(
              onClick = {
                val next = (customSpeed - 0.1f).coerceIn(0.5f, 3.0f)
                customSpeed = (Math.round(next * 20.0) / 20.0).toFloat()
                container.playback.setSpeed(customSpeed)
              }
            ) {
              Text("-0.1×")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              listOf(1.0f, 1.25f, 1.5f, 2.0f).forEach { p ->
                FilterChip(
                  selected = Math.abs(customSpeed - p) < 0.04f,
                  onClick = {
                    customSpeed = p
                    container.playback.setSpeed(p)
                  },
                  label = { Text("${p}×") }
                )
              }
            }

            OutlinedButton(
              onClick = {
                val next = (customSpeed + 0.1f).coerceIn(0.5f, 3.0f)
                customSpeed = (Math.round(next * 20.0) / 20.0).toFloat()
                container.playback.setSpeed(customSpeed)
              }
            ) {
              Text("+0.1×")
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showSpeedDialog = false }) { Text("Done") }
      },
    )
  }

  // Chapters & Bookmarks Bottom Sheet
  if (showChaptersSheet) {
    val chaptersSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
      onDismissRequest = { showChaptersSheet = false },
      sheetState = chaptersSheetState,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)
      ) {
        PrimaryTabRow(selectedTabIndex = selectedChaptersTab) {
          Tab(
            selected = selectedChaptersTab == 0,
            onClick = { selectedChaptersTab = 0 },
            text = { Text("Chapters (${queueTracks.size})") },
          )
          Tab(
            selected = selectedChaptersTab == 1,
            onClick = { selectedChaptersTab = 1 },
            text = { Text("Bookmarks (${bookmarks.size})") },
          )
        }

        Spacer(Modifier.height(12.dp))

        if (selectedChaptersTab == 0) {
          if (queueTracks.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
              Text("No playlist queue available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxWidth().height(360.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              itemsIndexed(queueTracks, key = { idx, t -> "${t.infoHash}_${t.fileIndex}_$idx" }) { idx, track ->
                val isCurrent = idx == currentQueueIndex
                Card(
                  modifier = Modifier.fillMaxWidth().clickable {
                    container.playback.jumpToQueueIndex(idx)
                    showChaptersSheet = false
                  },
                  colors = CardDefaults.cardColors(
                    containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                  ),
                  shape = RoundedCornerShape(10.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      "${idx + 1}.",
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                      color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                      track.title,
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f),
                    )
                    if (isCurrent && state.isPlaying) {
                      Spacer(Modifier.width(8.dp))
                      Text(
                        "Playing",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                      )
                    }
                  }
                }
              }
            }
          }
        } else {
          // Bookmarks Tab
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Saved Bookmarks", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { showAddBookmarkDialog = true }) {
              Text("+ Bookmark Here")
            }
          }
          if (bookmarks.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
              Text("No bookmarks saved yet. Tap '+ Bookmark Here' to save one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxWidth().height(340.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              items(bookmarks, key = { it.id }) { bm ->
                Card(
                  modifier = Modifier.fillMaxWidth().clickable {
                    if (state.infoHash == bm.infoHash && state.fileIndex == bm.fileIndex) {
                      container.playback.seekTo(bm.positionMs)
                    } else {
                      coroutineScope.launch {
                        container.playback.requestPlayback(
                          infoHash = bm.infoHash,
                          fileIndex = bm.fileIndex,
                          startPositionMs = bm.positionMs,
                        )
                      }
                    }
                    showChaptersSheet = false
                  },
                  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                  shape = RoundedCornerShape(10.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        formatDuration(bm.positionMs),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                      )
                      Text(
                        bm.note ?: bm.chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                    IconButton(onClick = { container.playback.deleteBookmark(bm.id) }) {
                      Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  // Add Bookmark Dialog
  if (showAddBookmarkDialog) {
    AlertDialog(
      onDismissRequest = { showAddBookmarkDialog = false; newBookmarkNote = "" },
      title = { Text("Add Bookmark at ${formatDuration(state.positionMs)}") },
      text = {
        Column {
          Text("Save this moment with an optional note:", style = MaterialTheme.typography.bodyMedium)
          Spacer(Modifier.height(8.dp))
          OutlinedTextField(
            value = newBookmarkNote,
            onValueChange = { newBookmarkNote = it },
            placeholder = { Text("Note (e.g. insightful quote, favorite scene)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            container.playback.addBookmark(newBookmarkNote)
            showAddBookmarkDialog = false
            newBookmarkNote = ""
          }
        ) {
          Text("Save Bookmark")
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddBookmarkDialog = false; newBookmarkNote = "" }) { Text("Cancel") }
      },
    )
  }

  // Sleep timer dialog
  if (showSleepDialog) {
    AlertDialog(
      onDismissRequest = { showSleepDialog = false },
      title = { Text("Sleep timer") },
      text = {
        Column {
          listOf(15, 30, 45, 60).forEach { mins ->
            TextButton(
              onClick = {
                container.playback.startSleepTimer(mins)
                showSleepDialog = false
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text("$mins minutes")
            }
          }
          TextButton(
            onClick = {
              container.playback.startSleepTimerEndOfTrack()
              showSleepDialog = false
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("End of current track")
          }
          if (state.sleepTimerRemainingSec != null || state.isSleepTimerEndOfTrack) {
            TextButton(
              onClick = {
                container.playback.cancelSleepTimer()
                showSleepDialog = false
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text("Turn off timer", color = MaterialTheme.colorScheme.error)
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showSleepDialog = false }) { Text("Cancel") }
      },
    )
  }

  // Full-Screen Spotify-style Lyrics Sheet
  if (showLyricsSheet) {
    ModalBottomSheet(
      onDismissRequest = { showLyricsSheet = false },
      sheetState = sheetState,
      containerColor = Color(0xFF0F1115),
    ) {
      val cleanBook = BookTitleCleaner.resolveCleanBookTitle(state.title, state.subtitle)
      LyricsView(
        lines = activeWindowLines,
        activeLineIndex = activeLineIndex,
        syncOffsetMs = syncOffsetMs,
        source = lyricsSource,
        companionBookTitle = companionBookTitle,
        activeChapterTitle = activeChapterTitle,
        isbn = companionBookIsbn,
        cleanBookTitle = cleanBook,
        alignmentConfidence = alignmentConfidence,
        alignmentStrategy = alignmentStrategy,
        allChapters = allCompanionChapters,
        onSeekToLine = { line -> container.playback.seekToLine(line) },
        onAdjustSync = { delta -> container.playback.adjustSyncOffset(delta) },
        onCalibrateSync = { line -> container.playback.calibrateSyncToLine(line) },
        onSelectChapter = { chapter -> container.playback.selectChapterForCurrentTrack(chapter) },
        onAttachFileText = { text, source ->
          container.playback.loadLyricsFromText(text, source)
        },
        onAttachCompanionFile = { bytes, fileName ->
          val hash = state.infoHash ?: return@LyricsView
          val audioFiles = container.engine.torrents.value.firstOrNull { it.infoHash == hash }?.audioFiles ?: emptyList()
          container.playback.attachCompanionBook(bytes, fileName, hash, audioFiles)
        },
      )
    }
  }
}

@Composable
private fun PauseGlyph(modifier: Modifier = Modifier, color: Color) {
  Canvas(modifier) {
    val barWidth = size.width * 0.24f
    val gap = size.width * 0.20f
    val left = (size.width - gap) / 2f - barWidth
    val right = (size.width + gap) / 2f
    drawRoundRect(
      color = color,
      topLeft = Offset(left, 0f),
      size = Size(barWidth, size.height),
      cornerRadius = CornerRadius(2f, 2f),
    )
    drawRoundRect(
      color = color,
      topLeft = Offset(right, 0f),
      size = Size(barWidth, size.height),
      cornerRadius = CornerRadius(2f, 2f),
    )
  }
}

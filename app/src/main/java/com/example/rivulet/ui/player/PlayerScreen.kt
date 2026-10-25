package com.example.rivulet.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rivulet.AppContainer
import com.example.rivulet.data.lyrics.BookTitleCleaner
import com.example.rivulet.ui.formatDuration
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val state by container.playback.state.collectAsStateWithLifecycle()
  val queueTracks by container.playback.currentQueue.collectAsStateWithLifecycle()
  val currentQueueIndex by container.playback.currentQueueIndex.collectAsStateWithLifecycle()
  val bookmarks by container.playback.bookmarks.collectAsStateWithLifecycle()
  val torrents by container.engine.torrents.collectAsStateWithLifecycle()
  var showSpeedDialog by remember { mutableStateOf(false) }
  var showChaptersSheet by remember { mutableStateOf(false) }
  var showAddBookmarkDialog by remember { mutableStateOf(false) }
  var newBookmarkNote by remember { mutableStateOf("") }
  var selectedChaptersTab by remember { mutableStateOf(0) }
  val coroutineScope = rememberCoroutineScope()
  val cleanBook = BookTitleCleaner.resolveCleanBookTitle(state.title, state.subtitle)
  PlayerLayout(
    state = state, title = cleanBook.ifBlank { state.title }, subtitle = state.title,
    chapterLabel = if (currentQueueIndex >= 0) "Chapter ${currentQueueIndex + 1} of ${queueTracks.size}" else null,
    onBack = onBack, onBookmark = { showAddBookmarkDialog = true }, onQueue = { showChaptersSheet = true },
    onPrevious = { container.playback.playPrevious() }, onBackTen = { container.playback.seekBy(-10_000) },
    onPlayPause = { container.playback.togglePlayPause() }, onForwardThirty = { container.playback.seekBy(30_000) },
    onNext = { container.playback.playNext() }, onSeek = { position ->
      val current = container.playback.state.value
      if (current.infoHash == state.infoHash && current.fileIndex == state.fileIndex) container.playback.seekTo(position)
    },
    onSpeed = { showSpeedDialog = true }, onBoost = { container.playback.toggleVoiceBoost() }, modifier = modifier,
  ) { readerModifier ->
    LyricsView(container.playback.readAlong, torrents.firstOrNull { it.infoHash == state.infoHash }?.audioFiles ?: emptyList(),
      cleanBook, onSeekToLine = { container.playback.seekToLine(it) }, modifier = readerModifier,
      onSyncNow = { container.playback.syncReadAlongNow(it) })
  }
  if (showSpeedDialog) AlertDialog(
    onDismissRequest = { showSpeedDialog = false }, title = { Text("Playback speed") },
    text = {
      Column {
        Text("${state.speed}×", style = MaterialTheme.typography.headlineMedium)
        Slider(state.speed, { container.playback.setSpeed(it) }, valueRange = 0.5f..3f)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
            FilterChip(state.speed == speed, onClick = { container.playback.setSpeed(speed) }, label = { Text("${speed}×") })
          }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          TextButton(onClick = { container.playback.setSpeed((state.speed - 0.05f).coerceAtLeast(0.5f)) }) { Text("−0.05×") }
          TextButton(onClick = { container.playback.setSpeed((state.speed + 0.05f).coerceAtMost(3f)) }) { Text("+0.05×") }
        }
      }
    }, confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("Done") } },
  )
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

}

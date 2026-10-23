package com.example.rivulet.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rivulet.data.lyrics.EpubChapter
import com.example.rivulet.data.lyrics.EpubExtractor
import com.example.rivulet.data.lyrics.TextSyncSource
import com.example.rivulet.data.lyrics.TimedLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LyricsView(
  lines: List<TimedLine>,
  activeLineIndex: Int,
  syncOffsetMs: Long,
  source: TextSyncSource?,
  companionBookTitle: String? = null,
  activeChapterTitle: String? = null,
  alignmentConfidence: Float? = null,
  alignmentStrategy: String? = null,
  allChapters: List<EpubChapter> = emptyList(),
  initialSearchQuery: String? = null,
  onSeekToLine: (TimedLine) -> Unit,
  onAdjustSync: (deltaMs: Long) -> Unit,
  onCalibrateSync: (TimedLine) -> Unit = {},
  onSelectChapter: (EpubChapter) -> Unit = {},
  onAttachFileText: (text: String, source: TextSyncSource) -> Unit,
  onAttachCompanionFile: (bytes: ByteArray, fileName: String) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  var userIsInteracting by remember { mutableStateOf(false) }
  var showZLibBrowser by remember { mutableStateOf(false) }
  var showChapterPicker by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  val filePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      scope.launch {
        withContext(Dispatchers.IO) {
          runCatching {
            val fileName = uri.lastPathSegment ?: "document.txt"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) {
              if (fileName.endsWith(".epub", ignoreCase = true) || fileName.endsWith(".txt", ignoreCase = true)) {
                onAttachCompanionFile(bytes, fileName)
              } else {
                val src = when {
                  fileName.endsWith(".lrc", ignoreCase = true) -> TextSyncSource.LRC
                  fileName.endsWith(".srt", ignoreCase = true) -> TextSyncSource.SRT
                  else -> TextSyncSource.LOCAL_ATTACHMENT
                }
                val content = EpubExtractor.extractTextFromBytes(bytes, fileName)
                onAttachFileText(content, src)
              }
            }
          }
        }
      }
    }

  // Smooth auto-scroll following the active sentence
  LaunchedEffect(activeLineIndex) {
    if (activeLineIndex >= 0 && !userIsInteracting && activeLineIndex < lines.size) {
      val targetIndex = (activeLineIndex - 2).coerceAtLeast(0)
      listState.animateScrollToItem(targetIndex)
    }
  }

  // Auto-scroll pause debounce when user scrolls manually
  LaunchedEffect(listState.isScrollInProgress) {
    if (listState.isScrollInProgress) {
      userIsInteracting = true
    } else if (userIsInteracting) {
      delay(3000)
      userIsInteracting = false
    }
  }

  Column(
    modifier = modifier.fillMaxSize().background(Color(0xFF0F1115)),
  ) {
    // Top Bar with Metadata & Sync Calibration
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xFF14171F))
        .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Icon(
            Icons.Default.FormatQuote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
          Spacer(Modifier.width(6.dp))
          Column {
            Text(
              text = if (!companionBookTitle.isNullOrBlank()) companionBookTitle else "Read Along",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = Color.White,
              maxLines = 1,
            )
            if (!activeChapterTitle.isNullOrBlank()) {
              Text(
                text = activeChapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
              )
            }
          }
        }

        // Sync adjustment buttons
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          OutlinedButton(
            onClick = { onAdjustSync(-500L) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            modifier = Modifier.height(28.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
          ) {
            Icon(Icons.Default.FastRewind, contentDescription = "-0.5s", modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(2.dp))
            Text("-0.5s", fontSize = 11.sp)
          }

          if (syncOffsetMs != 0L) {
            Text(
              "${if (syncOffsetMs > 0) "+" else ""}${syncOffsetMs / 1000.0}s",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 2.dp),
            )
          }

          OutlinedButton(
            onClick = { onAdjustSync(500L) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            modifier = Modifier.height(28.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
          ) {
            Text("+0.5s", fontSize = 11.sp)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Default.FastForward, contentDescription = "+0.5s", modifier = Modifier.size(12.dp))
          }
        }
      }

      // Secondary row: Match Confidence / Source Pill & Actions
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          if (source != null) {
            Surface(
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
              shape = RoundedCornerShape(6.dp),
            ) {
              Text(
                source.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }

          if (alignmentConfidence != null) {
            val percent = (alignmentConfidence * 100).toInt()
            val badgeColor = if (percent >= 80) Color(0xFF4CAF50) else Color(0xFFFF9800)
            Surface(
              color = badgeColor.copy(alpha = 0.2f),
              shape = RoundedCornerShape(6.dp),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = badgeColor, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                  "$percent% Matched",
                  style = MaterialTheme.typography.labelSmall,
                  color = badgeColor,
                  fontWeight = FontWeight.Bold,
                  fontSize = 10.sp,
                )
              }
            }
          }
        }

        // Action icons: Chapter picker & Web companion
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (allChapters.isNotEmpty()) {
            IconButton(
              onClick = { showChapterPicker = true },
              modifier = Modifier.size(30.dp),
            ) {
              Icon(
                Icons.Default.List,
                contentDescription = "Select Chapter",
                tint = Color.LightGray,
                modifier = Modifier.size(18.dp),
              )
            }
          }

          IconButton(
            onClick = { showZLibBrowser = true },
            modifier = Modifier.size(30.dp),
          ) {
            Icon(
              Icons.Default.Language,
              contentDescription = "Search zlib.li",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
          }

          IconButton(
            onClick = { filePicker.launch(arrayOf("*/*")) },
            modifier = Modifier.size(30.dp),
          ) {
            Icon(
              Icons.Default.Add,
              contentDescription = "Attach File",
              tint = Color.LightGray,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }
    }

    if (lines.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Card(
          colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2129)),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
          Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Icon(
              Icons.Default.Book,
              contentDescription = null,
              modifier = Modifier.size(48.dp),
              tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
              "No Companion Book Attached",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
              "Read along as the narrator speaks. You can search zlib.li for the ebook, or attach an EPUB/TXT from your phone.",
              style = MaterialTheme.typography.bodySmall,
              color = Color.Gray,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            // Action Buttons
            FilledTonalButton(
              onClick = { showZLibBrowser = true },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(8.dp))
              Text("Search zlib.li Web Companion")
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
              onClick = { filePicker.launch(arrayOf("*/*")) },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(8.dp))
              Text("Attach Local EPUB / Lyrics")
            }
          }
        }
      }
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        itemsIndexed(lines, key = { idx, line -> "${line.id}_$idx" }) { index, line ->
          val isActive = index == activeLineIndex
          val textColor by animateColorAsState(
            targetValue = if (isActive) Color.White else Color.Gray.copy(alpha = 0.5f),
            animationSpec = tween(durationMillis = 250),
            label = "lyricsColor",
          )
          val scale by animateFloatAsState(
            targetValue = if (isActive) 1.02f else 1.0f,
            animationSpec = tween(durationMillis = 250),
            label = "lyricsScale",
          )

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .scale(scale)
              .clip(RoundedCornerShape(8.dp))
              .combinedClickable(
                onClick = { onSeekToLine(line) },
                onLongClick = { onCalibrateSync(line) },
              )
              .then(
                if (isActive) {
                  Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                } else {
                  Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                }
              ),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = line.text,
              fontSize = if (isActive) 19.sp else 16.sp,
              fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
              lineHeight = if (isActive) 27.sp else 22.sp,
              color = textColor,
              modifier = Modifier.weight(1f),
            )

            // 1-Tap "Sync Here" calibration pill on active or hovered line
            if (isActive) {
              Spacer(Modifier.width(8.dp))
              Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable { onCalibrateSync(line) },
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                  Icon(
                    Icons.Default.Adjust,
                    contentDescription = "Sync line to current time",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                  )
                  Spacer(Modifier.width(4.dp))
                  Text(
                    "Sync Here",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                  )
                }
              }
            }
          }
        }

        item {
          Spacer(Modifier.height(48.dp))
        }
      }
    }
  }

  // Chapter Selector Modal Bottom Sheet
  if (showChapterPicker && allChapters.isNotEmpty()) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
      onDismissRequest = { showChapterPicker = false },
      sheetState = sheetState,
      containerColor = Color(0xFF14171F),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 12.dp),
      ) {
        Text(
          "Select Chapter for this Audio Track",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = Color.White,
        )
        Text(
          "If audio track numbers don't match the book chapters, tap a chapter to link it.",
          style = MaterialTheme.typography.bodySmall,
          color = Color.Gray,
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(
          modifier = Modifier.fillMaxWidth().height(360.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          itemsIndexed(allChapters) { idx, chapter ->
            val isCurrent = chapter.title == activeChapterTitle
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                  onSelectChapter(chapter)
                  showChapterPicker = false
                },
              color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color(0xFF1E2129),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    chapter.title,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                    fontSize = 14.sp,
                  )
                  Text(
                    "${chapter.wordCount} words",
                    fontSize = 11.sp,
                    color = Color.Gray,
                  )
                }
                if (isCurrent) {
                  Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = "Active Chapter",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                  )
                }
              }
            }
          }
        }
        Spacer(Modifier.height(24.dp))
      }
    }
  }

  // Z-Library Web Companion Dialog
  if (showZLibBrowser) {
    ZLibBrowserDialog(
      initialQuery = initialSearchQuery,
      onDismiss = { showZLibBrowser = false },
      onBookDownloaded = { bytes, fileName ->
        onAttachCompanionFile(bytes, fileName)
      },
    )
  }
}

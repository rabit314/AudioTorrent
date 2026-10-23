package com.example.rivulet.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.rivulet.data.lyrics.EpubExtractor
import com.example.rivulet.data.lyrics.TextSyncSource
import com.example.rivulet.data.lyrics.TimedLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LyricsView(
  lines: List<TimedLine>,
  activeLineIndex: Int,
  syncOffsetMs: Long,
  source: TextSyncSource?,
  onSeekToLine: (TimedLine) -> Unit,
  onAdjustSync: (deltaMs: Long) -> Unit,
  onAttachFileText: (text: String, source: TextSyncSource) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  var userIsInteracting by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  val filePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      scope.launch {
        val (text, extSource) = withContext(Dispatchers.IO) {
          runCatching {
            val fileName = uri.lastPathSegment ?: "document.txt"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
              val src = when {
                fileName.endsWith(".lrc", ignoreCase = true) -> TextSyncSource.LRC
                fileName.endsWith(".srt", ignoreCase = true) -> TextSyncSource.SRT
                fileName.endsWith(".epub", ignoreCase = true) -> TextSyncSource.EPUB_PACED
                else -> TextSyncSource.LOCAL_ATTACHMENT
              }
              val content = EpubExtractor.extractTextFromBytes(bytes, fileName)
              content to src
            } else null
          }.getOrNull() ?: ("" to TextSyncSource.LOCAL_ATTACHMENT)
        }
        if (text.isNotBlank()) {
          onAttachFileText(text, extSource)
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
    // Top Bar with Sync Calibration Pill
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          Icons.Default.FormatQuote,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
          "Read Along",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = Color.White,
        )
        if (source != null) {
          Spacer(Modifier.width(8.dp))
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
      }

      // Sync adjustment buttons
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        OutlinedButton(
          onClick = { onAdjustSync(-500L) },
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
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
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
          modifier = Modifier.height(28.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
        ) {
          Text("+0.5s", fontSize = 11.sp)
          Spacer(Modifier.width(2.dp))
          Icon(Icons.Default.FastForward, contentDescription = "+0.5s", modifier = Modifier.size(12.dp))
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
              Icons.Default.FormatQuote,
              contentDescription = null,
              modifier = Modifier.size(48.dp),
              tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
              "No Text Loaded Yet",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
              "Attach an EPUB, TXT, or LRC file from your phone to read along as the narrator speaks.",
              style = MaterialTheme.typography.bodySmall,
              color = Color.Gray,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
              onClick = {
                filePicker.launch(arrayOf("*/*"))
              },
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Attach Ebook / Lyrics")
            }
          }
        }
      }
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        itemsIndexed(lines, key = { idx, line -> "${line.id}_$idx" }) { index, line ->
          val isActive = index == activeLineIndex
          val textColor by animateColorAsState(
            targetValue = if (isActive) Color.White else Color.Gray.copy(alpha = 0.5f),
            animationSpec = tween(durationMillis = 250),
            label = "lyricsColor",
          )
          val scale by animateFloatAsState(
            targetValue = if (isActive) 1.03f else 1.0f,
            animationSpec = tween(durationMillis = 250),
            label = "lyricsScale",
          )

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .scale(scale)
              .clip(RoundedCornerShape(8.dp))
              .clickable { onSeekToLine(line) }
              .then(
                if (isActive) {
                  Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                } else {
                  Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                }
              ),
          ) {
            Text(
              text = line.text,
              fontSize = if (isActive) 20.sp else 16.sp,
              fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
              lineHeight = if (isActive) 28.sp else 22.sp,
              color = textColor,
            )
          }
        }

        item {
          Spacer(Modifier.height(48.dp))
        }
      }
    }
  }
}

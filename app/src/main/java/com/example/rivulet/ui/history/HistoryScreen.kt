package com.example.rivulet.ui.history

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.rivulet.AppContainer
import com.example.rivulet.data.HistoryDateGroup
import com.example.rivulet.data.HistoryEntry
import com.example.rivulet.ui.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
  container: AppContainer,
  onBack: () -> Unit,
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var historyMap by remember { mutableStateOf<Map<HistoryDateGroup, List<HistoryEntry>>>(emptyMap()) }
  var showClearDialog by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

  fun refresh() {
    scope.launch {
      val data = withContext(Dispatchers.IO) {
        container.database.getOrganizedHistory()
      }
      historyMap = data
    }
  }

  LaunchedEffect(Unit) {
    refresh()
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Listening History", fontWeight = FontWeight.SemiBold)
            val totalCount = historyMap.values.sumOf { it.size }
            Text(
              if (totalCount == 0) "No recent activity" else "$totalCount session${if (totalCount == 1) "" else "s"}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          if (historyMap.isNotEmpty()) {
            IconButton(onClick = { showClearDialog = true }) {
              Icon(Icons.Default.DeleteSweep, contentDescription = "Clear all history")
            }
          }
        },
      )
    },
  ) { padding ->
    if (historyMap.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.padding(32.dp),
        ) {
          Icon(
            Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
          )
          Spacer(Modifier.height(16.dp))
          Text(
            "No listening history yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(8.dp))
          Text(
            "As you stream and listen to audiobooks, your listening sessions and chapters will be organized here by date.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        historyMap.forEach { (group, entries) ->
          item(key = "header_${group.name}") {
            Text(
              text = group.title,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
          }

          items(entries, key = { it.id }) { entry ->
            HistoryCard(
              entry = entry,
              timeString = timeFormat.format(Date(entry.playedAt)),
              onResume = {
                scope.launch {
                  container.playback.requestPlayback(
                    infoHash = entry.infoHash,
                    fileIndex = entry.fileIndex,
                  ).onSuccess { onOpenPlayer() }
                }
              },
              onDelete = {
                scope.launch {
                  withContext(Dispatchers.IO) {
                    container.database.deleteHistoryEntry(entry.id)
                  }
                  refresh()
                }
              },
            )
          }
        }
      }
    }
  }

  if (showClearDialog) {
    AlertDialog(
      onDismissRequest = { showClearDialog = false },
      title = { Text("Clear Listening History?") },
      text = { Text("This will remove all recorded listening sessions from your history. Your downloaded files and saved bookmarks will not be affected.") },
      confirmButton = {
        TextButton(
          onClick = {
            showClearDialog = false
            scope.launch {
              withContext(Dispatchers.IO) {
                container.database.clearAllHistory()
              }
              refresh()
            }
          }
        ) {
          Text("Clear All", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearDialog = false }) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun HistoryCard(
  entry: HistoryEntry,
  timeString: String,
  onResume: () -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth().clickable(onClick = onResume),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    shape = RoundedCornerShape(12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (!entry.coverUrl.isNullOrBlank()) {
        AsyncImage(
          model = entry.coverUrl,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.size(52.dp, 68.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          entry.bookTitle,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          entry.fileName,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
          progress = { entry.progress },
          modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(4.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            if (entry.remainingMs > 0L) "${formatDuration(entry.remainingMs)} left" else "${(entry.progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            timeString,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(Modifier.width(8.dp))

      FilledTonalIconButton(
        onClick = onResume,
        modifier = Modifier.size(40.dp),
      ) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
      }

      IconButton(
        onClick = onDelete,
        modifier = Modifier.size(32.dp),
      ) {
        Icon(
          Icons.Outlined.Delete,
          contentDescription = "Delete entry",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

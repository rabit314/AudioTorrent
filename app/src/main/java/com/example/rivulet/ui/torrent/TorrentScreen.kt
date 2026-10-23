package com.example.rivulet.ui.torrent

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.rivulet.AppContainer
import com.example.rivulet.data.AudioFile
import com.example.rivulet.data.NATURAL_PATH_COMPARATOR
import com.example.rivulet.playback.QueueTrack
import com.example.rivulet.ui.formatBytes
import com.example.rivulet.ui.formatDuration
import com.example.rivulet.ui.formatRate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentScreen(
  container: AppContainer,
  infoHash: String,
  onBack: () -> Unit,
  onPlay: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val torrents by container.engine.torrents.collectAsStateWithLifecycle()
  val torrent = torrents.firstOrNull { it.infoHash == infoHash }
  val scope = rememberCoroutineScope()

  var preparing by remember { mutableStateOf<Int?>(null) }
  var showDeleteDialog by remember { mutableStateOf(false) }
  var deleteDiskFiles by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var sortOrder by remember { mutableStateOf(FileSortOrder.TORRENT_ORDER) }

  val sortedFiles = remember(torrent?.audioFiles, sortOrder) {
    val list = torrent?.audioFiles ?: emptyList()
    when (sortOrder) {
      FileSortOrder.TORRENT_ORDER -> list.sortedBy { it.index }
      FileSortOrder.NATURAL_PATH -> list.sortedWith(NATURAL_PATH_COMPARATOR)
      FileSortOrder.SIZE_DESC -> list.sortedByDescending { it.size }
    }
  }

  Scaffold(
    modifier = modifier.safeDrawingPadding(),
    topBar = {
      TopAppBar(
        title = {
          Text(
            torrent?.name ?: "Torrent",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          if (torrent != null) {
            IconButton(onClick = { showDeleteDialog = true }) {
              Icon(Icons.Default.Delete, contentDescription = "Delete torrent")
            }
          }
        },
      )
    },
  ) { padding ->
    if (torrent == null) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text("Torrent not found.")
      }
      return@Scaffold
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
      Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!torrent.coverUrl.isNullOrBlank()) {
          AsyncImage(
            model = torrent.coverUrl,
            contentDescription = torrent.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
          )
          Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
        LinearProgressIndicator(
          progress = { torrent.progress },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        val status = buildString {
          append(torrent.state)
          append(" · ${formatBytes(torrent.doneBytes)} / ${formatBytes(torrent.totalBytes)}")
          if (torrent.peers > 0) append(" · ${torrent.peers} peers")
          if (torrent.seeds > 0) append(" · ${torrent.seeds} seeds")
          if (torrent.downloadRate > 0) append(" · ↓ ${formatRate(torrent.downloadRate)}")
        }
        Text(
          status,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {

        if (!torrent.hasMetadata) {
          Spacer(Modifier.height(8.dp))
          Text(
            "Waiting for torrent metadata…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }

        torrent.error?.let {
          Spacer(Modifier.height(8.dp))
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (preparing != null) {
          Spacer(Modifier.height(8.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
              "Buffering from peers…",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        if (torrent.audioFiles.isNotEmpty()) {
          Spacer(Modifier.height(12.dp))
          val allWanted = torrent.audioFiles.all { it.isWanted || it.isDownloaded }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = {
                container.engine.setTorrentDownloadWanted(infoHash, !allWanted)
              },
            ) {
              Text(if (allWanted) "Stop downloading all" else "Download all for offline")
            }
          }
        }
      }

      HorizontalDivider()

      LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        item {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              "Audio files (${sortedFiles.size})",
              style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              FilterChip(
                selected = sortOrder == FileSortOrder.TORRENT_ORDER,
                onClick = { sortOrder = FileSortOrder.TORRENT_ORDER },
                label = { Text("Torrent Order") },
              )
              FilterChip(
                selected = sortOrder == FileSortOrder.NATURAL_PATH,
                onClick = { sortOrder = FileSortOrder.NATURAL_PATH },
                label = { Text("Path / Name") },
              )
            }
          }
        }
        if (sortedFiles.isEmpty()) {
          item {
            Text(
              "This torrent has no playable audio files.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        items(sortedFiles, key = { it.index }) { file ->
          FileRow(
            file = file,
            preparing = preparing == file.index,
            onToggleDownload = { wanted ->
              container.engine.setFileDownloadWanted(infoHash, file.index, wanted)
            },
            onPlay = {
              scope.launch {
                preparing = file.index
                val queueTracks = sortedFiles.map {
                  val titleText = if (it.directory.isNotBlank()) "${it.directory} - ${it.name}" else it.name
                  QueueTrack(
                    infoHash = infoHash,
                    fileIndex = it.index,
                    title = titleText,
                    subtitle = torrent.name,
                    coverUrl = torrent.coverUrl,
                  )
                }
                container.playback.requestPlayback(
                  infoHash = infoHash,
                  fileIndex = file.index,
                  queueOverride = queueTracks,
                )
                  .onSuccess { onPlay() }
                  .onFailure { error = it.message ?: "Couldn't start playback." }
                preparing = null
              }
            },
          )
        }
      }
    }
  }

  if (showDeleteDialog && torrent != null) {
    AlertDialog(
      onDismissRequest = { showDeleteDialog = false },
      title = { Text("Delete torrent") },
      text = {
        Column {
          Text("Remove \"${torrent.name}\" from your library?")
          Spacer(Modifier.height(12.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
              checked = deleteDiskFiles,
              onCheckedChange = { deleteDiskFiles = it },
            )
            Spacer(Modifier.width(8.dp))
            Text("Delete downloaded files from storage")
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val delFiles = deleteDiskFiles
            showDeleteDialog = false
            scope.launch {
              container.engine.removeTorrent(infoHash, delFiles)
              onBack()
            }
          }
        ) {
          Text("Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
      },
    )
  }

  error?.let { message ->
    AlertDialog(
      onDismissRequest = { error = null },
      title = { Text("Playback error") },
      text = { Text(message) },
      confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
    )
  }
}

@Composable
private fun FileRow(
  file: AudioFile,
  preparing: Boolean,
  onToggleDownload: (Boolean) -> Unit,
  onPlay: () -> Unit,
) {
  Card(Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        if (file.directory.isNotBlank()) {
          Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.padding(bottom = 4.dp),
          ) {
            Text(
              file.directory,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Text(
          file.name,
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        val statusText = buildString {
          append("#${file.index + 1} · ")
          append(formatBytes(file.size))
          append(" · ${(file.progress * 100).toInt()}%")
          if (file.isDownloaded) {
            append(" · Downloaded")
          } else if (file.isWanted) {
            append(" · Downloading")
          }
          if (file.savedPositionMs > 3000L) {
            append(" · Resume from ${formatDuration(file.savedPositionMs)}")
          }
        }
        Text(
          statusText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (file.progress > 0f && file.progress < 1f) {
          Spacer(Modifier.height(6.dp))
          LinearProgressIndicator(
            progress = { file.progress },
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      Spacer(Modifier.size(8.dp))

      // Offline download toggle
      if (!file.isDownloaded) {
        TextButton(onClick = { onToggleDownload(!file.isWanted) }) {
          Text(if (file.isWanted) "Stop" else "Get")
        }
      }

      if (preparing) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
      } else {
        IconButton(onClick = onPlay) {
          Icon(Icons.Default.PlayArrow, contentDescription = "Play ${file.name}")
        }
      }
    }
  }
}

enum class FileSortOrder {
  TORRENT_ORDER,
  NATURAL_PATH,
  SIZE_DESC,
}

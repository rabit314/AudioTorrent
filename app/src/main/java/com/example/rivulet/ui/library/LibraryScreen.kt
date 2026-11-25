package com.example.rivulet.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.rivulet.AppContainer
import com.example.rivulet.BuildConfig
import com.example.rivulet.R
import com.example.rivulet.data.ListeningHistoryItem
import com.example.rivulet.data.TorrentUi
import com.example.rivulet.data.WishlistBook
import com.example.rivulet.data.audiobookbay.AudioBookItem
import com.example.rivulet.theme.ReaderThemePreset
import com.example.rivulet.theme.appColorSchemeFor
import com.example.rivulet.ui.formatBytes
import com.example.rivulet.ui.formatDuration
import com.example.rivulet.ui.formatRate
import com.example.rivulet.ui.main.AboutDialog
import com.example.rivulet.ui.main.AppLinks
import com.example.rivulet.ui.main.ChangelogDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  container: AppContainer,
  onOpenTorrent: (String) -> Unit,
  onOpenPlayer: () -> Unit = {},
  onOpenAudioBookBay: () -> Unit = {},
  onOpenHistory: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val torrents by container.engine.torrents.collectAsStateWithLifecycle()
  val playbackState by container.playback.state.collectAsStateWithLifecycle()
  val readerTheme by container.playback.readAlong.readerPreferences.readerTheme.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var historyItems by remember { mutableStateOf<List<ListeningHistoryItem>>(emptyList()) }
  var wishlistItems by remember { mutableStateOf<List<WishlistBook>>(emptyList()) }
  var trendingBooks by remember { mutableStateOf<List<AudioBookItem>>(emptyList()) }
  var isTrendingLoading by remember { mutableStateOf(false) }
  var streamingItemId by remember { mutableStateOf<String?>(null) }
  var filterOfflineOnly by remember { mutableStateOf(false) }

  var showAddDialog by remember { mutableStateOf(false) }
  var showAboutDialog by remember { mutableStateOf(false) }
  var showChangelogDialog by remember { mutableStateOf(false) }
  var showThemeDialog by remember { mutableStateOf(false) }
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val uriHandler = LocalUriHandler.current
  var deleteCandidate by remember { mutableStateOf<TorrentUi?>(null) }
  var deleteDiskFiles by remember { mutableStateOf(false) }
  var magnet by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  val totalStorageUsed = torrents.sumOf { it.doneBytes }

  fun refreshHistory() {
    scope.launch(Dispatchers.IO) {
      val items = container.database.getListeningHistory(10)
      withContext(Dispatchers.Main) {
        historyItems = items
      }
    }
  }

  fun refreshWishlist() {
    scope.launch(Dispatchers.IO) {
      val items = container.database.getWishlist()
      withContext(Dispatchers.Main) {
        wishlistItems = items
      }
    }
  }

  // Load history, wishlist and trending feed on launch
  LaunchedEffect(Unit) {
    refreshHistory()
    refreshWishlist()
    isTrendingLoading = true
    container.audiobookbay.getLatest(1)
      .onSuccess { trendingBooks = it }
    isTrendingLoading = false
  }

  // Refresh history only on key lifecycle events: track changes or play/pause transitions
  LaunchedEffect(playbackState.infoHash, playbackState.fileIndex, playbackState.isPlaying) {
    refreshHistory()
  }

  LaunchedEffect(container) {
    container.pendingTorrent.collect { hash ->
      if (hash != null) {
        container.pendingTorrent.value = null
        onOpenTorrent(hash)
      }
    }
  }

  val filePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      showAddDialog = false
      scope.launch {
        busy = true
        val bytes = withContext(Dispatchers.IO) {
          runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          }.getOrNull()
        }
        if (bytes == null) {
          busy = false
          error = "Couldn't read that file."
          return@launch
        }
        container.engine
          .addTorrentFile(bytes)
          .onSuccess {
            busy = false
            onOpenTorrent(it)
          }
          .onFailure {
            busy = false
            error = it.message ?: "Couldn't add that torrent."
          }
      }
    }

  ModalNavigationDrawer(
    drawerState = drawerState,
    gesturesEnabled = drawerState.isOpen,
    drawerContent = {
      AppDrawerSheet(
        currentTheme = readerTheme,
        onOpenTheme = {
          scope.launch { drawerState.close() }
          showThemeDialog = true
        },
        onOpenHistory = {
          scope.launch { drawerState.close() }
          onOpenHistory()
        },
        onOpenSearch = {
          scope.launch { drawerState.close() }
          onOpenAudioBookBay()
        },
        onAddTorrent = {
          scope.launch { drawerState.close() }
          showAddDialog = true
        },
        onOpenChangelog = {
          scope.launch { drawerState.close() }
          showChangelogDialog = true
        },
        onOpenAbout = {
          scope.launch { drawerState.close() }
          showAboutDialog = true
        },
        onOpenUrl = { url ->
          scope.launch { drawerState.close() }
          uriHandler.openUri(url)
        },
      )
    },
  ) {
    Scaffold(
      modifier = modifier.safeDrawingPadding(),
      topBar = {
        TopAppBar(
          navigationIcon = {
            IconButton(
              onClick = { scope.launch { drawerState.open() } },
              modifier = Modifier.testTag("open-app-menu"),
            ) {
              Icon(
                Icons.Default.Menu,
                contentDescription = "Open menu",
                tint = MaterialTheme.colorScheme.onSurface,
              )
            }
          },
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
              painter = painterResource(R.drawable.app_logo),
              contentDescription = "AudioTorrent Icon",
              modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column {
              Text("AudioTorrent", maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(
                "${torrents.size} book${if (torrents.size == 1) "" else "s"} in library · ${formatBytes(totalStorageUsed)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        },
        actions = {
          IconButton(onClick = { showThemeDialog = true }) {
            Icon(Icons.Default.Palette, contentDescription = "App theme")
          }
          IconButton(onClick = onOpenHistory) {
            Icon(Icons.Default.History, contentDescription = "Listening History")
          }
          IconButton(onClick = onOpenAudioBookBay) {
            Icon(Icons.Default.Search, contentDescription = "Search audiobooks")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
          scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
      )
    },
    bottomBar = {
      if (playbackState.hasMedia) {
        Card(
          modifier =
            Modifier.fillMaxWidth()
              .clickable(onClick = onOpenPlayer)
              .padding(horizontal = 16.dp, vertical = 8.dp),
          border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
          if (playbackState.durationMs > 0L) {
            val progress = (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
              progress = { progress },
              modifier = Modifier.fillMaxWidth().height(2.dp),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f),
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (!playbackState.coverUrl.isNullOrBlank()) {
              AsyncImage(
                model = playbackState.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
              )
              Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
              Text(
                playbackState.title.ifBlank { "Now playing" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (playbackState.subtitle.isNotBlank()) {
                Text(
                  playbackState.subtitle,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { container.playback.togglePlayPause() }) {
              Icon(
                if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
        }
      }
    },
    floatingActionButton = {
      FloatingActionButton(
        onClick = { showAddDialog = true },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
      ) {
        Icon(Icons.Default.Add, contentDescription = "Add torrent")
      }
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(bottom = 80.dp),
    ) {
      // 1. Hero Card: Continue Listening
      val latestHistory = historyItems.firstOrNull()
      if (latestHistory != null) {
        item {
          HeroContinueListeningCard(
            item = latestHistory,
            isPlaying = playbackState.isPlaying && playbackState.infoHash == latestHistory.infoHash,
            onResume = {
              if (playbackState.infoHash == latestHistory.infoHash && playbackState.hasMedia) {
                container.playback.togglePlayPause()
              } else {
                scope.launch {
                  container.playback.requestPlayback(
                    infoHash = latestHistory.infoHash,
                    fileIndex = latestHistory.fileIndex,
                  )
                }
              }
            },
            onClick = { onOpenTorrent(latestHistory.infoHash) },
            modifier = Modifier.padding(16.dp),
          )
        }
      }

      // 2. Listening History Carousel
      if (historyItems.size > 1) {
        item {
          Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                "Listening History",
                style = MaterialTheme.typography.titleMedium,
              )
              TextButton(onClick = onOpenHistory) {
                Text("See all", style = MaterialTheme.typography.labelMedium)
              }
            }
            LazyRow(
              contentPadding = PaddingValues(horizontal = 16.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              items(historyItems, key = { it.infoHash }) { hist ->
                HistoryBookCard(
                  item = hist,
                  onClick = { onOpenTorrent(hist.infoHash) },
                )
              }
            }
          }
        }
      }

      // 2.5. Want to Listen (Wishlist) Shelf
      if (wishlistItems.isNotEmpty()) {
        item {
          Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(
              "Want to Listen",
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyRow(
              contentPadding = PaddingValues(horizontal = 16.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              items(wishlistItems, key = { it.infoHash }) { item ->
                WishlistBookCard(
                  item = item,
                  onStart = {
                    scope.launch {
                      container.engine.addMagnet(item.magnetUri, item.coverUrl)
                        .onSuccess { hash -> onOpenTorrent(hash) }
                    }
                  },
                  onRemove = {
                    scope.launch(Dispatchers.IO) {
                      container.database.removeFromWishlist(item.infoHash)
                      refreshWishlist()
                    }
                  },
                )
              }
            }
          }
        }
      }

      // 3. Trending on AudiobookBay Shelf
      item {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Trending on AudiobookBay", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onOpenAudioBookBay) { Text("See All →") }
          }

          if (isTrendingLoading && trendingBooks.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator(strokeWidth = 2.dp)
            }
          } else {
            LazyRow(
              contentPadding = PaddingValues(horizontal = 16.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              items(trendingBooks, key = { it.detailUrl }) { book ->
                val isStreaming = streamingItemId == book.detailUrl
                TrendingBookCard(
                  book = book,
                  isStreaming = isStreaming,
                  onStream = {
                    scope.launch {
                      streamingItemId = book.detailUrl
                      container.audiobookbay.getDetailAndMagnet(book)
                        .onSuccess { detail ->
                          container.engine.addMagnet(detail.magnetUri, detail.coverUrl, detail.title)
                            .onSuccess { hash ->
                              streamingItemId = null
                              onOpenTorrent(hash)
                            }
                            .onFailure {
                              streamingItemId = null
                              error = it.message
                            }
                        }
                        .onFailure {
                          streamingItemId = null
                          error = it.message
                        }
                    }
                  },
                )
              }
            }
          }
        }
      }

      // 5. My Audiobooks & Downloads
      item {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp, start = 16.dp, end = 16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("My Audiobooks", style = MaterialTheme.typography.titleMedium)
            FilterChip(
              selected = filterOfflineOnly,
              onClick = { filterOfflineOnly = !filterOfflineOnly },
              label = { Text("Downloaded Only") },
            )
          }
        }
      }

      val displayedTorrents = if (filterOfflineOnly) {
        torrents.filter { it.isFullyDownloaded }
      } else {
        torrents
      }

      if (displayedTorrents.isEmpty()) {
        item {
          Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              if (filterOfflineOnly) "No fully downloaded offline books yet."
              else "No audiobooks added yet. Tap a book above to start streaming!",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        items(displayedTorrents, key = { it.infoHash }) { torrent ->
          TorrentCard(
            torrent = torrent,
            onClick = { onOpenTorrent(torrent.infoHash) },
            onDelete = {
              deleteCandidate = torrent
              deleteDiskFiles = false
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
          )
        }
      }
    }
  }
  } // end ModalNavigationDrawer

  // Delete Confirmation Dialog
  deleteCandidate?.let { torrent ->
    AlertDialog(
      onDismissRequest = { deleteCandidate = null },
      title = { Text("Delete ${torrent.name}?") },
      text = {
        Column {
          Text("Are you sure you want to remove this audiobook from your library?")
          Spacer(Modifier.height(12.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
              checked = deleteDiskFiles,
              onCheckedChange = { deleteDiskFiles = it },
            )
            Spacer(Modifier.width(8.dp))
            Text("Also delete downloaded audio files from phone storage")
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val candidate = deleteCandidate ?: return@TextButton
            deleteCandidate = null
            scope.launch {
              container.engine.removeTorrent(candidate.infoHash, deleteDiskFiles)
              refreshHistory()
            }
          },
        ) {
          Text("Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
      },
    )
  }

  // Add Torrent Dialog
  if (showAddDialog) {
    AlertDialog(
      onDismissRequest = { if (!busy) showAddDialog = false },
      title = { Text("Add torrent") },
      text = {
        Column {
          OutlinedTextField(
            value = magnet,
            onValueChange = { magnet = it },
            label = { Text("Magnet link") },
            placeholder = { Text("magnet:?xt=urn:btih:…") },
            minLines = 2,
            maxLines = 4,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(12.dp))
          TextButton(
            onClick = {
              filePicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream"))
            },
            enabled = !busy,
          ) {
            Text("Choose a .torrent file…")
          }
          if (busy) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
              Spacer(Modifier.size(12.dp))
              Text("Fetching metadata…", style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      },
      confirmButton = {
        TextButton(
          enabled = magnet.isNotBlank() && !busy,
          onClick = {
            val link = magnet.trim()
            scope.launch {
              busy = true
              container.engine
                .addMagnet(link)
                .onSuccess {
                  busy = false
                  showAddDialog = false
                  magnet = ""
                  onOpenTorrent(it)
                }
                .onFailure {
                  busy = false
                  error = it.message ?: "Couldn't add that magnet link."
                }
            }
          },
        ) {
          Text("Add")
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddDialog = false }, enabled = !busy) { Text("Cancel") }
      },
    )
  }

  error?.let { message ->
    AlertDialog(
      onDismissRequest = { error = null },
      title = { Text("Notice") },
      text = { Text(message) },
      confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
    )
  }

  if (showAboutDialog) {
    AboutDialog(
      onDismiss = { showAboutDialog = false },
      onOpenChangelog = { showChangelogDialog = true },
    )
  }

  if (showChangelogDialog) {
    ChangelogDialog(
      onOpenReleases = {
        showChangelogDialog = false
        uriHandler.openUri(AppLinks.GITHUB_RELEASES)
      },
      onDismiss = { showChangelogDialog = false },
    )
  }

  if (showThemeDialog) {
    AppThemeDialog(
      current = readerTheme,
      onSelect = container.playback.readAlong.readerPreferences::setReaderTheme,
      onDismiss = { showThemeDialog = false },
    )
  }
}

@Composable
private fun AppDrawerSheet(
  currentTheme: ReaderThemePreset,
  onOpenTheme: () -> Unit,
  onOpenHistory: () -> Unit,
  onOpenSearch: () -> Unit,
  onAddTorrent: () -> Unit,
  onOpenChangelog: () -> Unit,
  onOpenAbout: () -> Unit,
  onOpenUrl: (String) -> Unit,
) {
  ModalDrawerSheet(
    modifier = Modifier.widthIn(max = 312.dp),
    windowInsets = DrawerDefaults.windowInsets,
    drawerContainerColor = MaterialTheme.colorScheme.surface,
    drawerContentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
      // Brand & Version Card
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpenChangelog),
      ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(
              painter = painterResource(R.drawable.app_logo),
              contentDescription = "AudioTorrent Logo",
              modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
            )
            Column(Modifier.weight(1f)) {
              Text(
                "AudioTorrent",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                "Audiobook Torrent & Read-Along",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.primaryContainer,
            ) {
              Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
            Text(
              "Build ${BuildConfig.VERSION_CODE}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
              "Changelog →",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Medium,
            )
          }
        }
      }

      Spacer(Modifier.height(14.dp))
      Text(
        "Library & Appearance",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
      )

      val itemColors = NavigationDrawerItemDefaults.colors(
        unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurface,
      )

      NavigationDrawerItem(
        label = { Text("App Theme") },
        icon = { Icon(Icons.Default.Palette, contentDescription = null) },
        badge = {
          Text(
            currentTheme.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        },
        selected = false,
        onClick = onOpenTheme,
        colors = itemColors,
      )
      NavigationDrawerItem(
        label = { Text("Listening History") },
        icon = { Icon(Icons.Default.History, contentDescription = null) },
        selected = false,
        onClick = onOpenHistory,
        colors = itemColors,
      )
      NavigationDrawerItem(
        label = { Text("Search Audiobooks") },
        icon = { Icon(Icons.Default.Search, contentDescription = null) },
        selected = false,
        onClick = onOpenSearch,
        colors = itemColors,
      )
      NavigationDrawerItem(
        label = { Text("Add Magnet / .torrent") },
        icon = { Icon(Icons.Default.AddCircleOutline, contentDescription = null) },
        selected = false,
        onClick = onAddTorrent,
        colors = itemColors,
      )

      Spacer(Modifier.height(6.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(Modifier.height(10.dp))

      Text(
        "App Info & Project",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
      )

      NavigationDrawerItem(
        label = { Text("What's New & Changelog") },
        icon = { Icon(Icons.Default.NewReleases, contentDescription = null) },
        badge = {
          Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
              "v${BuildConfig.VERSION_NAME}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        },
        selected = false,
        onClick = onOpenChangelog,
        colors = itemColors,
      )
      NavigationDrawerItem(
        label = { Text("About AudioTorrent") },
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        selected = false,
        onClick = onOpenAbout,
        colors = itemColors,
      )
      NavigationDrawerItem(
        label = {
          Column {
            Text("GitHub Repository")
            Text(
              "github.com/rabit314/AudioTorrent",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
        icon = { Icon(Icons.Default.Code, contentDescription = null) },
        badge = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp)) },
        selected = false,
        onClick = { onOpenUrl(AppLinks.GITHUB_REPO) },
        colors = itemColors,
      )
      NavigationDrawerItem(
        label = {
          Column {
            Text("Latest Releases & APK")
            Text(
              "Download or verify builds",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
        icon = { Icon(Icons.Default.Download, contentDescription = null) },
        badge = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp)) },
        selected = false,
        onClick = { onOpenUrl(AppLinks.GITHUB_RELEASES) },
        colors = itemColors,
      )
      NavigationDrawerItem(
        label = { Text("Report Issue / Feedback") },
        icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
        badge = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp)) },
        selected = false,
        onClick = { onOpenUrl(AppLinks.GITHUB_ISSUES) },
        colors = itemColors,
      )

      Spacer(Modifier.weight(1f))
      Spacer(Modifier.height(12.dp))
      Text(
        "AudioTorrent v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      )
    }
  }
}

@Composable
private fun AppThemeDialog(
  current: ReaderThemePreset,
  onSelect: (ReaderThemePreset) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("App theme") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          "Applies seamlessly across the library, player, and reading pages.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        ReaderThemePreset.entries.forEach { preset ->
          val previewScheme = appColorSchemeFor(preset)
          val selected = preset == current
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
              if (selected) 1.25.dp else 0.75.dp,
              if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(preset) },
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier = Modifier
                  .size(28.dp)
                  .clip(CircleShape)
                  .background(previewScheme.background)
                  .border(2.dp, previewScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
              ) {
                Box(
                  Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(previewScheme.primary)
                )
              }
              Spacer(Modifier.width(12.dp))
              Column(Modifier.weight(1f)) {
                Text(
                  preset.label,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                  color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  preset.tagline,
                  style = MaterialTheme.typography.labelSmall,
                  color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              RadioButton(selected = selected, onClick = { onSelect(preset) })
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
  )
}

@Composable
private fun HeroContinueListeningCard(
  item: ListeningHistoryItem,
  isPlaying: Boolean,
  onResume: () -> Unit,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    shape = RoundedCornerShape(16.dp),
    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier.size(width = 80.dp, height = 110.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
      ) {
        if (!item.coverUrl.isNullOrBlank()) {
          AsyncImage(
            model = item.coverUrl,
            contentDescription = item.bookTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
      }

      Spacer(Modifier.width(16.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          "Continue Listening",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
          item.bookTitle,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
          progress = { item.progress },
          modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(6.dp))
        val percent = (item.progress * 100).toInt()
        val remainingText = if (item.remainingMs > 0) "${formatDuration(item.remainingMs)} left" else ""
        Text(
          "$percent% completed ${if (remainingText.isNotBlank()) "· $remainingText" else ""}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(Modifier.width(8.dp))

      FilledIconButton(
        onClick = onResume,
        modifier = Modifier.size(52.dp),
      ) {
        Icon(
          if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
          contentDescription = if (isPlaying) "Pause" else "Play",
          modifier = Modifier.size(28.dp),
        )
      }
    }
  }
}

@Composable
private fun HistoryBookCard(
  item: ListeningHistoryItem,
  onClick: () -> Unit,
) {
  Card(
    modifier = Modifier.width(100.dp).clickable(onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(modifier = Modifier.padding(8.dp)) {
      Box(
        modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
      ) {
        if (!item.coverUrl.isNullOrBlank()) {
          AsyncImage(
            model = item.coverUrl,
            contentDescription = item.bookTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
      }
      Spacer(Modifier.height(6.dp))
      LinearProgressIndicator(
        progress = { item.progress },
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
      )
      Spacer(Modifier.height(4.dp))
      Text(
        item.bookTitle,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun TrendingBookCard(
  book: AudioBookItem,
  isStreaming: Boolean,
  onStream: () -> Unit,
) {
  Card(
    modifier = Modifier.width(130.dp),
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(modifier = Modifier.padding(8.dp)) {
      Box(
        modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
      ) {
        if (!book.coverUrl.isNullOrBlank()) {
          AsyncImage(
            model = book.coverUrl,
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
      }
      Spacer(Modifier.height(6.dp))
      Text(
        book.title,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(4.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (book.format.isNotBlank()) {
          Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
          ) {
            Text(
              book.format,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
        if (isStreaming) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
          IconButton(onClick = onStream, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Stream", tint = MaterialTheme.colorScheme.primary)
          }
        }
      }
    }
  }
}

@Composable
private fun TorrentCard(
  torrent: TorrentUi,
  onClick: () -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        if (!torrent.coverUrl.isNullOrBlank()) {
          AsyncImage(
            model = torrent.coverUrl,
            contentDescription = torrent.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)),
          )
          Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            torrent.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(Modifier.size(8.dp))
        Text(
          "${(torrent.progress * 100).toInt()}%",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
          Icon(
            Icons.Default.Delete,
            contentDescription = "Delete",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(Modifier.height(8.dp))
      LinearProgressIndicator(
        progress = { torrent.progress },
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
      )
      Spacer(Modifier.height(8.dp))

      val status = buildString {
        append(torrent.state)
        append(" · ${formatBytes(torrent.doneBytes)} / ${formatBytes(torrent.totalBytes)}")
        if (torrent.peers > 0) append(" · ${torrent.peers} peers")
        if (torrent.downloadRate > 0) append(" · ↓ ${formatRate(torrent.downloadRate)}")
      }
      Text(
        status,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (torrent.hasMetadata) {
        Text(
          "${torrent.audioFiles.size} audio chapter${if (torrent.audioFiles.size == 1) "" else "s"}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      torrent.error?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
    }
  }
}

@Composable
private fun WishlistBookCard(
  item: WishlistBook,
  onStart: () -> Unit,
  onRemove: () -> Unit,
) {
  Card(
    modifier = Modifier.width(130.dp),
    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(modifier = Modifier.padding(8.dp)) {
      Box(
        modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
      ) {
        if (!item.coverUrl.isNullOrBlank()) {
          coil.compose.AsyncImage(
            model = item.coverUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
          )
        } else {
          Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
      }
      Spacer(Modifier.height(6.dp))
      Text(
        item.title,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(6.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilledTonalButton(
          onClick = onStart,
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
          modifier = Modifier.height(28.dp),
        ) {
          Text("Play", style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
          Icon(
            Icons.Default.DeleteOutline,
            contentDescription = "Remove",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }
  }
}

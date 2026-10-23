package com.example.rivulet.ui.audiobookbay

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.rivulet.AppContainer
import com.example.rivulet.data.audiobookbay.AudioBookDetail
import com.example.rivulet.data.audiobookbay.AudioBookItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioBookBayScreen(
  container: AppContainer,
  onBack: () -> Unit,
  onOpenTorrent: (String) -> Unit,
  onOpenPlayer: () -> Unit,
  initialCategory: String = "All",
  modifier: Modifier = Modifier,
) {
  val playbackState by container.playback.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val focusManager = LocalFocusManager.current

  val categories = remember {
    listOf(
      "All",
      "Sci-Fi",
      "Fantasy",
      "Fiction",
      "Mystery",
      "Thriller",
      "Horror",
      "History",
      "Crime",
      "Romance",
      "Non-Fiction",
      "Biography",
      "Action",
      "Adventure",
      "Business",
      "Teen / YA",
    )
  }

  var selectedCategory by remember { mutableStateOf(initialCategory) }
  var query by remember { mutableStateOf("") }
  var books by remember { mutableStateOf<List<AudioBookItem>>(emptyList()) }
  var recentSearches by remember { mutableStateOf<List<String>>(emptyList()) }
  var isLoading by remember { mutableStateOf(false) }
  var loadingItemId by remember { mutableStateOf<String?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var page by remember { mutableIntStateOf(1) }

  var activeDetail by remember { mutableStateOf<AudioBookDetail?>(null) }
  var isDetailLoading by remember { mutableStateOf(false) }

  fun refreshSearches() {
    scope.launch(Dispatchers.IO) {
      val list = container.database.getRecentSearches(10)
      withContext(Dispatchers.Main) {
        recentSearches = list
      }
    }
  }

  fun loadBooks(newQuery: String, category: String = selectedCategory, targetPage: Int = 1) {
    scope.launch {
      isLoading = true
      errorMessage = null
      page = targetPage
      val trimmed = newQuery.trim()
      val result = if (trimmed.isNotBlank()) {
        container.audiobookbay.search(trimmed, category, targetPage)
      } else if (!category.equals("All", ignoreCase = true)) {
        container.audiobookbay.getByCategory(category, targetPage)
      } else {
        container.audiobookbay.getLatest(targetPage)
      }

      result.onSuccess {
        books = it
        isLoading = false
      }.onFailure {
        isLoading = false
        errorMessage = it.message ?: "Could not load books from AudiobookBay."
      }
    }
  }

  // Initial load
  LaunchedEffect(Unit) {
    refreshSearches()
    container.audiobookbay.login()
    loadBooks(query, selectedCategory, 1)
  }

  Scaffold(
    modifier = modifier.safeDrawingPadding(),
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("AudiobookBay")
            Text(
              "Discover & Stream Audiobooks",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
    bottomBar = {
      if (playbackState.hasMedia) {
        Card(
          modifier =
            Modifier.fillMaxWidth()
              .clickable(onClick = onOpenPlayer)
              .padding(horizontal = 16.dp, vertical = 8.dp),
          colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
          if (playbackState.durationMs > 0L) {
            val progress = (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
              progress = { progress },
              modifier = Modifier.fillMaxWidth().height(2.dp),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.primaryContainer,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (playbackState.subtitle.isNotBlank()) {
                Text(
                  playbackState.subtitle,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
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
              )
            }
          }
        }
      }
    },
  ) { padding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(padding)
    ) {
      // Search Bar
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          label = { Text("Search title, author, narrator…") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Search,
            capitalization = KeyboardCapitalization.Sentences,
          ),
          keyboardActions = KeyboardActions(
            onSearch = {
              if (query.isNotBlank()) {
                focusManager.clearFocus()
                val trimmed = query.trim()
                scope.launch(Dispatchers.IO) {
                  container.database.saveRecentSearch(trimmed)
                  refreshSearches()
                }
                loadBooks(trimmed, selectedCategory, 1)
              }
            }
          ),
          modifier = Modifier.weight(1f),
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
          trailingIcon = {
            if (query.isNotBlank()) {
              IconButton(onClick = {
                query = ""
                loadBooks("", selectedCategory, 1)
              }) {
                Icon(Icons.Default.Clear, contentDescription = "Clear search")
              }
            }
          },
        )
        Spacer(Modifier.width(8.dp))
        Button(
          onClick = {
            focusManager.clearFocus()
            val trimmed = query.trim()
            if (trimmed.isNotBlank()) {
              scope.launch(Dispatchers.IO) {
                container.database.saveRecentSearch(trimmed)
                refreshSearches()
              }
              loadBooks(trimmed, selectedCategory, 1)
            } else {
              loadBooks("", selectedCategory, 1)
            }
          },
          enabled = !isLoading,
        ) {
          Text("Go")
        }
      }

      // Category Filter Chips
      LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(categories, key = { it }) { cat ->
          FilterChip(
            selected = selectedCategory.equals(cat, ignoreCase = true),
            onClick = {
              selectedCategory = cat
              loadBooks(query, cat, 1)
            },
            label = { Text(cat) },
          )
        }
      }

      // Recent Searches Shelf (1-tap execute, single delete, clear all)
      if (recentSearches.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              "Recent Searches",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
              onClick = {
                scope.launch(Dispatchers.IO) {
                  container.database.clearRecentSearches()
                  refreshSearches()
                }
              },
              contentPadding = PaddingValues(0.dp),
            ) {
              Text("Clear all", style = MaterialTheme.typography.labelSmall)
            }
          }
          LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(recentSearches, key = { it }) { term ->
              InputChip(
                selected = query == term,
                onClick = {
                  query = term
                  focusManager.clearFocus()
                  scope.launch(Dispatchers.IO) {
                    container.database.saveRecentSearch(term)
                    refreshSearches()
                  }
                  loadBooks(term, selectedCategory, 1)
                },
                label = { Text(term) },
                leadingIcon = {
                  Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                  )
                },
                trailingIcon = {
                  IconButton(
                    onClick = {
                      scope.launch(Dispatchers.IO) {
                        container.database.deleteRecentSearch(term)
                        refreshSearches()
                      }
                    },
                    modifier = Modifier.size(18.dp),
                  ) {
                    Icon(
                      Icons.Default.Close,
                      contentDescription = "Remove",
                      modifier = Modifier.size(12.dp),
                    )
                  }
                },
              )
            }
          }
        }
      }

      // Content Area
      if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Loading audiobooks…", style = MaterialTheme.typography.bodyMedium)
          }
        }
      } else if (errorMessage != null) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Could not connect to AudiobookBay", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(errorMessage ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { loadBooks(query, selectedCategory, page) }) {
              Text("Retry")
            }
          }
        }
      } else if (books.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
          Text("No audiobooks found. Try another search term.", style = MaterialTheme.typography.bodyLarge)
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(books, key = { it.detailUrl }) { book ->
            val isItemLoading = loadingItemId == book.detailUrl
            AudioBookCard(
              book = book,
              isLoading = isItemLoading,
              onClick = {
                scope.launch {
                  isDetailLoading = true
                  container.audiobookbay.getDetailAndMagnet(book)
                    .onSuccess { activeDetail = it }
                    .onFailure { errorMessage = it.message }
                  isDetailLoading = false
                }
              },
              onStream = {
                scope.launch {
                  loadingItemId = book.detailUrl
                  container.audiobookbay.getDetailAndMagnet(book)
                    .onSuccess { detail ->
                      container.engine.addMagnet(detail.magnetUri, detail.coverUrl)
                        .onSuccess { infoHash ->
                          loadingItemId = null
                          onOpenTorrent(infoHash)
                        }
                        .onFailure {
                          loadingItemId = null
                          errorMessage = it.message
                        }
                    }
                    .onFailure {
                      loadingItemId = null
                      errorMessage = it.message
                    }
                }
              },
            )
          }

          item {
            // Pagination controls
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
              horizontalArrangement = Arrangement.Center,
            ) {
              if (page > 1) {
                TextButton(onClick = { loadBooks(query, selectedCategory, page - 1) }) {
                  Text("← Previous Page")
                }
                Spacer(Modifier.width(16.dp))
              }
              Text(
                "Page $page",
                modifier = Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.bodyMedium,
              )
              Spacer(Modifier.width(16.dp))
              TextButton(onClick = { loadBooks(query, selectedCategory, page + 1) }) {
                Text("Next Page →")
              }
            }
          }
        }
      }
    }
  }

  // Book Detail Bottom Sheet
  activeDetail?.let { detail ->
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = { activeDetail = null },
      sheetState = sheetState,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (!detail.coverUrl.isNullOrBlank()) {
          AsyncImage(
            model = detail.coverUrl,
            contentDescription = detail.title,
            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
          )
          Spacer(Modifier.height(16.dp))
        }

        Text(
          detail.title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))

        if (detail.description.isNotBlank()) {
          Text(
            detail.description.take(400) + if (detail.description.length > 400) "…" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(16.dp))
        }

        Button(
          onClick = {
            scope.launch {
              container.engine.addMagnet(detail.magnetUri, detail.coverUrl)
                .onSuccess { hash ->
                  activeDetail = null
                  onOpenTorrent(hash)
                }
                .onFailure { errorMessage = it.message }
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Stream & Download Audiobook")
        }

        Spacer(Modifier.height(8.dp))
        var savedToWishlist by remember { mutableStateOf(false) }
        OutlinedButton(
          onClick = {
            scope.launch(Dispatchers.IO) {
              val infoHash = detail.infoHash.ifBlank { detail.title.hashCode().toString() }
              container.database.addToWishlist(
                com.example.rivulet.data.WishlistBook(
                  infoHash = infoHash,
                  title = detail.title,
                  author = "",
                  coverUrl = detail.coverUrl,
                  magnetUri = detail.magnetUri,
                  addedAt = System.currentTimeMillis(),
                )
              )
              savedToWishlist = true
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(if (savedToWishlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(if (savedToWishlist) "Saved to Wishlist" else "Save to Wishlist")
        }
      }
    }
  }
}

@Composable
private fun AudioBookCard(
  book: AudioBookItem,
  isLoading: Boolean,
  onClick: () -> Unit,
  onStream: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      // Cover Image
      Box(
        modifier = Modifier.size(width = 68.dp, height = 90.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),
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

      Spacer(Modifier.width(12.dp))

      // Book Details
      Column(modifier = Modifier.weight(1f)) {
        Text(
          book.title,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (book.author.isNotBlank()) {
          Text(
            book.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (book.format.isNotBlank()) {
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            ) {
              Text(
                book.format,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
          if (book.fileSize.isNotBlank()) {
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
            ) {
              Text(
                book.fileSize,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
              )
            }
          }
        }
      }

      Spacer(Modifier.width(8.dp))

      // Action Button
      if (isLoading) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
      } else {
        IconButton(onClick = onStream) {
          Icon(Icons.Default.PlayArrow, contentDescription = "Stream book", tint = MaterialTheme.colorScheme.primary)
        }
      }
    }
  }
}

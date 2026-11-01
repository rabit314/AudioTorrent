package com.example.rivulet.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.rivulet.AppContainer
import com.example.rivulet.data.audiobookbay.*
import com.example.rivulet.data.catalog.AudioCatalogSearch
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookSearchScreen(container: AppContainer, onBack: () -> Unit, onOpenTorrent: (String) -> Unit, onOpenPlayer: () -> Unit,
  search: AudioCatalogSearch = container.catalogSearch) {
  val state by search.state.collectAsStateWithLifecycle()
  val player by container.playback.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val focus = LocalFocusManager.current
  var query by rememberSaveable { mutableStateOf(state.query) }
  var recent by remember { mutableStateOf<List<String>>(emptyList()) }
  var selected by remember { mutableStateOf<AudioBookItem?>(null) }
  var detail by remember { mutableStateOf<AudioBookDetail?>(null) }
  var detailError by remember { mutableStateOf<String?>(null) }
  var adding by remember { mutableStateOf(false) }
  var saved by remember(selected) { mutableStateOf(false) }
  fun refreshRecent() { scope.launch { recent = withContext(Dispatchers.IO) { container.database.getRecentSearches(10) } } }
  fun submit() {
    focus.clearFocus()
    search.search(query, state.source)
    if (query.isNotBlank()) scope.launch { withContext(Dispatchers.IO) { container.database.saveRecentSearch(query.trim()) }; refreshRecent() }
  }
  LaunchedEffect(Unit) { refreshRecent(); if (!state.started) search.search("", null) }
  LaunchedEffect(selected) {
    detail = null; detailError = null; adding = false
    val item = selected ?: return@LaunchedEffect
    try { detail = container.catalog.detail(item) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { detailError = error.message ?: "Could not load this recording." }
  }
  Scaffold(modifier = Modifier.safeDrawingPadding(), topBar = {
    TopAppBar(title = { Text("Search audiobooks") }, navigationIcon = {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    })
  }, bottomBar = {
    if (player.hasMedia) Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPlayer)) {
      Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(player.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = container.playback::togglePlayPause) { Text(if (player.isPlaying) "Pause" else "Play") }
      }
    }
  }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it }, label = { Text("Book title or author") }, singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
          modifier = Modifier.weight(1f).testTag("audiobook-query"))
        TextButton(onClick = { submit() }) { Text("Search") }
      }
      LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(state.source == null, { search.search(query, null) }, label = { Text("All") }) }
        items(AudioBookSource.entries) { source ->
          FilterChip(state.source == source, { search.search(query, source) }, label = { Text(source.label) })
        }
      }
      if (recent.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(recent) { term -> InputChip(state.query == term, { query = term; submit() }, label = { Text(term) }, trailingIcon = {
          TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { container.database.deleteRecentSearch(term) }; refreshRecent() } }, contentPadding = PaddingValues(0.dp)) { Text("×") }
        }) }
        item { TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { container.database.clearRecentSearches() }; refreshRecent() } }) { Text("Clear recent") } }
      }
      LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.errors.forEach { (source, message) -> item(key = "error:$source") {
          Text("${source.label}: $message", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          TextButton(onClick = { search.retry(source) }) { Text("Retry ${source.label}") }
        } }
        items(state.books, key = { "${it.source}:${it.providerId}" }) { book ->
          OutlinedCard(onClick = { selected = book }, modifier = Modifier.fillMaxWidth().testTag("audiobook-result")) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              AsyncImage(book.coverUrl, "Cover of ${book.title}", Modifier.size(64.dp, 88.dp), contentScale = ContentScale.Fit)
              Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium)
                Text(listOf(book.source.label, book.language).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
              }
            }
          }
        }
        if (state.loading.isNotEmpty()) item {
          LinearProgressIndicator(Modifier.fillMaxWidth())
          Text("Searching ${state.loading.joinToString { it.label }}…", style = MaterialTheme.typography.bodySmall)
        }
        if (state.books.isEmpty() && state.loading.isEmpty()) item {
          Text(if (state.errors.isEmpty()) "No audiobooks found. Try another title or author." else "Retry an unavailable source or try another search.")
        }
        if (state.more.isNotEmpty()) item { TextButton(onClick = search::more, enabled = state.loading.isEmpty()) { Text("Load more") } }
      }
    }
  }
  selected?.let { item ->
    ModalBottomSheet(onDismissRequest = { if (!adding) selected = null }) {
      Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleLarge)
        Text(item.source.label, style = MaterialTheme.typography.labelMedium)
        detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val loaded = detail
        if (loaded == null && detailError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (loaded != null) {
          Text(loaded.description.take(700), maxLines = 10, overflow = TextOverflow.Ellipsis)
          Button(onClick = {
            adding = true; detailError = null
            scope.launch {
              container.addAudiobook(loaded).onSuccess { hash -> selected = null; onOpenTorrent(hash) }
                .onFailure { detailError = it.message ?: "Could not add this recording." }
              adding = false
            }
          }, enabled = !adding, modifier = Modifier.fillMaxWidth()) { Text(if (adding) "Adding audiobook…" else "Stream & Download Audiobook") }
          if (loaded.source == AudioBookSource.AUDIOBOOKBAY) OutlinedButton(onClick = {
            scope.launch {
              withContext(Dispatchers.IO) {
                container.database.addToWishlist(com.example.rivulet.data.WishlistBook(loaded.infoHash, loaded.title, item.author, loaded.coverUrl, loaded.magnetUri, System.currentTimeMillis()))
              }
              saved = true
            }
          }, enabled = !saved && !adding) { Text(if (saved) "Saved to Wishlist" else "Save to Wishlist") }
        }
        TextButton(onClick = { selected = null }, enabled = !adding) { Text("Close") }
      }
    }
  }
}

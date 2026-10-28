package com.example.rivulet.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import coil.compose.AsyncImage
import com.example.rivulet.playback.PlaybackUi
import com.example.rivulet.ui.formatDuration
import kotlinx.coroutines.launch

enum class PlayerContentMode { COVER, TEXT }
data class PlayerTrackKey(val hash: String?, val index: Int)
data class PlayerSeekGesture(val track: PlayerTrackKey, val fraction: Float) {
  fun positionFor(current: PlayerTrackKey, durationMs: Long): Long? =
    if (track == current && durationMs > 0) (fraction.coerceIn(0f, 1f) * durationMs).toLong() else null
}

/** Same bounded reading/artwork panel in both modes; only its contents scroll. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerLayout(
  state: PlaybackUi,
  title: String,
  subtitle: String,
  chapterLabel: String?,
  onBack: () -> Unit,
  onBookmark: () -> Unit,
  onQueue: () -> Unit,
  onPrevious: () -> Unit,
  onBackTen: () -> Unit,
  onPlayPause: () -> Unit,
  onForwardThirty: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
  onSpeed: () -> Unit,
  onBoost: () -> Unit,
  modifier: Modifier = Modifier,
  textContent: @Composable (Modifier, Boolean) -> Unit,
) {
  val pager = rememberPagerState { PlayerContentMode.entries.size }
  val readerState = rememberSaveableStateHolder()
  val scope = rememberCoroutineScope()
  val mode = PlayerContentMode.entries[pager.currentPage]
  val readerVisible = pager.settledPage == PlayerContentMode.TEXT.ordinal && !pager.isScrollInProgress
  val appBarHeight = 56.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
  Scaffold(modifier = modifier.safeDrawingPadding(), topBar = {
    TopAppBar(title = {
      Column {
        Text("Now playing", style = MaterialTheme.typography.titleMedium)
        chapterLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
      }
    }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, actions = {
      IconButton(onClick = onBookmark, enabled = state.hasMedia) { Icon(Icons.Default.BookmarkBorder, "Add bookmark") }
      IconButton(onClick = onQueue, enabled = state.hasMedia) { Icon(Icons.Default.FormatListNumbered, "Chapters and bookmarks") }
    }, expandedHeight = appBarHeight)
  }) { insets ->
    BoxWithConstraints(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
      val landscape = maxWidth >= 480.dp && maxWidth > maxHeight
      val controlsMaxHeight = (maxHeight - 120.dp).coerceAtLeast(120.dp)
      val media: @Composable (Modifier) -> Unit = { panelModifier ->
        Column(panelModifier, horizontalAlignment = Alignment.CenterHorizontally) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == PlayerContentMode.COVER, onClick = { scope.launch { pager.animateScrollToPage(0) } }, label = { Text("Cover") }, modifier = Modifier.testTag("player-cover"))
            FilterChip(selected = mode == PlayerContentMode.TEXT, onClick = { scope.launch { pager.animateScrollToPage(1) } }, label = { Text("Text") }, modifier = Modifier.testTag("player-text"))
          }
          Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)).testTag("player-media")) {
            HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize().testTag("player-pager")) { page ->
              if (page == PlayerContentMode.COVER.ordinal) {
                if (!state.coverUrl.isNullOrBlank()) AsyncImage(state.coverUrl, "Book cover", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.MenuBook, "No cover available", Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary) }
              } else readerState.SaveableStateProvider("reader") {
                textContent(Modifier.fillMaxSize().testTag("reader-visible"), readerVisible)
              }
            }
          }
        }
      }
      val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
        PlayerControls(state, title, subtitle, onPrevious, onBackTen, onPlayPause, onForwardThirty, onNext, onSeek, onSpeed, onBoost, landscape, controlsModifier)
      }
      // Move the same media composition between Column and Row; keep pager/reader slots alive.
      val latestMedia by rememberUpdatedState(media)
      val mediaPanel = remember { movableContentOf<Modifier> { latestMedia(it) } }
      if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        mediaPanel(Modifier.weight(1f).fillMaxHeight())
        controls(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
      } else Column(Modifier.fillMaxSize()) {
        mediaPanel(Modifier.fillMaxWidth().weight(1f))
        controls(Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState()))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerControls(
  state: PlaybackUi, title: String, subtitle: String,
  onPrevious: () -> Unit, onBackTen: () -> Unit, onPlayPause: () -> Unit,
  onForwardThirty: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit,
  onSpeed: () -> Unit, onBoost: () -> Unit, compact: Boolean, modifier: Modifier,
) {
  val track = PlayerTrackKey(state.infoHash, state.fileIndex)
  val latestTrack by rememberUpdatedState(track)
  var drag by remember(track) { mutableStateOf<PlayerSeekGesture?>(null) }
  val duration = state.durationMs.coerceAtLeast(1)
  val fraction = drag?.fraction ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
  Column(modifier.padding(top = if (compact) 0.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(title.ifBlank { "Nothing playing" }, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    if (subtitle.isNotBlank() && subtitle != title) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    key(track) {
      val interaction = remember { MutableInteractionSource() }
      Slider(value = fraction, onValueChange = { drag = PlayerSeekGesture(track, it) }, onValueChangeFinished = {
        drag?.positionFor(latestTrack, state.durationMs)?.let(onSeek); drag = null
      }, enabled = state.hasMedia && state.durationMs > 0, interactionSource = interaction,
        thumb = { SliderDefaults.Thumb(interactionSource = interaction, enabled = state.hasMedia && state.durationMs > 0,
          thumbSize = DpSize(4.dp, 22.dp), modifier = Modifier.testTag("player-seek-thumb")) },
        modifier = Modifier.fillMaxWidth().testTag("player-seek"))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(formatDuration(drag?.positionFor(track, state.durationMs) ?: state.positionMs), style = MaterialTheme.typography.labelMedium)
      Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelMedium)
    }
    Row(Modifier.fillMaxWidth().widthIn(max = 420.dp).padding(vertical = if (compact) 2.dp else 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
      TransportButton(Icons.Default.SkipPrevious, "Previous chapter", state.hasPrevious || state.positionMs > 3000, onPrevious)
      TransportButton(Icons.Default.Replay10, "Back 10 seconds", state.hasMedia, onBackTen, tonal = true)
      TransportButton(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play",
        state.hasMedia, onPlayPause, primarySize = if (compact) 44.8.dp else 51.2.dp)
      TransportButton(Icons.Default.Forward30, "Forward 30 seconds", state.hasMedia, onForwardThirty, tonal = true)
      TransportButton(Icons.Default.SkipNext, "Next chapter", state.hasNext, onNext)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(onClick = onSpeed, enabled = state.hasMedia) { Icon(Icons.Default.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Speed ${state.speed}×") }
      TextButton(onClick = onBoost, enabled = state.hasMedia) { Icon(Icons.Default.GraphicEq, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (state.isVoiceBoost) "Boost on" else "Boost") }
    }
    if (state.isBuffering) Text("Buffering from peers…", style = MaterialTheme.typography.labelSmall)
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
  }
}

/** Visual geometry shrinks independently of the non-overlapping accessible button target. */
@Composable
private fun TransportButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, tonal: Boolean = false, primarySize: Dp? = null) {
  val colors = MaterialTheme.colorScheme
  val background = when {
    primarySize != null -> colors.primary
    tonal -> colors.secondaryContainer
    else -> androidx.compose.ui.graphics.Color.Transparent
  }
  val foreground = when {
    primarySize != null -> colors.onPrimary
    tonal -> colors.onSecondaryContainer
    else -> colors.onSurface
  }
  val visualSize = primarySize ?: 32.dp
  IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(visualSize.coerceAtLeast(48.dp))) {
    Box(Modifier.size(visualSize).background(if (enabled || background == androidx.compose.ui.graphics.Color.Transparent) background else background.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
      Icon(icon, label, Modifier.size(if (primarySize != null) 28.8.dp else 19.2.dp), tint = if (enabled) foreground else colors.onSurface.copy(alpha = 0.38f))
    }
  }
}

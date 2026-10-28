package com.example.rivulet.ui.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@Composable
internal fun FlowingReader(
  paragraphs: List<ReaderParagraph>, list: LazyListState, textSize: Int,
  activeId: Long?, selectedId: Long?, following: Boolean, editing: Boolean, visible: Boolean,
  onTap: (Long) -> Boolean, onLongPress: (Long) -> Unit, modifier: Modifier = Modifier,
) {
  val layouts = remember(paragraphs) { mutableStateMapOf<String, TextLayoutResult>() }
  var viewportWidth by remember { mutableIntStateOf(0) }
  var anchorKey by rememberSaveable { mutableStateOf<String?>(null) }
  var anchorCharacter by rememberSaveable { mutableIntStateOf(0) }
  var restoringAnchor by remember { mutableStateOf(false) }
  val fontScale = LocalDensity.current.fontScale
  val density = LocalDensity.current.density
  LaunchedEffect(list, paragraphs, following, visible) {
    if (following) { anchorKey = null; return@LaunchedEffect }
    if (!visible) return@LaunchedEffect
    snapshotFlow {
      if (restoringAnchor || !list.isScrollInProgress) null else {
        val paragraph = paragraphs.getOrNull(list.firstVisibleItemIndex)
        val layout = paragraph?.let { layouts[it.key] }
        if (layout == null) null else paragraph.key to layout.getLineStart(layout.getLineForVerticalPosition(list.firstVisibleItemScrollOffset.toFloat()))
      }
    }.filterNotNull().collect { (key, character) -> anchorKey = key; anchorCharacter = character }
  }
  LaunchedEffect(paragraphs, viewportWidth, textSize, fontScale, density, visible, following) {
    if (!visible || following || viewportWidth == 0) return@LaunchedEffect
    val key = anchorKey ?: return@LaunchedEffect
    val character = anchorCharacter
    val index = paragraphs.indexOfFirst { it.key == key }
    if (index < 0) return@LaunchedEffect
    restoringAnchor = true
    try {
      if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
      val layout = snapshotFlow { layouts[key]?.takeIf {
        it.size.width == viewportWidth && it.layoutInput.style.fontSize == textSize.sp &&
          it.layoutInput.density.fontScale == fontScale && it.layoutInput.density.density == density
      } }.filterNotNull().first()
      list.scrollToItem(index, layout.getBoundingBox(character.coerceIn(0, paragraphs[index].text.lastIndex)).top.roundToInt())
    } finally { restoringAnchor = false }
  }
  LaunchedEffect(paragraphs, activeId, following, editing, visible, textSize, fontScale, density, viewportWidth) {
    if (!visible || !following || editing || activeId == null) return@LaunchedEffect
    val index = paragraphs.indexOfFirst { block -> block.ranges.any { it.sentenceId == activeId } }
    if (index < 0) return@LaunchedEffect
    val block = paragraphs[index]
    if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
    val layout = snapshotFlow { layouts[block.key]?.takeIf { it.size.width == viewportWidth && it.layoutInput.style.fontSize == textSize.sp &&
      it.layoutInput.density.fontScale == fontScale && it.layoutInput.density.density == density } }
      .filterNotNull().first()
    val range = block.ranges.first { it.sentenceId == activeId }
    val sentenceTop = layout.getBoundingBox(range.start).top
    // Follow the sentence inside a long paragraph, with readable context above it.
    val offset = (sentenceTop - list.layoutInfo.viewportSize.height * 0.25f).roundToInt()
    list.animateScrollToItem(index, offset)
  }
  LazyColumn(state = list, modifier = modifier.onSizeChanged { viewportWidth = it.width }.testTag("readalong-lines"),
    contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    items(paragraphs, key = { it.key }) { paragraph ->
      ReaderParagraphText(paragraph, textSize, activeId, selectedId, editing, onTap, onLongPress,
        onLayout = { layouts[paragraph.key] = it })
      DisposableEffect(paragraph.key) { onDispose { layouts.remove(paragraph.key) } }
    }
  }
}

@Composable
private fun ReaderParagraphText(
  paragraph: ReaderParagraph, textSize: Int, activeId: Long?, selectedId: Long?, editing: Boolean,
  onTap: (Long) -> Boolean, onLongPress: (Long) -> Unit, onLayout: (TextLayoutResult) -> Unit,
) {
  var layout by remember(paragraph.text) { mutableStateOf<TextLayoutResult?>(null) }
  val tap by rememberUpdatedState(onTap)
  val longPress by rememberUpdatedState(onLongPress)
  val colors = MaterialTheme.colorScheme
  fun sentenceAt(point: Offset): Long? {
    val result = layout ?: return null
    if (point.y < 0 || point.y >= result.size.height) return null
    // A cue/single-sentence paragraph remains tappable across its row, including short final lines.
    if (paragraph.ranges.size == 1 && point.x >= 0 && point.x < result.size.width) return paragraph.ranges.single().sentenceId
    val line = result.getLineForVerticalPosition(point.y)
    if (point.x < result.getLineLeft(line) || point.x > result.getLineRight(line)) return null
    return paragraph.sentenceAt(result.getOffsetForPosition(point))
  }
  Text(paragraph.text, fontSize = textSize.sp, lineHeight = (textSize + 8).sp, fontWeight = FontWeight.Medium,
    color = colors.onSurfaceVariant, onTextLayout = { layout = it; onLayout(it) },
    modifier = Modifier.fillMaxWidth().testTag("reader-paragraph-${paragraph.key}").drawWithContent {
      val measured = layout
      val highlights = if (measured == null) emptyList() else paragraph.ranges.filter { it.sentenceId == activeId || it.sentenceId == selectedId }
        .map { range -> range to measured.getPathForRange(range.start, range.end) }
      highlights.forEach { (range, path) ->
        drawPath(path, colors.primaryContainer.copy(alpha = if (range.sentenceId == selectedId) 0.85f else 0.45f))
      }
      drawContent()
      if (measured != null) highlights.forEach { (range, path) ->
        clipPath(path) { drawText(measured, color = colors.onSurface) }
        if (range.sentenceId == selectedId) drawPath(path, colors.primary, style = Stroke(1.dp.toPx()))
      }
    }.pointerInput(paragraph) {
      // Tap detectors cancel on parent horizontal/vertical scrolling; swipes never select or seek.
      detectTapGestures(onTap = { point -> sentenceAt(point)?.let { tap(it) } },
        onLongPress = { point -> sentenceAt(point)?.let { longPress(it) } })
    }.semantics {
      customActions = paragraph.ranges.flatMap { range ->
        val label = paragraph.text.substring(range.start, range.end)
        listOf(
          CustomAccessibilityAction(if (editing) "Select: $label" else "Seek to: $label") { tap(range.sentenceId) },
          CustomAccessibilityAction("Correct sync: $label") { longPress(range.sentenceId); true },
        )
      }
      if (paragraph.ranges.size == 1) {
        val id = paragraph.ranges.single().sentenceId
        onClick(if (editing) "Select sentence" else "Seek to sentence") { tap(id) }
        onLongClick("Correct sync") { longPress(id); true }
      }
    })
}

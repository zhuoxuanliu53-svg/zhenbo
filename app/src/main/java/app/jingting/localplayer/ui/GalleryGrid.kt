package app.jingting.localplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.jingting.localplayer.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable internal fun MediaTile(
    item: MediaEntity, selecting: Boolean, number: Int, click: () -> Unit,
    longClick: (() -> Unit)?, modifier: Modifier = Modifier, menu: (@Composable () -> Unit)? = null,
    alreadyAdded: Boolean = false,
) {
    Column(modifier.testTag("media-${item.id}").clip(RoundedCornerShape(12.dp))
        .combinedClickable(enabled = !alreadyAdded, onClick = click, onLongClick = longClick,
            onLongClickLabel = "编辑文件")) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
            MediaArtwork(item.uri, item.isVideo, Modifier.fillMaxSize(), item.available)
            Text(duration(item.durationMs), color = Color(0xFFE9E1D7), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color(0xB0100E0C), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
            if (selecting) Box(Modifier.align(Alignment.TopEnd)) {
                if (alreadyAdded) Icon(QuietIcons.Check, "已添加", Modifier.padding(8.dp).size(28.dp).background(Ink, RoundedCornerShape(14.dp)), tint = Accent)
                else SelectionBadge(number, "选择${item.title}", click)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, Modifier.weight(1f).padding(top = 6.dp, bottom = 4.dp), minLines = 2, maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (!selecting && menu != null) Box { menu() }
        }
    }
}

/** One pointer detector owns long-press selection and dragging, including edge auto-scroll. */
@Composable internal fun ReorderableMediaGrid(
    entries: List<CollectionItem>, modifier: Modifier = Modifier,
    onLongPress: (String) -> Unit, persist: suspend (List<String>) -> Unit, onError: () -> Unit,
    tile: @Composable (CollectionItem, Modifier) -> Unit,
) {
    val state = rememberLazyGridState()
    var order by remember { mutableStateOf(entries.map { it.entry.id }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var moved by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pendingAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val currentEntries by rememberUpdatedState(entries)
    val begin by rememberUpdatedState(onLongPress)
    val save by rememberUpdatedState(persist)
    val fail by rememberUpdatedState(onError)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 52.dp.toPx() }
    val speed = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(entries, dragging, saving) {
        if (dragging == null && !saving) order = entries.map { it.entry.id }
        else if (dragging != null && entries.none { it.entry.id == dragging }) { dragging = null; order = entries.map { it.entry.id } }
    }
    fun moveAtPointer() {
        if (pendingAnchor != null) return
        val id = dragging ?: return
        val hitY = pointer.y.coerceIn(state.layoutInfo.viewportStartOffset + 1f, (state.layoutInfo.viewportEndOffset - 1f).coerceAtLeast(state.layoutInfo.viewportStartOffset + 1f))
        val target = state.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != id && pointer.x >= it.offset.x && pointer.x < it.offset.x + it.size.width &&
                hitY >= it.offset.y && hitY < it.offset.y + it.size.height
        } ?: return
        val targetId = target.key as? String ?: return
        val from = order.indexOf(id); val to = order.indexOf(targetId)
        // Wait for the layout to reflect the preceding swap; otherwise a second
        // pointer/scroll tick can immediately swap back against stale positions.
        if (target.index != to) return
        if (from >= 0 && to >= 0 && from != to) {
            pendingAnchor = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            order = order.toMutableList().apply { add(to, removeAt(from)) }
            moved = true
        }
    }
    LaunchedEffect(order) {
        // Apply after the new item provider has been composed. An immediate
        // requestScrollToItem here would remeasure the old order and still let
        // LazyGrid follow the moved first key during the subsequent layout.
        withContext(Dispatchers.Main.immediate) {
            pendingAnchor?.let { state.scrollToItem(it.first, it.second); pendingAnchor = null }
        }
    }
    LaunchedEffect(dragging) {
        withContext(Dispatchers.Main.immediate) { while (dragging != null) {
            val layout = state.layoutInfo
            val delta = when {
                pointer.y < layout.viewportStartOffset + edge -> -speed * ((layout.viewportStartOffset + edge - pointer.y) / edge).coerceIn(0f, 1f)
                pointer.y > layout.viewportEndOffset - edge -> speed * ((pointer.y - layout.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (delta != 0f && pendingAnchor == null) { state.scrollBy(delta); moveAtPointer() }
            delay(16)
        } }
    }
    // Pointer/auto-scroll ticks must not rebuild maps for the entire collection.
    val lookup = remember(entries) { entries.associateBy { it.entry.id } }
    val displayed = remember(order, lookup) { (order + lookup.keys).distinct().mapNotNull { lookup[it] } }
    LazyVerticalGrid(GridCells.Fixed(3), state = state, userScrollEnabled = dragging == null,
        modifier = modifier.testTag("collection-grid").pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var validHold = true
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            validHold = false
                            return@withTimeoutOrNull
                        }
                    }
                }
                if (!validHold) return@awaitEachGesture
                val pressed = down
                val item = state.layoutInfo.visibleItemsInfo.firstOrNull {
                    pressed.position.x >= it.offset.x && pressed.position.x < it.offset.x + it.size.width &&
                        pressed.position.y >= it.offset.y && pressed.position.y < it.offset.y + it.size.height
                } ?: return@awaitEachGesture
                val id = item.key as? String ?: return@awaitEachGesture
                if (saving) return@awaitEachGesture
                begin(id)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val start = pressed.position
                var dragStarted = false
                var completed = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == pressed.id } ?: break
                    if (!change.pressed) {
                        val wasCancelled = change.isConsumed
                        change.consume()
                        completed = !wasCancelled
                        break
                    }
                    if ((change.position - start).getDistance() >= viewConfiguration.touchSlop) {
                        if (!dragStarted) {
                            dragStarted = true
                            dragging = id; pointer = change.position
                            grab = start - Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
                            moved = false
                        }
                        change.consume()
                        pointer = change.position
                        moveAtPointer()
                    }
                }
                if (dragStarted && completed) {
                    dragging = null
                    if (moved) {
                        val result = order.toList(); saving = true
                        scope.launch(Dispatchers.Main.immediate) {
                            try { save(result) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { order = currentEntries.map { it.entry.id }; fail() }
                            finally { saving = false }
                        }
                    }
                } else if (dragStarted) {
                    dragging = null; pendingAnchor = null; moved = false; order = currentEntries.map { it.entry.id }
                }
            }
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(displayed, key = { it.entry.id }) { entry ->
            val active = dragging == entry.entry.id
            val scale by animateFloatAsState(if (active) 1.045f else 1f, tween(140), label = "lift")
            tile(entry, Modifier.animateItem(placementSpec = if (active) null else tween(180))
                .zIndex(if (active) 1f else 0f).graphicsLayer {
                    val info = if (active) state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.entry.id } else null
                    val offset = if (info != null) pointer - grab - Offset(info.offset.x.toFloat(), info.offset.y.toFloat()) else Offset.Zero
                    translationX = offset.x; translationY = offset.y; scaleX = scale; scaleY = scale
                    shadowElevation = if (active) 12.dp.toPx() else 0f; shape = RoundedCornerShape(12.dp)
                }.semantics {
                    onLongClick("编辑并拖动排序") { begin(entry.entry.id); true }
                })
        }
    }
}

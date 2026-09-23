package app.jingting.localplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jingting.localplayer.data.*
import app.jingting.localplayer.core.SelectionOrder
import androidx.compose.ui.semantics.*

@Composable internal fun LibraryPage(vm: PlayerViewModel, media: List<MediaEntity>, collections: List<CollectionEntity>, grant: () -> Unit, choose: () -> Unit, notify: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Keep IDs across page/activity restoration, but never offer actions for missing files.
    // An initial empty Room emission must not erase a restored selection.
    val availableIds = remember(media) { media.filter { it.available }.mapTo(hashSetOf()) { it.id } }
    val selected = chosen.filter { it in availableIds }
    val actions = remember { MediaActionState() }
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val permission by vm.permission.collectAsStateWithLifecycle()
    val visible = remember(media, query, filter) { media.filter { it.available && it.title.contains(query, true) && (filter == 0 || (filter == 2) == it.isVideo) } }
    BackHandler(selecting) { selecting = false; chosen = emptyList() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("媒体库", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { selecting = !selecting; chosen = emptyList() }) { Text(if (selecting) "完成" else "选择") }
            IconButton(onClick = choose) { Icon(QuietIcons.Add, "导入文件") }
            IconButton(onClick = vm::refresh) { Icon(QuietIcons.Refresh, "刷新") }
        }
        SearchField(query) { query = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("全部", "音频", "视频").forEachIndexed { i, label -> FilterChip(filter == i, { filter = i }, label = { Text(label) }) } }
        if (!permission.audio || !permission.video) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (permission.partial) "仅显示已授权的视频" else "允许访问本地音视频", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = grant) { Text(if (permission.partial) "管理" else "授权") }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        AnimatedVisibility(selecting, enter = fadeIn(tween(150)) + expandVertically(tween(150)), exit = fadeOut(tween(100)) + shrinkVertically(tween(150))) {
            SelectionActions(selected, actions, { vm.play(selected) }, { chosen = SelectionOrder.selectAll(selected, visible.map { it.id }) })
        }
        if (visible.isEmpty()) EmptyState(QuietIcons.LibraryMusic, if (query.isBlank()) "还没有文件" else "没有找到", "") { if (query.isBlank()) TextButton(onClick = choose) { Text("导入文件") } }
        LazyVerticalGrid(GridCells.Fixed(3), modifier = Modifier.weight(1f).testTag("library-grid"), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(visible, key = { it.id }) { item ->
                MediaRow(item, selecting, selected.indexOf(item.id) + 1,
                    onSelect = { chosen = SelectionOrder.toggle(selected, item.id) },
                    onPlay = { vm.playStartingAt(visible.map { it.id }, item.id) },
                    onAdd = { actions.addIds = listOf(item.id) }, onRename = { actions.renameIds = listOf(item.id) },
                    onExtract = { actions.export = ExportRequest(listOf(item.id), false) },
                    onLongPress = { selecting = true; if (item.id !in selected) chosen = selected + item.id }, modifier = Modifier.animateItem())
            }
        }
    }
    MediaActionDialogs(vm, actions, media, collections, notify)
}

@Composable internal fun CollectionsPage(vm: PlayerViewModel, media: List<MediaEntity>, collections: List<CollectionEntity>, notify: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // AnimatedContent's composition path changes between navigating into a collection
    // and restoring directly into it. Give each detail page a stable saveable registry,
    // which also survives the temporary empty result before Room resubscribes.
    val detailStates = rememberSaveableStateHolder()
    var creating by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<CollectionEntity?>(null) }
    var delete by remember { mutableStateOf<CollectionEntity?>(null) }
    val current = collections.find { it.id == selected }
    BackHandler(current != null) { selected = null }
    AnimatedContent(current?.id, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "collection-detail") { destination ->
    val shown = collections.find { it.id == destination }
    if (shown == null) Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("集合", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { creating = true }) { Icon(QuietIcons.Add, null, Modifier.size(18.dp)); Text("新建") }
        }
        if (collections.isEmpty()) EmptyState(QuietIcons.QueueMusic, "还没有集合", "") { TextButton(onClick = { creating = true }) { Text("创建集合") } }
        LazyColumn(contentPadding = PaddingValues(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(collections, key = { it.id }) { collection ->
                var menu by remember { mutableStateOf(false) }
                val entries by remember(collection.id) { vm.graph.dao.observeEntries(collection.id) }.collectAsStateWithLifecycle(emptyList())
                val cover = entries.firstOrNull()
                Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("collection-${collection.id}").combinedClickable(onClick = { selected = collection.id }, onLongClick = { menu = true }, onLongClickLabel = "编辑集合")) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        MediaArtwork(cover?.uri, cover?.isVideo ?: false, Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)), cover?.available ?: true)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(collection.name, maxLines = 2); Text("${entries.size} 项", color = Muted, style = MaterialTheme.typography.bodySmall) }
                        IconButton(onClick = { vm.playCollection(collection.id) }) { Icon(QuietIcons.PlayArrow, "播放${collection.name}") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(QuietIcons.MoreVert, "集合选项") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; rename = collection })
                                DropdownMenuItem(text = { Text("删除集合") }, onClick = { menu = false; delete = collection })
                            }
                        }
                    }
                }
            }
        }
    } else detailStates.SaveableStateProvider(shown.id) {
        CollectionDetail(vm, shown, media, collections, notify, { selected = null }, { rename = shown })
    }
    }
    if (creating) NameDialog("创建集合", "", { creating = false }) { vm.createCollection(it); creating = false }
    rename?.let { item -> NameDialog("重命名集合", item.name, { rename = null }) { vm.renameCollection(item, it); rename = null } }
    delete?.let { item -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除「${item.name}」？") }, text = { Text("文件会保留，关联的定时播放会关闭。") }, confirmButton = { TextButton(onClick = { vm.deleteCollection(item.id); delete = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } }) }
}

@Composable private fun CollectionDetail(vm: PlayerViewModel, collection: CollectionEntity, media: List<MediaEntity>, collections: List<CollectionEntity>, notify: () -> Unit, back: () -> Unit, renameCollection: () -> Unit) {
    val entries by remember(collection.id) { vm.graph.dao.observeEntries(collection.id) }.collectAsStateWithLifecycle(emptyList())
    var editing by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    val entryIds = remember(entries) { entries.mapTo(hashSetOf()) { it.entry.id } }
    val selected = chosen.filter { it in entryIds }
    var adding by rememberSaveable { mutableStateOf(false) }
    val actions = remember { MediaActionState() }
    val entryLookup = remember(entries) { entries.associateBy { it.entry.id } }
    val selectedEntries = selected.mapNotNull { entryLookup[it] }
    val selectedIds = selectedEntries.map { it.entry.mediaId }
    BackHandler(editing) { editing = false; chosen = emptyList() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(QuietIcons.ArrowBack, "返回集合") }
            Text(collection.name, Modifier.weight(1f).clickable(onClick = renameCollection), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
            if (editing) IconButton(onClick = { adding = true }) { Icon(QuietIcons.Add, "添加文件") }
            TextButton(onClick = { editing = !editing; chosen = emptyList() }) { Text(if (editing) "完成" else "编辑") }
        }
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.CenterStart) {
            if (editing) SelectionActions(selectedIds, actions, { vm.play(selectedIds) }, { chosen = SelectionOrder.selectAll(selected, entries.map { it.entry.id }) },
                remove = { vm.removeEntries(selected); chosen = emptyList() },
                order = { vm.applyEntryOrder(collection.id, selected); chosen = emptyList() })
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.play(entries.map { it.entry.mediaId }) }, enabled = entries.isNotEmpty()) { Icon(QuietIcons.PlayArrow, null); Text("播放全部") }
                OutlinedButton(onClick = { adding = true }) { Icon(QuietIcons.Add, null, Modifier.size(18.dp)); Text("添加文件") }
            }
        }
        if (entries.isEmpty()) EmptyState(QuietIcons.QueueMusic, "集合为空", "")
        ReorderableMediaGrid(entries, Modifier.weight(1f),
            onLongPress = { id -> editing = true; if (id !in selected) chosen = selected + id },
            persist = { vm.reorderEntries(collection.id, it) }, onError = { vm.message.value = "排序未保存，请重试" }) { entry, motion ->
            val item = MediaEntity(entry.entry.mediaId, entry.uri, entry.title, entry.durationMs, entry.isVideo, entry.available)
            MediaRow(item, editing, selected.indexOf(entry.entry.id) + 1,
                { chosen = SelectionOrder.toggle(selected, entry.entry.id) },
                { vm.playStartingAt(entries.map { it.entry.mediaId }, item.id) },
                { actions.addIds = listOf(item.id) }, { actions.renameIds = listOf(item.id) },
                { actions.export = ExportRequest(listOf(item.id), false) },
                onRemove = { vm.removeEntries(listOf(entry.entry.id)) }, modifier = motion)
        }
    }
    if (adding) AddFilesSheet(media, entries.map { it.entry.mediaId }.toSet(), { adding = false }) { vm.addToCollection(collection.id, it); adding = false }
    MediaActionDialogs(vm, actions, media, collections.filter { it.id != collection.id }, notify)
}

@Composable private fun MediaRow(item: MediaEntity, selecting: Boolean, selectedNumber: Int, onSelect: () -> Unit, onPlay: () -> Unit, onAdd: () -> Unit, onRename: () -> Unit, onExtract: () -> Unit, onRemove: (() -> Unit)? = null, onLongPress: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    MediaTile(item, selecting, selectedNumber, { if (selecting) onSelect() else onPlay() },
        onLongPress?.let { action -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); action() } }, modifier,
        menu = {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(QuietIcons.MoreVert, "${item.title}的选项", Modifier.size(20.dp)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("播放") }, onClick = { menu = false; onPlay() })
                DropdownMenuItem(text = { Text("加入集合") }, onClick = { menu = false; onAdd() })
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(if (item.isVideo) "提取音频" else "导出音频") }, onClick = { menu = false; onExtract() })
                if (onRemove != null) DropdownMenuItem(text = { Text("移出集合") }, onClick = { menu = false; onRemove() })
            }
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AddFilesSheet(media: List<MediaEntity>, existing: Set<String>, dismiss: () -> Unit, add: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    val availableIds = remember(media, existing) { media.filter { it.available && it.id !in existing }.mapTo(hashSetOf()) { it.id } }
    val selected = chosen.filter { it in availableIds }
    var submitted by remember { mutableStateOf(false) }
    val visible = remember(media, query) { media.filter { it.available && it.title.contains(query, true) } }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Ink) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("添加到集合", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton(onClick = { if (!submitted && selected.isNotEmpty()) { submitted = true; add(selected.toList()) } }, enabled = selected.isNotEmpty() && !submitted) { Text("添加 (${selected.size})") } }
            SearchField(query) { query = it }
            LazyVerticalGrid(GridCells.Fixed(3), modifier = Modifier.weight(1f).testTag("add-files-grid"), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible, key = { it.id }) { item ->
                    val toggle = { if (item.id !in existing) chosen = SelectionOrder.toggle(selected, item.id) }
                    MediaTile(item, true, selected.indexOf(item.id) + 1, toggle,
                        { if (item.id !in existing && item.id !in selected) chosen = selected + item.id }, Modifier.animateItem(), alreadyAdded = item.id in existing)
                }
            }
        }
    }
}

@Composable private fun CollectionPicker(items: List<CollectionEntity>, create: (String) -> Unit, choose: (String) -> Unit, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("加入集合") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        items.forEach { item -> TextButton(onClick = { if (!submitted) { submitted = true; choose(item.id) } }, enabled = !submitted, modifier = Modifier.fillMaxWidth()) { Text(item.name) } }
        OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("新集合名称") })
        TextButton(onClick = { if (!submitted && name.isNotBlank()) { submitted = true; create(name) } }, enabled = name.isNotBlank() && !submitted) { Text("创建集合") }
    } }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}

@Composable private fun SearchField(query: String, change: (String) -> Unit) {
    OutlinedTextField(query, change, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true, placeholder = { Text("搜索") }, leadingIcon = { Icon(QuietIcons.Search, null) }, shape = RoundedCornerShape(14.dp))
}

internal data class ExportRequest(val ids: List<String>, val concatenate: Boolean)
internal class MediaActionState {
    var addIds by mutableStateOf<List<String>?>(null)
    var renameIds by mutableStateOf<List<String>?>(null)
    var export by mutableStateOf<ExportRequest?>(null)
}

@Composable private fun SelectionActions(ids: List<String>, actions: MediaActionState, play: () -> Unit, all: () -> Unit, remove: (() -> Unit)? = null, order: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("已选 ${ids.size}", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = all) { Text("全选") }
        TextButton(onClick = play, enabled = ids.isNotEmpty()) { Text("播放") }
        TextButton(onClick = { actions.export = ExportRequest(ids, true) }, enabled = ids.size >= 2) { Text("拼接音频") }
        Box {
            IconButton(onClick = { menu = true }, enabled = ids.isNotEmpty()) { Icon(QuietIcons.MoreVert, "批量操作") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("加入集合") }, onClick = { menu = false; actions.addIds = ids })
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; actions.renameIds = ids })
                DropdownMenuItem(text = { Text("分别导出音频") }, onClick = { menu = false; actions.export = ExportRequest(ids, false) })
                if (order != null) DropdownMenuItem(text = { Text("按所选顺序移到最前") }, onClick = { menu = false; order() })
                if (remove != null) DropdownMenuItem(text = { Text("移出集合") }, onClick = { menu = false; remove() })
            }
        }
    }
}

@Composable internal fun SelectionBadge(number: Int, label: String, click: () -> Unit) {
    Box(Modifier.size(44.dp).semantics { contentDescription = label; stateDescription = if (number > 0) "已选第${number}项" else "未选择" }.clickable(onClick = click), contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (number > 0) Accent else Panel).border(1.dp, if (number > 0) Accent else Muted, CircleShape), contentAlignment = Alignment.Center) {
            if (number > 0) Text(number.toString(), color = Ink, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable internal fun MediaActionDialogs(vm: PlayerViewModel, actions: MediaActionState, media: List<MediaEntity>, collections: List<CollectionEntity>, notify: () -> Unit) {
    actions.addIds?.let { ids -> CollectionPicker(collections,
        { vm.createCollectionWithMedia(it, ids); actions.addIds = null },
        { vm.addToCollection(it, ids); actions.addIds = null }, { actions.addIds = null }) }
    actions.renameIds?.let { ids -> RenameMediaDialog(media, ids, { actions.renameIds = null }) { names -> vm.renameMediaBatch(names); actions.renameIds = null } }
    actions.export?.let { request -> ExportDialog(vm, media, request.ids, request.concatenate, { actions.export = null }, { ordered, names, mode ->
        notify(); vm.exportBatch(ordered, names, request.concatenate, mode); actions.export = null
    }, { vm.play(listOf(it)) }) }
}

@Composable private fun RenameMediaDialog(media: List<MediaEntity>, ids: List<String>, dismiss: () -> Unit, save: (Map<String, String>) -> Unit) {
    val unique = remember(ids) { ids.distinct() }
    var names by remember(ids) { mutableStateOf(unique.associateWith { id -> media.find { it.id == id }?.title.orEmpty() }) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (unique.size == 1) "重命名" else "批量重命名") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            unique.forEachIndexed { index, id -> OutlinedTextField(names[id].orEmpty(), { value -> names = names + (id to value.take(80)) }, label = { Text(if (unique.size == 1) "名称（仅在枕波中）" else "${index + 1}. 名称") }, singleLine = true) }
        }
    }, confirmButton = { TextButton(onClick = { save(names) }, enabled = names.values.all { it.isNotBlank() }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

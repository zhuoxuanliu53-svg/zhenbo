package app.jingting.localplayer.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jingting.localplayer.core.*
import app.jingting.localplayer.BuildConfig
import app.jingting.localplayer.data.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter

internal val Accent = NightPalette.Accent
internal val Ink = NightPalette.Ink
internal val Panel = NightPalette.Panel
internal val Muted = NightPalette.Muted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JingTingScreen(vm: PlayerViewModel = viewModel()) {
    NightTheme {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var expanded by rememberSaveable { mutableStateOf(false) }
        var page by rememberSaveable { mutableStateOf<String?>(null) }
        var taskEditor by remember { mutableStateOf(false) }
        val mediaActions = remember { MediaActionState() }
        val pageStates = rememberSaveableStateHolder()
        BackHandler(page != null) { page = null }
        val media by vm.media.collectAsStateWithLifecycle()
        val collections by vm.collections.collectAsStateWithLifecycle()
        val player by vm.playback.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val editPlaying = { player.ids.getOrNull(player.index)?.let { mediaActions.renameIds = listOf(it) }; Unit }
        val snack = remember { SnackbarHostState() }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val files = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) result.data?.let { data ->
                val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
                vm.import(uris, data.flags)
            }
        }
        val askNotifications: () -> Unit = { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        val chooseFile: () -> Unit = {
            files.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*")); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); putExtra(Intent.EXTRA_LOCAL_ONLY, true); addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) })
        }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.resume() }
            lifecycle.lifecycle.addObserver(observer); vm.resume()
            onDispose { lifecycle.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.message.value = null } }
        LaunchedEffect(player.message) { player.message?.let { snack.showSnackbar(it); vm.graph.playback.clearMessage() } }
        Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snack) }, topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page != null) IconButton(onClick = { page = null }) { Icon(QuietIcons.ArrowBack, "返回") }
                Text(when (page) { "tasks" -> "定时与导出"; "settings" -> "设置"; else -> "枕波" }, modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                if (page == null) {
                    IconButton(onClick = { page = "tasks" }) { Icon(QuietIcons.Schedule, "定时与导出") }
                    IconButton(onClick = { page = "settings" }) { Icon(QuietIcons.Settings, "设置") }
                }
            }
        }, bottomBar = {
            Column {
                if (player.ids.isNotEmpty()) MiniPlayer(player, media.find { it.id == player.ids.getOrNull(player.index) }, { expanded = true }, { vm.action { vm.graph.playback.toggle() } }, editPlaying)
                if (page == null) NavigationBar(containerColor = Ink) {
                    listOf("媒体库" to QuietIcons.LibraryMusic, "集合" to QuietIcons.QueueMusic).forEachIndexed { i, (label, icon) -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) }) }
                }
            }
        }) { padding ->
            AnimatedContent(page ?: "tab-$tab", modifier = Modifier.padding(padding).fillMaxSize(), transitionSpec = { (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 35 }) togetherWith fadeOut(tween(120)) }, label = "page") { destination ->
                when (destination) {
                    "tasks" -> TasksPage(vm, collections, askNotifications)
                    "settings" -> SettingsPage(vm, { permissions.launch(vm.graph.media.requestedPermissions()) })
                    else -> pageStates.SaveableStateProvider(destination) {
                        if (destination == "tab-0") LibraryPage(vm, media, collections, { permissions.launch(vm.graph.media.requestedPermissions()) }, chooseFile, askNotifications)
                        else CollectionsPage(vm, media, collections, askNotifications)
                    }
                }
            }
        }
        if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Panel) { FullPlayer(vm, player, media.find { it.id == player.ids.getOrNull(player.index) }, editPlaying) { taskEditor = true } }
        if (taskEditor) TaskEditor(vm, collections, initialKind = "countdown", dismiss = { taskEditor = false }, notify = askNotifications)
        MediaActionDialogs(vm, mediaActions, media, collections, askNotifications)
    }
}

@Composable internal fun SectionTitle(title: String, subtitle: String? = null) { Column(Modifier.padding(bottom = 16.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium); if (subtitle != null) Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) } }
@Composable internal fun EmptyState(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(icon, null, tint = Accent, modifier = Modifier.size(52.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Text(text, color = Muted); action?.invoke() }
}
internal fun duration(ms: Long): String { val total = ms / 1000; return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60) else "%d:%02d".format(total / 60, total % 60) }

@Composable internal fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var submitted by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text(if (title == "重命名") "名称（仅在枕波中）" else "名称") })
    }, confirmButton = { TextButton(onClick = { if (!submitted && name.isNotBlank()) { submitted = true; save(name) } }, enabled = name.isNotBlank() && !submitted) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun MiniPlayer(p: PlaybackState, media: MediaEntity?, expand: () -> Unit, toggle: () -> Unit, edit: () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().combinedClickable(onClick = expand, onLongClick = edit, onLongClickLabel = "重命名")) { Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { MediaArtwork(media?.uri, media?.isVideo ?: false, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), media?.available ?: false); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium); Text(if (p.preparing) "准备播放…" else "${duration(p.positionMs)} · ${p.mode.label}", color = Muted, style = MaterialTheme.typography.labelSmall) }; IconButton(onClick = toggle) { Icon(if (p.isPlaying) QuietIcons.Pause else QuietIcons.PlayArrow, if (p.isPlaying) "暂停" else "继续播放", tint = Accent) } } }
}

@Composable private fun FullPlayer(vm: PlayerViewModel, p: PlaybackState, media: MediaEntity?, edit: () -> Unit, openTimer: () -> Unit) {
    var modeDialog by remember { mutableStateOf(false) }
    // A track may end while a finger is down. Never seek the next track with the old drag.
    var dragged by remember(p.ids.getOrNull(p.index)) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MediaArtwork(media?.uri, media?.isVideo ?: false, Modifier.padding(vertical = 16.dp).size(200.dp).clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = {}, onLongClick = edit, onLongClickLabel = "重命名"), media?.available ?: false)
        Text(p.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        SubtitleControls(vm)
        Text("${p.index + 1} / ${p.ids.size}", color = Muted, modifier = Modifier.padding(top = 8.dp))
        Slider(value = (dragged ?: p.positionMs.toFloat()).coerceIn(0f, p.durationMs.coerceAtLeast(1).toFloat()), onValueChange = { dragged = it }, onValueChangeFinished = { dragged?.let { vm.action { vm.graph.playback.seek(it.toLong()) } }; dragged = null }, valueRange = 0f..p.durationMs.coerceAtLeast(1).toFloat(), enabled = p.durationMs > 0, modifier = Modifier.padding(top = 16.dp).semantics { contentDescription = "播放进度" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(duration(dragged?.toLong() ?: p.positionMs), color = Muted); Text(duration(p.durationMs), color = Muted) }
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.action { vm.graph.playback.skip(false) } }) { Icon(QuietIcons.SkipPrevious, "上一项") }
            IconButton(onClick = { vm.action { vm.graph.playback.jump(-10_000) } }) { Icon(QuietIcons.Replay10, "后退10秒") }
            FilledIconButton(onClick = { vm.action { vm.graph.playback.toggle() } }, modifier = Modifier.size(76.dp)) { Icon(if (p.isPlaying) QuietIcons.Pause else QuietIcons.PlayArrow, if (p.isPlaying) "暂停" else "播放", Modifier.size(40.dp)) }
            IconButton(onClick = { vm.action { vm.graph.playback.jump(10_000) } }) { Icon(QuietIcons.Forward10, "前进10秒") }
            IconButton(onClick = { vm.action { vm.graph.playback.skip(true) } }) { Icon(QuietIcons.SkipNext, "下一项") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { modeDialog = true }) { Icon(if (p.mode == PlaybackMode.SHUFFLE) QuietIcons.Shuffle else QuietIcons.Repeat, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(p.mode.label) }
            TextButton(onClick = openTimer) { Icon(QuietIcons.Bedtime, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (p.sleepDeadline > 0) "${((p.sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 59_999) / 60_000} 分钟后停止" else "计时任务") }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (modeDialog) ModePicker(p.mode, { modeDialog = false }) { mode -> vm.action { vm.graph.playback.setMode(mode) }; modeDialog = false }
}

@Composable private fun ModePicker(selected: PlaybackMode, dismiss: () -> Unit, choose: (PlaybackMode) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("播放方式") }, text = { Column {
        PlaybackMode.entries.forEach { mode -> Row(Modifier.fillMaxWidth().clickable { choose(mode) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == mode, onClick = { choose(mode) })
            Column { Text(mode.label); Text(mode.description, style = MaterialTheme.typography.bodySmall, color = Muted) }
        } }
    } }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}

@Composable private fun SettingsPage(vm: PlayerViewModel, grant: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsRow("媒体访问权限", "", grant)
        SettingsRow("后台播放", "系统设置") { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        SettingsRow("导出文件", "Music/静听/") { info = "导出文件" }
        SettingsRow("隐私", "仅在本机") { info = "隐私" }
        Text("枕波 ${BuildConfig.VERSION_NAME}", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 24.dp))
    }
    info?.let { title -> AlertDialog(onDismissRequest = { info = null }, title = { Text(title) }, text = { Text(if (title == "隐私") "没有账号、广告或数据上传，不申请联网权限。集合和播放记录仅保存在本机。" else "音频保存为 M4A 或 WAV，原文件不变。卸载后导出文件仍保留；重装并授权后可重新扫描。再次导出会生成新文件，不覆盖旧文件。集合和定时计划随卸载清除。") }, confirmButton = { TextButton(onClick = { info = null }) { Text("知道了") } }) }
}

@Composable private fun SettingsRow(title: String, value: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f)); Text(value, color = Muted, style = MaterialTheme.typography.bodySmall); Icon(QuietIcons.ChevronRight, null, tint = Muted)
    }
}

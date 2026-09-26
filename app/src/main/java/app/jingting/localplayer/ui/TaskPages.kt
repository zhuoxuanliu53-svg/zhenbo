package app.jingting.localplayer.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jingting.localplayer.core.*
import app.jingting.localplayer.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.*

@Composable internal fun TasksPage(vm: PlayerViewModel, collections: List<CollectionEntity>, notify: () -> Unit) {
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val exports by vm.exports.collectAsStateWithLifecycle()
    val timer by vm.timer.collectAsStateWithLifecycle()
    val allowed by vm.exactAllowed.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<ScheduleEntity?>(null) }
    var kind by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TaskSection("计时任务", { kind = "countdown" }) }
        item {
            val task = timer
            if (task == null) Text("从现在播放，到时停止", color = Muted, style = MaterialTheme.typography.bodySmall)
            else Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.combinedClickable(onClick = { kind = "countdown" }, onLongClick = { kind = "countdown" }, onLongClickLabel = "编辑计时任务")) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(if (task.active) duration(task.remainingMs(now)) else task.result, style = if (task.active) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium)
                Text("起始来源：${task.sourceTitle} · ${task.mode.label}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { kind = "countdown" }) { Text(if (task.active) "编辑" else "再次计时") }
                    if (task.active) TextButton(onClick = { vm.action { vm.graph.timers.cancel() } }) { Text("取消计时") }
                }
            } }
        }
        item { TaskSection("定时任务", { kind = "clock" }) }
        if (!allowed) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("定时开始需要闹钟权限", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("开启") }
            }
        }
        if (schedules.isEmpty()) item { Text("在指定时间开始播放", color = Muted, style = MaterialTheme.typography.bodySmall) }
        items(schedules, key = { it.id }) { s ->
            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.combinedClickable(onClick = { edit = s; kind = "clock" }, onLongClick = { edit = s; kind = "clock" }, onLongClickLabel = "编辑定时任务")) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).combinedClickable(onClick = { edit = s; kind = "clock" }, onLongClick = { edit = s; kind = "clock" })) {
                        Text("%02d:%02d".format(s.hour, s.minute), style = MaterialTheme.typography.headlineMedium)
                        Text(if (s.collectionId.isBlank()) "已选队列" else collections.find { it.id == s.collectionId }?.name ?: "集合已删除", color = Accent)
                        Text(repeatLabel(s), color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text("${PlaybackMode.valueOf(s.mode).label} · ${if (s.stopMinutes > 0) "${s.stopMinutes} 分钟" else "不限时"}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(s.enabled, { vm.action { vm.graph.schedules.enable(s, it) } })
                }
                Text(if (s.enabled && !allowed) "未启用：需要闹钟权限" else s.lastResult, color = Muted, style = MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick = { edit = s; kind = "clock" }) { Text("编辑") }; TextButton(onClick = { vm.action { vm.graph.schedules.remove(s.id) } }) { Text("删除") } }
            } }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("音频导出", style = MaterialTheme.typography.titleMedium) }
        if (exports.isEmpty()) item { Text("还没有导出记录", color = Muted) }
        items(exports, key = { it.id }) { e -> Surface(color = Panel, shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp)) {
            Text(e.title, maxLines = 2)
            val active = e.state in setOf("queued", "running", "publishing")
            Text(when (e.state) { "queued" -> "等待导出"; "running" -> "${e.phase.ifBlank { "处理中" }} ${e.progress}%"; "publishing" -> "正在保存"; "completed" -> if (e.subtitleOptions != null) "音频和字幕已保存" else "已保存至 Music/静听/"; "cancelled" -> "已取消"; else -> e.error ?: "导出失败" }, color = if (e.state == "completed") Accent else Muted, style = MaterialTheme.typography.bodySmall)
            if (e.state == "completed" && e.strategy != null) Text("${exportStrategyLabel(e.strategy)} · ${"%.1f".format(e.elapsedMs / 1000.0)} 秒", color = Muted, style = MaterialTheme.typography.labelSmall)
            if (!active && e.subtitleOptions != null && e.state in setOf("failed", "cancelled")) TextButton(onClick = { vm.action { vm.graph.exports.retrySubtitles(e.id) } }) { Text(if(e.outputUri != null) "重试字幕" else "重试") }
            if (active) { LinearProgressIndicator(progress = { e.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)); TextButton(onClick = { vm.graph.exports.cancel(e.id) }) { Text("取消任务") } }
        } } }
    }
    kind?.let { type -> TaskEditor(vm, collections, initialKind = type, initial = edit, dismiss = { kind = null; edit = null }, notify = notify) }
}

@Composable private fun TaskSection(title: String, add: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = add) { Icon(if (title == "计时任务") QuietIcons.Timer else QuietIcons.Schedule, "新建$title") }
    }
}

@Composable internal fun TaskEditor(vm: PlayerViewModel, collections: List<CollectionEntity>, initialKind: String, dismiss: () -> Unit, initial: ScheduleEntity? = null, notify: () -> Unit = {}) {
    val timer by vm.timer.collectAsStateWithLifecycle()
    val player by vm.playback.collectAsStateWithLifecycle()
    val active = remember { timer?.takeIf { it.active } }
    val later = remember { LocalDateTime.now().plusHours(1) }
    var kind by remember { mutableStateOf(initialKind) }
    var source by remember { mutableStateOf(initial?.collectionId ?: if (initialKind == "countdown") active?.collectionId.orEmpty() else "") }
    var sourceMenu by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(initial?.queueIds?.let(::decodeQueue).takeUnless { it.isNullOrEmpty() } ?: player.ids) }
    var mode by remember { mutableStateOf(initial?.mode?.let(PlaybackMode::valueOf) ?: player.mode) }
    var modeMenu by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf((initial?.stopMinutes ?: active?.let { ((it.remainingMs(SystemClock.elapsedRealtime()) + 59_999) / 60_000).coerceAtLeast(1).toInt() } ?: 30).toString()) }
    var hour by remember { mutableStateOf((initial?.hour ?: later.hour).toString()) }
    var minute by remember { mutableStateOf((initial?.minute ?: later.minute).toString()) }
    var days by remember { mutableIntStateOf(initial?.days ?: 0) }
    var date by remember { mutableStateOf(initial?.date ?: later.toLocalDate().toString()) }
    var conflict by remember { mutableStateOf(initial?.conflict ?: ConflictPolicy.SKIP.name) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentAvailable = if (kind == "clock") queue.isNotEmpty() else player.ids.isNotEmpty()
    val sourceValid = if (source.isBlank()) currentAvailable else collections.any { it.id == source }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("播放任务") }, text = {
        Column(Modifier.heightIn(max = 490.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(kind == "clock", { kind = "clock"; error = null }, label = { Text("定时任务") }, leadingIcon = { Icon(QuietIcons.Schedule, null, Modifier.size(18.dp)) })
                FilterChip(kind == "countdown", { kind = "countdown"; if (minutes == "0") minutes = "30"; error = null }, label = { Text("计时任务") }, leadingIcon = { Icon(QuietIcons.Timer, null, Modifier.size(18.dp)) })
            }
            Text(if (kind == "clock") "指定时间开始" else "现在播放，计时结束后停止", color = Muted, style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { sourceMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(if (source.isBlank()) if (kind == "clock" && initial?.queueIds != null && initial.queueIds != "[]") "已选队列" else "当前队列" else collections.find { it.id == source }?.name ?: "选择集合"); Spacer(Modifier.weight(1f)); Icon(QuietIcons.ChevronRight, null) }
                DropdownMenu(sourceMenu, { sourceMenu = false }, modifier = Modifier.heightIn(max = 260.dp)) {
                    DropdownMenuItem(text = { Text("当前队列（${player.ids.size} 项）") }, enabled = player.ids.isNotEmpty(), onClick = { source = ""; queue = player.ids; sourceMenu = false })
                    collections.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { source = c.id; sourceMenu = false }) }
                }
            }
            if (!sourceValid) Text("先播放文件，或选择一个集合", color = Muted, style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { modeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(mode.label); Spacer(Modifier.weight(1f)); Icon(QuietIcons.ChevronRight, null) }
                DropdownMenu(modeMenu, { modeMenu = false }) { PlaybackMode.entries.forEach { m -> DropdownMenuItem(text = { Text(m.label) }, onClick = { mode = m; modeMenu = false }) } }
            }
            if (kind == "clock") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f).testTag("task-hour"), label = { Text("时") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f).testTag("task-minute"), label = { Text("分") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) { listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label -> FilterChip(days and (1 shl index) != 0, { days = days xor (1 shl index) }, label = { Text(label) }) } }
                if (days == 0) OutlinedTextField(date, { date = it.take(10) }, label = { Text("仅一次 · 日期 YYYY-MM-DD") }, singleLine = true)
            } else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(15, 30, 60).forEach { value -> FilterChip(minutes == value.toString(), { minutes = value.toString() }, label = { Text("$value 分钟") }) } }
            OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text(if (kind == "clock") "播放分钟数（0 为不限时）" else "剩余分钟数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("task-duration"))
            if (kind == "clock") {
                Text("到点时正在播放", color = Muted, style = MaterialTheme.typography.bodySmall)
                ConflictPolicy.entries.forEach { policy -> Row(Modifier.fillMaxWidth().clickable { conflict = policy.name }, verticalAlignment = Alignment.CenterVertically) { RadioButton(conflict == policy.name, { conflict = policy.name }); Text(policy.label) } }
            }
            if (kind == "countdown" && active != null) TextButton(onClick = {
                if (!busy) {
                    busy = true
                    scope.launch(Dispatchers.Main.immediate) {
                        try { vm.graph.timers.cancel(); dismiss() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "未能取消计时，请重试" }
                        finally { busy = false }
                    }
                }
            }, enabled = !busy) { Text("取消计时") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = submit@{
        if (busy) return@submit
        val n = minutes.toIntOrNull()
        val h = hour.toIntOrNull()
        val m = minute.toIntOrNull()
        if (n == null || n !in (if (kind == "clock") 0..1440 else 1..1440)) { error = "请输入有效时长" }
        else if (kind == "clock" && (h !in 0..23 || m !in 0..59)) { error = "请输入有效时间" }
        // MediaController is confined to the main application thread, including after I/O.
        else {
            busy = true
            scope.launch(Dispatchers.Main.immediate) {
            try {
                notify()
                if (kind == "countdown") vm.graph.timers.startCountdown(source.ifBlank { null }, mode, n)
                else vm.graph.schedules.save((initial ?: ScheduleEntity(collectionId = source, hour = h!!, minute = m!!)).copy(collectionId = source, queueIds = if (source.isBlank()) JSONArray(queue).toString() else "[]", hour = h!!, minute = m!!, days = days, date = if (days == 0) date else null, mode = mode.name, stopMinutes = n, conflict = conflict, enabled = true))
                dismiss()
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error = e.message ?: "请检查任务内容" }
            catch (_: Exception) { error = "未能启用，请检查文件和权限" }
            finally { busy = false }
            }
        }
    }, enabled = sourceValid && !busy, modifier = Modifier.testTag("save-task")) { Text(if (busy) "正在设置" else if (kind == "clock") "保存任务" else if (active != null) "更新计时" else "开始计时") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}

private fun repeatLabel(s: ScheduleEntity) = if (s.days == 0) "仅一次 · ${s.date}" else "每周 " + (0..6).filter { s.days and (1 shl it) != 0 }.joinToString("、") { listOf("一", "二", "三", "四", "五", "六", "日")[it] }
private fun decodeQueue(value: String) = runCatching { val a = JSONArray(value); List(a.length()) { a.getString(it) } }.getOrDefault(emptyList())
private fun exportStrategyLabel(value: String) = when (value) { "COPY_AAC", "AAC_COPY", "REMUX" -> "原音质直接复制"; "PCM_WAV", "WAV" -> "原音质 WAV"; "AAC", "TRANSCODE_AAC", "ENCODE_AAC" -> "AAC 转换"; else -> value }

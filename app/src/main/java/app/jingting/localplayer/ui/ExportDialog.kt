package app.jingting.localplayer.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.jingting.localplayer.data.MediaEntity
import app.jingting.localplayer.export.ExportPreview
import kotlinx.coroutines.CancellationException

@Composable internal fun ExportDialog(vm: PlayerViewModel, media: List<MediaEntity>, ids: List<String>, concatenate: Boolean, dismiss: () -> Unit, save: (List<String>, List<String>, String, app.jingting.localplayer.subtitle.SubtitleExportOptions?) -> Unit, preview: (String) -> Unit) {
    var names by remember(ids) { mutableStateOf(if (concatenate) listOf("我的音频拼接") else ids.map { id -> media.find { it.id == id }?.title?.substringBeforeLast('.') ?: "音频" }) }
    var mode by remember { mutableStateOf("ORIGINAL") }
    var analysis by remember(ids) { mutableStateOf<List<ExportPreview>?>(null) }
    var error by remember(ids) { mutableStateOf<String?>(null) }
    var subtitles by remember { mutableStateOf<app.jingting.localplayer.subtitle.SubtitleExportOptions?>(null) }
    var submitted by remember(ids) { mutableStateOf(false) }
    LaunchedEffect(ids, concatenate) {
        try { analysis = if (concatenate) listOf(vm.graph.exports.preview(ids)) else ids.map { vm.graph.exports.preview(listOf(it)) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "部分文件没有音轨或无法读取，请重新选择" }
    }
    val original = analysis?.all { it.originalAvailable } == true
    val canExport = (subtitles == null || (subtitles!!.directory.isNotBlank() && subtitles!!.sources.any { it.mode != "NONE" })) && !submitted && ids.isNotEmpty() && analysis != null && error == null && names.all { it.isNotBlank() } && (mode == "AAC" || original)
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (concatenate) "拼接音频" else if (ids.size > 1) "分别导出音频" else "导出音频") }, text = {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (concatenate) OutlinedTextField(names.first(), { names = listOf(it.take(80)) }, label = { Text("文件名称") }, singleLine = true)
            ids.forEachIndexed { index, id -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", color = Accent, modifier = Modifier.width(24.dp))
                if (concatenate) Text(media.find { it.id == id }?.title ?: "媒体文件", Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                else OutlinedTextField(names[index], { value -> names = names.toMutableList().apply { this[index] = value.take(80) } }, label = { Text("文件名称") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { preview(id) }) { Icon(QuietIcons.PlayArrow, "试听第${index + 1}项") }
            } }
            if (analysis == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            ExportChoice("保留原音质", mode == "ORIGINAL", original, { mode = "ORIGINAL" })
            if (mode == "ORIGINAL" || !original) Text(analysis?.let { results ->
                if (original) results.map { it.originalDescription }.distinct().joinToString("；")
                else results.firstOrNull { !it.originalAvailable }?.reason ?: "这些文件需要转换后才能拼接"
            } ?: "正在检查音轨", color = Muted, style = MaterialTheme.typography.bodySmall)
            ExportChoice("兼容 M4A", mode == "AAC", analysis != null, { mode = "AAC" })
            if (mode == "AAC") Text("AAC 重新编码，体积较小；可能有音质损失。", color = Muted, style = MaterialTheme.typography.bodySmall)
            SubtitleExportEditor(vm, media, ids) { subtitles = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(if (subtitles == null) "保存到 Music/静听/" else "音频与字幕保存到所选文件夹", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }, confirmButton = { TextButton(onClick = { if (!submitted) { submitted = true; save(ids, names, mode, subtitles) } }, enabled = canExport, modifier = Modifier.testTag("confirm-export")) { Text("开始导出") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun ExportChoice(label: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = choose), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, choose, enabled = enabled)
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else Muted)
    }
}

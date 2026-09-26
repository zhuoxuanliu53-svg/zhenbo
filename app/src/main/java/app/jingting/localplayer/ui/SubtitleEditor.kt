package app.jingting.localplayer.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import app.jingting.localplayer.data.MediaEntity
import app.jingting.localplayer.subtitle.*
import kotlinx.coroutines.*

@Composable internal fun SubtitleExportEditor(vm: PlayerViewModel, media: List<MediaEntity>, ids: List<String>, changed: (SubtitleExportOptions?) -> Unit) {
    var enabled by remember { mutableStateOf(false) }
    var directory by remember { mutableStateOf(vm.graph.subtitles.directory) }
    var sources by remember(ids) { mutableStateOf(ids.map { SubtitleSource(it) }) }
    var editing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri != null) try { vm.graph.subtitles.authorizeDirectory(uri); directory = uri.toString(); error = null } catch (_: Exception) { error = "请选择可写入的本地文件夹" }
    }
    LaunchedEffect(enabled, directory, sources) { changed(if (enabled) SubtitleExportOptions(directory.orEmpty(), sources) else null) }
    Row { Checkbox(enabled, { enabled = it }); Text("同时导出字幕", Modifier.padding(top=12.dp)) }
    if(enabled) {
        ids.forEach { id ->
            val item = media.find { it.id == id }
            if(item?.isVideo == true) {
                var tracks by remember(id) { mutableStateOf<List<SubtitleTrack>?>(null) }
                var scanError by remember(id) { mutableStateOf(false) }
                LaunchedEffect(id) {
                    try {
                        tracks = vm.graph.subtitles.embedded.inspect(item.uri)
                        val supported = tracks!!.filter { it.supported }
                        if(supported.size == 1 && sources.single { it.mediaId == id }.mode == "NONE") sources = sources.map { if(it.mediaId == id) it.copy(mode="EMBEDDED", trackId=supported.single().id) else it }
                    } catch(e: CancellationException) { throw e } catch (_: Exception) { tracks = emptyList(); scanError = true }
                }
                val selected = sources.single { it.mediaId == id }
                Text(item.title, maxLines=2, style=MaterialTheme.typography.labelLarge)
                if(tracks == null) Text("检查字幕轨…", style=MaterialTheme.typography.bodySmall)
                if(scanError) Text("无法读取内置字幕，可尝试识别画面", style=MaterialTheme.typography.bodySmall)
                fun select(mode: String, track: Int = -1) { sources = sources.map { if(it.mediaId == id) it.copy(mode=mode, trackId=track) else it } }
                Row { TextButton(onClick={select("NONE")}) { Text(if(selected.mode=="NONE") "✓ 无字幕" else "无字幕") }; TextButton(onClick={select("OCR"); editing=id}) { Text(if(selected.mode=="OCR") "✓ 识别画面 · 调整" else "识别画面") } }
                tracks.orEmpty().forEach { track ->
                    TextButton(enabled=track.supported, onClick={select("EMBEDDED",track.id)}) {
                        Text("${if(selected.mode=="EMBEDDED" && selected.trackId==track.id) "✓ " else ""}${track.label ?: "字幕轨 ${track.id}"} · ${track.language ?: "未标注语言"}${if(!track.supported) " · 暂不支持" else ""}")
                    }
                }
            }
        }
        TextButton(onClick={picker.launch(directory?.let(Uri::parse))}) { Text(if(directory == null) "选择保存文件夹" else "更改音频与字幕保存文件夹") }
        error?.let { Text(it, color=MaterialTheme.colorScheme.error) }
    }
    editing?.let { id -> media.find { it.id==id }?.let { item ->
        OcrRegionDialog(vm, item, sources.single { it.mediaId==id }.region, {editing=null}) { region -> sources=sources.map { if(it.mediaId==id) it.copy(region=region) else it }; editing=null }
    } }
}

@Composable private fun OcrRegionDialog(vm: PlayerViewModel, media: MediaEntity, initial: SubtitleRegion, dismiss: () -> Unit, save: (SubtitleRegion) -> Unit) {
    var region by remember { mutableStateOf(initial) }
    var position by remember { mutableStateOf((media.durationMs / 3).toFloat()) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var requested by remember { mutableStateOf(position) }
    var preview by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(requested) {
        try { val next=VideoFrames.preview(vm.graph.context,media.uri,requested.toLong()*1000); frame=next }
        catch(e: CancellationException) {throw e} catch (_: Exception) {preview="无法读取此画面"}
    }
    val shown=frame
    DisposableEffect(shown) { onDispose { shown?.recycle() } }
    AlertDialog(onDismissRequest=dismiss, title={Text("框选字幕区域")}, text={Column {
        Text("拖动画面框选区域", style=MaterialTheme.typography.bodySmall)
        shown?.let { bitmap ->
            var size by remember { mutableStateOf(Size(1f,1f)) }; var start by remember { mutableStateOf(Offset.Zero) }
            Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height).onSizeChanged {size=Size(it.width.toFloat(),it.height.toFloat())}.pointerInput(size) {
                detectDragGestures(onDragStart={start=it}, onDrag={change,_ ->
                    change.consume()
                    val l=(minOf(start.x,change.position.x)/size.width).coerceIn(0f,.99f); val r=(maxOf(start.x,change.position.x)/size.width).coerceIn(l+.01f,1f)
                    val t=(minOf(start.y,change.position.y)/size.height).coerceIn(0f,.99f); val b=(maxOf(start.y,change.position.y)/size.height).coerceIn(t+.01f,1f)
                    region=SubtitleRegion(l,t,r,b)
                })
            }) {
                Image(bitmap.asImageBitmap(), "字幕预览", Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) { drawRect(Accent,Offset(region.left*this.size.width,region.top*this.size.height),Size((region.right-region.left)*this.size.width,(region.bottom-region.top)*this.size.height),style=Stroke(2.dp.toPx())) }
            }
        }
        Slider(position,{position=it},onValueChangeFinished={requested=position},valueRange=0f..media.durationMs.coerceAtLeast(1).toFloat())
        TextButton(enabled=shown!=null&&!busy,onClick={
            val bitmap=shown!!.copy(Bitmap.Config.ARGB_8888,false); val selected=region
            scope.launch { busy=true; try { preview=withContext(Dispatchers.IO) {val crop=VideoFrames.crop(bitmap,selected); try {OfflineOcr(vm.graph.context).use {it.recognize(crop)}.ifBlank {"未识别到文字"}} finally {if(crop!==bitmap)crop.recycle();bitmap.recycle()}} } catch(e:CancellationException){throw e} catch(_:Exception){preview="识别未完成"} finally{busy=false} }
        }) {Text(if(busy) "识别中…" else "识别当前画面")}
        Text(preview, style=MaterialTheme.typography.bodyMedium)
    }},confirmButton={TextButton(onClick={save(region)}){Text("使用此区域")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}

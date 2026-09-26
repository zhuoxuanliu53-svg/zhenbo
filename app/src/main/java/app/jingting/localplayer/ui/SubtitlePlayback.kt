package app.jingting.localplayer.ui

import android.app.KeyguardManager
import android.content.*
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.repeatOnLifecycle
import app.jingting.localplayer.*
import app.jingting.localplayer.subtitle.*
import kotlinx.coroutines.*

@Composable internal fun currentSubtitle(vm: PlayerViewModel): String {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val id=playback.ids.getOrNull(playback.index).orEmpty()
    val row by remember(id) {vm.graph.dao.observeSubtitle(id)}.collectAsStateWithLifecycle(initialValue=null)
    val cues=remember(row?.srt) {row?.srt?.let(SubRip::decode).orEmpty()}
    var text by remember(id) {mutableStateOf("")}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(id,cues,row?.offsetMs,lifecycle) {
        text=""
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val player=vm.graph.playback.connect()
            while(isActive) {
                text=if(player.currentMediaItem?.mediaId==id) SubRip.current(cues,(player.currentPosition-(row?.offsetMs?:0))*1000) else ""
                delay(100)
            }
        }
    }
    return text
}

@Composable internal fun SubtitleControls(vm: PlayerViewModel) {
    val context=LocalContext.current
    val playback by vm.playback.collectAsStateWithLifecycle()
    val id=playback.ids.getOrNull(playback.index)
    val row by remember(id) {vm.graph.dao.observeSubtitle(id.orEmpty())}.collectAsStateWithLifecycle(initialValue=null)
    var offsetDialog by remember {mutableStateOf(false)}
    var importTarget by remember {mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri!=null&&importTarget!=null) { val target=importTarget!!; vm.action {vm.graph.subtitles.importSrt(target,uri);vm.message.value="字幕已导入"} } }
    val directory=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri -> if(uri!=null) vm.action {vm.graph.subtitles.authorizeDirectory(uri); val count=vm.graph.subtitles.matchDirectory(uri);vm.message.value="已匹配 $count 份字幕；未匹配的文件可手动导入"} }
    val text=currentSubtitle(vm)
    if(text.isNotBlank()) Text(text, textAlign=TextAlign.Center, modifier=Modifier.fillMaxWidth().padding(vertical=12.dp))
    Row(horizontalArrangement=Arrangement.Center,modifier=Modifier.fillMaxWidth()) {
        TextButton(onClick={context.startActivity(Intent(context,PurePlaybackActivity::class.java))}){Text("纯播放页")}
        TextButton(enabled=id!=null,onClick={importTarget=id;picker.launch(arrayOf("application/x-subrip","text/*","application/octet-stream"))}){Text("导入字幕")}
        if(row!=null) TextButton(onClick={offsetDialog=true}){Text("校准")}
    }
    TextButton(onClick={directory.launch(vm.graph.subtitles.directory?.let(Uri::parse))}){Text("从文件夹匹配字幕")}
    if(offsetDialog&&id!=null) AlertDialog(onDismissRequest={offsetDialog=false},title={Text("字幕时间")},text={Column {
        Text("${row?.offsetMs?:0} 毫秒 · 正值为延后")
        Row { TextButton(onClick={vm.action{vm.graph.subtitles.setOffset(id,-250)}}){Text("提前 0.25 秒")}; TextButton(onClick={vm.action{vm.graph.subtitles.setOffset(id,250)}}){Text("延后 0.25 秒")} }
    }},confirmButton={TextButton(onClick={offsetDialog=false}){Text("完成")}})
}

class PurePlaybackActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setContent {
            val vm: PlayerViewModel=androidx.lifecycle.viewmodel.compose.viewModel()
            val playback by vm.playback.collectAsStateWithLifecycle()
            val subtitle=currentSubtitle(vm)
            var fontSize by remember {mutableFloatStateOf(28f)}
            var keepScreen by remember {mutableStateOf(false)}
            val lifecycle=LocalLifecycleOwner.current.lifecycle
            val lifecycleState by lifecycle.currentStateFlow.collectAsState()
            DisposableEffect(keepScreen,playback.isPlaying,lifecycleState) {
                if(keepScreen&&playback.isPlaying&&lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose {window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            }
            MaterialTheme(colorScheme=NightColorScheme) { Surface(Modifier.fillMaxSize(),color=NightPalette.Ink) { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={finish()}){Text("退出")}
                    Row(verticalAlignment=Alignment.CenterVertically){Text("保持亮屏",style=MaterialTheme.typography.labelMedium);Switch(keepScreen,{keepScreen=it})}
                }
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),contentAlignment=Alignment.Center) {
                    AnimatedContent(targetState=subtitle,transitionSpec={fadeIn() togetherWith fadeOut()},label="subtitle") {line -> Text(line.ifBlank {"♪"},color=NightPalette.Text,fontSize=fontSize.sp,lineHeight=(fontSize*1.5f).sp,textAlign=TextAlign.Center) }
                }
                Row(verticalAlignment=Alignment.CenterVertically){Text("字");Slider(fontSize,{fontSize=it},valueRange=20f..44f,modifier=Modifier.weight(1f));Text("字",fontSize=24.sp)}
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    IconButton(onClick={vm.action{vm.graph.playback.jump(-10000)}}){Icon(QuietIcons.Replay10,"后退10秒")}
                    FilledIconButton(onClick={vm.action{vm.graph.playback.toggle()}},modifier=Modifier.size(72.dp)){Icon(if(playback.isPlaying)QuietIcons.Pause else QuietIcons.PlayArrow,"播放或暂停",Modifier.size(40.dp))}
                    IconButton(onClick={vm.action{vm.graph.playback.jump(10000)}}){Icon(QuietIcons.Forward10,"前进10秒")}
                }
                Spacer(Modifier.height(20.dp))
            } } }
        }
    }
}

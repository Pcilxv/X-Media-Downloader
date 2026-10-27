package cn.xinyu.xmedia

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import cn.xinyu.xmedia.core.MediaItem
import cn.xinyu.xmedia.core.MediaKind
import cn.xinyu.xmedia.core.ResolvedPost
import cn.xinyu.xmedia.data.HistoryEntry
import cn.xinyu.xmedia.download.DownloadState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val Blue = Color(0xFF1D9BF0)
private val Dark = darkColorScheme(primary = Blue, background = Color.Black, surface = Color.Black,
    surfaceVariant = Color(0xFF15191C), onSurfaceVariant = Color(0xFF9AA5AF))
private val Light = lightColorScheme(primary = Blue, background = Color.White, surface = Color.White,
    surfaceVariant = Color(0xFFF3F6F8), onSurfaceVariant = Color(0xFF657786))

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppUi(activity: MainActivity, app: XMediaApp) {
    val dark = isSystemInDarkTheme()
    SideEffect {
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        insets.isAppearanceLightStatusBars = !dark
        insets.isAppearanceLightNavigationBars = !dark
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        val history by app.history.entries.collectAsState()
        val task by DownloadState.state.collectAsState()
        var page by remember { mutableIntStateOf(0) }
        var preview by remember { mutableStateOf<HistoryEntry?>(null) }
        LaunchedEffect(activity.showDownloadPageRequest) {
            if (activity.showDownloadPageRequest > 0) { page = 0; preview = null }
        }
        Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("X", fontSize = 28.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(12.dp))
                Text("媒体下载", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { activity.openAccount() }) { Icon(Icons.Default.AccountCircle, "X 账户") }
            }
        }, bottomBar = {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                val colors = NavigationBarItemDefaults.colors(selectedIconColor = Blue, selectedTextColor = Blue, indicatorColor = Blue.copy(alpha = .15f))
                NavigationBarItem(selected = page == 0, onClick = { page = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("首页") }, colors = colors)
                NavigationBarItem(selected = page == 1, onClick = { page = 1 }, icon = { Icon(Icons.Default.Collections, null) }, label = { Text("记录") }, colors = colors)
            }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (page == 0) Home(activity, history, task.message, task.running, task.percent, task.error, onAll = { page = 1 }, onOpen = { preview = it })
                else History(history, onOpen = { preview = it })
            }
        }
        activity.selection?.let { SelectionDialog(it, activity::dismissSelection, activity::queue) }
        if (activity.accountStatusVisible) AlertDialog(
            onDismissRequest = activity::dismissAccountStatus,
            title = { Text("登录信息已保存") },
            text = { Text("本机已保存 X 登录会话，可以直接下载。下载时会检查会话是否仍然有效；如果过期，应用会提示重新登录。") },
            confirmButton = { TextButton(onClick = activity::dismissAccountStatus) { Text("知道了") } },
            dismissButton = { TextButton(onClick = activity::launchLogin) { Text("重新登录") } },
        )
        preview?.let { entry -> PreviewDialog(entry, onClose = { preview = null }, capturing = activity.capturingFrame,
            onCapture = { positionMs -> activity.captureFrame(entry, positionMs) }) }
    }
}

@Composable private fun Home(activity: MainActivity, history: List<HistoryEntry>, task: String, running: Boolean,
                            percent: Int?, taskError: Boolean, onAll: () -> Unit, onOpen: (HistoryEntry) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(26.dp))
        Text("留住喜欢的内容", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text("从 X 分享帖子，或粘贴链接开始", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(30.dp))
        OutlinedTextField(value = activity.input, onValueChange = activity::updateInput, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("粘贴 X 帖子链接") }, singleLine = false, maxLines = 3,
            shape = RoundedCornerShape(14.dp), colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = Color.Transparent))
        Spacer(Modifier.height(12.dp))
        Button(onClick = { activity.resolve() }, enabled = !activity.loading, modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White)) {
            if (activity.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else { Text("开始下载", fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); Icon(Icons.Default.Download, null) }
        }
        if (activity.message.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(activity.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(28.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Share, null, tint = Blue); Spacer(Modifier.width(15.dp))
                Column { Text("从 X 分享更快捷", fontWeight = FontWeight.Bold); Spacer(Modifier.height(3.dp));
                    Text("点击分享 → 分享… → 媒体下载", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (task.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task, color = if (taskError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                        if (running) TextButton(onClick = activity::cancelDownload) { Text("取消") }
                    }
                    if (running) { Spacer(Modifier.height(8.dp)); if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("最近保存", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onAll) { Text("全部 ›") }
        }
        if (history.isEmpty()) {
            Text("下载完成后，内容会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 22.dp))
        } else history.take(3).forEach { entry ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(entry) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(entry.uri, entry.kind, Modifier.size(72.dp)); Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) { Text(entry.title.ifBlank { "X 媒体" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp)); Text(kindLabel(entry.kind) + if (entry.quality.isNotBlank()) " · ${entry.quality}" else "", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(26.dp))
    }
}

@Composable private fun History(history: List<HistoryEntry>, onOpen: (HistoryEntry) -> Unit) {
    var filter by remember { mutableIntStateOf(0) }
    val filtered = when (filter) { 1 -> history.filter { it.kind != MediaKind.VIDEO }; 2 -> history.filter { it.kind == MediaKind.VIDEO }; else -> history }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(26.dp))
        Text("下载记录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp)); Text("你的收藏，随时查看", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "图片", "视频").forEachIndexed { i, label ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) }, shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.onBackground,
                        selectedLabelColor = MaterialTheme.colorScheme.background))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有下载记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(filtered, key = { it.id }) { entry ->
                Column(Modifier.clickable { onOpen(entry) }) {
                    Box {
                        Thumbnail(entry.uri, entry.kind, Modifier.fillMaxWidth().aspectRatio(1.32f))
                        Surface(Modifier.padding(10.dp), color = Color(0xDD101820), shape = CircleShape) {
                            Text(kindLabel(entry.kind), Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(entry.title.ifBlank { "X 媒体" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestamp)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable private fun Thumbnail(uri: String, kind: MediaKind, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as XMediaApp
    val request = remember(uri, kind) { app.thumbnailRequest(uri, kind) }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        AsyncImage(model = request, imageLoader = app.imageLoader, contentDescription = kindLabel(kind), modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        if (kind == MediaKind.VIDEO) Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

@Composable internal fun SelectionDialog(post: ResolvedPost, onClose: () -> Unit, onConfirm: (List<MediaItem>) -> Unit) {
    val app = LocalContext.current.applicationContext as XMediaApp
    val picked = remember(post.postId) { mutableStateMapOf<String, Boolean>() }
    val selected = post.items.filter { picked["${it.postId}:${it.id}"] == true }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.82f), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Text("选择要下载的内容", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("共 ${post.items.size} 项 · 点击图片选择", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    val all = selected.size == post.items.size
                    TextButton(onClick = { post.items.forEach { picked["${it.postId}:${it.id}"] = !all } }) { Text(if (all) "取消全选" else "全选") }
                }
                LazyVerticalGrid(GridCells.Fixed(2), modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(post.items, key = { "${it.postId}:${it.id}" }) { item ->
                        val key = "${item.postId}:${item.id}"
                        val checked = picked[key] == true
                        Surface(Modifier.fillMaxWidth().aspectRatio(.95f).clickable { picked[key] = !checked },
                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                            border = if (checked) BorderStroke(2.dp, Blue) else null) {
                            Box(Modifier.fillMaxSize()) {
                                AsyncImage(model = item.preview, imageLoader = app.imageLoader, contentDescription = kindLabel(item.kind),
                                    modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(58.dp)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .8f)))))
                                Column(Modifier.align(Alignment.BottomStart).padding(9.dp)) {
                                    Text(kindLabel(item.kind) + if (item.quoted) " · 引用帖" else "", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                    Text(item.author.ifBlank { "X 用户" }, color = Color.White.copy(alpha = .85f), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                }
                                Surface(Modifier.align(Alignment.TopEnd).padding(5.dp), color = Color.Black.copy(alpha = .55f), shape = CircleShape) {
                                    Checkbox(checked = checked, onCheckedChange = { picked[key] = it }, colors = CheckboxDefaults.colors(uncheckedColor = Color.White))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty(), shape = CircleShape) { Text("下载 ${selected.size} 项") }
                }
            }
        }
    }
}

@Composable internal fun PreviewDialog(entry: HistoryEntry, onClose: () -> Unit,
                                      capturing: Boolean = false, onCapture: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val exists = remember(entry.uri) { runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(entry.uri), "r")?.use { true } ?: false }.getOrDefault(false) }
    val loader = remember { ImageLoader.Builder(context).components { add(AnimatedImageDecoder.Factory()) }.build() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                if (!exists) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("文件已被移动或删除，记录仍然保留。", color = Color.White, modifier = Modifier.padding(16.dp))
                } else if (entry.kind == MediaKind.VIDEO) VideoPreview(entry.uri, Modifier.fillMaxSize(), capturing, onCapture)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(model = Uri.parse(entry.uri), imageLoader = loader, contentDescription = "下载的媒体",
                        modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                }
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .55f)).padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.author.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "媒体预览", modifier = Modifier.weight(1f), color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭预览", tint = Color.White) }
                }
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = if (entry.kind == MediaKind.VIDEO && exists) 48.dp else 0.dp)
                    .fillMaxWidth().background(Color.Black.copy(alpha = .65f)).padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (entry.title.isNotBlank()) Text(entry.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        if (entry.quality.isNotBlank()) Text(entry.quality, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.postUrl))) } },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("查看原文")
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun VideoPreview(uri: String, modifier: Modifier = Modifier,
                                      capturing: Boolean = false, onCapture: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(context).build().apply {
        setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.parse(uri)))
        repeatMode = Player.REPEAT_MODE_ONE
        playWhenReady = true
        prepare()
    } }
    var playing by remember(player) { mutableStateOf(player.playWhenReady) }
    var ready by remember(player) { mutableStateOf(player.playbackState == Player.STATE_READY) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    val owner = context as? LifecycleOwner
    DisposableEffect(player, owner) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onPlaybackStateChanged(playbackState: Int) { ready = playbackState == Player.STATE_READY }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(listener)
        ready = player.playbackState == Player.STATE_READY
        playing = player.playWhenReady
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }
    val togglePlayback = {
        if (player.playWhenReady) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
    }
    Column(modifier.background(Color.Black)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { PlayerView(it).apply {
                this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            } }, modifier = Modifier.fillMaxSize())
            Box(Modifier.matchParentSize().clickable(onClickLabel = if (playing) "暂停视频" else "播放视频", onClick = togglePlayback))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = togglePlayback, modifier = Modifier.size(40.dp)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "播放", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Slider(value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { if (duration > 0) { position = (duration * it).toLong(); player.seekTo(position) } },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp), enabled = duration > 0,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Blue, inactiveTrackColor = Color.Gray))
            Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = { onCapture(player.currentPosition) }, enabled = ready && !capturing, modifier = Modifier.size(40.dp)) {
                if (capturing) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Default.PhotoCamera, "保存当前帧", tint = if (ready) Color.White else Color.Gray, modifier = Modifier.size(22.dp))
            }
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}

private fun kindLabel(kind: MediaKind) = when (kind) { MediaKind.PHOTO -> "图片"; MediaKind.VIDEO -> "视频"; MediaKind.GIF -> "GIF" }

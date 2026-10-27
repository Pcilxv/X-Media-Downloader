package cn.xinyu.xmedia.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import cn.xinyu.xmedia.MainActivity
import cn.xinyu.xmedia.R
import cn.xinyu.xmedia.XMediaApp
import cn.xinyu.xmedia.core.MediaItem
import cn.xinyu.xmedia.core.MediaKind
import cn.xinyu.xmedia.core.MediaFileNames
import cn.xinyu.xmedia.core.PostLinks
import cn.xinyu.xmedia.core.Variant
import cn.xinyu.xmedia.core.VariantPicker
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val jobs = mutableSetOf<Job>()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "下载进度", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            synchronized(jobs) { jobs.toList().forEach { it.cancel() } }
            DownloadState.state.value = TaskState(message = "下载已取消。")
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("准备下载…", null))
        val json = intent?.getStringExtra(ITEMS)
        if (json == null) { stopSelfResult(startId); return START_NOT_STICKY }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val array = JSONArray(json)
                val items = (0 until array.length()).map { MediaItem.fromJson(array.getJSONObject(it)) }
                mutex.withLock {
                    items.forEachIndexed { i, item ->
                        update("下载 ${i + 1}/${items.size}", null)
                        save(item)
                    }
                }
                update("下载完成，共 ${items.size} 个文件。", null, running = false)
            } catch (_: CancellationException) {
                DownloadState.state.value = TaskState(message = "下载已取消。")
            } catch (e: Exception) {
                val msg = e.message?.take(180) ?: "下载失败，请检查网络后重试。"
                DownloadState.state.value = TaskState(message = msg, error = true)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(msg, null))
            } finally { stopSelfResult(startId) }
        }
        synchronized(jobs) { jobs.add(job) }
        job.invokeOnCompletion { synchronized(jobs) { jobs.remove(job) } }
        job.start()
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun update(message: String, percent: Int?, running: Boolean = true) {
        DownloadState.state.value = TaskState(running, message, percent)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message, percent))
    }
    private fun notification(message: String, percent: Int?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("X 媒体下载").setContentText(message).setContentIntent(open).setOngoing(DownloadState.state.value.running)
            .setProgress(100, percent ?: 0, percent == null && DownloadState.state.value.running)
            .addAction(0, "取消", cancel).build()
    }

    private suspend fun save(item: MediaItem) {
        val selected = VariantPicker.best(item.variants)
        val stem = MediaFileNames.stem(item, System.currentTimeMillis())
        val temporary = File(cacheDir, "xmedia-${UUID.randomUUID()}.part")
        val converted = File(cacheDir, "xmedia-${UUID.randomUUID()}.gif")
        val manifest = File(cacheDir, "xmedia-${UUID.randomUUID()}.m3u8")
        var published: Uri? = null
        try {
            if (selected.isHls) {
                update("正在下载并封装高清视频…", null)
                val source = if (selected.hlsManifest != null) {
                    manifest.writeText(selected.hlsManifest); Uri.fromFile(manifest).toString()
                } else selected.url
                exportHls(source, temporary, selected)
            } else download(selected.url, temporary)
            currentCoroutineContext().ensureActive()
            val file = if (item.kind == MediaKind.GIF) {
                update("正在转换 GIF…", null)
                GifConverter.convert(this, temporary, converted) { update(it, null) }; converted
            } else temporary
            val mime = when (item.kind) {
                MediaKind.GIF -> "image/gif"
                MediaKind.VIDEO -> "video/mp4"
                MediaKind.PHOTO -> when {
                    selected.url.contains("format=png", true) -> "image/png"
                    selected.url.contains("format=webp", true) -> "image/webp"
                    else -> "image/jpeg"
                }
            }
            val extension = when (mime) { "image/gif" -> "gif"; "image/png" -> "png"; "image/webp" -> "webp"; "video/mp4" -> "mp4"; else -> "jpg" }
            val collection = if (item.kind == MediaKind.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$stem.$extension"); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (item.kind == MediaKind.VIDEO) "Movies" else "Pictures") + "/X媒体下载")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            published = contentResolver.insert(collection, values) ?: error("无法在手机相册中创建文件。")
            contentResolver.openOutputStream(published)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("无法写入手机相册。")
            currentCoroutineContext().ensureActive()
            val completed = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(contentResolver.update(published, completed, null, null) > 0) { "无法完成媒体保存。" }
            val quality = when {
                item.kind != MediaKind.VIDEO -> ""
                selected.uncertain -> "画质未能完全确认"
                selected.height > 0 -> "${selected.width}×${selected.height}"
                selected.bitrate > 0 -> "${selected.bitrate / 1000} kbps"
                else -> "画质未知"
            }
            (application as XMediaApp).history.add(published.toString(), item.postUrl, item.title, item.author, item.kind, quality)
            published = null
        } finally {
            published?.let { runCatching { contentResolver.delete(it, null, null) } }
            temporary.delete(); converted.delete(); manifest.delete()
        }
    }

    private suspend fun download(source: String, target: File) {
        var url = source
        repeat(4) { attempt ->
            require(PostLinks.safeCdn(url)) { "媒体地址不安全。" }
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            response.use { r ->
                if (r.code in 300..399) {
                    if (attempt == 3) error("媒体地址跳转过多。")
                    url = URI(url).resolve(r.header("Location") ?: error("媒体跳转地址无效。" )).toString()
                } else {
                    if (!r.isSuccessful) error("下载失败（HTTP ${r.code}），请稍后重试。")
                    val body = r.body ?: error("媒体内容为空。")
                    val size = body.contentLength()
                    var count = 0L; var lastPercent = -1
                    body.byteStream().use { input -> target.outputStream().buffered().use { output ->
                        val buf = ByteArray(64*1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buf); if (read < 0) break
                            output.write(buf, 0, read); count += read
                            if (size > 0) {
                                val percent = ((count * 100 / size).toInt()).coerceIn(0,100)
                                if (percent != lastPercent) { update("正在下载…", percent); lastPercent = percent }
                            }
                        }
                    } }
                    if (count == 0L || (size > 0 && count != size)) error("下载未完成，请重试。")
                    return
                }
            }
        }
    }

    private suspend fun exportHls(url: String, target: File, selected: Variant) = suspendCancellableCoroutine<Unit> { continuation ->
        val handler = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        handler.post {
            if (!continuation.isActive) return@post
            try {
                transformer = Transformer.Builder(this).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (!continuation.isActive) return
                        val dimensionsMismatch = selected.width > 0 && selected.height > 0 && exportResult.width > 0 && exportResult.height > 0 &&
                            setOf(selected.width, selected.height) != setOf(exportResult.width, exportResult.height)
                        val expectedAudio = selected.hlsManifest?.lineSequence()?.any {
                            it.startsWith("#EXT-X-MEDIA:") && Regex("(?:^|,)TYPE=AUDIO(?:,|$)").containsMatchIn(it.substringAfter(':'))
                        } == true
                        when {
                            exportResult.videoConversionProcess != ExportResult.CONVERSION_PROCESS_TRANSMUXED ->
                                continuation.resumeWithException(IllegalStateException("当前设备不能无损封装最高画质，未自动降级。"))
                            dimensionsMismatch -> continuation.resumeWithException(IllegalStateException("导出视频分辨率与所选画质不一致，已取消保存。"))
                            expectedAudio && exportResult.audioMimeType == null -> continuation.resumeWithException(IllegalStateException("无法保存流媒体音轨，已取消保存。"))
                            else -> continuation.resume(Unit)
                        }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("最高画质流媒体保存失败，未自动降级。", exportException))
                    }
                }).build()
                transformer!!.start(PlayerMediaItem.fromUri(url), target.absolutePath)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
        continuation.invokeOnCancellation { handler.post { transformer?.cancel() } }
    }

    companion object {
        const val ITEMS = "items"; const val CANCEL = "cancel"
        private const val CHANNEL = "xmedia_downloads"; private const val NOTIFICATION_ID = 47
    }
}

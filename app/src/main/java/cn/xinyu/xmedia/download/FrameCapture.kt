package cn.xinyu.xmedia.download

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.inspector.frame.FrameExtractor
import cn.xinyu.xmedia.XMediaApp
import cn.xinyu.xmedia.core.MediaFileNames
import cn.xinyu.xmedia.core.MediaKind
import cn.xinyu.xmedia.data.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Extracts a full-size local video frame and publishes it as a photo. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object FrameCapture {
    suspend fun save(context: Context, entry: HistoryEntry, positionMs: Long): Uri = withContext(Dispatchers.IO) {
        require(entry.kind == MediaKind.VIDEO) { "只能从视频中截取画面。" }
        val app = context.applicationContext as XMediaApp
        val source = MediaItem.fromUri(Uri.parse(entry.uri))
        val bitmap = FrameExtractor.Builder(app, source).build().use { extractor ->
            val future = extractor.getFrame(positionMs.coerceAtLeast(0L))
            try { future.get(45, TimeUnit.SECONDS).bitmap }
            catch (e: TimeoutException) { future.cancel(true); throw IllegalStateException("提取画面超时，请稍后重试。", e) }
            catch (e: ExecutionException) { throw IllegalStateException("无法从此视频提取画面，请检查视频文件。", e.cause ?: e) }
        }
        var published: Uri? = null
        try {
            currentCoroutineContext().ensureActive()
            val width = bitmap.width; val height = bitmap.height
            val name = MediaFileNames.frameStem(entry.author, entry.postUrl, System.currentTimeMillis()) + ".png"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/X媒体下载")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            published = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在手机相册中创建截图。")
            resolver.openOutputStream(published)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "保存截图失败。" }
            } ?: error("无法写入手机相册。")
            currentCoroutineContext().ensureActive()
            val completed = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(published, completed, null, null) > 0) { "无法完成截图保存。" }
            app.history.add(published.toString(), entry.postUrl, "视频截帧 · ${entry.title}", entry.author,
                MediaKind.PHOTO, "${width}×${height}")
            val result = published
            published = null
            result
        } finally {
            published?.let { runCatching { app.contentResolver.delete(it, null, null) } }
            bitmap.recycle()
        }
    }
}

package cn.xinyu.xmedia

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.xinyu.xmedia.core.MediaKind
import cn.xinyu.xmedia.data.HistoryEntry
import cn.xinyu.xmedia.download.FrameCapture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FrameCaptureTest {
    @Test fun capturesVideoWithTwitterTimingFlags() = runBlocking {
        assumeTrue("media writes are restricted to an isolated emulator", Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as XMediaApp
        val marker = "frame-timing-${UUID.randomUUID()}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$marker.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/X媒体下载测试")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val source = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            context.contentResolver.openOutputStream(source)!!.use { out ->
                instrumentation.context.assets.open("twitter-timing-synthetic.mp4").use { it.copyTo(out) }
            }
            context.contentResolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val entry = HistoryEntry(0, source.toString(), "https://x.com/test/status/123", marker, "test", MediaKind.VIDEO, 0, "")
            val captured = FrameCapture.save(context, entry, 500)
            context.contentResolver.openInputStream(captured)!!.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)!!
                try { assertEquals(64, bitmap.width); assertEquals(64, bitmap.height) } finally { bitmap.recycle() }
            }
        } finally {
            app.history.entries.value.filter { it.title == "视频截帧 · $marker" }.forEach { item ->
                context.contentResolver.delete(Uri.parse(item.uri), null, null); app.history.removeRecord(item.id)
            }
            context.contentResolver.delete(source, null, null)
        }
    }

    @Test fun capturesDifferentFullSizeFramesAndAddsPhotoHistory() = runBlocking {
        assumeTrue("media writes are restricted to an isolated emulator", Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as XMediaApp
        val marker = "frame-test-${UUID.randomUUID()}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$marker.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/X媒体下载测试")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val source = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            context.contentResolver.openOutputStream(source)!!.use { out ->
                instrumentation.context.assets.open("portrait-synthetic.mp4").use { it.copyTo(out) }
            }
            context.contentResolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val post = "https://x.com/test/status/123"
            val entry = HistoryEntry(0, source.toString(), post, marker, "test", MediaKind.VIDEO, 0, "180×320")
            val first = FrameCapture.save(context, entry, 100)
            val second = FrameCapture.save(context, entry, 700)
            val created = app.history.entries.value.filter { it.title == "视频截帧 · $marker" }
            assertEquals(2, created.size)
            assertTrue(created.all { it.kind == MediaKind.PHOTO && it.postUrl == post && it.author == "test" })
            val frames = listOf(first, second).map { uri ->
                assertEquals("image/png", context.contentResolver.getType(uri))
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0).startsWith("@test_123_截帧_"))
                }
                context.contentResolver.openInputStream(uri)!!.use { stream -> BitmapFactory.decodeStream(stream) }!!
            }
            try {
                assertTrue(frames.all { it.width == 180 && it.height == 320 })
                assertFalse("captures at distinct times should differ", frames[0].sameAs(frames[1]))
            } finally { frames.forEach { it.recycle() } }
            val failure = runCatching { FrameCapture.save(context, entry.copy(uri = "content://media/external/video/media/999999999"), 100) }
            assertTrue("a missing video should report failure", failure.isFailure)
            assertEquals("failed captures must not add history", 2, app.history.entries.value.count { it.title == "视频截帧 · $marker" })
        } finally {
            app.history.entries.value.filter { it.title == "视频截帧 · $marker" }.forEach { item ->
                context.contentResolver.delete(Uri.parse(item.uri), null, null); app.history.removeRecord(item.id)
            }
            context.contentResolver.delete(source, null, null)
        }
    }
}

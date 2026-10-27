package cn.xinyu.xmedia

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.xinyu.xmedia.core.MediaItem
import cn.xinyu.xmedia.core.MediaKind
import cn.xinyu.xmedia.core.ResolvedPost
import cn.xinyu.xmedia.core.Variant
import cn.xinyu.xmedia.data.HistoryEntry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Captures synthetic-only screens on the isolated test emulator for manual layout inspection. */
@RunWith(AndroidJUnit4::class)
class UiEvidenceTest {
    @Test fun videoStartsLoopsAndTappingPictureTogglesPlayback() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = File.createTempFile("ui-loop-", ".mp4", context.cacheDir)
        try {
            instrumentation.context.assets.open("portrait-synthetic.mp4").use { src -> video.outputStream().use { src.copyTo(it) } }
            fun findPlayer(view: View): PlayerView? {
                if (view is PlayerView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) findPlayer(view.getChildAt(i))?.let { return it }
                return null
            }
            fun tap(x: Float, y: Float) {
                val now = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
                instrumentation.sendPointerSync(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0))
                instrumentation.waitForIdleSync()
            }
            val repeats = AtomicInteger(0)
            val capturedPosition = AtomicLong(-1)
            val listener = object : Player.Listener {
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) repeats.incrementAndGet()
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    VideoPreview(Uri.fromFile(video).toString(), Modifier.fillMaxSize(), onCapture = { capturedPosition.set(it) })
                } } }
                var player: Player? = null
                for (i in 0 until 50) {
                    Thread.sleep(100)
                    scenario.onActivity { activity -> player = findPlayer(activity.window.decorView)?.player }
                    if (player != null) break
                }
                assertTrue("player should be attached to the screen", player != null)
                scenario.onActivity {
                    assertTrue("opening a video should start playback", player!!.playWhenReady)
                    assertEquals(Player.REPEAT_MODE_ONE, player!!.repeatMode)
                    player!!.addListener(listener)
                }
                for (i in 0 until 60) { if (repeats.get() > 0) break else Thread.sleep(100) }
                assertTrue("the video should actually loop after its last frame", repeats.get() > 0)
                val shot = instrumentation.uiAutomation.takeScreenshot()
                val x = shot.width * .5f; val y = shot.height * .45f
                shot.recycle()
                tap(x, y)
                var pausedAt = -1L
                scenario.onActivity { assertFalse("tapping the video should pause it", player!!.playWhenReady); pausedAt = player!!.currentPosition }
                var ready = false
                for (i in 0 until 30) {
                    scenario.onActivity { ready = player!!.playbackState == Player.STATE_READY }
                    if (ready) break else Thread.sleep(100)
                }
                assertTrue("player must be ready for capture", ready)
                tap(x * 1.88f, y * (0.96f / 0.45f))
                assertTrue("capture button should respond", capturedPosition.get() >= 0)
                assertTrue("capture button should use the paused playback position", kotlin.math.abs(capturedPosition.get() - pausedAt) <= 100)
                tap(x, y)
                scenario.onActivity { assertTrue("tapping again should resume playback", player!!.playWhenReady); player!!.removeListener(listener) }
            }
        } finally { video.delete() }
    }

    @Test fun captureSelectionAndPortraitVideo() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val image = File.createTempFile("ui-synthetic-", ".png", context.cacheDir)
        val video = File.createTempFile("ui-synthetic-", ".mp4", context.cacheDir)
        val evidence = context.getExternalFilesDir("visual")!!
        try {
            val bitmap = Bitmap.createBitmap(240, 300, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(56, 99, 145))
            val paint = Paint().apply { color = Color.rgb(229, 177, 101) }
            canvas.drawCircle(120f, 105f, 60f, paint)
            paint.color = Color.rgb(38, 61, 80)
            canvas.drawRect(35f, 185f, 205f, 300f, paint)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            instrumentation.context.assets.open("portrait-synthetic.mp4").use { src -> video.outputStream().use { src.copyTo(it) } }
            val postUrl = "https://x.com/test/status/123"
            val preview = Uri.fromFile(image).toString()
            val items = (1..4).map { n ->
                MediaItem("$n", "123", postUrl, "example", "合成测试内容", if (n == 2) MediaKind.GIF else MediaKind.PHOTO,
                    preview, n == 4, listOf(Variant("https://pbs.twimg.com/media/test.jpg", "image/jpeg")))
            }
            val confirmed = AtomicInteger(0)
            fun tap(x: Float, y: Float) {
                val now = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
                instrumentation.sendPointerSync(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0))
                instrumentation.waitForIdleSync()
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme { SelectionDialog(ResolvedPost("123", postUrl, items), {}, { confirmed.set(it.size) }) } } }
                instrumentation.waitForIdleSync(); Thread.sleep(1000)
                val shot1 = instrumentation.uiAutomation.takeScreenshot()
                assertTrue(shot1.width > 0 && shot1.height > 0)
                val screenWidth = shot1.width.toFloat(); val screenHeight = shot1.height.toFloat()
                File(evidence, "selection.png").outputStream().use { shot1.compress(Bitmap.CompressFormat.PNG, 100, it) }
                shot1.recycle()
                tap(screenWidth * .82f, screenHeight * .28f)
                tap(screenWidth * .75f, screenHeight * .81f)
                assertTrue("应将四个选中项目交给下载流程", confirmed.get() == 4)
                val entry = HistoryEntry(1, Uri.fromFile(video).toString(), postUrl, "竖屏视频预览 · 合成测试内容", "example", MediaKind.VIDEO, 0, "180×320")
                scenario.onActivity { activity -> activity.setContent { MaterialTheme { PreviewDialog(entry, {}) } } }
                instrumentation.waitForIdleSync(); Thread.sleep(1500)
                val shot2 = instrumentation.uiAutomation.takeScreenshot()
                assertTrue(shot2.width > 0 && shot2.height > 0)
                File(evidence, "video.png").outputStream().use { shot2.compress(Bitmap.CompressFormat.PNG, 100, it) }
                shot2.recycle()
            }
        } finally { image.delete(); video.delete() }
    }
}

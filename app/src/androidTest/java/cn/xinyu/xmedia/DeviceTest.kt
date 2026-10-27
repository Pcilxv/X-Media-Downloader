package cn.xinyu.xmedia

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Movie
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaExtractor
import android.media.MediaFormat
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import cn.xinyu.xmedia.core.MediaKind
import cn.xinyu.xmedia.core.MediaItem
import cn.xinyu.xmedia.core.Variant
import cn.xinyu.xmedia.core.HlsVariants
import cn.xinyu.xmedia.core.XSession
import cn.xinyu.xmedia.core.WebTweetCapture
import cn.xinyu.xmedia.core.PostParser
import cn.xinyu.xmedia.data.HistoryStore
import cn.xinyu.xmedia.download.DownloadService
import cn.xinyu.xmedia.download.DownloadState
import cn.xinyu.xmedia.download.GifConverter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONArray
import okhttp3.OkHttpClient
import okhttp3.Request
import coil3.request.SuccessResult

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    @Test fun foregroundClipboardRecognizesNewXPostOnce() {
        assumeTrue("clipboard test is restricted to an isolated emulator", Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val prefs = context.getSharedPreferences("clipboard", Context.MODE_PRIVATE)
        val link = "https://x.com/test/status/123456789"
        prefs.edit().clear().commit()
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "分享 $link"))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var observed = ""
                for (i in 0 until 40) {
                    Thread.sleep(100)
                    scenario.onActivity { observed = it.input }
                    if (observed == link) break
                }
                assertEquals("foreground should read the new post", link, observed)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(1000)
                scenario.onActivity { assertEquals("same clipboard item must not start again", "", it.input) }
            }
            Thread.sleep(30)
            clipboard.setPrimaryClip(ClipData.newPlainText("test again", link))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var observed = ""
                for (i in 0 until 40) {
                    Thread.sleep(100)
                    scenario.onActivity { observed = it.input }
                    if (observed == link) break
                }
                assertEquals("copying the same link again should be recognized", link, observed)
            }
            clipboard.clearPrimaryClip()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.moveToState(Lifecycle.State.CREATED)
                val nextLink = "https://twitter.com/example/status/987654321"
                clipboard.setPrimaryClip(ClipData.newPlainText("warm return", nextLink))
                scenario.moveToState(Lifecycle.State.RESUMED)
                var observed = ""
                for (i in 0 until 40) {
                    Thread.sleep(100)
                    scenario.onActivity { observed = it.input }
                    if (observed == nextLink) break
                }
                assertEquals("returning to a running app should recognize a newly copied post", nextLink, observed)
            }
        } finally { clipboard.clearPrimaryClip(); prefs.edit().clear().commit() }
    }
    @Test fun webFallbackCapturesOnlyTheRequestedPost() = runBlocking {
        assumeTrue("WebView capture test is restricted to an isolated emulator", Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk"))
        val fixture = """{"data":{"items":[{"rest_id":"999","legacy":{"full_text":"unrelated-private-text"}},{"rest_id":"123","legacy":{"full_text":"wanted","extended_entities":{"media":[{"id_str":"one","type":"photo","media_url_https":"https://pbs.twimg.com/media/example.jpg"}]}}}]}}"""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val captured = WebTweetCapture.captureInternal(activity, "123", fixture)
            assertFalse("unrelated posts should not cross the bridge", captured.contains("unrelated-private-text"))
            assertEquals("one", PostParser.parse(captured, "123").items.single().id)
        }
    }
    @Test fun accountButtonRecognizesSavedSessionWithoutReopeningLogin() {
        assumeTrue("session test is restricted to an isolated emulator", Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk"))
        val cookies = CookieManager.getInstance()
        try {
            cookies.setCookie("https://x.com", "auth_token=synthetic-test-token; Path=/; Secure; HttpOnly")
            cookies.setCookie("https://x.com", "ct0=synthetic-test-csrf; Path=/; Secure")
            cookies.flush()
            assertTrue(XSession.hasSavedCookies())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.openAccount()
                    assertTrue("account button should show saved-session status", activity.accountStatusVisible)
                }
            }
        } finally {
            cookies.setCookie("https://x.com", "auth_token=; Max-Age=0; Path=/; Secure; HttpOnly")
            cookies.setCookie("https://x.com", "ct0=; Max-Age=0; Path=/; Secure")
            cookies.flush()
        }
    }
    @Test fun convertsActualVideoIntoAnAnimatedGif() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cache = instrumentation.targetContext.cacheDir
        val input = File.createTempFile("gif-test-", ".mp4", cache)
        val output = File.createTempFile("gif-test-", ".gif", cache)
        try {
            instrumentation.context.assets.open("sample.mp4").use { it.copyTo(input.outputStream()) }
            GifConverter.convert(instrumentation.targetContext, input, output) { }
            assertTrue(output.length() > 20)
            assertTrue(output.inputStream().use { String(it.readNBytes(3), Charsets.US_ASCII) } == "GIF")
            val image = Movie.decodeFile(output.absolutePath)
            assertNotNull(image)
            assertEquals(64, image.width()); assertEquals(64, image.height())
            assertTrue("expected an animated GIF", image.duration() >= 500)
        } finally { input.delete(); output.delete() }
    }
    @Test fun convertsVideoWithTwitterTimingFlagsIntoAnAnimatedGif() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val input = File.createTempFile("gif-timing-", ".part", instrumentation.targetContext.cacheDir)
        val output = File.createTempFile("gif-timing-", ".gif", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("twitter-timing-synthetic.mp4").use { source -> input.outputStream().use { source.copyTo(it) } }
            GifConverter.convert(instrumentation.targetContext, input, output) { }
            val image = Movie.decodeFile(output.absolutePath)
            assertNotNull("output must be a real decodable GIF", image)
            assertEquals(64, image.width()); assertEquals(64, image.height())
            assertTrue("GIF timing should remain animated", image.duration() >= 800)
            fun render(time: Int): IntArray {
                val frame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                image.setTime(time)
                image.draw(Canvas(frame), 0f, 0f)
                return IntArray(64 * 64).also { frame.getPixels(it, 0, 64, 0, 0, 64, 64); frame.recycle() }
            }
            val first = render(0)
            val later = render(500)
            assertTrue("GIF must contain visible color", first.toSet().size > 10)
            assertFalse("GIF frames must change over time", first.contentEquals(later))
        } finally { input.delete(); output.delete() }
    }
    @Test fun historyCanPersistAndReadMediaTypes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "history-test-${java.util.UUID.randomUUID()}.db"
        val store = HistoryStore(context, databaseName)
        try {
            store.add("content://example/1", "https://x.com/test/status/123", "记录测试", "test", MediaKind.GIF, "")
            val item = store.entries.value.single()
            assertEquals(MediaKind.GIF, item.kind)
            assertEquals("https://x.com/test/status/123", item.postUrl)
        } finally { store.close(); context.deleteDatabase(databaseName) }
    }
    @Test fun emulatorSavesPublicPhotoAndVideoAndHistory() = runBlocking {
        assumeTrue("network download test is restricted to an Android emulator", Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as XMediaApp
        val marker = "test-${java.util.UUID.randomUUID()}"
        val post = "https://x.com/historyinmemes/status/1790637656616943991"
        val photo = "https://pbs.twimg.com/amplify_video_thumb/1790637589910654976/img/jyhLQQZkq-lJJ9fN.jpg?name=orig"
        val video = "https://video.twimg.com/amplify_video/1790637589910654976/vid/avc1/272x270/du7PkQofwIDrjAEz.mp4?tag=14"
        val hls = "https://video.twimg.com/amplify_video/1790637589910654976/pl/osiKl6ALz2B8l9Cw.m3u8?tag=14&v=cfc"
        val manifest = OkHttpClient().newCall(Request.Builder().url(hls).build()).execute().use { response ->
            assertTrue("public HLS manifest should be accessible", response.isSuccessful); response.body.string()
        }
        val hlsBest = cn.xinyu.xmedia.core.VariantPicker.best(HlsVariants.expand(Variant(hls, "application/x-mpegURL"), manifest).filter { it.width == 364 })
        val entries = listOf(
            MediaItem("photo", "1790637656616943991", post, "test", marker, MediaKind.PHOTO, photo, false, listOf(Variant(photo, "image/jpeg"))),
            MediaItem("video", "1790637656616943991", post, "test", marker, MediaKind.VIDEO, photo, false, listOf(Variant(video, "video/mp4", 288000, 272, 270))),
            MediaItem("hls", "1790637656616943991", post, "test", marker, MediaKind.VIDEO, photo, false, listOf(hlsBest))
        )
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            kotlinx.coroutines.delay(1200)
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)
                .putExtra(DownloadService.ITEMS, JSONArray(entries.map { it.toJson() }).toString()))
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline && app.history.entries.value.count { it.title == marker } < 3) {
                if (DownloadState.state.value.error) error(DownloadState.state.value.message)
                kotlinx.coroutines.delay(250)
            }
            val created = app.history.entries.value.filter { it.title == marker }
            assertEquals("public media download did not complete", 3, created.size)
            assertEquals(setOf(MediaKind.PHOTO, MediaKind.VIDEO), created.map { it.kind }.toSet())
            created.forEach { item ->
                context.contentResolver.query(Uri.parse(item.uri), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue("saved filename must start with the creator's handle", cursor.getString(0).startsWith("@test_"))
                }
                val bytes = context.contentResolver.openInputStream(Uri.parse(item.uri))!!.use { it.readNBytes(12) }
                assertTrue("saved media should not be empty", bytes.size >= 8)
                if (item.kind == MediaKind.PHOTO) assertEquals(0xff, bytes[0].toInt() and 255)
                else {
                    assertEquals("ftyp", String(bytes, 4, 4, Charsets.US_ASCII))
                    val extractor = MediaExtractor()
                    try {
                        context.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r")!!.use { extractor.setDataSource(it.fileDescriptor) }
                        val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                        assertTrue("video should retain audio", mimes.any { it.startsWith("audio/") })
                        assertTrue("video track should be present", mimes.any { it.startsWith("video/") })
                    } finally { extractor.release() }
                    assertTrue("video history thumbnail should load", app.imageLoader.execute(app.thumbnailRequest(item.uri, item.kind)) is SuccessResult)
                }
            }
        } finally {
            if (DownloadState.state.value.running) context.startService(Intent(context, DownloadService::class.java).setAction(DownloadService.CANCEL))
            app.history.entries.value.filter { it.title == marker }.forEach { item ->
                context.contentResolver.delete(Uri.parse(item.uri), null, null); app.history.removeRecord(item.id)
            }
        }
    }
}

package cn.xinyu.xmedia

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cn.xinyu.xmedia.core.LoginRequired
import cn.xinyu.xmedia.core.MediaItem
import cn.xinyu.xmedia.core.PostLinks
import cn.xinyu.xmedia.core.ResolvedPost
import cn.xinyu.xmedia.core.XSession
import cn.xinyu.xmedia.core.WebTweetCapture
import cn.xinyu.xmedia.download.DownloadService
import cn.xinyu.xmedia.download.FrameCapture
import cn.xinyu.xmedia.data.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    var input by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var selection by mutableStateOf<ResolvedPost?>(null)
        private set
    var accountStatusVisible by mutableStateOf(false)
        private set
    var capturingFrame by mutableStateOf(false)
        private set
    var showDownloadPageRequest by mutableIntStateOf(0)
        private set
    private var pendingLink: String? = null
    private var pendingItems: List<MediaItem>? = null
    private var clipboardCheckPending = false
    private var skipClipboardOnNextResume = false
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            message = "登录信息已保存在手机，可以开始下载。"
            pendingLink?.let { resolve(it) }
        }
        else if (pendingLink != null) message = "登录尚未完成，下载没有开始。"
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingItems?.let { startServiceFor(it) }; pendingItems = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppUi(this, application as XMediaApp) }
        handleIntent(intent)
    }
    override fun onResume() {
        super.onResume()
        clipboardCheckPending = !skipClipboardOnNextResume
        skipClipboardOnNextResume = false
        window.decorView.post { inspectClipboardIfReady() }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) inspectClipboardIfReady()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            // A share already identifies the intended post; do not also launch the clipboard item.
            skipClipboardOnNextResume = true
            clipboardCheckPending = false
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (text.isNotBlank()) { input = text; resolve(text) }
        }
    }
    private fun inspectClipboardIfReady() {
        if (!clipboardCheckPending || !hasWindowFocus() || loading || selection != null || pendingItems != null || accountStatusVisible) return
        clipboardCheckPending = false
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        if (clip.itemCount == 0) return
        // Only inspect text; never coerce a clipboard URI or upload unrelated content.
        val text = clip.getItemAt(0).text?.toString() ?: return
        val link = PostLinks.clipboardPost(text) ?: return
        val identity = "${clip.description.timestamp}|${PostLinks.postId(link)}"
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        val prefs = getSharedPreferences("clipboard", MODE_PRIVATE)
        if (prefs.getString("last_processed", null) == fingerprint) return
        prefs.edit().putString("last_processed", fingerprint).apply()
        input = link
        resolve(link)
    }
    fun updateInput(value: String) { input = value }
    fun openAccount() {
        if (XSession.hasSavedCookies()) accountStatusVisible = true else launchLogin()
    }
    fun dismissAccountStatus() { accountStatusVisible = false; inspectClipboardIfReady() }
    fun launchLogin() { accountStatusVisible = false; skipClipboardOnNextResume = true; login.launch(Intent(this, LoginActivity::class.java)) }
    fun resolve(value: String = input) {
        if (loading) { message = "正在解析另一个帖子，请稍候。"; return }
        val link = try { PostLinks.extract(value) } catch (e: Exception) { message = e.message.orEmpty(); return }
        showDownloadPageRequest++
        pendingLink = link; loading = true; message = "正在解析帖子…"
        lifecycleScope.launch {
            try {
                val post = (application as XMediaApp).resolver.resolve(link) { postId ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { message = "正在通过 X 网页解析帖子…" }
                    WebTweetCapture.capture(this@MainActivity, postId)
                }
                pendingLink = null; message = ""
                if (post.items.size == 1) queue(post.items) else selection = post
            } catch (e: LoginRequired) { message = e.message.orEmpty(); launchLogin() }
            catch (e: Exception) { message = e.message ?: "解析失败，请检查网络或稍后重试。" }
            finally { loading = false; inspectClipboardIfReady() }
        }
    }
    fun dismissSelection() { selection = null; inspectClipboardIfReady() }
    fun queue(items: List<MediaItem>) {
        if (items.isEmpty()) { message = "请先选择要下载的媒体。"; return }
        selection = null
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingItems = items; notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startServiceFor(items)
    }
    private fun startServiceFor(items: List<MediaItem>) {
        val data = JSONArray(items.map { it.toJson() }).toString()
        ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java).putExtra(DownloadService.ITEMS, data))
        message = "已加入下载队列。"
    }
    fun cancelDownload() { startService(Intent(this, DownloadService::class.java).setAction(DownloadService.CANCEL)) }
    fun captureFrame(entry: HistoryEntry, positionMs: Long) {
        if (capturingFrame) return
        capturingFrame = true
        lifecycleScope.launch {
            try {
                FrameCapture.save(this@MainActivity, entry, positionMs)
                Toast.makeText(this@MainActivity, "截图已保存到相册和图片记录", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() }?.take(120) ?: "请检查视频文件和手机可用空间。"
                Toast.makeText(this@MainActivity, "截图失败：$detail", Toast.LENGTH_LONG).show()
            } finally { capturingFrame = false }
        }
    }
}

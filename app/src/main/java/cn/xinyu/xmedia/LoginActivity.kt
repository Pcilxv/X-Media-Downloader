package cn.xinyu.xmedia

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import cn.xinyu.xmedia.core.XSession
import java.net.URI

class LoginActivity : Activity() {
    private lateinit var browser: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        ViewCompat.setOnApplyWindowInsetsListener(column) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom); insets
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        }
        val note = TextView(this).apply { text = "请在 X 官方页面登录。登录完成后，点击下方按钮继续下载。"; setTextColor(Color.BLACK); setPadding(24, 20, 24, 20) }
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme != "https") return true
                    val host = uri.host?.lowercase().orEmpty()
                    return !(host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com") ||
                        host == "google.com" || host.endsWith(".google.com") || host == "appleid.apple.com")
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        val done = Button(this).apply {
            text = "我已完成登录"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply { setColor(Color.rgb(29, 155, 240)); cornerRadius = 48f }
            setOnClickListener {
                CookieManager.getInstance().flush()
                if (XSession.hasSavedCookies()) {
                    setResult(RESULT_OK); finish()
                } else note.text = "尚未检测到完整的登录状态，请先完成 X 登录和验证。"
            }
        }
        column.addView(note)
        column.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56.dp()).apply { setMargins(16.dp(), 8.dp(), 16.dp(), 8.dp()) })
        setContentView(column)
        browser.loadUrl("https://x.com/i/flow/login")
    }
    override fun onDestroy() { browser.destroy(); super.onDestroy() }
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    @Deprecated("Deprecated in Android")
    override fun onBackPressed() { if (browser.canGoBack()) browser.goBack() else super.onBackPressed() }
}

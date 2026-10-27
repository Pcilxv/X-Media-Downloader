package cn.xinyu.xmedia.core

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Loads only the selected post in a temporary WebView; no cookies or login fields leave the WebView. */
object WebTweetCapture {
    suspend fun capture(activity: Activity, postId: String): String = captureInternal(activity, postId, null)

    internal suspend fun captureInternal(activity: Activity, postId: String, syntheticJson: String?): String {
        require(postId.matches(Regex("\\d+"))) { "帖子编号无效。" }
        return try {
            withTimeout(45_000) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { continuation ->
                        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
                            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                            continuation.resumeWithException(IllegalStateException("手机网页组件不支持备用解析，请更新 Android System WebView。"))
                            return@suspendCancellableCoroutine
                        }
                        val parent = activity.window.decorView as ViewGroup
                        val view = WebView(activity)
                        var cleaned = false
                        fun cleanup() {
                            if (cleaned) return
                            cleaned = true
                            view.stopLoading(); parent.removeView(view); view.destroy()
                        }
                        fun fail(error: Exception) {
                            if (!continuation.isActive) return
                            cleanup(); continuation.resumeWithException(error)
                        }
                        try {
                            view.settings.javaScriptEnabled = true
                            view.settings.domStorageEnabled = true
                            view.settings.allowFileAccess = false
                            view.settings.allowContentAccess = false
                            view.settings.blockNetworkImage = true
                            view.settings.mediaPlaybackRequiresUserGesture = true
                            view.setBackgroundColor(Color.TRANSPARENT)
                            view.alpha = 0f; view.translationX = -10000f
                            view.isFocusable = false; view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            val origins = setOf("https://x.com", "https://twitter.com")
                            WebViewCompat.addWebMessageListener(view, "XMediaResult", origins) { _, message, origin, isMainFrame, _ ->
                                if (!continuation.isActive || !isMainFrame || origin.scheme != "https" || origin.host !in setOf("x.com", "twitter.com")) return@addWebMessageListener
                                val value = message.data ?: return@addWebMessageListener
                                if (value.length > 2_000_000 || !value.contains("\"rest_id\":\"$postId\"")) return@addWebMessageListener
                                cleanup(); continuation.resume(value)
                            }
                            WebViewCompat.addDocumentStartJavaScript(view, captureScript(postId), origins)
                            view.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val host = request.url.host?.lowercase().orEmpty()
                                    return request.url.scheme != "https" || host !in setOf("x.com", "twitter.com", "www.x.com", "www.twitter.com")
                                }
                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    if (syntheticJson == null && url != null && Uri.parse(url).path?.startsWith("/i/flow/login") == true) {
                                        fail(LoginRequired("X 登录已失效，请重新登录后再试。"))
                                    }
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) fail(IllegalStateException("无法打开 X 帖子，请检查手机网络。"))
                                }
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    if (syntheticJson != null && request.url.path?.contains("/i/api/graphql/") == true) {
                                        return WebResourceResponse("application/json", "UTF-8", ByteArrayInputStream(syntheticJson.toByteArray()))
                                    }
                                    return null
                                }
                            }
                            parent.addView(view, ViewGroup.LayoutParams(320, 640))
                            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { cleanup() } }
                            if (syntheticJson == null) view.loadUrl("https://x.com/i/web/status/$postId")
                            else view.loadDataWithBaseURL("https://x.com/", "<html><script>fetch('/i/api/graphql/test/TweetDetail?focalTweetId=$postId');</script></html>", "text/html", "UTF-8", null)
                        } catch (error: Exception) { fail(IllegalStateException("启动 X 网页解析失败。", error)) }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("X 网页未返回目标帖子的媒体信息，请检查帖子是否可见后重试。")
        }
    }

    internal fun captureScript(id: String): String = """
        (() => {
          if (window !== window.top) return;
          const target = '$id';
          const matches = u => {
            try { const x = new URL(u, location.href); return (x.hostname === 'x.com' || x.hostname === 'twitter.com' || x.hostname === 'api.x.com') && x.pathname.includes('/graphql/') && /(TweetDetail|TweetResultByRestId|TweetResultById)/i.test(x.pathname); }
            catch (_) { return false; }
          };
          const emit = text => {
            if (typeof text !== 'string' || !text.includes(target)) return;
            try {
              const stack = [JSON.parse(text)]; let seen = 0;
              while (stack.length && seen++ < 50000) {
                const v = stack.pop(); if (!v || typeof v !== 'object') continue;
                if (String(v.rest_id || '') === target && v.legacy && typeof v.legacy === 'object') {
                  window.XMediaResult.postMessage(JSON.stringify({data:{items:[v]}})); return;
                }
                for (const child of Object.values(v)) if (child && typeof child === 'object') stack.push(child);
              }
            } catch (_) {}
          };
          const originalFetch = window.fetch;
          window.fetch = function(...args) {
            return originalFetch.apply(this, args).then(response => {
              if (matches(response.url)) { try { response.clone().text().then(emit).catch(() => {}); } catch (_) {} }
              return response;
            });
          };
          const originalOpen = XMLHttpRequest.prototype.open;
          const originalSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(method, url, ...rest) { this.__xmediaUrl = String(url); return originalOpen.call(this, method, url, ...rest); };
          XMLHttpRequest.prototype.send = function(...args) {
            if (matches(this.__xmediaUrl)) this.addEventListener('load', () => { try { emit(this.responseText); } catch (_) {} });
            return originalSend.apply(this, args);
          };
        })();
    """.trimIndent()
}

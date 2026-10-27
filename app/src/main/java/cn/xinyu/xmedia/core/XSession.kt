package cn.xinyu.xmedia.core

import android.webkit.CookieManager

/** Only exposes whether the local WebView has the cookies needed for a session. */
object XSession {
    fun hasSavedCookies(): Boolean {
        val cookies = CookieManager.getInstance().getCookie("https://x.com").orEmpty()
        val values = cookies.split(';').map { it.trim() }.associate {
            val pair = it.split('=', limit = 2)
            pair[0] to pair.getOrElse(1) { "" }
        }
        return !values["auth_token"].isNullOrBlank() && !values["ct0"].isNullOrBlank()
    }
}

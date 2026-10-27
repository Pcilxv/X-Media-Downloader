package cn.xinyu.xmedia.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

enum class MediaKind { PHOTO, VIDEO, GIF }

data class Variant(
    val url: String,
    val contentType: String,
    val bitrate: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val uncertain: Boolean = false,
    val hlsManifest: String? = null,
) {
    val area: Long get() = width.toLong() * height
    val isHls: Boolean get() = contentType.contains("mpegurl", true) || URI(url).path.endsWith(".m3u8", true)
    fun toJson() = JSONObject().put("url", url).put("contentType", contentType).put("bitrate", bitrate)
        .put("width", width).put("height", height).put("uncertain", uncertain).put("hlsManifest", hlsManifest)
    companion object {
        fun fromJson(o: JSONObject) = Variant(o.getString("url"), o.getString("contentType"),
            o.optLong("bitrate"), o.optInt("width"), o.optInt("height"), o.optBoolean("uncertain"),
            o.optString("hlsManifest").takeIf { it.isNotEmpty() && it != "null" })
    }
}

data class MediaItem(
    val id: String,
    val postId: String,
    val postUrl: String,
    val author: String,
    val title: String,
    val kind: MediaKind,
    val preview: String,
    val quoted: Boolean,
    val variants: List<Variant>,
) {
    fun toJson() = JSONObject().put("id", id).put("postId", postId).put("postUrl", postUrl)
        .put("author", author).put("title", title).put("kind", kind.name).put("preview", preview)
        .put("quoted", quoted).put("variants", JSONArray(variants.map { it.toJson() }))
    companion object {
        fun fromJson(o: JSONObject): MediaItem {
            val a = o.getJSONArray("variants")
            return MediaItem(o.getString("id"), o.getString("postId"), o.getString("postUrl"),
                o.optString("author"), o.optString("title"), MediaKind.valueOf(o.getString("kind")),
                o.optString("preview"), o.optBoolean("quoted"), (0 until a.length()).map { Variant.fromJson(a.getJSONObject(it)) })
        }
    }
}

data class ResolvedPost(val postId: String, val canonicalUrl: String, val items: List<MediaItem>)

object PostLinks {
    private val urlPattern = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    private val statusPattern = Regex("/(?:i/web/|i/)?(?:[A-Za-z0-9_]+/)?status(?:es)?/(\\d+)(?:/|$)")
    fun extract(text: String): String {
        val found = urlPattern.find(text)?.value?.trimEnd('.', ',', ')', '）', ']', '。')
            ?: throw IllegalArgumentException("未找到帖子链接，请分享或粘贴 X 帖子链接。")
        val uri = runCatching { URI(found) }.getOrElse { throw IllegalArgumentException("链接格式无效。") }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("链接格式无效。")
        require(uri.scheme.equals("https", true) && uri.userInfo == null && uri.port in listOf(-1, 443)) { "只支持安全的 X 帖子链接。" }
        require(host in setOf("x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "t.co")) { "只支持 X 帖子链接。" }
        return found
    }
    fun postId(url: String): String {
        val uri = URI(extract(url))
        require(!uri.host.equals("t.co", true)) { "短链接尚未解析。" }
        return statusPattern.find(uri.path)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("请分享具体帖子的链接，而不是用户主页。")
    }
    /** Find a concrete X post in clipboard text without following unrelated short links. */
    fun clipboardPost(text: String): String? = urlPattern.findAll(text).map { match ->
        match.value.trimEnd('.', ',', ')', '）', ']', '。')
    }.firstOrNull { candidate -> runCatching { postId(candidate) }.isSuccess }
    fun safeCdn(url: String): Boolean = runCatching {
        val u = URI(url)
        val host = u.host?.lowercase() ?: return false
        u.scheme.equals("https", true) && u.userInfo == null && u.port in listOf(-1, 443) &&
            (host == "twimg.com" || host.endsWith(".twimg.com"))
    }.getOrDefault(false)
}

object VariantPicker {
    fun best(variants: List<Variant>): Variant {
        require(variants.isNotEmpty()) { "没有可下载的媒体版本。" }
        val sorted = variants.sortedWith(compareByDescending<Variant> { it.area }.thenByDescending { it.bitrate })
        val best = sorted.first()
        val unknown = variants.any { it.area == 0L && it.url != best.url }
        return best.copy(uncertain = best.uncertain || unknown || (best.area == 0L && best.bitrate == 0L))
    }
}

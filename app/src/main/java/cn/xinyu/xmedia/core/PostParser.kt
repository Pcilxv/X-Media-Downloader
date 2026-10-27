package cn.xinyu.xmedia.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object PostParser {
    fun parse(raw: String, postId: String): ResolvedPost {
        val json = JSONObject(raw)
        val match = findTweet(json, postId) ?: throw IllegalStateException("未找到指定帖子，帖子可能已删除或当前账号无权查看。")
        val items = mutableListOf<MediaItem>()
        collect(match, postId, false, items)
        val quoted = unwrap(match.optJSONObject("quoted_status_result")?.optJSONObject("result"))
        if (quoted != null) {
            val quotedId = quoted.optString("rest_id")
            if (quotedId.isNotEmpty() && quotedId != postId) collect(quoted, quotedId, true, items)
        }
        val retweeted = unwrap(match.optJSONObject("legacy")?.optJSONObject("retweeted_status_result")?.optJSONObject("result"))
        if (retweeted != null && items.isEmpty()) {
            val originalId = retweeted.optString("rest_id")
            if (originalId.isNotEmpty()) collect(retweeted, originalId, true, items)
        }
        if (items.isEmpty()) throw IllegalStateException("此帖子没有可识别的图片、动图或视频。")
        return ResolvedPost(postId, "https://x.com/i/web/status/$postId", items.distinctBy { it.postId to it.id })
    }

    private fun unwrap(o: JSONObject?): JSONObject? = o?.optJSONObject("tweet") ?: o

    private fun findTweet(value: Any?, id: String): JSONObject? {
        when (value) {
            is JSONObject -> {
                val obj = unwrap(value) ?: return null
                if (obj.optString("rest_id") == id && obj.optJSONObject("legacy") != null) return obj
                val keys = value.keys()
                while (keys.hasNext()) findTweet(value.opt(keys.next()), id)?.let { return it }
            }
            is JSONArray -> for (i in 0 until value.length()) findTweet(value.opt(i), id)?.let { return it }
        }
        return null
    }

    private fun collect(tweet: JSONObject, postId: String, quoted: Boolean, out: MutableList<MediaItem>) {
        val legacy = tweet.optJSONObject("legacy") ?: return
        val media = legacy.optJSONObject("extended_entities")?.optJSONArray("media") ?: return
        val user = tweet.optJSONObject("core")?.optJSONObject("user_results")?.optJSONObject("result")
        val userLegacy = user?.optJSONObject("legacy")
        val author = userLegacy?.optString("screen_name").orEmpty().ifEmpty { user?.optJSONObject("core")?.optString("screen_name").orEmpty() }
        val title = legacy.optString("full_text").replace('\n', ' ').take(140)
        val origin = if (author.isBlank()) "https://x.com/i/web/status/$postId" else "https://x.com/$author/status/$postId"
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val kind = when (m.optString("type")) { "photo" -> MediaKind.PHOTO; "video" -> MediaKind.VIDEO; "animated_gif" -> MediaKind.GIF; else -> continue }
            val preview = m.optString("media_url_https")
            val variants = if (kind == MediaKind.PHOTO) {
                val url = originalPhoto(preview)
                if (PostLinks.safeCdn(url)) listOf(Variant(url, "image/*")) else emptyList()
            } else {
                val a = m.optJSONObject("video_info")?.optJSONArray("variants") ?: JSONArray()
                (0 until a.length()).mapNotNull { n ->
                    val v = a.optJSONObject(n) ?: return@mapNotNull null
                    val u = v.optString("url")
                    val t = v.optString("content_type")
                    if (!PostLinks.safeCdn(u) || !(t.equals("video/mp4", true) || t.contains("mpegurl", true))) return@mapNotNull null
                    val dimensions = Regex("/(\\d{2,5})x(\\d{2,5})/").find(URI(u).path)
                    Variant(u, t, v.optLong("bitrate"), dimensions?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                        dimensions?.groupValues?.get(2)?.toIntOrNull() ?: 0)
                }
            }
            if (variants.isNotEmpty()) out += MediaItem(m.optString("id_str").ifEmpty { m.optString("media_key").ifEmpty { "$postId-$i" } },
                postId, origin, author, title, kind, preview, quoted, variants)
        }
    }

    private fun originalPhoto(url: String): String {
        val uri = URI(url)
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val kv = it.split('=', limit = 2); if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val ext = query["format"] ?: uri.path.substringAfterLast('.', "jpg")
        val path = if (uri.path.endsWith(".$ext", true)) uri.path.dropLast(ext.length + 1) else uri.path
        return "https://${uri.host}$path?format=$ext&name=orig"
    }
}

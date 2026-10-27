package cn.xinyu.xmedia.core

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class LoginRequired(message: String = "请先登录 X，然后继续下载。") : Exception(message)

class XResolver {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private var transaction: XTransaction? = null
    private var lastCookieFingerprint: Int? = null

    suspend fun resolve(shared: String, webCapture: suspend (String) -> String): ResolvedPost = withContext(Dispatchers.IO) {
        var url = PostLinks.extract(shared)
        if (URI(url).host.equals("t.co", true)) {
            val request = Request.Builder().url(url).header("User-Agent", XTransaction.USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 300..399) throw IllegalStateException("X 短链接无法跳转（${response.code}）。")
                val target = response.header("Location") ?: throw IllegalStateException("X 短链接没有目标地址。")
                url = PostLinks.extract(URI(url).resolve(target).toString())
            }
        }
        val postId = PostLinks.postId(url)
        val cookie = CookieManager.getInstance().getCookie("https://x.com").orEmpty()
        val parts = cookie.split(';').map { it.trim() }.associate {
            val p = it.split('=', limit = 2); p[0] to p.getOrElse(1) { "" }
        }
        if (parts["auth_token"].isNullOrBlank() || parts["ct0"].isNullOrBlank()) throw LoginRequired()
        if (lastCookieFingerprint != cookie.hashCode()) { transaction = null; lastCookieFingerprint = cookie.hashCode() }
        val signing = try { transaction ?: XTransaction.create(client, cookie).also { transaction = it } }
            catch (_: IllegalStateException) { null }
        val variables = JSONObject().put("focalTweetId", postId).put("with_rux_injections", true)
            .put("includePromotedContent", false).put("withCommunity", true)
            .put("withQuickPromoteEligibilityTweetFields", true).put("withBirdwatchNotes", true)
            .put("withVoice", true).put("withV2Timeline", true)
        val toggles = JSONObject().put("withArticleRichContentState", true).put("withArticlePlainText", false)
            .put("withArticleSummaryText", true).put("withArticleVoiceOver", true).put("withGrokAnalyze", false)
            .put("withDisallowedReplyControls", false)
        val path = "/i/api/graphql/wjV7NuWEsHgTBFfW3zYKzQ/TweetDetail"
        val endpoint = "https://x.com$path".toHttpUrl().newBuilder()
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("features", features().toString())
            .addQueryParameter("fieldToggles", toggles.toString()).build()
        val raw = if (signing == null) webCapture(postId) else {
        val request = Request.Builder().url(endpoint).header("User-Agent", XTransaction.USER_AGENT)
            .header("Authorization", PUBLIC_WEB_BEARER).header("Cookie", cookie)
            .header("x-csrf-token", parts.getValue("ct0")).header("x-twitter-auth-type", "OAuth2Session")
            .header("x-twitter-active-user", "yes").header("x-client-transaction-id", signing.calculate("GET", path)).build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                401, 403 -> throw LoginRequired("X 登录已失效或无权查看此帖子，请检查账号状态。")
                429 -> throw IllegalStateException("X 请求过于频繁，请稍后再试。")
                404 -> { transaction = null; throw IllegalStateException("X 接口暂不可用，请重试；如持续发生，需要更新应用。") }
            }
            if (!response.isSuccessful) throw IllegalStateException("读取 X 帖子失败（${response.code}）。")
            response.body?.string() ?: throw IllegalStateException("X 返回了空内容。")
        }
        }
        val json = runCatching { JSONObject(raw) }.getOrElse { throw IllegalStateException("X 返回的数据格式异常，需要重试或更新应用。") }
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0 && (json.isNull("data") || !json.has("data"))) {
            val code = errors.optJSONObject(0)?.optInt("code", -1) ?: -1
            when (code) {
                32, 89, 215, 239 -> throw LoginRequired("X 登录已失效，请重新登录后再试。")
                88, 429 -> throw IllegalStateException("X 请求过于频繁，请稍后再试。")
                34, 144 -> throw IllegalStateException("帖子不存在，或当前账号无权查看。")
                else -> throw IllegalStateException("X 暂时无法解析这个帖子${if (code >= 0) "（错误 $code）" else ""}，请稍后重试。")
            }
        }
        val post = PostParser.parse(raw, postId)
        post.copy(items = post.items.map { item ->
            if (item.kind == MediaKind.VIDEO) item.copy(variants = item.variants.flatMap { if (it.isHls) expandHls(it) else listOf(it) }) else item
        })
    }

    private fun expandHls(master: Variant): List<Variant> {
        require(PostLinks.safeCdn(master.url)) { "媒体地址不安全。" }
        val response = client.newCall(Request.Builder().url(master.url).header("User-Agent", XTransaction.USER_AGENT).build()).execute()
        val text = response.use {
            if (!it.isSuccessful) throw IllegalStateException("读取高清视频信息失败（${it.code}），未自动降级。")
            it.body?.string() ?: throw IllegalStateException("高清视频信息为空。")
        }
        return HlsVariants.expand(master, text)
    }

    private fun features(): JSONObject {
        val trueKeys = listOf("articles_preview_enabled", "c9s_tweet_anatomy_moderator_badge_enabled", "communities_web_enable_tweet_community_results_fetch",
            "creator_subscriptions_tweet_preview_api_enabled", "freedom_of_speech_not_reach_fetch_enabled", "graphql_is_translatable_rweb_tweet_is_translatable_enabled",
            "longform_notetweets_consumption_enabled", "longform_notetweets_inline_media_enabled", "longform_notetweets_rich_text_read_enabled",
            "responsive_web_edit_tweet_api_enabled", "responsive_web_graphql_exclude_directive_enabled", "responsive_web_graphql_skip_user_profile_image_extensions_enabled",
            "responsive_web_graphql_timeline_navigation_enabled", "responsive_web_profile_redirect_enabled", "responsive_web_twitter_article_tweet_consumption_enabled",
            "rweb_tipjar_consumption_enabled", "rweb_video_timestamps_enabled", "standardized_nudges_misinfo", "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled",
            "tweetypie_unmention_optimization_enabled", "view_counts_everywhere_api_enabled", "rweb_video_screen_enabled", "responsive_web_grok_show_grok_translated_post")
        val falseKeys = listOf("creator_subscriptions_quote_tweet_preview_enabled", "responsive_web_enhance_cards_enabled", "responsive_web_grok_community_note_auto_translation_is_enabled",
            "responsive_web_grok_imagine_annotation_enabled", "responsive_web_media_download_video_enabled", "tweet_awards_web_tipping_enabled",
            "tweet_with_visibility_results_prefer_gql_media_interstitial_enabled", "verified_phone_label_enabled", "responsive_web_grok_analyze_button_fetch_trends_enabled",
            "premium_content_api_read_enabled", "profile_label_improvements_pcf_label_in_post_enabled", "responsive_web_grok_share_attachment_enabled",
            "responsive_web_grok_analyze_post_followups_enabled", "responsive_web_grok_image_annotation_enabled", "responsive_web_grok_analysis_button_from_backend", "responsive_web_jetfuel_frame")
        return JSONObject().apply { trueKeys.forEach { put(it, true) }; falseKeys.forEach { put(it, false) } }
    }

    companion object {
        // Public web-client bearer published by X, not a user credential.
        private const val PUBLIC_WEB_BEARER = "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
    }
}

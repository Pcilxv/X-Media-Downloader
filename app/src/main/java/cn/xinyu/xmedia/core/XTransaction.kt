package cn.xinyu.xmedia.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.*

/** Port of the MIT-licensed twscrape XClIdGen and its credited XClientTransaction algorithm. */
class XTransaction private constructor(private val key: IntArray, private val animation: String) {
    fun calculate(method: String, path: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val time = nowSeconds - 1682924400L
        val hash = MessageDigest.getInstance("SHA-256").digest("${method.uppercase()}!$path!${time}obfiowerehiring$animation".toByteArray())
        val payload = mutableListOf<Int>()
        payload.addAll(key.toList())
        for (i in 0..3) payload += ((time shr (i * 8)) and 255).toInt()
        payload.addAll(hash.take(16).map { it.toInt() and 255 })
        payload += 3
        val random = SecureRandom().nextInt(256)
        val encoded = ByteArray(payload.size + 1)
        encoded[0] = random.toByte()
        payload.forEachIndexed { i, b -> encoded[i + 1] = (b xor random).toByte() }
        return Base64.getEncoder().withoutPadding().encodeToString(encoded)
    }

    companion object {
        private val asset = Regex("https://[\\w.-]+/(?:x-web|responsive-web/client-web)/[\\w./-]+\\.js")
        private val indicesFile = Regex("(?:\\.{0,2}/)?[\\w./-]*?\\b(?:ondemand\\.s|sign\\.o)[\\w.-]*\\.js")
        private val indices = Regex("\\(\\w\\[(\\d{1,2})\\],\\s*16\\)")

        fun create(client: OkHttpClient, cookie: String): XTransaction {
            fun fetch(url: String, credentials: Boolean = false): String {
                val u = URI(url)
                if (credentials && u.host != "x.com") throw IllegalStateException("登录信息只能发送给 X。")
                if (!credentials && !PostLinks.safeCdn(url)) throw IllegalStateException("X 资源地址无效。")
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT)
                    .apply { if (credentials) header("Cookie", cookie) }.build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("读取 X 网页失败（${response.code}）。")
                    return response.body?.string() ?: throw IllegalStateException("X 网页内容为空。")
                }
            }
            val html = fetch("https://x.com/tesla", true)
            val doc = Jsoup.parse(html)
            val verification = doc.selectFirst("meta[name=twitter-site-verification]")?.attr("content")
                ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("登录状态未能验证，请重新登录 X。")
            val key = try { Base64.getDecoder().decode(verification).map { it.toInt() and 255 }.toIntArray() }
                catch (_: Exception) { throw IllegalStateException("X 网页验证信息无效。") }
            val scripts = asset.findAll(html).map { it.value }.distinct().toList()
            if (scripts.isEmpty()) throw IllegalStateException("X 网页结构已变化，无法读取请求信息。")
            if (scripts.any { it.contains("entry-client-logged-out") }) {
                throw IllegalStateException("X 为原生请求返回了未登录版本的网页。")
            }
            var signing = scripts.firstOrNull { indicesFile.containsMatchIn(it) }
            if (signing == null) {
                for (script in scripts) {
                    val body = runCatching { fetch(script) }.getOrNull() ?: continue
                    val relative = indicesFile.find(body)?.value ?: continue
                    signing = URI(script).resolve(relative).toString()
                    break
                }
            }
            val signingUrl = signing ?: throw IllegalStateException("X 请求标识暂不可用，需要更新应用。")
            val scriptText = fetch(signingUrl)
            val nums = indices.findAll(scriptText).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            if (nums.isEmpty() || nums.any { it >= key.size } || key.size < 6) throw IllegalStateException("X 请求标识信息无效。")
            val paths = doc.select("svg[id^=loading-x-anim] g:first-child path:nth-child(2)").map { it.attr("d").trim() }
            if (paths.isEmpty()) throw IllegalStateException("X 网页动画信息缺失，请重新登录。")
            val rows = paths[key[5] % paths.size].drop(9).split('C').map { row ->
                Regex("\\d+").findAll(row).map { it.value.toDouble() }.toList()
            }
            val frameIndex = key[nums.first()] % 16
            if (frameIndex >= rows.size || rows[frameIndex].size < 11) throw IllegalStateException("X 请求标识解析失败。")
            var time = 1L
            nums.drop(1).forEach { time *= (key[it] % 16) }
            val frameTime = floor(time / 10.0 + 0.5) * 10.0 / 4096.0
            return XTransaction(key, animationKey(rows[frameIndex], frameTime))
        }

        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

        private fun solve(v: Double, minimum: Double, maximum: Double, floorIt: Boolean): Double {
            val result = v * (maximum - minimum) / 255.0 + minimum
            return if (floorIt) floor(result) else Math.rint(result * 100) / 100
        }
        private fun cubic(a: Double, b: Double, t: Double) = 3*a*(1-t)*(1-t)*t + 3*b*(1-t)*t*t + t*t*t
        private fun curve(c: List<Double>, t: Double): Double {
            if (t <= 0) return (if (c[0] > 0) c[1] / c[0] else if (c[1] == 0.0 && c[2] > 0) c[3] / c[2] else 0.0) * t
            if (t >= 1) return 1 + (if (c[2] < 1) (c[3]-1)/(c[2]-1) else if (c[2] == 1.0 && c[0] < 1) (c[1]-1)/(c[0]-1) else 0.0) * (t-1)
            var low = 0.0; var high = 1.0; var mid = 0.0
            repeat(60) { mid = (low+high)/2; val x = cubic(c[0],c[2],mid); if (abs(t-x) < 0.00001) return cubic(c[1],c[3],mid); if (x < t) low=mid else high=mid }
            return cubic(c[1],c[3],mid)
        }
        private fun hexFloat(value: Double): String {
            var x = value; var quotient = x.toInt(); var fraction = x - quotient; val result = StringBuilder()
            while (quotient > 0) { quotient = (x / 16).toInt(); val remainder = (x - quotient * 16).toInt(); result.insert(0, remainder.toString(16).uppercase()); x = quotient.toDouble() }
            if (fraction == 0.0) return result.toString()
            result.append('.')
            repeat(64) { if (fraction == 0.0) return result.toString(); fraction *= 16; val integer=fraction.toInt(); fraction-=integer; result.append(integer.toString(16).uppercase()) }
            return result.toString()
        }
        internal fun animationKey(frames: List<Double>, time: Double): String {
            val curves = frames.drop(7).mapIndexed { i, x -> solve(x, if (i % 2 == 0) 0.0 else -1.0, 1.0, false) }
            val t = curve(curves, time)
            val colors = (0..2).map { (frames[it]*(1-t)+frames[it+3]*t).coerceIn(0.0,255.0) }
            val rotation = solve(frames[6],60.0,360.0,true)*t * PI / 180.0
            val matrix = listOf(cos(rotation), -sin(rotation), sin(rotation), cos(rotation))
            val parts = colors.map { Math.rint(it).toInt().toString(16) }.toMutableList()
            matrix.forEach { v ->
                val h = hexFloat(abs(Math.rint(v*100)/100))
                parts += when { h.startsWith('.') -> "0$h".lowercase(); h.isEmpty() -> "0"; else -> h }
            }
            parts += listOf("0", "0")
            return parts.joinToString("").replace(".", "").replace("-", "")
        }
    }
}

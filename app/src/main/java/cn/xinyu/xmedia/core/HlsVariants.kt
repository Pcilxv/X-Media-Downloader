package cn.xinyu.xmedia.core

import java.net.URI

object HlsVariants {
    fun expand(master: Variant, text: String): List<Variant> {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val common = lines.filter { it.startsWith("#EXT-X-VERSION:") || it == "#EXT-X-INDEPENDENT-SEGMENTS" ||
            it.startsWith("#EXT-X-MEDIA:") || it.startsWith("#EXT-X-SESSION-KEY:") }.map { line ->
            Regex("URI=\"([^\"]+)\"").replace(line) { match ->
                val url = URI(master.url).resolve(match.groupValues[1]).toString()
                require(PostLinks.safeCdn(url)) { "流媒体包含非 X 的媒体地址。" }
                "URI=\"$url\""
            }
        }
        val variants = mutableListOf<Variant>()
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue
            val attrs = lines[i].substringAfter(':')
            val dimensions = Regex("RESOLUTION=(\\d+)x(\\d+)").find(attrs)
            val bandwidth = Regex("(?:^|,)BANDWIDTH=(\\d+)").find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val next = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith('#') } ?: continue
            val url = URI(master.url).resolve(next).toString()
            require(PostLinks.safeCdn(url)) { "流媒体包含非 X 的媒体地址。" }
            val manifest = (listOf("#EXTM3U") + common + listOf(lines[i], url, "")).joinToString("\n")
            variants += Variant(url, "application/x-mpegURL", bandwidth,
                dimensions?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                dimensions?.groupValues?.get(2)?.toIntOrNull() ?: 0, hlsManifest = manifest)
        }
        return variants.ifEmpty { listOf(master.copy(uncertain = true)) }
    }
}

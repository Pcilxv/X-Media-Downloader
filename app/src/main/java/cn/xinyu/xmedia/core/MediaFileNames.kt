package cn.xinyu.xmedia.core

/** The X handle belongs to the media's original post, including quoted posts. */
object MediaFileNames {
    private val validHandle = Regex("[A-Za-z0-9_]{1,15}")
    private val unsafeId = Regex("[^A-Za-z0-9_-]")

    private fun authorPrefix(author: String): String {
        val handle = author.trim().removePrefix("@")
        return if (validHandle.matches(handle)) "@$handle" else "未知用户"
    }
    private fun safeId(value: String) = value.replace(unsafeId, "_").take(80).ifBlank { "unknown" }

    fun stem(item: MediaItem, timestamp: Long): String {
        return "${authorPrefix(item.author)}_${safeId(item.postId)}_${safeId(item.id)}_$timestamp"
    }

    fun frameStem(author: String, postUrl: String, timestamp: Long): String {
        val postId = runCatching { PostLinks.postId(postUrl) }.getOrDefault("unknown")
        return "${authorPrefix(author)}_${safeId(postId)}_截帧_$timestamp"
    }
}

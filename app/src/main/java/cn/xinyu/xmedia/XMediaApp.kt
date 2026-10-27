package cn.xinyu.xmedia

import android.app.Application
import android.net.Uri
import cn.xinyu.xmedia.core.XResolver
import cn.xinyu.xmedia.core.MediaKind
import cn.xinyu.xmedia.data.HistoryStore
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder

class XMediaApp : Application() {
    lateinit var history: HistoryStore
        private set
    val resolver = XResolver()
    val imageLoader: ImageLoader by lazy { ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build() }
    fun thumbnailRequest(uri: String, kind: MediaKind): ImageRequest = ImageRequest.Builder(this).data(Uri.parse(uri)).apply {
        if (kind == MediaKind.VIDEO) decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
    }.build()
    override fun onCreate() { super.onCreate(); history = HistoryStore(this) }
}

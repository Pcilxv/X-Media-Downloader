package cn.xinyu.xmedia.download

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.content.Context
import android.graphics.Bitmap
import androidx.media3.inspector.MediaExtractorCompat
import com.bumptech.glide.gifencoder.AnimatedGifEncoder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.roundToInt

/** Decodes one frame at a time and writes GIF incrementally; it never holds the entire animation in memory. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object GifConverter {
    suspend fun convert(context: Context, source: File, output: File, progress: (String) -> Unit) {
        // Media3 parses X's MP4 timing tables that some Android system extractors reject.
        val extractor = MediaExtractorCompat(context.applicationContext)
        var codec: MediaCodec? = null
        var bitmap: Bitmap? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("动图文件中没有视频轨道。")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("无法识别动图格式。")
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var encoder: AnimatedGifEncoder? = null; var lastPixels: IntArray? = null
            var lastTime = 0L; var lastDelay = 100L; var width = 0; var height = 0; var frames = 0
            output.outputStream().buffered().use { stream ->
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index) ?: error("无法读取动图帧。")
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        if (info.size > 0) {
                            val image = codec.getOutputImage(index) ?: error("当前手机的视频解码器无法转换这张动图。")
                            val frameWidth = image.cropRect.width(); val frameHeight = image.cropRect.height()
                            val estimatedBytes = frameWidth.toLong() * frameHeight * 32
                            if (estimatedBytes > Runtime.getRuntime().maxMemory() / 2) {
                                image.close()
                                error("这张动图的原始尺寸超出当前手机的可用内存，无法安全转换。")
                            }
                            val pixels = image.use { imageToRgb(it) }
                            if (encoder == null) {
                                width = frameWidth; height = frameHeight
                                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                encoder = AnimatedGifEncoder().apply {
                                    setSize(width, height); setRepeat(0); setQuality(10)
                                    check(start(stream)) { "无法创建 GIF 文件。" }
                                }
                            }
                            if (pixels.size != width * height) error("动图的帧尺寸发生变化，无法安全转换。")
                            if (lastPixels != null) {
                                lastDelay = ((info.presentationTimeUs - lastTime) / 1000).coerceAtLeast(10)
                                bitmap!!.setPixels(lastPixels, 0, width, 0, 0, width, height)
                                encoder.setDelay(lastDelay.coerceAtMost(655350).toInt())
                                check(encoder.addFrame(bitmap)) { "写入 GIF 帧失败。" }
                            }
                            lastPixels = pixels; lastTime = info.presentationTimeUs; frames++
                            if (frames % 3 == 0) progress("正在转换 GIF · 已处理 $frames 帧")
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
                val last = lastPixels ?: error("动图没有可解码的画面。")
                bitmap!!.setPixels(last, 0, width, 0, 0, width, height)
                encoder!!.setDelay(lastDelay.coerceAtMost(655350).toInt())
                check(encoder.addFrame(bitmap)) { "写入 GIF 帧失败。" }
                check(encoder.finish()) { "完成 GIF 文件失败。" }
            }
        } finally {
            bitmap?.recycle(); runCatching { codec?.stop() }; codec?.release(); extractor.release()
        }
    }

    private fun imageToRgb(image: Image): IntArray {
        require(image.planes.size >= 3) { "不支持的动图像素格式。" }
        val crop = image.cropRect; val w = crop.width(); val h = crop.height(); val p = image.planes
        val yBuf = p[0].buffer; val uBuf = p[1].buffer; val vBuf = p[2].buffer
        val yStart = yBuf.position(); val uStart = uBuf.position(); val vStart = vBuf.position()
        val out = IntArray(w*h)
        for (y in 0 until h) for (x in 0 until w) {
            val px = crop.left+x; val py = crop.top+y
            val yy = (yBuf.get(yStart + py*p[0].rowStride + px*p[0].pixelStride).toInt() and 255) - 16
            val u = (uBuf.get(uStart + (py/2)*p[1].rowStride + (px/2)*p[1].pixelStride).toInt() and 255) - 128
            val v = (vBuf.get(vStart + (py/2)*p[2].rowStride + (px/2)*p[2].pixelStride).toInt() and 255) - 128
            val c = 1.164 * yy.coerceAtLeast(0)
            val r = (c+1.596*v).roundToInt().coerceIn(0,255)
            val g = (c-0.392*u-0.813*v).roundToInt().coerceIn(0,255)
            val b = (c+2.017*u).roundToInt().coerceIn(0,255)
            out[y*w+x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }
}

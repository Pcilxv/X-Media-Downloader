package cn.xinyu.xmedia.core

import org.junit.Assert.*
import org.junit.Test

class PostParserTest {
    private val sample = """
      {"data":{"items":[
        {"rest_id":"88","legacy":{"extended_entities":{"media":[{"id_str":"wrong","type":"photo","media_url_https":"https://pbs.twimg.com/media/wrong.jpg"}]}}},
        {"rest_id":"123","legacy":{"full_text":"测试帖子","extended_entities":{"media":[
          {"id_str":"p1","type":"photo","media_url_https":"https://pbs.twimg.com/media/picture.jpg"},
          {"id_str":"v1","type":"video","media_url_https":"https://pbs.twimg.com/preview.jpg","video_info":{"variants":[
            {"content_type":"video/mp4","bitrate":832000,"url":"https://video.twimg.com/vid/640x360/v.mp4"},
            {"content_type":"video/mp4","bitrate":2176000,"url":"https://video.twimg.com/vid/1280x720/v.mp4"}]}},
          {"id_str":"g1","type":"animated_gif","media_url_https":"https://pbs.twimg.com/g.jpg","video_info":{"variants":[
            {"content_type":"video/mp4","url":"https://video.twimg.com/tweet_video/g.mp4"}]}}
        ]}},"core":{"user_results":{"result":{"legacy":{"screen_name":"alice"}}}},
        "quoted_status_result":{"result":{
          "rest_id":"456","legacy":{"full_text":"引用帖","extended_entities":{"media":[
            {"id_str":"q1","type":"photo","media_url_https":"https://pbs.twimg.com/media/quote.png"}]}},
          "core":{"user_results":{"result":{"legacy":{"screen_name":"quoted_author"}}}}
        }}}
      ]}}
    """.trimIndent()

    @Test fun findsExactPostAndKeepsMixedMediaOrderAndQuoteOrigin() {
        val result = PostParser.parse(sample, "123")
        assertEquals(listOf("p1", "v1", "g1", "q1"), result.items.map { it.id })
        assertEquals(listOf(MediaKind.PHOTO, MediaKind.VIDEO, MediaKind.GIF, MediaKind.PHOTO), result.items.map { it.kind })
        assertEquals("https://pbs.twimg.com/media/picture?format=jpg&name=orig", result.items[0].variants.single().url)
        assertEquals("https://x.com/alice/status/123", result.items[0].postUrl)
        assertTrue(result.items.last().quoted)
        assertTrue(result.items.last().postUrl.endsWith("/456"))
        assertEquals(1280, VariantPicker.best(result.items[1].variants).width)
    }
    @Test fun cannotAccidentallyDownloadOtherPost() {
        val result = runCatching { PostParser.parse(sample, "999") }
        assertTrue(result.isFailure)
    }
    @Test fun validatesUrlsAndDetectsUnknownQuality() {
        assertEquals("123", PostLinks.postId("分享 https://x.com/alice/status/123?s=20"))
        assertEquals("123", PostLinks.postId("https://twitter.com/i/web/status/123"))
        assertTrue(runCatching { PostLinks.extract("https://x.com.attacker.example/a/status/123") }.isFailure)
        assertFalse(PostLinks.safeCdn("https://video.twimg.com.attacker.example/v.mp4"))
        val v = listOf(Variant("https://video.twimg.com/a.mp4", "video/mp4", 1200, 1280, 720),
            Variant("https://video.twimg.com/b.mp4", "video/mp4", 2200))
        assertTrue(VariantPicker.best(v).uncertain)
    }
    @Test fun clipboardAcceptsOnlyConcreteXPostsAndSkipsUnrelatedUrls() {
        assertEquals("https://x.com/alice/status/123?s=20", PostLinks.clipboardPost("分享 https://example.com/first https://x.com/alice/status/123?s=20"))
        assertEquals("https://twitter.com/i/web/status/456", PostLinks.clipboardPost("https://twitter.com/i/web/status/456。"))
        assertNull(PostLinks.clipboardPost("https://x.com/alice"))
        assertNull(PostLinks.clipboardPost("https://x.com.attacker.example/alice/status/123"))
        assertNull(PostLinks.clipboardPost("https://t.co/other-site"))
    }
    @Test fun serializationPreservesSelectionIdentity() {
        val item = PostParser.parse(sample, "123").items[1]
        assertEquals(item, MediaItem.fromJson(item.toJson()))
    }
    @Test fun filenamesUseOriginalAuthorAndSafeFallback() {
        val item = PostParser.parse(sample, "123").items[1]
        assertEquals("@alice_123_v1_1000", MediaFileNames.stem(item, 1000))
        assertEquals("@alice_123_v1_1000", MediaFileNames.stem(item.copy(author = "@alice"), 1000))
        assertEquals("未知用户_123_v1_1000", MediaFileNames.stem(item.copy(author = ""), 1000))
        assertEquals("未知用户_123_bad_name_1000", MediaFileNames.stem(item.copy(author = "../other", id = "bad/name"), 1000))
        val quote = PostParser.parse(sample, "123").items.last()
        assertTrue(quote.quoted)
        assertEquals("@quoted_author_456_q1_1000", MediaFileNames.stem(quote, 1000))
        assertEquals("@alice_123_截帧_1000", MediaFileNames.frameStem("alice", item.postUrl, 1000))
        assertEquals("未知用户_unknown_截帧_1000", MediaFileNames.frameStem("../unknown", "not a link", 1000))
    }
    @Test fun hlsChoiceKeepsSeparateAudioAndRejectsOffsiteMedia() {
        val root = Variant("https://video.twimg.com/path/master.m3u8", "application/x-mpegURL")
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Main",URI="sound.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360,AUDIO="aud"
            lo.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,AUDIO="aud"
            hi.m3u8
        """.trimIndent()
        val chosen = VariantPicker.best(HlsVariants.expand(root, playlist))
        assertEquals(1080, chosen.height)
        assertTrue(chosen.hlsManifest!!.contains("URI=\"https://video.twimg.com/path/sound.m3u8\""))
        assertTrue(chosen.hlsManifest!!.contains("https://video.twimg.com/path/hi.m3u8"))
        assertFalse(chosen.hlsManifest!!.contains("lo.m3u8"))
        assertTrue(runCatching { HlsVariants.expand(root, playlist.replace("sound.m3u8", "https://evil.example/a")) }.isFailure)
    }
    @Test fun transactionAnimationMatchesUpstreamReferenceVectors() {
        val frames = listOf(12.0,127.0,65.0,250.0,28.0,200.0,120.0,100.0,150.0,180.0,220.0)
        assertEquals("56606b075c28f5c28f5c40e3d70a3d70a3d80e3d70a3d70a3d8075c28f5c28f5c400", XTransaction.animationKey(frames, .4))
        val second = listOf(1.0,25.0,80.0,240.0,140.0,50.0,20.0,40.0,220.0,160.0,30.0)
        assertEquals("11950100100", XTransaction.animationKey(second, .0))
        assertEquals("f08c3201eb851eb851eb80fd70a3d70a3d70fd70a3d70a3d701eb851eb851eb800", XTransaction.animationKey(second, 1.0))
    }
}

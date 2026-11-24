package com.zachvlat.freetok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TikTokDownloaderManagerTest {
    @Test
    fun extractsAllowedVideoLink() {
        val html = """
            <div class="col-sm-9">
                <a href="https://v16.tiktokcdn.com/video/123.mp4">Video without watermark</a>
            </div>
        """.trimIndent()
        assertEquals(
            "https://v16.tiktokcdn.com/video/123.mp4",
            TikTokDownloaderManager.extractDownloadLink(html)
        )
    }

    @Test
    fun rejectsLinksToOtherHosts() {
        val html = """<div class="col-sm-9"><a href="https://attacker.example/video.mp4">without</a></div>"""
        assertNull(TikTokDownloaderManager.extractDownloadLink(html))
    }

    @Test
    fun extractsVideoTitle() {
        val html = """<html><head><meta property="og:title" content="Funny cat"></head></html>"""
        assertEquals("Funny cat", TikTokDownloaderManager.extractTitle(html))
    }

    @Test
    fun ignores404TextInPage() {
        val html = """
            <p>Look at 404 in this comment</p>
            <div class="download-links"><a href="https://www.tiktok.com/video/1.mp4">download</a></div>
        """.trimIndent()
        assertEquals(
            "https://www.tiktok.com/video/1.mp4",
            TikTokDownloaderManager.extractDownloadLink(html)
        )
    }
}

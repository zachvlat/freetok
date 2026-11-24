package com.zachvlat.freetok

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object TikTokDownloaderManager {
    private const val DOWNLOAD_DIR = "freetok_videos"
    private const val MAX_VIDEO_BYTES = 500L * 1024 * 1024

    // One client for every call: OkHttp is thread-safe and reuses connections.
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun downloadVideo(tiktokUrl: String, context: Context): Pair<String, String?>? = withContext(Dispatchers.IO) {
        try {
            val finalUrl = resolveShortUrl(tiktokUrl)
            val pageHtml = submitUrl(finalUrl)
            val extracted = pageHtml?.let { extractDownloadLink(it) }
            val (videoUrl, cookies, pageTitle) = if (extracted != null) {
                Triple(extracted, null, pageHtml?.let { extractTitle(it) })
            } else {
                tryAlternativeDownloader(finalUrl)
            }
            if (videoUrl == null) return@withContext null
            val file = downloadVideoFile(videoUrl, context, cookies) ?: return@withContext null
            Pair(file.absolutePath, pageTitle ?: titleFromUrl(finalUrl))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // We only follow vm.tiktok.com links; everything else is already final.
    private fun resolveShortUrl(url: String): String {
        if (!url.contains("vm.tiktok.com")) return url
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                response.request.url.toString()
            }
        } catch (e: Exception) {
            url
        }
    }

    private suspend fun submitUrl(tiktokUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val initialHtml = client.newCall(
                Request.Builder().url("https://urlebird.com/snap/").get().build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }

            val hiddenInput = Jsoup.parse(initialHtml).select("input[type=hidden]").firstOrNull()
                ?: return@withContext null
            val hiddenValue = hiddenInput.attr("value")
            val hiddenName = hiddenInput.attr("name").ifBlank { "7bcf5d98fa85924fc353b1825576aaf8aa230262" }

            val formBody = FormBody.Builder()
                .add("url", tiktokUrl)
                .add(hiddenName, hiddenValue)
                .build()

            client.newCall(
                Request.Builder()
                    .url("https://urlebird.com/snap/")
                    .addHeader("Referer", "https://urlebird.com/snap/")
                    .addHeader("Origin", "https://urlebird.com")
                    .post(formBody)
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string() ?: return@withContext null
                if (html.contains("TikTok Downloader") &&
                    html.contains("Save TikTok videos") &&
                    html.length < 50000
                ) return@withContext null
                html
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    internal fun extractDownloadLink(html: String): String? {
        if (html.contains("Url is empty", true) ||
            html.contains("incorrectly", true) ||
            html.contains("error has occurred", true) ||
            html.contains("rate limit", true)
        ) return null

        val doc = Jsoup.parse(html)
        val selectors = listOf(
            ".col-sm-9 a",
            ".download-links a",
            "a[href*='.mp4']",
            "a[href*='tiktokcdn.com']",
            "a:contains(without)",
            "a[href*=download]"
        )
        for (selector in selectors) {
            val links = try { doc.select(selector) } catch (e: Exception) { continue }
            for (link in links) {
                val href = link.attr("href")
                if (href.isNotEmpty() &&
                    !href.contains("{") &&
                    !href.contains("javascript") &&
                    allowedHttpsUrl(href) != null
                ) return href
            }
        }
        return null
    }

    internal fun extractTitle(html: String): String? {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        if (title.isNullOrBlank() || title.contains("TikTok Downloader", true)) return null
        return title.take(120)
    }

    private fun titleFromUrl(url: String): String? {
        val parts = url.trimEnd('/').split('/')
        val id = parts.lastOrNull()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val creator = parts.firstOrNull { it.startsWith("@") }
        return when {
            creator != null && id != null -> "$creator · $id"
            id != null -> "Video $id"
            else -> null
        }
    }

    private suspend fun tryAlternativeDownloader(tiktokUrl: String): Triple<String?, String?, String?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(tiktokUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Triple(null, null, null)
                val body = response.body ?: return@withContext Triple(null, null, null)
                val cookies = response.headers("Set-Cookie").joinToString("; ") { cookie ->
                    cookie.split(";")[0].trim()
                }
                val html = String(body.bytes(), Charsets.UTF_8)
                if (!html.contains("<html") && !html.contains("<!DOCTYPE") && !html.contains("tiktok")) {
                    return@withContext Triple(null, null, null)
                }

                val patterns = listOf(
                    "\"playAddr\":\"([^\"]+)\"",
                    "\"downloadAddr\":\"([^\"]+)\"",
                    "\"uri\":\"([^\"]+\\.mp4[^\"]*)\"",
                    "\"url\":\"([^\"]+tiktokcdn[^\"]*)\"",
                    "\"src\":\"([^\"]+\\.mp4[^\"]*)\"",
                    "playAddr: ?\"([^\"]+)\"",
                    "downloadAddr: ?\"([^\"]+)\"",
                    "videoUrl: ?\"([^\"]+)\"",
                    "src: ?\"([^\"]+\\.mp4[^\"]*)\"",
                    "https://[^\"']*tiktokcdn[^\"']*\\.mp4",
                    "https://[^\"']*p16-sign[^\"']*\\.mp4",
                    "https://[^\"']*v\\.tiktok[^\"']*\\.mp4"
                )
                for (pattern in patterns) {
                    try {
                        for (match in Regex(pattern, RegexOption.IGNORE_CASE).findAll(html)) {
                            val candidate = match.groupValues.getOrNull(1) ?: match.value
                            val videoUrl = candidate.replace("\\u002F", "/")
                                .replace("\\/", "/")
                                .replace("\\", "")
                                .replace("\"", "")
                                .trim()
                            if (allowedHttpsUrl(videoUrl) != null) {
                                return@withContext Triple(videoUrl, cookies, extractTitle(html))
                            }
                        }
                    } catch (e: Exception) {
                        // A bad pattern must not stop the other patterns.
                    }
                }

                val scripts = Regex(
                    "<script[^>]*>([^<]*(?:videoURL|playAddr|downloadAddr)[^<]*)</script>",
                    RegexOption.IGNORE_CASE
                )
                for (script in scripts.findAll(html)) {
                    for (pattern in patterns.take(5)) {
                        val match = try {
                            Regex(pattern, RegexOption.IGNORE_CASE).find(script.groupValues[1])
                        } catch (e: Exception) {
                            null
                        }
                        val candidate = match?.groupValues?.getOrNull(1) ?: continue
                        val videoUrl = candidate.replace("\\u002F", "/")
                            .replace("\\/", "/")
                            .replace("\\", "")
                            .replace("\"", "")
                            .trim()
                        if (allowedHttpsUrl(videoUrl) != null) {
                            return@withContext Triple(videoUrl, cookies, extractTitle(html))
                        }
                    }
                }
                Triple(null, null, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Triple(null, null, null)
        }
    }

    private suspend fun downloadVideoFile(
        videoUrl: String,
        context: Context,
        cookies: String? = null
    ): File? = withContext(Dispatchers.IO) {
        val parsed = allowedHttpsUrl(videoUrl) ?: return@withContext null
        val downloadDir = File(context.getExternalFilesDir(null) ?: return@withContext null, DOWNLOAD_DIR)
        if (!downloadDir.exists() && !downloadDir.mkdirs()) return@withContext null

        val finalFile = try {
            File.createTempFile("tiktok_", ".mp4", downloadDir)
        } catch (e: IOException) {
            return@withContext null
        }
        val tempFile = File(downloadDir, "${finalFile.name}.part")

        try {
            val requestBuilder = Request.Builder()
                .url(parsed)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://www.tiktok.com/")
                .addHeader("Origin", "https://www.tiktok.com")
                .addHeader("Accept", "*/*")
            // Cookies came from TikTok; never send them to another site.
            if (!cookies.isNullOrEmpty() && isTikTokHost(parsed.host)) {
                requestBuilder.addHeader("Cookie", cookies)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (allowedHttpsUrl(response.request.url.toString()) == null) {
                    throw IOException("redirect to an untrusted host")
                }
                if (!response.isSuccessful) throw IOException("download failed: ${response.code}")
                val body = response.body ?: throw IOException("empty response")
                val contentType = response.header("Content-Type") ?: ""
                if (!contentType.startsWith("video/", true) &&
                    !contentType.startsWith("application/octet-stream", true)
                ) throw IOException("not a video: $contentType")
                if (body.contentLength() > MAX_VIDEO_BYTES) throw IOException("video too large")

                FileOutputStream(tempFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var total = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            total += bytesRead
                            if (total > MAX_VIDEO_BYTES) throw IOException("video too large")
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }
            if (!tempFile.renameTo(finalFile)) throw IOException("could not save video")
            finalFile
        } catch (e: Exception) {
            tempFile.delete()
            finalFile.delete()
            e.printStackTrace()
            null
        }
    }

    private fun isTikTokHost(host: String): Boolean {
        val value = host.lowercase()
        return value == "tiktok.com" || value.endsWith(".tiktok.com") ||
            value == "tiktokcdn.com" || value.endsWith(".tiktokcdn.com") ||
            value == "tiktokcdn-us.com" || value.endsWith(".tiktokcdn-us.com")
    }

    private fun allowedHttpsUrl(url: String): HttpUrl? {
        val parsed = try { url.toHttpUrlOrNull() } catch (e: Exception) { null } ?: return null
        val allowed = isTikTokHost(parsed.host) ||
            parsed.host == "urlebird.com" ||
            parsed.host.endsWith(".urlebird.com")
        return if (parsed.scheme == "https" && allowed) parsed else null
    }
}

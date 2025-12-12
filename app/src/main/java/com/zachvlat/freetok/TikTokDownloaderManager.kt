package com.zachvlat.freetok

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object TikTokDownloaderManager {
    
    private const val DOWNLOAD_DIR = "freetok_videos"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(GzipInterceptor()) // custom gzip handler
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Accept-Encoding", "gzip, deflate, br")
                .addHeader("Cache-Control", "max-age=0")
                .addHeader("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                .addHeader("Sec-Ch-Ua-Mobile", "?0")
                .addHeader("Sec-Ch-Ua-Platform", "\"macOS\"")
                .addHeader("Sec-Fetch-Dest", "document")
                .addHeader("Sec-Fetch-Mode", "navigate")
                .addHeader("Sec-Fetch-Site", "none")
                .addHeader("Sec-Fetch-User", "?1")
                .addHeader("Upgrade-Insecure-Requests", "1")
                .addHeader("Connection", "keep-alive")
                .build()
            chain.proceed(request)
        }
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // -------------------------------
    // GZIP INTERCEPTOR (FIXED)
    // -------------------------------
    private class GzipInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)

            val contentEncoding = response.header("Content-Encoding")
            val contentTypeHeader = response.header("Content-Type")

            // If server returns gzip manually (non-standard)
            if (contentEncoding != null && contentEncoding.contains("gzip", ignoreCase = true)) {

                val compressedBytes = response.body?.bytes() ?: return response

                val decompressedBytes = GZIPInputStream(compressedBytes.inputStream()).use {
                    it.readBytes()
                }

                // Parse mediaType safely
                val mediaType = contentTypeHeader?.toMediaTypeOrNull()
                    ?: "text/html; charset=utf-8".toMediaType()

                val newBody = ResponseBody.create(mediaType, decompressedBytes)

                return response.newBuilder()
                    .body(newBody)
                    .removeHeader("Content-Encoding") // prevent double-unzip
                    .build()
            }

            return response
        }
    }

    // -------------------------------
    // MAIN DOWNLOAD METHOD
    // -------------------------------
    suspend fun downloadVideo(tiktokUrl: String, context: Context): String? {
        return withContext(Dispatchers.IO) {
            try {
                val finalUrl = resolveShortUrl(tiktokUrl)
                android.util.Log.d("FreeTok", "Resolved URL: $finalUrl")

                // Try urlebird.com first
                val html = submitUrl(finalUrl)
                var videoUrl: String? = null
                var cookies: String? = null
                
                if (html != null) {
                    android.util.Log.d("FreeTok", "HTML received from urlebird.com, length: ${html.length}")
                    
                    val downloadLink = extractDownloadLink(html)
                    if (downloadLink != null) {
                        android.util.Log.d("FreeTok", "Download link found from urlebird.com: $downloadLink")
                        videoUrl = downloadLink
                    } else {
                        android.util.Log.e("FreeTok", "No download link found in urlebird.com HTML")
                    }
                } else {
                    android.util.Log.e("FreeTok", "urlebird.com returned null")
                }

                // Fallback: Try alternative method
                if (videoUrl == null) {
                    android.util.Log.d("FreeTok", "Trying alternative download method")
                    val result = tryAlternativeDownloader(finalUrl)
                    videoUrl = result.first
                    cookies = result.second
                }

                if (videoUrl != null) {
                    // Download the video file locally
                    val localFile = downloadVideoFile(videoUrl, context, cookies)
                    if (localFile != null && localFile.exists()) {
                        android.util.Log.d("FreeTok", "Video downloaded successfully: ${localFile.absolutePath}")
                        return@withContext localFile.absolutePath
                    } else {
                        android.util.Log.e("FreeTok", "Failed to download video file")
                        return@withContext null
                    }
                } else {
                    android.util.Log.e("FreeTok", "Could not get video URL")
                    return@withContext null
                }
                
            } catch (e: Exception) {
                android.util.Log.e("FreeTok", "Exception in downloadVideo: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    // -------------------------------
    // RESOLVE SHORT TIKTOK URL
    // -------------------------------
    private suspend fun resolveShortUrl(url: String): String {
        return if (url.contains("vm.tiktok.com")) {
            try {
                val tempClient = OkHttpClient()
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                val response = tempClient.newCall(request).execute()
                response.request.url.toString()
            } catch (e: Exception) {
                url // fallback
            }
        } else url
    }

    // -------------------------------
    // SUBMIT URL TO URLEBIRD.COM
    // -------------------------------
    private suspend fun submitUrl(tiktokUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("FreeTok", "Making initial request to urlebird.com")
            val initialRes = client.newCall(
                Request.Builder()
                    .url("https://urlebird.com/snap/")
                    .get()
                    .build()
            ).execute()

            if (!initialRes.isSuccessful) {
                android.util.Log.e("FreeTok", "Initial request failed: ${initialRes.code} ${initialRes.message}")
                return@withContext null
            }
            
            val initialHtml = initialRes.body?.string() ?: run {
                android.util.Log.e("FreeTok", "Initial response body is null")
                return@withContext null
            }

            android.util.Log.d("FreeTok", "Initial HTML length: ${initialHtml.length}")
            
            val doc = Jsoup.parse(initialHtml)
            
            // Look for multiple possible hidden input names
            val hiddenInput = doc.select("input[type=hidden]").firstOrNull()
            val hiddenValue = hiddenInput?.attr("value")
            val hiddenName = hiddenInput?.attr("name")
            
            if (hiddenValue == null) {
                android.util.Log.e("FreeTok", "Could not find hidden input value")
                android.util.Log.d("FreeTok", "Available inputs: ${doc.select("input").map { it.attr("name") to it.attr("value") }}")
                return@withContext null
            }
            
            android.util.Log.d("FreeTok", "Hidden input name: $hiddenName, value: ${hiddenValue.take(20)}...")

            // Build form with the correct input name
            val formBody = FormBody.Builder()
                .add("url", tiktokUrl)
                .add(hiddenName ?: "7bcf5d98fa85924fc353b1825576aaf8aa230262", hiddenValue)
                .build()

            android.util.Log.d("FreeTok", "Submitting form to urlebird.com")
            val submitRes = client.newCall(
                Request.Builder()
                    .url("https://urlebird.com/snap/")
                    .addHeader("Referer", "https://urlebird.com/snap/")
                    .addHeader("Origin", "https://urlebird.com")
                    .post(formBody)
                    .build()
            ).execute()

            if (!submitRes.isSuccessful) {
                android.util.Log.e("FreeTok", "Submit request failed: ${submitRes.code} ${submitRes.message}")
                return@withContext null
            }

            val responseHtml = submitRes.body?.string()
            android.util.Log.d("FreeTok", "Submit response length: ${responseHtml?.length ?: 0}")
            
            // Check if we got the same page back
            if (responseHtml?.contains("TikTok Downloader") == true && 
                responseHtml.contains("Save TikTok videos") &&
                responseHtml.length < 50000) {
                android.util.Log.e("FreeTok", "Got homepage instead of results - urlebird.com is blocking requests")
                return@withContext null
            }
            
            responseHtml
        } catch (e: IOException) {
            android.util.Log.e("FreeTok", "IOException in submitUrl: ${e.message}")
            e.printStackTrace()
            null
        } catch (e: Exception) {
            android.util.Log.e("FreeTok", "Exception in submitUrl: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // -------------------------------
    // PARSE VIDEO DOWNLOAD LINK
    // -------------------------------
    private fun extractDownloadLink(html: String): String? {
        try {
            // Check for server errors
            if (html.contains("Url is empty", true) ||
                html.contains("incorrectly", true) ||
                html.contains("error has occurred", true) ||
                html.contains("403", true) ||
                html.contains("404", true) ||
                html.contains("rate limit", true)
            ) {
                return null
            }

            val doc = Jsoup.parse(html)
            
            // Try multiple selectors for robustness
            val possibleSelectors = listOf(
                ".col-sm-9 a",
                ".download-links a",
                "a[href*='.mp4']",
                "a[href*='tiktokcdn.com']",
                "a:contains(without)",
                "a[href*=download]"
            )
            
            for (selector in possibleSelectors) {
                try {
                    val links = doc.select(selector)
                    for (link in links) {
                        val href = link.attr("href")
                        if (href.isNotEmpty() && 
                            !href.contains("{") && 
                            !href.contains("javascript") &&
                            (href.contains("tiktokcdn.com") || href.contains(".mp4") || href.startsWith("http"))) {
                            return href
                        }
                    }
                } catch (e: Exception) {
                    // Continue to next selector
                }
            }

            return null

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // -------------------------------
    // ALTERNATIVE DOWNLOADER
    // -------------------------------
    private suspend fun tryAlternativeDownloader(tiktokUrl: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("FreeTok", "Trying to extract video URL directly from TikTok")
            
            // Create a simple client without gzip handling to avoid issues
            val simpleClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            
            // Get TikTok page HTML with proper headers (no gzip)
            val response = simpleClient.newCall(
                Request.Builder()
                    .url(tiktokUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .addHeader("Accept-Encoding", "identity") // Force no compression
                    .addHeader("Cache-Control", "no-cache")
                    .addHeader("Pragma", "no-cache")
                    .get()
                    .build()
            ).execute()

            if (!response.isSuccessful) {
                android.util.Log.e("FreeTok", "Failed to fetch TikTok page: ${response.code} ${response.message}")
                return@withContext Pair(null, null)
            }

            val responseBody = response.body
            if (responseBody == null) {
                android.util.Log.e("FreeTok", "Response body is null")
                return@withContext Pair(null, null)
            }

            // Extract cookies from response headers
            val cookies = response.headers("Set-Cookie").joinToString("; ") { cookie ->
                cookie.split(";")[0].trim()
            }
            android.util.Log.d("FreeTok", "Extracted cookies: ${cookies.take(100)}...")

            // Get content type to check encoding
            val contentType = response.header("Content-Type") ?: "unknown"
            android.util.Log.d("FreeTok", "Content-Type: $contentType")
            
            // Try to get the raw bytes first
            val bytes = responseBody.bytes()
            android.util.Log.d("FreeTok", "Response bytes length: ${bytes.size}")
            
            // Try to decode as UTF-8, if fails try other encodings
            val html = try {
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                try {
                    String(bytes, Charsets.ISO_8859_1)
                } catch (e2: Exception) {
                    android.util.Log.e("FreeTok", "Failed to decode response bytes")
                    return@withContext Pair(null, null)
                }
            }
            
            android.util.Log.d("FreeTok", "Decoded HTML length: ${html.length}")
            android.util.Log.d("FreeTok", "HTML preview: ${html.take(500)}")
            
            // Check if HTML looks reasonable
            if (!html.contains("<html") && !html.contains("<!DOCTYPE") && !html.contains("tiktok")) {
                android.util.Log.e("FreeTok", "Response doesn't look like HTML - might still be compressed")
                return@withContext Pair(null, null)
            }
            
            // More comprehensive video URL patterns
            val videoUrlPatterns = listOf(
                // JSON-like patterns
                "\"playAddr\":\"([^\"]+)\"",
                "\"downloadAddr\":\"([^\"]+)\"",
                "\"uri\":\"([^\"]+\\.mp4[^\"]*)\"",
                "\"url\":\"([^\"]+tiktokcdn[^\"]*)\"",
                "\"src\":\"([^\"]+\\.mp4[^\"]*)\"",
                
                // JavaScript patterns
                "playAddr: ?\"([^\"]+)\"",
                "downloadAddr: ?\"([^\"]+)\"",
                "videoUrl: ?\"([^\"]+)\"",
                "src: ?\"([^\"]+\\.mp4[^\"]*)\"",
                
                // Direct URL patterns
                "https://[^\"']*tiktokcdn[^\"']*\\.mp4",
                "https://[^\"']*p16-sign[^\"']*\\.mp4",
                "https://[^\"']*v\\.tiktok[^\"']*\\.mp4"
            )
            
            for ((index, pattern) in videoUrlPatterns.withIndex()) {
                try {
                    android.util.Log.d("FreeTok", "Trying pattern $index: $pattern")
                    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                    val matches = regex.findAll(html)
                    
                    for (match in matches) {
                        var videoUrl = when {
                            match.groupValues.size > 1 -> match.groupValues[1]
                            else -> match.value
                        }
                        
                        // Clean up the URL
                        videoUrl = videoUrl.replace("\\u002F", "/")
                            .replace("\\/", "/")
                            .replace("\\", "")
                            .replace("\"", "")
                            .trim()
                        
                        android.util.Log.d("FreeTok", "Potential URL: $videoUrl")
                        
                        if (videoUrl.isNotEmpty() && 
                            (videoUrl.contains("tiktokcdn.com") || 
                             videoUrl.contains("p16-sign") || 
                             videoUrl.contains(".mp4") ||
                             videoUrl.startsWith("http"))) {
                            android.util.Log.d("FreeTok", "Found valid video URL: $videoUrl")
                            return@withContext Pair(videoUrl, cookies)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.d("FreeTok", "Pattern $index failed: ${e.message}")
                }
            }
            
            // Try to find script tags with video data
            val scriptRegex = Regex("<script[^>]*>([^<]*(?:videoURL|playAddr|downloadAddr)[^<]*)</script>", RegexOption.IGNORE_CASE)
            val scriptMatches = scriptRegex.findAll(html)
            
            for (scriptMatch in scriptMatches) {
                val scriptContent = scriptMatch.groupValues[1]
                android.util.Log.d("FreeTok", "Found relevant script: ${scriptContent.take(200)}")
                
                // Try patterns within this script content
                for (pattern in videoUrlPatterns.take(5)) { // Only use the first 5 patterns for scripts
                    try {
                        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                        val match = regex.find(scriptContent)
                        if (match != null && match.groupValues.size > 1) {
                            var videoUrl = match.groupValues[1]
                                .replace("\\u002F", "/")
                                .replace("\\/", "/")
                                .replace("\\", "")
                                .replace("\"", "")
                                .trim()
                            
                            if (videoUrl.isNotEmpty() && videoUrl.contains("tiktokcdn.com")) {
                                android.util.Log.d("FreeTok", "Found video URL in script: $videoUrl")
                                return@withContext Pair(videoUrl, cookies)
                            }
                        }
                    } catch (e: Exception) {
                        // Continue
                    }
                }
            }
            
            android.util.Log.e("FreeTok", "Could not extract video URL from TikTok page with any method")
            Pair(null, null)
            
        } catch (e: Exception) {
            android.util.Log.e("FreeTok", "Exception in alternative downloader: ${e.message}")
            e.printStackTrace()
            Pair(null, null)
        }
    }

    // -------------------------------
    // DOWNLOAD VIDEO FILE LOCALLY
    // -------------------------------
    private suspend fun downloadVideoFile(videoUrl: String, context: Context, cookies: String? = null): File? = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("FreeTok", "Starting video file download from: $videoUrl")
            
            // Create download directory
            val downloadDir = File(context.getExternalFilesDir(null), DOWNLOAD_DIR)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            
            // Generate unique filename
            val fileName = "tiktok_${System.currentTimeMillis()}.mp4"
            val videoFile = File(downloadDir, fileName)
            
            // Create HTTP client with proper headers for video download
            val downloadClient = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            
            val requestBuilder = Request.Builder()
                .url(videoUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://www.tiktok.com/")
                .addHeader("Origin", "https://www.tiktok.com")
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Accept-Encoding", "identity") // No compression for video
                .addHeader("Connection", "keep-alive")
            
            // Add cookies if available
            if (!cookies.isNullOrEmpty()) {
                requestBuilder.addHeader("Cookie", cookies)
                android.util.Log.d("FreeTok", "Adding cookies to video download request")
            }
            
            val request = requestBuilder.build()
            
            val response = downloadClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                android.util.Log.e("FreeTok", "Video download failed: ${response.code} ${response.message}")
                return@withContext null
            }
            
            val responseBody = response.body ?: return@withContext null
            val contentLength = responseBody.contentLength()
            
            android.util.Log.d("FreeTok", "Downloading video file, size: ${contentLength / (1024 * 1024)}MB")
            
            // Download the file
            responseBody.byteStream().use { inputStream ->
                FileOutputStream(videoFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // Log progress every 10MB
                        if (totalBytesRead % (10 * 1024 * 1024) == 0L) {
                            val progress = if (contentLength > 0) {
                                (totalBytesRead * 100 / contentLength)
                            } else 0
                            android.util.Log.d("FreeTok", "Download progress: $progress%")
                        }
                    }
                    outputStream.flush()
                }
            }
            
            android.util.Log.d("FreeTok", "Video download completed: ${videoFile.absolutePath}")
            android.util.Log.d("FreeTok", "File size: ${videoFile.length() / (1024 * 1024)}MB")
            
            return@withContext videoFile
            
        } catch (e: Exception) {
            android.util.Log.e("FreeTok", "Exception downloading video file: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}

package com.zachvlat.freetok

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.zachvlat.freetok.ui.theme.FreetokTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FreetokTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TikTokDownloader(
                        context = this@MainActivity,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun TikTokDownloader(context: Context, modifier: Modifier = Modifier) {
    var url by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var localVideoPath by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    localVideoPath?.let { path ->
        VideoPlayerScreen(
            videoUrl = path, 
            onBack = { localVideoPath = null },
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "FreeTok",
            fontSize = 32.sp,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Paste TikTok URL") },
            placeholder = { Text("https://tiktok.com/...") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            singleLine = true
        )

        Button(
            onClick = {
                android.util.Log.d("FreeTok", "Watch button clicked, URL: $url")
                if (url.isNotBlank()) {
                    isLoading = true
                    status = "Processing..."
                    android.util.Log.d("FreeTok", "Starting download process")
                    
                    coroutineScope.launch {
                        try {
                            status = "Extracting video URL..."
                            val localPath = TikTokDownloaderManager.downloadVideo(url, context)
                            if (localPath != null) {
                                localVideoPath = localPath
                                status = "Video downloaded successfully!"
                                android.util.Log.d("FreeTok", "Video downloaded to: $localPath")
                            } else {
                                status = "Failed to download video"
                                android.util.Log.e("FreeTok", "Failed to download video")
                            }
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                            android.util.Log.e("FreeTok", "Exception during download", e)
                        } finally {
                            isLoading = false
                            android.util.Log.d("FreeTok", "Download process finished")
                        }
                    }
                } else {
                    android.util.Log.d("FreeTok", "URL is blank")
                }
            },
            enabled = !isLoading && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Watch")
            }
        }

        if (status.isNotEmpty()) {
            Text(
                text = status,
                modifier = Modifier.padding(top = 16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TikTokDownloaderPreview() {
    FreetokTheme {
        // TikTokDownloader() // Skip preview since it requires Context
    }
}
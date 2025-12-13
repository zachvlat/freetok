package com.zachvlat.freetok

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.activity.compose.BackHandler
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
        
        // Handle incoming intent
        val sharedUrl = getSharedUrl(intent)
        
        setContent {
            var showInfoDialog by remember { mutableStateOf(false) }
            val backDispatcher = onBackPressedDispatcher
            
            FreetokTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        context = this@MainActivity,
                        initialUrl = sharedUrl,
                        onInfoClick = { showInfoDialog = true },
                        onBackPressed = { backDispatcher.onBackPressed() },
                        modifier = Modifier.padding(innerPadding)
                    )
                    
                    // Info Dialog
                    if (showInfoDialog) {
                        InfoDialog(
                            onDismiss = { showInfoDialog = false },
                            onTakeMeThere = {
                                showInfoDialog = false
                                openAppSettings(this@MainActivity)
                            }
                        )
                    }
                }
            }
        }
    }
    
    // Handle new intent when app is already running
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val sharedUrl = getSharedUrl(intent)
        // Update the UI with the new URL
        // This will be handled in the composable
    }
    
    private fun getSharedUrl(intent: Intent?): String? {
        return when {
            // Handle text sharing (SEND intent)
            intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)
            }
            // Handle URL opening (VIEW intent)
            intent?.action == Intent.ACTION_VIEW -> {
                intent.data?.toString()
            }
            else -> null
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}

@Composable
fun InfoDialog(
    onDismiss: () -> Unit,
    onTakeMeThere: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Enable 'Open by Default'")
        },
        text = {
            Column {
                Text("To make FreeTok open TikTok links automatically:")
                Spacer(modifier = Modifier.height(8.dp))
                Text("1. Tap 'Take Me There' below")
                Text("2. Scroll down and tap 'Open by default'")
                Text("3. Select 'Open supported links'")
                Text("4. Choose 'Add supported links'")
                Text("5. Enable TikTok URL support")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Now TikTok links will open directly in FreeTok!")
            }
        },
        confirmButton = {
            TextButton(onClick = onTakeMeThere) {
                Text("Take Me There")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("OK Got It!")
            }
        }
    )
}

@Composable
fun MainScreen(context: Context, initialUrl: String? = null, onInfoClick: () -> Unit = {}, onBackPressed: () -> Unit = {}, modifier: Modifier = Modifier) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var currentVideoUrl by remember { mutableStateOf<String?>(null) }
    var currentOriginalUrl by remember { mutableStateOf<String?>(null) }
    
    // Handle back press
    BackHandler(enabled = currentScreen != Screen.Home) {
        when (currentScreen) {
            is Screen.VideoPlayer -> {
                currentScreen = Screen.Home
                currentVideoUrl = null
                currentOriginalUrl = null
            }
            is Screen.Favorites -> {
                currentScreen = Screen.Home
            }
            is Screen.Home -> {
                // This shouldn't happen due to enabled condition, but handle anyway
                onBackPressed()
            }
        }
    }
    
    when (currentScreen) {
        is Screen.Home -> {
            TikTokDownloader(
                context = context,
                initialUrl = initialUrl,
                onVideoDownloaded = { localPath, originalUrl ->
                    currentVideoUrl = localPath
                    currentOriginalUrl = originalUrl
                    currentScreen = Screen.VideoPlayer
                },
                onFavoritesClick = { currentScreen = Screen.Favorites },
                onInfoClick = onInfoClick,
                modifier = modifier
            )
        }
        is Screen.VideoPlayer -> {
            currentVideoUrl?.let { videoUrl ->
                VideoPlayerScreen(
                    videoUrl = videoUrl,
                    originalUrl = currentOriginalUrl,
                    onBack = { 
                        currentScreen = Screen.Home
                        currentVideoUrl = null
                        currentOriginalUrl = null
                    },
                    modifier = modifier
                )
            }
        }
        is Screen.Favorites -> {
            FavoritesListScreen(
                onBack = { currentScreen = Screen.Home },
                onVideoSelected = { localPath, originalUrl ->
                    currentVideoUrl = localPath
                    currentOriginalUrl = originalUrl
                    currentScreen = Screen.VideoPlayer
                },
                modifier = modifier
            )
        }
    }
}

sealed class Screen {
    object Home : Screen()
    object VideoPlayer : Screen()
    object Favorites : Screen()
}

@Composable
fun TikTokDownloader(
    context: Context, 
    initialUrl: String? = null,
    onVideoDownloaded: (String, String) -> Unit = { _, _ -> },
    onFavoritesClick: () -> Unit = {},
    onInfoClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var url by remember { mutableStateOf(initialUrl ?: "") }
    var isLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    
    // Auto-download if URL was shared from another app
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank() && url.isNotBlank()) {
            android.util.Log.d("FreeTok", "Auto-downloading shared URL: $initialUrl")
            isLoading = true
            status = "Processing..."
            
            try {
                val localPath = TikTokDownloaderManager.downloadVideo(initialUrl, context)
                if (localPath != null) {
                    onVideoDownloaded(localPath, initialUrl)
                    android.util.Log.d("FreeTok", "Video downloaded to: $localPath")
                } else {
                    status = "Failed to download video"
                    android.util.Log.e("FreeTok", "Failed to download shared video")
                }
            } catch (e: Exception) {
                status = "Error: ${e.message}"
                android.util.Log.e("FreeTok", "Exception during shared video download", e)
            } finally {
                isLoading = false
                android.util.Log.d("FreeTok", "Shared video download process finished")
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Info button in top right corner
        IconButton(
            onClick = onInfoClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Info",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        
        Column(
            modifier = Modifier
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
                                    onVideoDownloaded(localPath, url)
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

            // Favorites button
            Button(
                onClick = onFavoritesClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("My Favorites")
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
}

@Preview(showBackground = true)
@Composable
fun TikTokDownloaderPreview() {
    FreetokTheme {
        // TikTokDownloader() // Skip preview since it requires Context
    }
}
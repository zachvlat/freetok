package com.zachvlat.freetok

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object FavoritesManager {
    private const val PREFS_NAME = "favorites_prefs"
    private const val FAVORITES_KEY = "favorite_videos"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val mutex = Mutex()

    suspend fun addFavorite(
        context: Context,
        originalUrl: String,
        localPath: String,
        title: String? = null
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val favorites = readFavorites(context).toMutableList()
                val existingIndex = favorites.indexOfFirst { it.originalUrl == originalUrl }
                if (existingIndex >= 0) {
                    val old = favorites[existingIndex]
                    val updated = old.copy(
                        localPath = localPath,
                        timestamp = System.currentTimeMillis(),
                        title = title ?: old.title
                    )
                    favorites[existingIndex] = updated
                    val saved = saveFavorites(context, favorites)
                    if (saved && old.localPath != localPath) File(old.localPath).delete()
                    saved
                } else {
                    favorites.add(
                        FavoriteVideo(
                            id = System.currentTimeMillis().toString(),
                            originalUrl = originalUrl,
                            localPath = localPath,
                            timestamp = System.currentTimeMillis(),
                            title = title
                        )
                    )
                    saveFavorites(context, favorites)
                }
            }
        }

    suspend fun removeFavorite(context: Context, videoId: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val favorites = readFavorites(context)
                val removed = favorites.filter { it.id == videoId }
                if (removed.isEmpty()) {
                    true
                } else {
                    val saved = saveFavorites(context, favorites.filter { it.id != videoId })
                    if (saved) removed.forEach { File(it.localPath).delete() }
                    saved
                }
            }
        }

    suspend fun removeFavoriteByUrl(context: Context, originalUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val favorites = readFavorites(context)
                val removed = favorites.filter { it.originalUrl == originalUrl }
                if (removed.isEmpty()) {
                    true
                } else {
                    val saved = saveFavorites(context, favorites.filter { it.originalUrl != originalUrl })
                    if (saved) removed.forEach { File(it.localPath).delete() }
                    saved
                }
            }
        }

    suspend fun getFavorites(context: Context): List<FavoriteVideo> =
        withContext(Dispatchers.IO) { readFavorites(context) }

    suspend fun isFavorite(context: Context, originalUrl: String): Boolean =
        withContext(Dispatchers.IO) { readFavorites(context).any { it.originalUrl == originalUrl } }

    private fun readFavorites(context: Context): List<FavoriteVideo> {
        return try {
            val favoritesJson = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(FAVORITES_KEY, null) ?: return emptyList()
            json.decodeFromString(favoritesJson)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun saveFavorites(context: Context, favorites: List<FavoriteVideo>): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(FAVORITES_KEY, json.encodeToString(favorites))
                .commit()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

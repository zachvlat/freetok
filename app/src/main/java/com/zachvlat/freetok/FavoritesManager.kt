package com.zachvlat.freetok

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object FavoritesManager {
    private const val PREFS_NAME = "favorites_prefs"
    private const val FAVORITES_KEY = "favorite_videos"
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    suspend fun addFavorite(context: Context, originalUrl: String, localPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val favorites = getFavorites(context).toMutableList()
                val existingIndex = favorites.indexOfFirst { it.originalUrl == originalUrl }
                
                if (existingIndex >= 0) {
                    // Update existing favorite
                    favorites[existingIndex] = favorites[existingIndex].copy(
                        localPath = localPath,
                        timestamp = System.currentTimeMillis()
                    )
                } else {
                    // Add new favorite
                    val newFavorite = FavoriteVideo(
                        id = System.currentTimeMillis().toString(),
                        originalUrl = originalUrl,
                        localPath = localPath,
                        timestamp = System.currentTimeMillis()
                    )
                    favorites.add(newFavorite)
                }
                
                saveFavorites(context, favorites)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
    
    suspend fun removeFavorite(context: Context, videoId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val favorites = getFavorites(context).toMutableList()
                favorites.removeAll { it.id == videoId }
                saveFavorites(context, favorites)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
    
    suspend fun removeFavoriteByUrl(context: Context, originalUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val favorites = getFavorites(context).toMutableList()
                favorites.removeAll { it.originalUrl == originalUrl }
                saveFavorites(context, favorites)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
    
    fun getFavorites(context: Context): List<FavoriteVideo> {
        return try {
            val prefs = getPreferences(context)
            val favoritesJson = prefs.getString(FAVORITES_KEY, null) ?: return emptyList()
            json.decodeFromString<List<FavoriteVideo>>(favoritesJson)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    fun isFavorite(context: Context, originalUrl: String): Boolean {
        return getFavorites(context).any { it.originalUrl == originalUrl }
    }
    
    private fun saveFavorites(context: Context, favorites: List<FavoriteVideo>) {
        try {
            val prefs = getPreferences(context)
            val favoritesJson = json.encodeToString(favorites)
            prefs.edit()
                .putString(FAVORITES_KEY, favoritesJson)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
package com.zachvlat.freetok

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FavoritesManagerTest {
    @Test
    fun addFavoritePersistsAndRemovesItsFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://www.tiktok.com/@test/video/1"
        FavoritesManager.removeFavoriteByUrl(context, url)

        val video = File(context.cacheDir, "favorite_test.mp4").apply { createNewFile() }
        assertTrue(FavoritesManager.addFavorite(context, url, video.absolutePath))
        assertTrue(FavoritesManager.isFavorite(context, url))

        val favorite = FavoritesManager.getFavorites(context).first { it.originalUrl == url }
        assertTrue(File(favorite.localPath).exists())

        assertTrue(FavoritesManager.removeFavorite(context, favorite.id))
        assertFalse(FavoritesManager.isFavorite(context, url))
        assertFalse(File(favorite.localPath).exists())
    }
}

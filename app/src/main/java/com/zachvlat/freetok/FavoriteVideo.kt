package com.zachvlat.freetok

import kotlinx.serialization.Serializable

@Serializable
data class FavoriteVideo(
    val id: String,
    val originalUrl: String,
    val localPath: String,
    val thumbnailPath: String? = null,
    val timestamp: Long,
    val title: String? = null
)
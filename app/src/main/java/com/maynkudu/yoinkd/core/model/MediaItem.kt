package com.maynkudu.yoinkd.core.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaItem(
    val id: String,
    val title: String,
    val uri: String,
    val fileName: String,
    val kind: MediaKind,
    val mime: String,
    val bytes: Long,
    val duration: Double,
    val addedAt: Long,
    val artist: String? = null,
    val thumbnailPath: String? = null,
    val sourceUrl: String? = null,
    val jobId: String? = null,
    val audioCodec: String? = null,
    val videoCodec: String? = null,
)

@Serializable
data class PreparedMedia(
    val filePath: String,
    val title: String,
    val kind: MediaKind,
    val mime: String,
    val duration: Double,
    val artist: String? = null,
    val thumbnailPath: String? = null,
    val sourceUrl: String? = null,
    val audioCodec: String? = null,
    val videoCodec: String? = null,
)

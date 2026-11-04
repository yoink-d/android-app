package com.maynkudu.yoinkd.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maynkudu.yoinkd.core.model.DownloadJob
import com.maynkudu.yoinkd.core.model.MediaItem
import com.maynkudu.yoinkd.core.model.mediaJson
import kotlinx.serialization.encodeToString

@Entity(tableName = "download_jobs", indices = [Index("phase"), Index("created_at")])
data class StoredJob(
    @PrimaryKey val id: String,
    val phase: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "attempt_id") val attemptId: String?,
    val payload: String,
) {
    fun model(): DownloadJob = mediaJson.decodeFromString(payload)
    companion object {
        fun from(job: DownloadJob) = StoredJob(job.id, job.phase.name, job.createdAt,
            job.attemptId, mediaJson.encodeToString(job))
    }
}

@Entity(tableName = "library", indices = [Index(value = ["job_id"], unique = true), Index("added_at")])
data class StoredMedia(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "job_id") val jobId: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    val payload: String,
) {
    fun model(): MediaItem = mediaJson.decodeFromString(payload)
    companion object {
        fun from(item: MediaItem) = StoredMedia(item.id, item.jobId, item.addedAt, mediaJson.encodeToString(item))
    }
}

@Entity(tableName = "preferences")
data class PreferencesRow(@PrimaryKey val id: Int = 1, val payload: String)

@Entity(tableName = "file_operations")
data class PendingOperation(@PrimaryKey val id: String, val payload: String)

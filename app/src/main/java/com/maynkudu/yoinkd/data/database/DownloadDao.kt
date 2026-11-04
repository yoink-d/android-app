package com.maynkudu.yoinkd.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_jobs ORDER BY created_at ASC")
    fun observe(): Flow<List<StoredJob>>
    @Query("SELECT * FROM download_jobs ORDER BY created_at ASC")
    suspend fun all(): List<StoredJob>
    @Query("SELECT * FROM download_jobs WHERE id = :id")
    suspend fun get(id: String): StoredJob?
    @Upsert suspend fun put(job: StoredJob)
    @Query("DELETE FROM download_jobs WHERE id = :id AND phase IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun deleteTerminal(id: String)
    @Query("DELETE FROM download_jobs WHERE phase IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearHistory()
}

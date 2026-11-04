package com.maynkudu.yoinkd.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library ORDER BY added_at DESC") fun observe(): Flow<List<StoredMedia>>
    @Query("SELECT * FROM library ORDER BY added_at DESC") suspend fun all(): List<StoredMedia>
    @Query("SELECT * FROM library WHERE id = :id") suspend fun get(id: String): StoredMedia?
    @Query("SELECT * FROM library WHERE job_id = :jobId") suspend fun forJob(jobId: String): StoredMedia?
    @Upsert suspend fun put(item: StoredMedia)
    @Query("DELETE FROM library WHERE id = :id") suspend fun delete(id: String)
}

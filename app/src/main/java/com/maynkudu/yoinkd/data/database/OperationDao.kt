package com.maynkudu.yoinkd.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface OperationDao {
    @Query("SELECT * FROM file_operations") suspend fun all(): List<PendingOperation>
    @Upsert suspend fun put(operation: PendingOperation)
    @Query("DELETE FROM file_operations WHERE id = :id") suspend fun delete(id: String)
}

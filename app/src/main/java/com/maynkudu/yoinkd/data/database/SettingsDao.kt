package com.maynkudu.yoinkd.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM preferences WHERE id = 1") fun observe(): Flow<PreferencesRow?>
    @Query("SELECT * FROM preferences WHERE id = 1") suspend fun get(): PreferencesRow?
    @Upsert suspend fun put(preferences: PreferencesRow)
}

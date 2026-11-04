package com.maynkudu.yoinkd.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [StoredJob::class, StoredMedia::class, PreferencesRow::class, PendingOperation::class],
    version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    abstract fun library(): LibraryDao
    abstract fun settings(): SettingsDao
    abstract fun operations(): OperationDao
}

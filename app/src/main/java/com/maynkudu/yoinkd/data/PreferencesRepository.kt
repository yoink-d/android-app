package com.maynkudu.yoinkd.data

import androidx.room.withTransaction
import com.maynkudu.yoinkd.core.model.AppPreferences
import com.maynkudu.yoinkd.core.model.mediaJson
import com.maynkudu.yoinkd.data.database.AppDatabase
import com.maynkudu.yoinkd.data.database.PreferencesRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.encodeToString

class PreferencesRepository(private val database: AppDatabase, scope: CoroutineScope) {
    val preferences = database.settings().observe().map { decode(it) }
        .stateIn(scope, SharingStarted.Eagerly, AppPreferences())

    suspend fun current(): AppPreferences = decode(database.settings().get())

    suspend fun update(transform: (AppPreferences) -> AppPreferences) = database.withTransaction {
        database.settings().put(PreferencesRow(payload = mediaJson.encodeToString(transform(current()))))
    }

    private fun decode(row: PreferencesRow?): AppPreferences = row?.let {
        mediaJson.decodeFromString<AppPreferences>(it.payload)
    } ?: AppPreferences()
}

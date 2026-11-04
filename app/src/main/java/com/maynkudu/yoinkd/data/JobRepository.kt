package com.maynkudu.yoinkd.data

import androidx.room.withTransaction
import com.maynkudu.yoinkd.core.model.DownloadJob
import com.maynkudu.yoinkd.core.model.DownloadPhase
import com.maynkudu.yoinkd.core.model.DownloadRules
import com.maynkudu.yoinkd.data.database.AppDatabase
import com.maynkudu.yoinkd.data.database.StoredJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class JobRepository(private val database: AppDatabase, scope: CoroutineScope) {
    val jobs = database.downloads().observe().map { rows -> rows.map { it.model() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun all(): List<DownloadJob> = database.downloads().all().map { it.model() }
    suspend fun get(id: String): DownloadJob? = database.downloads().get(id)?.model()
    suspend fun put(job: DownloadJob) = database.downloads().put(StoredJob.from(job))

    suspend fun change(id: String, transform: (DownloadJob) -> DownloadJob): DownloadJob? = database.withTransaction {
        val current = get(id) ?: return@withTransaction null
        transform(current).also { put(it) }
    }

    // A stopped attempt cannot overwrite pause/cancel or a newer attempt's progress.
    suspend fun forAttempt(id: String, attempt: String, transform: (DownloadJob) -> DownloadJob): DownloadJob? =
        database.withTransaction {
            val current = get(id) ?: return@withTransaction null
            if (current.attemptId != attempt || current.phase in setOf(DownloadPhase.PAUSING,
                    DownloadPhase.CANCELLING) || current.phase.terminal) return@withTransaction null
            transform(current).also { put(it) }
        }

    suspend fun restore() = database.withTransaction {
        all().forEach { job ->
            val saved = database.library().forJob(job.id)?.model()
            val phase = DownloadRules.restoredPhase(job.phase, saved != null)
            put(job.copy(phase = phase, attemptId = null, storedItemId = saved?.id ?: job.storedItemId,
                message = if (phase == DownloadPhase.PAUSED && job.phase.occupiesSlot)
                    "Paused when yoink'd closed. Resume to continue." else job.message))
        }
    }

    suspend fun remove(id: String) = database.downloads().deleteTerminal(id)
    suspend fun clearHistory() = database.downloads().clearHistory()
}

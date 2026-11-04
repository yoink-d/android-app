package com.maynkudu.yoinkd.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadPhase(val label: String) {
    QUEUED("Queued"), EXTRACTING("Checking source"), DOWNLOADING("Downloading"),
    WAITING("Waiting for network"), PAUSING("Pausing"), PAUSED("Paused"),
    PROCESSING("Preparing media"), SAVING("Saving"), CANCELLING("Cancelling"),
    COMPLETED("Completed"), FAILED("Failed"), CANCELLED("Cancelled");

    val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
    val occupiesSlot: Boolean get() = this in setOf(EXTRACTING, DOWNLOADING, WAITING, PAUSING,
        PROCESSING, SAVING, CANCELLING)
    val canPause: Boolean get() = this == QUEUED || this in setOf(EXTRACTING, DOWNLOADING, WAITING, PROCESSING)
    val canCancel: Boolean get() = canPause || this == PAUSED
}

@Serializable
data class DownloadJob(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val createdAt: Long,
    val selection: SourceSelection,
    val direct: Boolean,
    val phase: DownloadPhase = DownloadPhase.QUEUED,
    val attemptId: String? = null,
    val receivedBytes: Long = 0,
    val expectedBytes: Long? = null,
    val processedSeconds: Double? = null,
    val expectedSeconds: Double? = null,
    val message: String? = null,
    val thumbnail: String? = null,
    val platform: String? = null,
    val artist: String? = null,
    val storedItemId: String? = null,
) {
    val progress: Float? get() = if (phase == DownloadPhase.PROCESSING) {
        if (processedSeconds != null && expectedSeconds != null && expectedSeconds > 0)
            (processedSeconds / expectedSeconds).toFloat().coerceIn(0f, 1f) else null
    } else expectedBytes?.takeIf { it > 0 }?.let { (receivedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

object DownloadRules {
    const val SLOT_LIMIT = 2

    fun next(jobs: List<DownloadJob>, acquiringSource: Boolean): DownloadJob? {
        if (jobs.count { it.phase.occupiesSlot } >= SLOT_LIMIT) return null
        return jobs.sortedBy { it.createdAt }.firstOrNull {
            it.phase == DownloadPhase.QUEUED && (it.direct || !acquiringSource)
        }
    }

    fun restoredPhase(phase: DownloadPhase, committed: Boolean): DownloadPhase = when {
        committed -> DownloadPhase.COMPLETED
        phase == DownloadPhase.CANCELLING -> DownloadPhase.CANCELLED
        phase.occupiesSlot -> DownloadPhase.PAUSED
        else -> phase
    }
}

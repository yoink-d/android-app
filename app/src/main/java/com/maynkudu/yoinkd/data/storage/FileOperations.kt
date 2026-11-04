package com.maynkudu.yoinkd.data.storage

import com.maynkudu.yoinkd.core.model.MediaItem
import kotlinx.serialization.Serializable

@Serializable
enum class FileOperationKind { SAVE, DELETE, RELOCATE }

@Serializable
data class MoveEntry(val itemId: String, val originalUri: String, val targetUri: String, val created: Boolean)

@Serializable
data class FileOperation(
    val id: String,
    val kind: FileOperationKind,
    val item: MediaItem? = null,
    val targetCreated: Boolean = false,
    val attemptId: String? = null,
    val entries: List<MoveEntry> = emptyList(),
    val folderUri: String? = null,
    val folderName: String = "Downloads/yoink'd",
    val committed: Boolean = false,
    val pendingFileNames: List<String> = emptyList(),
)

data class StorageStatus(val importing: Boolean = false, val moving: Boolean = false,
    val name: String = "", val index: Int = 0, val total: Int = 0) {
    val busy: Boolean get() = importing || moving
}

data class ImportReport(val count: Int, val errors: List<String>)

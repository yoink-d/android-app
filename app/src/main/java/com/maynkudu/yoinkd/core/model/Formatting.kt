package com.maynkudu.yoinkd.core.model

import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_000.0)
    else -> "${bytes.coerceAtLeast(0)} B"
}

fun formatDuration(value: Double?): String {
    val seconds = value?.takeIf { it.isFinite() && it >= 0 }?.toLong() ?: return "Unavailable"
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

fun managedFileName(id: String, title: String, extension: String): String {
    require(id.length <= 64 && id.matches(Regex("[A-Za-z0-9-]+")))
    require(extension.length <= 12 && extension.matches(Regex("[A-Za-z0-9]+")))
    val cleaned = title.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim('.', ' ').ifBlank { "Media" }
    val budget = 240 - id.length - extension.length - 2
    var bytes = 0
    val safeTitle = buildString {
        for (point in cleaned.codePoints().toArray()) {
            val character = String(Character.toChars(point))
            val size = character.toByteArray().size
            if (bytes + size > budget) break
            append(character)
            bytes += size
        }
    }
    return "$id-$safeTitle.${extension.lowercase()}"
}

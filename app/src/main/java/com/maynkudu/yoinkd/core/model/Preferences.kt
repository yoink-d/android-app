package com.maynkudu.yoinkd.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Appearance(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

@Serializable
enum class AccentTheme(val label: String, val hue: Double, val saturation: Double) {
    NEUTRAL("Neutral", 0.0, 0.0), SAGE("Sage", 0.40, 0.30),
    LAVENDER("Lavender", 0.74, 0.38), AMBER("Amber", 0.105, 0.52),
    OCEAN("Ocean", 0.57, 0.38), DYNAMIC("Dynamic", 0.0, 0.0)
}

@Serializable
enum class MediaFilter(val label: String) { ALL("All"), VIDEO("Video"), AUDIO("Audio") }

@Serializable
enum class MediaSort(val label: String) {
    NEWEST("Newest first"), OLDEST("Oldest first"), TITLE("Title"), REVERSE_TITLE("Title, Z to A")
}

@Serializable
enum class DisplayMode(val label: String) { GRID("Grid"), LIST("List") }

@Serializable
data class AppPreferences(
    val appearance: Appearance = Appearance.SYSTEM,
    val theme: AccentTheme = AccentTheme.SAGE,
    val librarySort: MediaSort = MediaSort.NEWEST,
    val libraryDisplay: DisplayMode = DisplayMode.GRID,
    val libraryFilter: MediaFilter = MediaFilter.ALL,
    val historySort: MediaSort = MediaSort.NEWEST,
    val historyDisplay: DisplayMode = DisplayMode.LIST,
    val historyFilter: MediaFilter = MediaFilter.ALL,
    val folderUri: String? = null,
    val folderName: String = "Downloads/yoink'd",
)

fun <T> filterAndSort(
    items: List<T>, query: String, filter: MediaFilter, sort: MediaSort,
    title: (T) -> String, kind: (T) -> MediaKind, date: (T) -> Long,
): List<T> {
    val filtered = items.filter {
        (filter == MediaFilter.ALL || kind(it).name == filter.name) && title(it).contains(query.trim(), true)
    }
    return when (sort) {
        MediaSort.NEWEST -> filtered.sortedByDescending(date)
        MediaSort.OLDEST -> filtered.sortedBy(date)
        MediaSort.TITLE -> filtered.sortedBy { title(it).lowercase() }
        MediaSort.REVERSE_TITLE -> filtered.sortedByDescending { title(it).lowercase() }
    }
}

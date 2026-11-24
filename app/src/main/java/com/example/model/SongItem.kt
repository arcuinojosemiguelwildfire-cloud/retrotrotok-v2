package com.example.model

enum class RetroDecade(val label: String, val era: String) {
    DECADE_70S("70s Classics", "1970–1979"),
    DECADE_80S("80s Hits", "1980–1989"),
    DECADE_90S("90s Favorites", "1990–1999"),
    DECADE_00S("00s Hits", "2000–2009"),
    ALL("All Eras", "1970–2009"),
    DECADE_UNKNOWN("Other / Uncategorized", "Other")
}

data class SongItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val releaseYear: Int?,
    val decade: RetroDecade,
    val genre: String,
    val durationSeconds: Int,
    val energyBpm: Int = 120,
    val keyTag: String = "",
    val audioRelativePath: String = "",
    val artworkRelativePath: String? = null,
    val licenseApprovalStatus: String = "UNREVIEWED",
    val fileSizeBytes: Long = 0L,
    val sha256Hash: String = "",
    val isAvailable: Boolean = true
) {
    val isApprovedForGuestUse: Boolean
        get() = isAvailable && licenseApprovalStatus == "APPROVED_FOR_EVENT_USE"
}

package com.example.model

import com.example.video.CameraFramingMode

enum class EventModeStatus {
    NOT_STARTED,
    ACTIVE,
    PAUSED,
    ENDED
}

enum class FeedSortOrder(val label: String) {
    NEWEST("Newest"),
    MOST_LIKED("Most Liked"),
    FEATURED_FIRST("Featured First")
}

data class EventSummarySnapshot(
    val eventName: String,
    val eventDescription: String,
    val startedAtMs: Long?,
    val endedAtMs: Long,
    val publishedVideoCount: Int,
    val totalRecordingCount: Int,
    val totalLikesCount: Int,
    val totalFavoritesCount: Int,
    val totalStorageBytes: Long
)

data class KioskSettings(
    val eventName: String = "Acme 40th Anniversary Gala",
    val eventTheme: String = "Back to the 80s & 90s Nostalgia",
    val eventDescription: String = "",
    val eventModeStatus: EventModeStatus = EventModeStatus.ACTIVE,
    val eventStartedAtMs: Long? = null,
    val eventEndedAtMs: Long? = null,
    val eventStatsResetAtMs: Long = 0L,
    val lastSavedEventSummary: EventSummarySnapshot? = null,
    val defaultRecordingDurationSeconds: Int = 30, // 15, 30, 60
    val countdownDurationSeconds: Int = 3,         // 3 or 5
    val allowGuestDeletion: Boolean = false,       // Default OFF
    val isKioskLockEnabled: Boolean = true,
    val isWideViewEnabled: Boolean = true,         // Default ON (Wide View / WIDE_FIT)
    val showFeaturedSection: Boolean = true,
    val enableGuestLikes: Boolean = true,
    val enableGuestFavorites: Boolean = true,
    val enableDiscover: Boolean = true,
    val defaultFeedSort: FeedSortOrder = FeedSortOrder.NEWEST,
    val requireGuestNickname: Boolean = false,
    val idleTimeoutSeconds: Int = 60,
    val freeStorageMb: Long = 18450L,
    val totalStorageMb: Long = 65536L,
    val totalRecordedVideos: Int = 24,
    val totalSongsInCatalog: Int = 36
) {
    val isAcceptingGuestRecordings: Boolean
        get() = eventModeStatus == EventModeStatus.ACTIVE

    val cameraFramingMode: CameraFramingMode
        get() = if (isWideViewEnabled) CameraFramingMode.WIDE_FIT else CameraFramingMode.PORTRAIT_CROP
}

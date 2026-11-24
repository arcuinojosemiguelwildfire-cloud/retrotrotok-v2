package com.example.data.repository

import com.example.model.EventSummarySnapshot
import com.example.model.FeedSortOrder
import com.example.model.KioskSettings
import kotlinx.coroutines.flow.Flow

interface KioskSettingsRepository {
    fun getSettings(): Flow<KioskSettings>
    suspend fun updateEventName(name: String)
    suspend fun updateTheme(theme: String)
    suspend fun updateEventDetails(name: String, description: String)
    suspend fun startEvent(name: String, description: String)
    suspend fun pauseEvent()
    suspend fun resumeEvent()
    suspend fun endEvent(summary: EventSummarySnapshot)
    suspend fun resetEventStatistics()
    suspend fun updateRecordingDuration(durationSeconds: Int)
    suspend fun toggleGuestDeletion(enabled: Boolean)
    suspend fun toggleKioskLock(enabled: Boolean)
    suspend fun toggleWideView(enabled: Boolean)
    suspend fun updateFeedControls(
        showFeaturedSection: Boolean = true,
        enableGuestLikes: Boolean = true,
        enableGuestFavorites: Boolean = true,
        enableDiscover: Boolean = true,
        allowGuestDeletion: Boolean = false,
        defaultFeedSort: FeedSortOrder = FeedSortOrder.NEWEST,
        requireGuestNickname: Boolean = false
    )
    suspend fun clearTempCache()
    suspend fun validateAdminPin(pin: String): Boolean
}

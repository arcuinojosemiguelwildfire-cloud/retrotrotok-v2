package com.example.data.mock

import com.example.data.repository.KioskSettingsRepository
import com.example.model.EventModeStatus
import com.example.model.EventSummarySnapshot
import com.example.model.FeedSortOrder
import com.example.model.KioskSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MockKioskSettingsRepository : KioskSettingsRepository {

    private val _settings = MutableStateFlow(KioskSettings())
    private val validAdminPin = "1234"

    override fun getSettings(): Flow<KioskSettings> = _settings.asStateFlow()

    override suspend fun updateEventName(name: String) {
        _settings.update { it.copy(eventName = name) }
    }

    override suspend fun updateTheme(theme: String) {
        _settings.update { it.copy(eventTheme = theme) }
    }

    override suspend fun updateEventDetails(name: String, description: String) {
        _settings.update {
            it.copy(
                eventName = name.ifBlank { it.eventName },
                eventDescription = description
            )
        }
    }

    override suspend fun startEvent(name: String, description: String) {
        val now = System.currentTimeMillis()
        _settings.update {
            it.copy(
                eventName = name.ifBlank { it.eventName },
                eventDescription = description,
                eventModeStatus = EventModeStatus.ACTIVE,
                eventStartedAtMs = now,
                eventEndedAtMs = null
            )
        }
    }

    override suspend fun pauseEvent() {
        _settings.update { it.copy(eventModeStatus = EventModeStatus.PAUSED) }
    }

    override suspend fun resumeEvent() {
        _settings.update { it.copy(eventModeStatus = EventModeStatus.ACTIVE) }
    }

    override suspend fun endEvent(summary: EventSummarySnapshot) {
        val now = System.currentTimeMillis()
        _settings.update {
            it.copy(
                eventModeStatus = EventModeStatus.ENDED,
                eventEndedAtMs = now,
                lastSavedEventSummary = summary
            )
        }
    }

    override suspend fun resetEventStatistics() {
        _settings.update {
            it.copy(
                eventStatsResetAtMs = System.currentTimeMillis(),
                lastSavedEventSummary = null
            )
        }
    }

    override suspend fun updateRecordingDuration(durationSeconds: Int) {
        _settings.update { it.copy(defaultRecordingDurationSeconds = durationSeconds) }
    }

    override suspend fun toggleGuestDeletion(enabled: Boolean) {
        _settings.update { it.copy(allowGuestDeletion = enabled) }
    }

    override suspend fun toggleKioskLock(enabled: Boolean) {
        _settings.update { it.copy(isKioskLockEnabled = enabled) }
    }

    override suspend fun toggleWideView(enabled: Boolean) {
        _settings.update { it.copy(isWideViewEnabled = enabled) }
    }

    override suspend fun updateFeedControls(
        showFeaturedSection: Boolean,
        enableGuestLikes: Boolean,
        enableGuestFavorites: Boolean,
        enableDiscover: Boolean,
        allowGuestDeletion: Boolean,
        defaultFeedSort: FeedSortOrder,
        requireGuestNickname: Boolean
    ) {
        _settings.update {
            it.copy(
                showFeaturedSection = showFeaturedSection,
                enableGuestLikes = enableGuestLikes,
                enableGuestFavorites = enableGuestFavorites,
                enableDiscover = enableDiscover,
                allowGuestDeletion = allowGuestDeletion,
                defaultFeedSort = defaultFeedSort,
                requireGuestNickname = requireGuestNickname
            )
        }
    }

    override suspend fun clearTempCache() {
        val currentFree = _settings.value.freeStorageMb
        _settings.update { it.copy(freeStorageMb = currentFree + 1240L) }
    }

    override suspend fun validateAdminPin(pin: String): Boolean {
        return pin == validAdminPin
    }
}

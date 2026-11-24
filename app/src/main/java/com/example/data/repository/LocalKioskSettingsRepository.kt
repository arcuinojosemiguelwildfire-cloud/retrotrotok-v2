package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.model.EventModeStatus
import com.example.model.EventSummarySnapshot
import com.example.model.FeedSortOrder
import com.example.model.KioskSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest

/**
 * Persistent local SharedPreferences-backed KioskSettingsRepository safe for API 23+.
 * Stores Event Mode state, Feed Controls, Recording Settings, and saved Event Summaries locally.
 */
class LocalKioskSettingsRepository(
    context: Context
) : KioskSettingsRepository {

    companion object {
        private const val PREFS_NAME = "retrotok_kiosk_settings"
        private const val KEY_EVENT_NAME = "event_name"
        private const val KEY_EVENT_THEME = "event_theme"
        private const val KEY_EVENT_DESC = "event_description"
        private const val KEY_EVENT_STATUS = "event_mode_status"
        private const val KEY_EVENT_STARTED_AT = "event_started_at"
        private const val KEY_EVENT_ENDED_AT = "event_ended_at"
        private const val KEY_STATS_RESET_AT = "event_stats_reset_at"
        private const val KEY_DEFAULT_DURATION = "default_recording_duration"
        private const val KEY_ALLOW_GUEST_DELETION = "allow_guest_deletion"
        private const val KEY_KIOSK_LOCK = "kiosk_lock_enabled"
        private const val KEY_WIDE_VIEW_ENABLED = "camera_wide_view_enabled"
        private const val KEY_SHOW_FEATURED = "show_featured_section"
        private const val KEY_ENABLE_LIKES = "enable_guest_likes"
        private const val KEY_ENABLE_FAVORITES = "enable_guest_favorites"
        private const val KEY_ENABLE_DISCOVER = "enable_discover"
        private const val KEY_DEFAULT_FEED_SORT = "default_feed_sort"
        private const val KEY_REQUIRE_NICKNAME = "require_guest_nickname"

        private const val KEY_SUM_EXISTS = "summary_exists"
        private const val KEY_SUM_NAME = "summary_name"
        private const val KEY_SUM_DESC = "summary_desc"
        private const val KEY_SUM_START = "summary_start"
        private const val KEY_SUM_END = "summary_end"
        private const val KEY_SUM_PUB = "summary_published"
        private const val KEY_SUM_REC = "summary_recordings"
        private const val KEY_SUM_LIKES = "summary_likes"
        private const val KEY_SUM_FAVS = "summary_favs"
        private const val KEY_SUM_BYTES = "summary_bytes"

        private const val KEY_PIN_HASH = "admin_pin_sha256"
        private const val DEFAULT_PIN = "1234"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettingsFromPrefs())

    override fun getSettings(): Flow<KioskSettings> = _settings.asStateFlow()

    override suspend fun updateEventName(name: String) {
        val clean = name.trim().ifBlank { return }
        prefs.edit().putString(KEY_EVENT_NAME, clean).apply()
        _settings.update { it.copy(eventName = clean) }
    }

    override suspend fun updateTheme(theme: String) {
        val clean = theme.trim()
        prefs.edit().putString(KEY_EVENT_THEME, clean).apply()
        _settings.update { it.copy(eventTheme = clean) }
    }

    override suspend fun updateEventDetails(name: String, description: String) {
        val cleanName = name.trim().ifBlank { _settings.value.eventName }
        val cleanDesc = description.trim()
        prefs.edit()
            .putString(KEY_EVENT_NAME, cleanName)
            .putString(KEY_EVENT_DESC, cleanDesc)
            .apply()
        _settings.update { it.copy(eventName = cleanName, eventDescription = cleanDesc) }
    }

    override suspend fun startEvent(name: String, description: String) {
        val now = System.currentTimeMillis()
        val cleanName = name.trim().ifBlank { _settings.value.eventName }
        val cleanDesc = description.trim()
        prefs.edit()
            .putString(KEY_EVENT_NAME, cleanName)
            .putString(KEY_EVENT_DESC, cleanDesc)
            .putString(KEY_EVENT_STATUS, EventModeStatus.ACTIVE.name)
            .putLong(KEY_EVENT_STARTED_AT, now)
            .remove(KEY_EVENT_ENDED_AT)
            .apply()
        _settings.update {
            it.copy(
                eventName = cleanName,
                eventDescription = cleanDesc,
                eventModeStatus = EventModeStatus.ACTIVE,
                eventStartedAtMs = now,
                eventEndedAtMs = null
            )
        }
    }

    override suspend fun pauseEvent() {
        prefs.edit().putString(KEY_EVENT_STATUS, EventModeStatus.PAUSED.name).apply()
        _settings.update { it.copy(eventModeStatus = EventModeStatus.PAUSED) }
    }

    override suspend fun resumeEvent() {
        val startedAt = _settings.value.eventStartedAtMs ?: System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_EVENT_STATUS, EventModeStatus.ACTIVE.name)
            .putLong(KEY_EVENT_STARTED_AT, startedAt)
            .remove(KEY_EVENT_ENDED_AT)
            .apply()
        _settings.update {
            it.copy(
                eventModeStatus = EventModeStatus.ACTIVE,
                eventStartedAtMs = startedAt,
                eventEndedAtMs = null
            )
        }
    }

    override suspend fun endEvent(summary: EventSummarySnapshot) {
        val now = summary.endedAtMs
        prefs.edit()
            .putString(KEY_EVENT_STATUS, EventModeStatus.ENDED.name)
            .putLong(KEY_EVENT_ENDED_AT, now)
            .putBoolean(KEY_SUM_EXISTS, true)
            .putString(KEY_SUM_NAME, summary.eventName)
            .putString(KEY_SUM_DESC, summary.eventDescription)
            .putLong(KEY_SUM_START, summary.startedAtMs ?: 0L)
            .putLong(KEY_SUM_END, summary.endedAtMs)
            .putInt(KEY_SUM_PUB, summary.publishedVideoCount)
            .putInt(KEY_SUM_REC, summary.totalRecordingCount)
            .putInt(KEY_SUM_LIKES, summary.totalLikesCount)
            .putInt(KEY_SUM_FAVS, summary.totalFavoritesCount)
            .putLong(KEY_SUM_BYTES, summary.totalStorageBytes)
            .apply()
        _settings.update {
            it.copy(
                eventModeStatus = EventModeStatus.ENDED,
                eventEndedAtMs = now,
                lastSavedEventSummary = summary
            )
        }
    }

    override suspend fun resetEventStatistics() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_STATS_RESET_AT, now)
            .remove(KEY_SUM_EXISTS)
            .apply()
        _settings.update {
            it.copy(
                eventStatsResetAtMs = now,
                lastSavedEventSummary = null
            )
        }
    }

    override suspend fun updateRecordingDuration(durationSeconds: Int) {
        prefs.edit().putInt(KEY_DEFAULT_DURATION, durationSeconds).apply()
        _settings.update { it.copy(defaultRecordingDurationSeconds = durationSeconds) }
    }

    override suspend fun toggleGuestDeletion(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_GUEST_DELETION, enabled).apply()
        _settings.update { it.copy(allowGuestDeletion = enabled) }
    }

    override suspend fun toggleKioskLock(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KIOSK_LOCK, enabled).apply()
        _settings.update { it.copy(isKioskLockEnabled = enabled) }
    }

    override suspend fun toggleWideView(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIDE_VIEW_ENABLED, enabled).apply()
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
        prefs.edit()
            .putBoolean(KEY_SHOW_FEATURED, showFeaturedSection)
            .putBoolean(KEY_ENABLE_LIKES, enableGuestLikes)
            .putBoolean(KEY_ENABLE_FAVORITES, enableGuestFavorites)
            .putBoolean(KEY_ENABLE_DISCOVER, enableDiscover)
            .putBoolean(KEY_ALLOW_GUEST_DELETION, allowGuestDeletion)
            .putString(KEY_DEFAULT_FEED_SORT, defaultFeedSort.name)
            .putBoolean(KEY_REQUIRE_NICKNAME, requireGuestNickname)
            .apply()
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
        // Real filesystem cleanup is handled by VideoStorageManager & MusicStorageManager
    }

    override suspend fun validateAdminPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: sha256(DEFAULT_PIN).also {
            prefs.edit().putString(KEY_PIN_HASH, it).apply()
        }
        return sha256(pin) == storedHash
    }

    private fun loadSettingsFromPrefs(): KioskSettings {
        val defaults = KioskSettings()
        if (!prefs.contains(KEY_EVENT_STARTED_AT) && !prefs.contains(KEY_EVENT_STATUS)) {
            val initNow = System.currentTimeMillis()
            prefs.edit()
                .putString(KEY_EVENT_STATUS, EventModeStatus.ACTIVE.name)
                .putLong(KEY_EVENT_STARTED_AT, initNow)
                .apply()
        }

        val status = prefs.getString(KEY_EVENT_STATUS, defaults.eventModeStatus.name)
            ?.let { raw -> EventModeStatus.values().firstOrNull { it.name == raw } }
            ?: defaults.eventModeStatus

        val sortOrder = prefs.getString(KEY_DEFAULT_FEED_SORT, defaults.defaultFeedSort.name)
            ?.let { raw -> FeedSortOrder.values().firstOrNull { it.name == raw } }
            ?: defaults.defaultFeedSort

        val startedAt = if (prefs.contains(KEY_EVENT_STARTED_AT)) prefs.getLong(KEY_EVENT_STARTED_AT, 0L).takeIf { it > 0L } else null
        val endedAt = if (prefs.contains(KEY_EVENT_ENDED_AT)) prefs.getLong(KEY_EVENT_ENDED_AT, 0L).takeIf { it > 0L } else null

        val summary = if (prefs.getBoolean(KEY_SUM_EXISTS, false)) {
            EventSummarySnapshot(
                eventName = prefs.getString(KEY_SUM_NAME, defaults.eventName) ?: defaults.eventName,
                eventDescription = prefs.getString(KEY_SUM_DESC, "") ?: "",
                startedAtMs = prefs.getLong(KEY_SUM_START, 0L).takeIf { it > 0L },
                endedAtMs = prefs.getLong(KEY_SUM_END, 0L),
                publishedVideoCount = prefs.getInt(KEY_SUM_PUB, 0),
                totalRecordingCount = prefs.getInt(KEY_SUM_REC, 0),
                totalLikesCount = prefs.getInt(KEY_SUM_LIKES, 0),
                totalFavoritesCount = prefs.getInt(KEY_SUM_FAVS, 0),
                totalStorageBytes = prefs.getLong(KEY_SUM_BYTES, 0L)
            )
        } else {
            null
        }

        return defaults.copy(
            eventName = prefs.getString(KEY_EVENT_NAME, defaults.eventName) ?: defaults.eventName,
            eventTheme = prefs.getString(KEY_EVENT_THEME, defaults.eventTheme) ?: defaults.eventTheme,
            eventDescription = prefs.getString(KEY_EVENT_DESC, "") ?: "",
            eventModeStatus = status,
            eventStartedAtMs = startedAt,
            eventEndedAtMs = endedAt,
            eventStatsResetAtMs = prefs.getLong(KEY_STATS_RESET_AT, 0L),
            lastSavedEventSummary = summary,
            defaultRecordingDurationSeconds = prefs.getInt(KEY_DEFAULT_DURATION, defaults.defaultRecordingDurationSeconds),
            allowGuestDeletion = prefs.getBoolean(KEY_ALLOW_GUEST_DELETION, defaults.allowGuestDeletion),
            isKioskLockEnabled = prefs.getBoolean(KEY_KIOSK_LOCK, defaults.isKioskLockEnabled),
            isWideViewEnabled = prefs.getBoolean(KEY_WIDE_VIEW_ENABLED, defaults.isWideViewEnabled),
            showFeaturedSection = prefs.getBoolean(KEY_SHOW_FEATURED, defaults.showFeaturedSection),
            enableGuestLikes = prefs.getBoolean(KEY_ENABLE_LIKES, defaults.enableGuestLikes),
            enableGuestFavorites = prefs.getBoolean(KEY_ENABLE_FAVORITES, defaults.enableGuestFavorites),
            enableDiscover = prefs.getBoolean(KEY_ENABLE_DISCOVER, defaults.enableDiscover),
            defaultFeedSort = sortOrder,
            requireGuestNickname = prefs.getBoolean(KEY_REQUIRE_NICKNAME, defaults.requireGuestNickname)
        )
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

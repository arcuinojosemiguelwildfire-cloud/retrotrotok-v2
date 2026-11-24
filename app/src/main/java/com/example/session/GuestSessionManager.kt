package com.example.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Isolated anonymous local guest session state.
 * Never stores permanent accounts and never deletes published videos on session end.
 */
data class GuestSession(
    val sessionId: String,
    val startedAtMs: Long,
    val nickname: String = "",
    val hasAcknowledgedPrivacyNotice: Boolean = false
)

class GuestSessionManager {

    private val _currentSession = MutableStateFlow(createFreshSession())
    val currentSession: StateFlow<GuestSession> = _currentSession.asStateFlow()

    fun startNewSession(): GuestSession {
        val fresh = createFreshSession()
        _currentSession.value = fresh
        return fresh
    }

    fun acknowledgePrivacyNotice() {
        _currentSession.update { it.copy(hasAcknowledgedPrivacyNotice = true) }
    }

    fun updateNickname(nickname: String) {
        _currentSession.update { it.copy(nickname = nickname.take(20)) }
    }

    fun endCurrentSession(): GuestSession {
        // Replaces temporary guest session state cleanly with a fresh isolated session
        return startNewSession()
    }

    private fun createFreshSession(): GuestSession {
        val now = System.currentTimeMillis()
        val token = UUID.randomUUID().toString().take(8)
        return GuestSession(
            sessionId = "guest_${now}_$token",
            startedAtMs = now
        )
    }
}

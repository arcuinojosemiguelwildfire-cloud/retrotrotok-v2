package com.example.cloud

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.example.model.CloudPairingState
import com.example.model.CloudPairingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedTokenPayload(
    val ivBase64: String,
    val cipherTextBase64: String
)

interface TokenCipher {
    fun encrypt(plaintext: String): EncryptedTokenPayload
    fun decrypt(ivBase64: String, cipherTextBase64: String): String?
}

/**
 * Hardware/OS-backed AndroidKeyStore AES-256-GCM cipher compatible with API 23+ (Android 6.0.1+).
 * Never falls back to plaintext token storage.
 */
class AndroidKeyStoreTokenCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : TokenCipher {

    companion object {
        private const val TAG = "AndroidKeyStoreCipher"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        const val DEFAULT_KEY_ALIAS = "retrotok_cloud_device_token_aes_gcm_v1"
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun encrypt(plaintext: String): EncryptedTokenPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivBytes = cipher.iv
        return EncryptedTokenPayload(
            ivBase64 = Base64.encodeToString(ivBytes, Base64.NO_WRAP),
            cipherTextBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        )
    }

    override fun decrypt(ivBase64: String, cipherTextBase64: String): String? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = keyStore.getKey(keyAlias, null) as? SecretKey ?: return null
            val ivBytes = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cipherBytes = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to decrypt stored device token; re-pairing required (${e.javaClass.simpleName})")
            null
        }
    }
}

/**
 * Stores kiosk cloud pairing metadata and encrypted `device_token` safely.
 * Plaintext `device_token` is never written to SharedPreferences, Room, or logs.
 */
class CloudCredentialStore(
    context: Context,
    private val tokenCipher: TokenCipher = AndroidKeyStoreTokenCipher()
) {
    companion object {
        private const val TAG = "CloudCredentialStore"
        private const val PREFS_NAME = "retrotok_cloud_credentials"
        private const val KEY_DEVICE_TOKEN_IV = "device_token_iv"
        private const val KEY_DEVICE_TOKEN_ENC = "device_token_enc"
        private const val KEY_PAIRING_STATUS = "pairing_status"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_IDENTIFIER = "device_identifier"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PAIRED_EVENT_ID = "paired_event_id"
        private const val KEY_PAIRED_EVENT_KEY = "paired_event_key"
        private const val KEY_PAIRED_EVENT_NAME = "paired_event_name"
        private const val KEY_PAIRED_EVENT_STATUS = "paired_event_status"
        private const val KEY_REMAINING_CAPACITY = "remaining_capacity"
        private const val KEY_PAIRED_AT = "paired_at"
        private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"
        private const val KEY_LAST_ERROR = "last_error"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()
    private val _pairingState = MutableStateFlow(loadPairingStateLocked())
    val pairingState: StateFlow<CloudPairingState> = _pairingState.asStateFlow()

    fun getOrCreateDeviceIdentifier(): String = synchronized(lock) {
        val existing = prefs.getString(KEY_DEVICE_IDENTIFIER, null)?.trim()
        if (!existing.isNullOrBlank()) return existing
        val generated = "kiosk-${UUID.randomUUID().toString().replace("-", "").take(10)}"
        prefs.edit().putString(KEY_DEVICE_IDENTIFIER, generated).apply()
        _pairingState.value = loadPairingStateLocked()
        return generated
    }

    fun getDeviceToken(): String? = synchronized(lock) {
        val iv = prefs.getString(KEY_DEVICE_TOKEN_IV, null) ?: return null
        val enc = prefs.getString(KEY_DEVICE_TOKEN_ENC, null) ?: return null
        return tokenCipher.decrypt(iv, enc)?.takeIf { it.length >= 32 }
    }

    fun setPairingInProgress(deviceName: String, eventId: Long) = synchronized(lock) {
        prefs.edit()
            .putString(KEY_PAIRING_STATUS, CloudPairingStatus.PAIRING.name)
            .putString(KEY_DEVICE_NAME, deviceName.ifBlank { "RetroTok Kiosk" })
            .putLong(KEY_PAIRED_EVENT_ID, eventId)
            .remove(KEY_LAST_ERROR)
            .apply()
        _pairingState.value = loadPairingStateLocked()
    }

    fun savePairedCredentials(
        deviceToken: String,
        deviceId: Long,
        deviceIdentifier: String,
        deviceName: String,
        pairedAt: String,
        eventId: Long,
        eventKey: String? = null,
        eventName: String? = null,
        eventStatus: String? = null,
        remainingCapacity: Int? = null
    ): Boolean = synchronized(lock) {
        if (deviceToken.length < 32) {
            markPairingFailed("Invalid device token received from server.")
            return false
        }
        val encrypted = try {
            tokenCipher.encrypt(deviceToken)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt device token using AndroidKeyStore (${e.javaClass.simpleName})")
            markPairingFailed("Secure Keystore unavailable on device; cannot save device token.")
            return false
        }

        val editor = prefs.edit()
            .putString(KEY_DEVICE_TOKEN_IV, encrypted.ivBase64)
            .putString(KEY_DEVICE_TOKEN_ENC, encrypted.cipherTextBase64)
            .putString(KEY_PAIRING_STATUS, CloudPairingStatus.PAIRED.name)
            .putLong(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_IDENTIFIER, deviceIdentifier)
            .putString(KEY_DEVICE_NAME, deviceName.ifBlank { "RetroTok Kiosk" })
            .putString(KEY_PAIRED_AT, pairedAt)
            .putLong(KEY_PAIRED_EVENT_ID, eventId)
            .remove(KEY_LAST_ERROR)

        if (eventKey != null) editor.putString(KEY_PAIRED_EVENT_KEY, eventKey) else editor.remove(KEY_PAIRED_EVENT_KEY)
        if (eventName != null) editor.putString(KEY_PAIRED_EVENT_NAME, eventName) else editor.remove(KEY_PAIRED_EVENT_NAME)
        if (eventStatus != null) editor.putString(KEY_PAIRED_EVENT_STATUS, eventStatus) else editor.remove(KEY_PAIRED_EVENT_STATUS)
        if (remainingCapacity != null) editor.putInt(KEY_REMAINING_CAPACITY, remainingCapacity) else editor.remove(KEY_REMAINING_CAPACITY)

        editor.commit()
        _pairingState.value = loadPairingStateLocked()
        return true
    }

    fun updateEventSnapshot(
        eventId: Long,
        eventKey: String?,
        eventName: String?,
        eventStatus: String?,
        remainingCapacity: Int?
    ) = synchronized(lock) {
        val editor = prefs.edit()
            .putLong(KEY_PAIRED_EVENT_ID, eventId)
        if (eventKey != null) editor.putString(KEY_PAIRED_EVENT_KEY, eventKey)
        if (eventName != null) editor.putString(KEY_PAIRED_EVENT_NAME, eventName)
        if (eventStatus != null) editor.putString(KEY_PAIRED_EVENT_STATUS, eventStatus)
        if (remainingCapacity != null) editor.putInt(KEY_REMAINING_CAPACITY, remainingCapacity)
        editor.apply()
        _pairingState.value = loadPairingStateLocked()
    }

    fun updateHeartbeat(lastSeenAt: String) = synchronized(lock) {
        prefs.edit()
            .putString(KEY_LAST_HEARTBEAT_AT, lastSeenAt)
            .remove(KEY_LAST_ERROR)
            .apply()
        _pairingState.value = loadPairingStateLocked()
    }

    fun markPairingFailed(message: String) = synchronized(lock) {
        val hasToken = hasValidStoredTokenLocked()
        prefs.edit()
            .putString(
                KEY_PAIRING_STATUS,
                if (hasToken) CloudPairingStatus.PAIRED.name else CloudPairingStatus.PAIRING_FAILED.name
            )
            .putString(KEY_LAST_ERROR, CloudLogSanitizer.sanitize(message))
            .apply()
        _pairingState.value = loadPairingStateLocked()
    }

    fun markTokenRevoked(message: String = "Device authorization revoked or expired. Please re-pair this kiosk.") =
        synchronized(lock) {
            prefs.edit()
                .remove(KEY_DEVICE_TOKEN_IV)
                .remove(KEY_DEVICE_TOKEN_ENC)
                .putString(KEY_PAIRING_STATUS, CloudPairingStatus.REVOKED.name)
                .putString(KEY_LAST_ERROR, CloudLogSanitizer.sanitize(message))
                .commit()
            _pairingState.value = loadPairingStateLocked()
        }

    fun unpair() = synchronized(lock) {
        val identifier = getOrCreateDeviceIdentifier()
        prefs.edit()
            .remove(KEY_DEVICE_TOKEN_IV)
            .remove(KEY_DEVICE_TOKEN_ENC)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PAIRED_EVENT_ID)
            .remove(KEY_PAIRED_EVENT_KEY)
            .remove(KEY_PAIRED_EVENT_NAME)
            .remove(KEY_PAIRED_EVENT_STATUS)
            .remove(KEY_REMAINING_CAPACITY)
            .remove(KEY_PAIRED_AT)
            .remove(KEY_LAST_HEARTBEAT_AT)
            .remove(KEY_LAST_ERROR)
            .putString(KEY_PAIRING_STATUS, CloudPairingStatus.UNPAIRED.name)
            .putString(KEY_DEVICE_IDENTIFIER, identifier)
            .commit()
        _pairingState.value = loadPairingStateLocked()
    }

    private fun hasValidStoredTokenLocked(): Boolean {
        val iv = prefs.getString(KEY_DEVICE_TOKEN_IV, null) ?: return false
        val enc = prefs.getString(KEY_DEVICE_TOKEN_ENC, null) ?: return false
        return tokenCipher.decrypt(iv, enc)?.let { it.length >= 32 } ?: false
    }

    private fun loadPairingStateLocked(): CloudPairingState {
        val identifier = prefs.getString(KEY_DEVICE_IDENTIFIER, null)?.takeIf { it.isNotBlank() }
            ?: "kiosk-${UUID.randomUUID().toString().replace("-", "").take(10)}".also {
                prefs.edit().putString(KEY_DEVICE_IDENTIFIER, it).apply()
            }

        val hasToken = hasValidStoredTokenLocked()
        val rawStatus = prefs.getString(KEY_PAIRING_STATUS, CloudPairingStatus.UNPAIRED.name)
        var status = CloudPairingStatus.values().firstOrNull { it.name == rawStatus } ?: CloudPairingStatus.UNPAIRED
        if (status == CloudPairingStatus.PAIRED && !hasToken) {
            status = CloudPairingStatus.REVOKED
        }

        val deviceId = if (prefs.contains(KEY_DEVICE_ID)) prefs.getLong(KEY_DEVICE_ID, 0L).takeIf { it > 0L } else null
        val eventId = if (prefs.contains(KEY_PAIRED_EVENT_ID)) prefs.getLong(KEY_PAIRED_EVENT_ID, 0L).takeIf { it > 0L } else null
        val remainingCap = if (prefs.contains(KEY_REMAINING_CAPACITY)) prefs.getInt(KEY_REMAINING_CAPACITY, 0) else null

        return CloudPairingState(
            pairingStatus = status,
            hasDeviceToken = hasToken,
            deviceId = deviceId,
            deviceIdentifier = identifier,
            deviceName = prefs.getString(KEY_DEVICE_NAME, "RetroTok Kiosk") ?: "RetroTok Kiosk",
            pairedEventId = eventId,
            pairedEventKey = prefs.getString(KEY_PAIRED_EVENT_KEY, null),
            pairedEventName = prefs.getString(KEY_PAIRED_EVENT_NAME, null),
            pairedEventStatus = prefs.getString(KEY_PAIRED_EVENT_STATUS, null),
            remainingCapacity = remainingCap,
            pairedAt = prefs.getString(KEY_PAIRED_AT, null),
            lastHeartbeatAt = prefs.getString(KEY_LAST_HEARTBEAT_AT, null),
            lastErrorMessage = prefs.getString(KEY_LAST_ERROR, null)
        )
    }
}

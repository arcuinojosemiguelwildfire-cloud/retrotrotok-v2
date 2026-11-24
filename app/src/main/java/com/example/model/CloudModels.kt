package com.example.model

enum class CloudPairingStatus(val label: String) {
    UNPAIRED("Unpaired"),
    PAIRING("Pairing..."),
    PAIRED("Paired"),
    PAIRING_FAILED("Pairing Failed"),
    REVOKED("Token Revoked")
}

enum class CloudUploadStatus(val displayLabel: String) {
    LOCAL_ONLY("Saved locally"),
    QUEUED("Waiting for internet"),
    REQUESTING_UPLOAD("Preparing upload"),
    UPLOADING("Uploading..."),
    VERIFYING("Verifying upload"),
    UPLOADED("Uploaded"),
    RETRY_PENDING("Retry scheduled"),
    FAILED("Upload failed");

    companion object {
        fun fromRaw(raw: String?): CloudUploadStatus {
            return values().firstOrNull { it.name == raw } ?: LOCAL_ONLY
        }
    }
}

data class CloudPairingState(
    val pairingStatus: CloudPairingStatus = CloudPairingStatus.UNPAIRED,
    val hasDeviceToken: Boolean = false,
    val deviceId: Long? = null,
    val deviceIdentifier: String = "",
    val deviceName: String = "RetroTok Kiosk",
    val pairedEventId: Long? = null,
    val pairedEventKey: String? = null,
    val pairedEventName: String? = null,
    val pairedEventStatus: String? = null,
    val remainingCapacity: Int? = null,
    val pairedAt: String? = null,
    val lastHeartbeatAt: String? = null,
    val lastErrorMessage: String? = null
) {
    val isPairedAndAuthorized: Boolean
        get() = pairingStatus == CloudPairingStatus.PAIRED && hasDeviceToken && pairedEventId != null
}

data class CloudUploadSummary(
    val videoId: String,
    val status: CloudUploadStatus,
    val cloudVideoId: Long? = null,
    val eventId: Long? = null,
    val qrToken: String? = null,
    val viewingUrl: String? = null,
    val attemptCount: Int = 0,
    val nextRetryAtMs: Long? = null,
    val uploadedAt: String? = null,
    val expiresAt: String? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null
)

data class CloudQueueCounts(
    val localOnlyCount: Int = 0,
    val queuedCount: Int = 0,
    val uploadingCount: Int = 0,
    val uploadedCount: Int = 0,
    val retryPendingCount: Int = 0,
    val failedCount: Int = 0
) {
    val pendingOrRetryCount: Int
        get() = queuedCount + retryPendingCount + uploadingCount
}

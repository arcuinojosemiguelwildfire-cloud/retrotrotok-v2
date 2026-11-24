package com.example.cloud

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Redacts sensitive tokens, pairing codes, QR tokens, and presigned URL query parameters
 * from logs and UI-visible error strings.
 */
object CloudLogSanitizer {
    private val bearerRegex = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val urlQueryRegex = Regex("(https?://[^\\s?]+)\\?[^\\s]+")
    private val hexTokenRegex = Regex("\\b[a-fA-F0-9]{48,}\\b")

    fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(bearerRegex, "Bearer [REDACTED]")
            .replace(urlQueryRegex, "$1?[REDACTED]")
            .replace(hexTokenRegex, "[REDACTED_TOKEN]")
    }
}

@JsonClass(generateAdapter = false)
data class CloudApiErrorBody(
    @Json(name = "code") val code: String? = null,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = false)
data class CloudApiEnvelope<T>(
    @Json(name = "ok") val ok: Boolean = false,
    @Json(name = "data") val data: T? = null,
    @Json(name = "error") val error: CloudApiErrorBody? = null
)

@JsonClass(generateAdapter = false)
data class CloudApiErrorEnvelope(
    @Json(name = "ok") val ok: Boolean = false,
    @Json(name = "error") val error: CloudApiErrorBody? = null
)

@JsonClass(generateAdapter = false)
data class PairDeviceRequest(
    @Json(name = "pairing_code") val pairingCode: String,
    @Json(name = "device_identifier") val deviceIdentifier: String,
    @Json(name = "device_name") val deviceName: String?,
    @Json(name = "app_version") val appVersion: String?,
    @Json(name = "android_sdk_int") val androidSdkInt: Int?
)

@JsonClass(generateAdapter = false)
data class PairDeviceResponseData(
    @Json(name = "device_id") val deviceId: Long,
    @Json(name = "device_identifier") val deviceIdentifier: String,
    @Json(name = "device_name") val deviceName: String? = null,
    @Json(name = "device_token") val deviceToken: String,
    @Json(name = "paired_at") val pairedAt: String
)

@JsonClass(generateAdapter = false)
data class HeartbeatRequest(
    @Json(name = "app_version") val appVersion: String?
)

@JsonClass(generateAdapter = false)
data class HeartbeatResponseData(
    @Json(name = "device_id") val deviceId: Long,
    @Json(name = "last_seen_at") val lastSeenAt: String,
    @Json(name = "status") val status: String
)

@JsonClass(generateAdapter = false)
data class EventInfoResponseData(
    @Json(name = "id") val id: Long,
    @Json(name = "external_event_key") val externalEventKey: String? = null,
    @Json(name = "event_name") val eventName: String,
    @Json(name = "event_description") val eventDescription: String? = null,
    @Json(name = "status") val status: String,
    @Json(name = "max_videos") val maxVideos: Int? = null,
    @Json(name = "active_video_count") val activeVideoCount: Int? = null,
    @Json(name = "available_video_count") val availableVideoCount: Int? = null,
    @Json(name = "pending_upload_count") val pendingUploadCount: Int? = null,
    @Json(name = "failed_upload_count") val failedUploadCount: Int? = null,
    @Json(name = "remaining_capacity") val remainingCapacity: Int? = null,
    @Json(name = "total_uploaded_bytes") val totalUploadedBytes: Long? = null,
    @Json(name = "started_at") val startedAt: String? = null,
    @Json(name = "ended_at") val endedAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = false)
data class InitiateVideoUploadRequest(
    @Json(name = "kiosk_video_id") val kioskVideoId: String,
    @Json(name = "file_size") val fileSize: Long,
    @Json(name = "duration_seconds") val durationSeconds: Int,
    @Json(name = "mime_type") val mimeType: String = "video/mp4",
    @Json(name = "width") val width: Int? = null,
    @Json(name = "height") val height: Int? = null,
    @Json(name = "checksum") val checksum: String? = null,
    @Json(name = "nickname") val nickname: String? = null,
    @Json(name = "caption") val caption: String? = null,
    @Json(name = "soundtrack_title") val soundtrackTitle: String? = null,
    @Json(name = "soundtrack_artist") val soundtrackArtist: String? = null,
    @Json(name = "effect_type") val effectType: String? = null
)

@JsonClass(generateAdapter = false)
data class InitiateVideoUploadResponseData(
    @Json(name = "cloud_video_id") val cloudVideoId: Long,
    @Json(name = "event_id") val eventId: Long,
    @Json(name = "kiosk_video_id") val kioskVideoId: String,
    @Json(name = "status") val status: String,
    @Json(name = "idempotent_replay") val idempotentReplay: Boolean? = null,
    @Json(name = "already_available") val alreadyAvailable: Boolean? = null,
    @Json(name = "upload_attempt") val uploadAttempt: Int? = null,
    @Json(name = "storage_key") val storageKey: String? = null,
    @Json(name = "upload_url") val uploadUrl: String? = null,
    @Json(name = "upload_headers") val uploadHeaders: Map<String, String>? = null,
    @Json(name = "upload_expires_at") val uploadExpiresAt: String? = null,
    @Json(name = "expected_file_size") val expectedFileSize: Long? = null,
    @Json(name = "uploaded_at") val uploadedAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null
)

@JsonClass(generateAdapter = false)
data class CompleteVideoUploadRequest(
    @Json(name = "client_verified") val clientVerified: Boolean = true
)

@JsonClass(generateAdapter = false)
data class CompleteVideoUploadResponseData(
    @Json(name = "cloud_video_id") val cloudVideoId: Long,
    @Json(name = "event_id") val eventId: Long,
    @Json(name = "kiosk_video_id") val kioskVideoId: String,
    @Json(name = "status") val status: String,
    @Json(name = "already_completed") val alreadyCompleted: Boolean? = null,
    @Json(name = "qr_token") val qrToken: String? = null,
    @Json(name = "viewing_url") val viewingUrl: String? = null,
    @Json(name = "uploaded_at") val uploadedAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null
)

@JsonClass(generateAdapter = false)
data class UploadAttemptStatusData(
    @Json(name = "upload_attempt") val uploadAttempt: Int? = null,
    @Json(name = "upload_status") val uploadStatus: String? = null,
    @Json(name = "last_error_code") val lastErrorCode: String? = null,
    @Json(name = "last_error") val lastError: String? = null,
    @Json(name = "presigned_expires_at") val presignedExpiresAt: String? = null,
    @Json(name = "completed_at") val completedAt: String? = null
)

@JsonClass(generateAdapter = false)
data class VideoStatusResponseData(
    @Json(name = "cloud_video_id") val cloudVideoId: Long,
    @Json(name = "event_id") val eventId: Long,
    @Json(name = "kiosk_video_id") val kioskVideoId: String,
    @Json(name = "status") val status: String,
    @Json(name = "file_size") val fileSize: Long? = null,
    @Json(name = "duration_seconds") val durationSeconds: Int? = null,
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "storage_key") val storageKey: String? = null,
    @Json(name = "uploaded_at") val uploadedAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "deleted_at") val deletedAt: String? = null,
    @Json(name = "latest_upload_attempt") val latestUploadAttempt: UploadAttemptStatusData? = null
)

@JsonClass(generateAdapter = false)
data class CloudEventVideoSummary(
    @Json(name = "cloud_video_id") val cloudVideoId: Long,
    @Json(name = "event_id") val eventId: Long,
    @Json(name = "kiosk_video_id") val kioskVideoId: String,
    @Json(name = "nickname") val nickname: String? = null,
    @Json(name = "status") val status: String,
    @Json(name = "file_size") val fileSize: Long? = null,
    @Json(name = "duration_seconds") val durationSeconds: Int? = null,
    @Json(name = "viewing_url") val viewingUrl: String? = null,
    @Json(name = "uploaded_at") val uploadedAt: String? = null
)

@JsonClass(generateAdapter = false)
data class EventVideosResponseData(
    @Json(name = "event_id") val eventId: Long,
    @Json(name = "storage_configured") val storageConfigured: Boolean? = null,
    @Json(name = "videos") val videos: List<CloudEventVideoSummary> = emptyList(),
    @Json(name = "next_cursor") val nextCursor: Long? = null
)

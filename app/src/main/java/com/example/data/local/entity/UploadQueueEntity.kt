package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.model.CloudUploadStatus
import com.example.model.CloudUploadSummary

@Entity(
    tableName = "upload_queue",
    indices = [
        Index(value = ["status"]),
        Index(value = ["eventId"]),
        Index(value = ["cloudVideoId"]),
        Index(value = ["nextRetryAtMs"])
    ]
)
data class UploadQueueEntity(
    @PrimaryKey
    val videoId: String,
    val status: String = CloudUploadStatus.LOCAL_ONLY.name,
    val eventId: Long? = null,
    val cloudVideoId: Long? = null,
    val sha256Checksum: String? = null,
    val storageKey: String? = null,
    val qrToken: String? = null,
    val viewingUrl: String? = null,
    val attemptCount: Int = 0,
    val lastAttemptAtMs: Long? = null,
    val nextRetryAtMs: Long? = null,
    val uploadedAt: String? = null,
    val expiresAt: String? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toSummary(): CloudUploadSummary {
        return CloudUploadSummary(
            videoId = videoId,
            status = CloudUploadStatus.fromRaw(status),
            cloudVideoId = cloudVideoId,
            eventId = eventId,
            qrToken = qrToken,
            viewingUrl = viewingUrl,
            attemptCount = attemptCount,
            nextRetryAtMs = nextRetryAtMs,
            uploadedAt = uploadedAt,
            expiresAt = expiresAt,
            lastErrorCode = lastErrorCode,
            lastErrorMessage = lastErrorMessage
        )
    }
}

package com.example.cloud

import android.os.Build
import android.util.Log
import com.example.data.local.dao.UploadQueueDao
import com.example.data.local.dao.VideoDao
import com.example.data.local.entity.UploadQueueEntity
import com.example.data.local.entity.VideoEntity
import com.example.model.CloudPairingState
import com.example.model.CloudPairingStatus
import com.example.model.CloudQueueCounts
import com.example.model.CloudUploadStatus
import com.example.video.VideoStorageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

sealed class PairingActionResult {
    data class Success(val pairingState: CloudPairingState) : PairingActionResult()
    data class Error(val message: String) : PairingActionResult()
}

/**
 * Coordinates kiosk device pairing, event verification, heartbeat, and the persistent offline-first
 * cloud video upload queue with exponential backoff, deduplication, and interrupted-upload recovery.
 */
class CloudUploadManager(
    private val credentialStore: CloudCredentialStore,
    private val backendGateway: CloudBackendGateway,
    private val r2Uploader: R2DirectUploader,
    private val videoDao: VideoDao,
    private val uploadQueueDao: UploadQueueDao,
    private val storageManager: VideoStorageManager,
    private val connectivityObserver: ConnectivityObserver,
    private val appVersion: String = "1.0",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val minVideoBytesForCloud: Long = DEFAULT_MIN_VIDEO_BYTES,
    private val maxVideoBytesForCloud: Long = DEFAULT_MAX_VIDEO_BYTES,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    autoStartObservers: Boolean = true
) {

    companion object {
        private const val TAG = "CloudUploadManager"
        const val DEFAULT_MIN_VIDEO_BYTES = 1_048_576L // 1 MiB
        const val DEFAULT_MAX_VIDEO_BYTES = 104_857_600L // 100 MiB
        const val MAX_AUTO_ATTEMPTS = 8
        private val BACKOFF_DELAYS_MS = longArrayOf(
            15_000L,
            30_000L,
            60_000L,
            120_000L,
            300_000L,
            600_000L
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val processMutex = Mutex()
    private val inFlightVideoIds = HashSet<String>()
    private val inFlightLock = Any()

    val pairingState: StateFlow<CloudPairingState> = credentialStore.pairingState
    val isOnline: StateFlow<Boolean> = connectivityObserver.isOnline

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    val queueCounts: StateFlow<CloudQueueCounts> = uploadQueueDao.observeAll()
        .map { entries ->
            CloudQueueCounts(
                localOnlyCount = entries.count { it.status == CloudUploadStatus.LOCAL_ONLY.name },
                queuedCount = entries.count { it.status == CloudUploadStatus.QUEUED.name },
                uploadingCount = entries.count {
                    it.status == CloudUploadStatus.REQUESTING_UPLOAD.name ||
                        it.status == CloudUploadStatus.UPLOADING.name ||
                        it.status == CloudUploadStatus.VERIFYING.name
                },
                uploadedCount = entries.count { it.status == CloudUploadStatus.UPLOADED.name },
                retryPendingCount = entries.count { it.status == CloudUploadStatus.RETRY_PENDING.name },
                failedCount = entries.count { it.status == CloudUploadStatus.FAILED.name }
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, CloudQueueCounts())

    init {
        if (autoStartObservers) {
            observeConnectivityChanges()
        }
    }

    private fun observeConnectivityChanges() {
        scope.launch {
            connectivityObserver.isOnline
                .collect { online ->
                    if (online && credentialStore.pairingState.value.isPairedAndAuthorized) {
                        processPendingQueue(forceRetryDelayReset = false)
                    }
                }
        }
    }

    /**
     * Pairs this kiosk using a one-time organizer pairing code and verifies the explicitly chosen
     * target numeric event ID before storing the device bearer token.
     */
    suspend fun pairAndVerifyEvent(
        pairingCode: String,
        eventId: Long,
        deviceName: String
    ): PairingActionResult = withContext(ioDispatcher) {
        val cleanCode = pairingCode.trim()
        val cleanName = deviceName.trim().ifBlank { "RetroTok Kiosk" }.take(120)
        if (cleanCode.length < 4) {
            return@withContext PairingActionResult.Error("Enter a valid organizer pairing code.")
        }
        if (eventId <= 0L) {
            return@withContext PairingActionResult.Error("Enter a valid numeric Event ID (e.g., 1).")
        }
        if (!connectivityObserver.checkIsOnlineNow()) {
            credentialStore.markPairingFailed("No internet connection. Connect to Wi-Fi before pairing.")
            return@withContext PairingActionResult.Error("No internet connection. Local event mode remains active.")
        }

        credentialStore.setPairingInProgress(cleanName, eventId)
        val identifier = credentialStore.getOrCreateDeviceIdentifier()
        val pairResult = backendGateway.pairDevice(
            PairDeviceRequest(
                pairingCode = cleanCode,
                deviceIdentifier = identifier,
                deviceName = cleanName,
                appVersion = appVersion,
                androidSdkInt = Build.VERSION.SDK_INT
            )
        )

        val pairData = when (pairResult) {
            is CloudApiResult.Success -> pairResult.data
            is CloudApiResult.HttpError -> {
                val msg = when (pairResult.errorCode) {
                    "INVALID_PAIRING_CODE" -> "Invalid pairing code. Check Admin Portal or ask organizer."
                    "RATE_LIMIT_EXCEEDED" -> "Too many pairing attempts. Wait a minute before retrying."
                    "PAIRING_NOT_CONFIGURED" -> "Server pairing is not configured on backend."
                    else -> pairResult.message
                }
                credentialStore.markPairingFailed(msg)
                return@withContext PairingActionResult.Error(msg)
            }
            is CloudApiResult.NetworkError -> {
                credentialStore.markPairingFailed(pairResult.message)
                return@withContext PairingActionResult.Error(pairResult.message)
            }
        }

        val saved = credentialStore.savePairedCredentials(
            deviceToken = pairData.deviceToken,
            deviceId = pairData.deviceId,
            deviceIdentifier = pairData.deviceIdentifier,
            deviceName = pairData.deviceName ?: cleanName,
            pairedAt = pairData.pairedAt,
            eventId = eventId
        )
        if (!saved) {
            return@withContext PairingActionResult.Error(
                credentialStore.pairingState.value.lastErrorMessage ?: "Unable to encrypt device token."
            )
        }

        // Verify the target event exists and is readable using the new Bearer token
        when (val eventResult = backendGateway.getEventInfo(eventId)) {
            is CloudApiResult.Success -> {
                val ev = eventResult.data
                credentialStore.updateEventSnapshot(
                    eventId = ev.id,
                    eventKey = ev.externalEventKey,
                    eventName = ev.eventName,
                    eventStatus = ev.status,
                    remainingCapacity = ev.remainingCapacity
                )
            }
            is CloudApiResult.HttpError -> {
                if (eventResult.isUnauthorizedOrRevoked) {
                    credentialStore.markTokenRevoked()
                } else {
                    credentialStore.unpair()
                    val msg = if (eventResult.httpCode == 404) {
                        "Event #$eventId was not found on the server. Pairing was rolled back."
                    } else {
                        "Paired device could not access Event #$eventId: ${eventResult.message}"
                    }
                    credentialStore.markPairingFailed(msg)
                    return@withContext PairingActionResult.Error(msg)
                }
            }
            is CloudApiResult.NetworkError -> {
                credentialStore.markPairingFailed("Paired, but event verification timed out: ${eventResult.message}")
            }
        }

        // Non-fatal initial heartbeat
        when (val hb = backendGateway.sendHeartbeat(HeartbeatRequest(appVersion))) {
            is CloudApiResult.Success -> credentialStore.updateHeartbeat(hb.data.lastSeenAt)
            else -> Unit
        }

        enqueueUnqueuedLocalVideosForSync()
        launchBackgroundSync(forceRetryDelayReset = true)
        PairingActionResult.Success(credentialStore.pairingState.value)
    }

    suspend fun sendHeartbeatAndRefreshEvent(): PairingActionResult = withContext(ioDispatcher) {
        val state = credentialStore.pairingState.value
        val eventId = state.pairedEventId
        if (!state.hasDeviceToken || eventId == null) {
            return@withContext PairingActionResult.Error("Kiosk is not paired with a cloud event.")
        }
        if (!connectivityObserver.checkIsOnlineNow()) {
            return@withContext PairingActionResult.Error("No internet connection.")
        }

        when (val hb = backendGateway.sendHeartbeat(HeartbeatRequest(appVersion))) {
            is CloudApiResult.Success -> credentialStore.updateHeartbeat(hb.data.lastSeenAt)
            is CloudApiResult.HttpError -> {
                if (hb.isUnauthorizedOrRevoked) {
                    credentialStore.markTokenRevoked()
                    return@withContext PairingActionResult.Error("Device token revoked on server. Re-pair required.")
                }
                return@withContext PairingActionResult.Error(hb.message)
            }
            is CloudApiResult.NetworkError -> return@withContext PairingActionResult.Error(hb.message)
        }

        when (val evRes = backendGateway.getEventInfo(eventId)) {
            is CloudApiResult.Success -> {
                val ev = evRes.data
                credentialStore.updateEventSnapshot(
                    eventId = ev.id,
                    eventKey = ev.externalEventKey,
                    eventName = ev.eventName,
                    eventStatus = ev.status,
                    remainingCapacity = ev.remainingCapacity
                )
            }
            is CloudApiResult.HttpError -> {
                if (evRes.isUnauthorizedOrRevoked) {
                    credentialStore.markTokenRevoked()
                    return@withContext PairingActionResult.Error("Device token revoked on server. Re-pair required.")
                }
                return@withContext PairingActionResult.Error(evRes.message)
            }
            is CloudApiResult.NetworkError -> return@withContext PairingActionResult.Error(evRes.message)
        }

        PairingActionResult.Success(credentialStore.pairingState.value)
    }

    fun unpairDevice() {
        credentialStore.unpair()
    }

    /**
     * Called immediately after a local recording or SAF import is committed to Room.
     * Local publication succeeds regardless of internet/pairing; cloud upload runs asynchronously.
     */
    fun onVideoPublishedAsync(videoId: String) {
        scope.launch {
            enqueueAndMaybeUpload(videoId, forceImmediateAttempt = true)
        }
    }

    /**
     * Manual retry button from Admin Video Manager for a single video.
     */
    fun retrySingleVideoAsync(videoId: String) {
        scope.launch {
            enqueueAndMaybeUpload(videoId, forceImmediateAttempt = true)
        }
    }

    /**
     * Manual "Sync Pending" trigger from Admin Console.
     */
    fun triggerSyncAllAsync() {
        scope.launch {
            enqueueUnqueuedLocalVideosForSync()
            processPendingQueue(forceRetryDelayReset = true)
        }
    }

    suspend fun enqueueUnqueuedLocalVideosForSync() = withContext(ioDispatcher) {
        val state = credentialStore.pairingState.value
        val eventId = state.pairedEventId
        val allVideos = videoDao.getAllVideosOnce()
        val now = timeProvider()
        for (video in allVideos) {
            if (video.publicationStatus != "PUBLISHED" && video.publicationStatus != "IMPORTED") continue
            val existing = uploadQueueDao.getByVideoId(video.videoId)
            if (existing == null) {
                val initialStatus = if (state.isPairedAndAuthorized) {
                    CloudUploadStatus.QUEUED
                } else {
                    CloudUploadStatus.LOCAL_ONLY
                }
                uploadQueueDao.insertIfAbsent(
                    UploadQueueEntity(
                        videoId = video.videoId,
                        status = initialStatus.name,
                        eventId = eventId,
                        createdAtMs = now,
                        updatedAtMs = now
                    )
                )
            } else if (state.isPairedAndAuthorized && existing.status == CloudUploadStatus.LOCAL_ONLY.name) {
                uploadQueueDao.upsert(
                    existing.copy(
                        status = CloudUploadStatus.QUEUED.name,
                        eventId = existing.eventId ?: eventId,
                        updatedAtMs = now
                    )
                )
            }
        }
    }

    suspend fun enqueueAndMaybeUpload(
        videoId: String,
        forceImmediateAttempt: Boolean = true
    ) = withContext(ioDispatcher) {
        val video = videoDao.getVideoById(videoId) ?: return@withContext
        if (video.publicationStatus != "PUBLISHED" && video.publicationStatus != "IMPORTED") {
            return@withContext
        }
        val state = credentialStore.pairingState.value
        val now = timeProvider()
        val existing = uploadQueueDao.getByVideoId(videoId)
        if (existing != null && existing.status == CloudUploadStatus.UPLOADED.name) {
            return@withContext
        }

        val targetStatus = if (state.isPairedAndAuthorized) {
            CloudUploadStatus.QUEUED.name
        } else {
            CloudUploadStatus.LOCAL_ONLY.name
        }

        val entry = if (existing == null) {
            UploadQueueEntity(
                videoId = videoId,
                status = targetStatus,
                eventId = state.pairedEventId,
                createdAtMs = now,
                updatedAtMs = now
            )
        } else {
            existing.copy(
                status = targetStatus,
                eventId = existing.eventId ?: state.pairedEventId,
                nextRetryAtMs = if (forceImmediateAttempt) null else existing.nextRetryAtMs,
                lastErrorCode = null,
                lastErrorMessage = if (targetStatus == CloudUploadStatus.LOCAL_ONLY.name) {
                    "Saved locally; pair kiosk in Admin Console for cloud upload."
                } else {
                    null
                },
                updatedAtMs = now
            )
        }
        uploadQueueDao.upsert(entry)

        if (state.isPairedAndAuthorized && forceImmediateAttempt) {
            processSingleVideo(videoId, ignoreBackoff = true)
        }
    }

    fun launchBackgroundSync(forceRetryDelayReset: Boolean = false) {
        scope.launch {
            processPendingQueue(forceRetryDelayReset = forceRetryDelayReset)
        }
    }

    suspend fun processPendingQueue(forceRetryDelayReset: Boolean = false) = withContext(ioDispatcher) {
        if (!credentialStore.pairingState.value.isPairedAndAuthorized) {
            return@withContext
        }
        if (!connectivityObserver.checkIsOnlineNow()) {
            return@withContext
        }

        processMutex.withLock {
            _isSyncing.value = true
            try {
                val now = timeProvider()
                val candidates = uploadQueueDao.getPendingAndInterrupted()
                for (item in candidates) {
                    if (!credentialStore.pairingState.value.isPairedAndAuthorized) break
                    if (!connectivityObserver.checkIsOnlineNow()) break
                    if (!forceRetryDelayReset &&
                        item.status == CloudUploadStatus.RETRY_PENDING.name &&
                        item.nextRetryAtMs != null &&
                        item.nextRetryAtMs > now
                    ) {
                        continue
                    }
                    processSingleVideo(item.videoId, ignoreBackoff = forceRetryDelayReset)
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Executes the complete upload/recovery state machine for one video while guaranteeing
     * no two coroutines upload the same video concurrently.
     */
    suspend fun processSingleVideo(
        videoId: String,
        ignoreBackoff: Boolean = false
    ) = withContext(ioDispatcher) {
        val acquired = synchronized(inFlightLock) {
            inFlightVideoIds.add(videoId)
        }
        if (!acquired) {
            Log.d(TAG, "Skipping duplicate concurrent upload attempt for $videoId")
            return@withContext
        }

        try {
            val video = videoDao.getVideoById(videoId) ?: return@withContext
            val pairing = credentialStore.pairingState.value
            val now = timeProvider()

            var entry = uploadQueueDao.getByVideoId(videoId) ?: UploadQueueEntity(
                videoId = videoId,
                status = CloudUploadStatus.QUEUED.name,
                eventId = pairing.pairedEventId,
                createdAtMs = now,
                updatedAtMs = now
            )

            if (entry.status == CloudUploadStatus.UPLOADED.name) {
                return@withContext
            }

            if (!pairing.isPairedAndAuthorized || pairing.pairedEventId == null) {
                uploadQueueDao.upsert(
                    entry.copy(
                        status = CloudUploadStatus.LOCAL_ONLY.name,
                        lastErrorMessage = if (pairing.pairingStatus == CloudPairingStatus.REVOKED) {
                            "Device token revoked; re-pair in Admin Console."
                        } else {
                            "Not paired with a cloud event."
                        },
                        updatedAtMs = now
                    )
                )
                return@withContext
            }

            if (!ignoreBackoff &&
                entry.status == CloudUploadStatus.RETRY_PENDING.name &&
                entry.nextRetryAtMs != null &&
                entry.nextRetryAtMs > now
            ) {
                return@withContext
            }

            val eventId = entry.eventId ?: pairing.pairedEventId

            if (!connectivityObserver.checkIsOnlineNow()) {
                uploadQueueDao.upsert(
                    entry.copy(
                        status = CloudUploadStatus.QUEUED.name,
                        eventId = eventId,
                        lastErrorCode = "OFFLINE",
                        lastErrorMessage = "Waiting for internet connection.",
                        updatedAtMs = now
                    )
                )
                return@withContext
            }

            // Local file validation before touching network (never deletes local files)
            val localFile = resolveLocalVideoFile(video)
            if (localFile == null || !localFile.exists() || !localFile.isFile) {
                uploadQueueDao.upsert(
                    entry.copy(
                        status = CloudUploadStatus.FAILED.name,
                        eventId = eventId,
                        lastErrorCode = "LOCAL_FILE_MISSING",
                        lastErrorMessage = "Local MP4 file missing on disk.",
                        updatedAtMs = now
                    )
                )
                return@withContext
            }

            val actualFileSize = localFile.length()
            if (actualFileSize < minVideoBytesForCloud || actualFileSize > maxVideoBytesForCloud) {
                val limitMsg = if (actualFileSize < minVideoBytesForCloud) {
                    "Video file (${actualFileSize / 1024} KB) is below ${minVideoBytesForCloud / 1024} KB minimum for cloud upload; kept locally."
                } else {
                    "Video file exceeds ${maxVideoBytesForCloud / (1024 * 1024)} MB cloud upload limit; kept locally."
                }
                uploadQueueDao.upsert(
                    entry.copy(
                        status = CloudUploadStatus.FAILED.name,
                        eventId = eventId,
                        lastErrorCode = "INVALID_FILE_SIZE",
                        lastErrorMessage = limitMsg,
                        updatedAtMs = now
                    )
                )
                return@withContext
            }

            val clampedDuration = video.duration
            if (clampedDuration !in 1..65) {
                uploadQueueDao.upsert(
                    entry.copy(
                        status = CloudUploadStatus.FAILED.name,
                        eventId = eventId,
                        lastErrorCode = "INVALID_DURATION",
                        lastErrorMessage = "Duration ${clampedDuration}s is outside backend limit (1-65s); kept locally.",
                        updatedAtMs = now
                    )
                )
                return@withContext
            }

            // Step 0: Interrupted upload reconciliation via GET /videos/{videoId}/status
            val existingCloudId = entry.cloudVideoId
            if (existingCloudId != null) {
                when (val statusRes = backendGateway.getVideoStatus(existingCloudId)) {
                    is CloudApiResult.Success -> {
                        val s = statusRes.data
                        if (s.status == "AVAILABLE") {
                            uploadQueueDao.upsert(
                                entry.copy(
                                    status = CloudUploadStatus.UPLOADED.name,
                                    eventId = s.eventId,
                                    cloudVideoId = s.cloudVideoId,
                                    storageKey = s.storageKey ?: entry.storageKey,
                                    uploadedAt = s.uploadedAt ?: entry.uploadedAt,
                                    expiresAt = s.expiresAt ?: entry.expiresAt,
                                    lastErrorCode = null,
                                    lastErrorMessage = null,
                                    nextRetryAtMs = null,
                                    updatedAtMs = timeProvider()
                                )
                            )
                            return@withContext
                        } else if (entry.status == CloudUploadStatus.VERIFYING.name ||
                            entry.status == CloudUploadStatus.UPLOADING.name
                        ) {
                            // Maybe PUT finished right before process stopped — attempt /complete first
                            if (attemptCompleteOnly(entry, existingCloudId)) {
                                return@withContext
                            }
                            entry = uploadQueueDao.getByVideoId(videoId) ?: entry
                        }
                    }
                    is CloudApiResult.HttpError -> {
                        if (statusRes.isUnauthorizedOrRevoked) {
                            handleTokenRevoked(entry)
                            return@withContext
                        }
                        if (statusRes.isRetryable) {
                            scheduleRetry(entry, statusRes.errorCode, statusRes.message, statusRes.retryAfterSeconds)
                            return@withContext
                        }
                    }
                    is CloudApiResult.NetworkError -> {
                        scheduleRetry(entry, "NETWORK_ERROR", statusRes.message, null)
                        return@withContext
                    }
                }
            }

            // Step 1: Compute SHA-256 checksum & request presigned R2 URL from backend
            val nextAttempt = entry.attemptCount + 1
            entry = entry.copy(
                status = CloudUploadStatus.REQUESTING_UPLOAD.name,
                eventId = eventId,
                attemptCount = nextAttempt,
                lastAttemptAtMs = timeProvider(),
                nextRetryAtMs = null,
                lastErrorCode = null,
                lastErrorMessage = null,
                updatedAtMs = timeProvider()
            )
            uploadQueueDao.upsert(entry)

            val checksum = entry.sha256Checksum?.takeIf { it.length == 64 }
                ?: Sha256Helper.computeFileSha256Hex(localFile)
            entry = entry.copy(sha256Checksum = checksum, updatedAtMs = timeProvider())
            uploadQueueDao.upsert(entry)

            val initiateReq = InitiateVideoUploadRequest(
                kioskVideoId = video.videoId,
                fileSize = actualFileSize,
                durationSeconds = clampedDuration,
                mimeType = "video/mp4",
                width = video.resolutionWidth.takeIf { it in 120..3840 },
                height = video.resolutionHeight.takeIf { it in 120..3840 },
                checksum = checksum,
                nickname = video.nickname.take(80),
                caption = video.caption.take(280),
                soundtrackTitle = video.soundtrackTitle.take(160),
                soundtrackArtist = video.soundtrackArtist.take(160),
                effectType = video.effectType.take(48)
            )

            val initiateData = when (
                val initRes = backendGateway.initiateVideoUpload(
                    eventId = eventId,
                    idempotencyKey = "retrotok-$videoId",
                    request = initiateReq
                )
            ) {
                is CloudApiResult.Success -> initRes.data
                is CloudApiResult.HttpError -> {
                    handleHttpError(entry, initRes)
                    return@withContext
                }
                is CloudApiResult.NetworkError -> {
                    scheduleRetry(entry, "NETWORK_ERROR", initRes.message, null)
                    return@withContext
                }
            }

            entry = entry.copy(
                cloudVideoId = initiateData.cloudVideoId,
                eventId = initiateData.eventId,
                storageKey = initiateData.storageKey ?: entry.storageKey,
                updatedAtMs = timeProvider()
            )
            uploadQueueDao.upsert(entry)

            if (initiateData.alreadyAvailable == true || initiateData.status == "AVAILABLE") {
                uploadQueueDao.upsert(
                    entry.copy(
                        status = CloudUploadStatus.UPLOADED.name,
                        uploadedAt = initiateData.uploadedAt ?: entry.uploadedAt,
                        expiresAt = initiateData.expiresAt ?: entry.expiresAt,
                        nextRetryAtMs = null,
                        lastErrorCode = null,
                        lastErrorMessage = null,
                        updatedAtMs = timeProvider()
                    )
                )
                return@withContext
            }

            val uploadUrl = initiateData.uploadUrl?.takeIf { it.isNotBlank() }
            if (uploadUrl == null || !uploadUrl.startsWith("https://")) {
                uploadQueueDao.upsert(
                    entry.copy(
                        status = CloudUploadStatus.FAILED.name,
                        lastErrorCode = "INVALID_UPLOAD_URL",
                        lastErrorMessage = "Server returned an invalid or non-HTTPS R2 upload URL.",
                        updatedAtMs = timeProvider()
                    )
                )
                return@withContext
            }

            // Step 2: Direct signed PUT to Cloudflare R2
            entry = entry.copy(
                status = CloudUploadStatus.UPLOADING.name,
                updatedAtMs = timeProvider()
            )
            uploadQueueDao.upsert(entry)

            when (val r2Result = r2Uploader.uploadFile(
                uploadUrl = uploadUrl,
                uploadHeaders = initiateData.uploadHeaders,
                file = localFile,
                expectedSizeBytes = initiateData.expectedFileSize ?: actualFileSize
            )) {
                is R2UploadResult.Success -> Unit
                is R2UploadResult.Failure -> {
                    if (r2Result.retryable) {
                        scheduleRetry(entry, r2Result.errorCode, r2Result.message, null)
                    } else {
                        uploadQueueDao.upsert(
                            entry.copy(
                                status = CloudUploadStatus.FAILED.name,
                                lastErrorCode = r2Result.errorCode,
                                lastErrorMessage = r2Result.message,
                                updatedAtMs = timeProvider()
                            )
                        )
                    }
                    return@withContext
                }
            }

            // Step 3: Notify backend to HeadObject-verify R2 upload and mark AVAILABLE
            entry = entry.copy(
                status = CloudUploadStatus.VERIFYING.name,
                updatedAtMs = timeProvider()
            )
            uploadQueueDao.upsert(entry)

            when (val completeRes = backendGateway.completeVideoUpload(initiateData.cloudVideoId)) {
                is CloudApiResult.Success -> {
                    val done = completeRes.data
                    uploadQueueDao.upsert(
                        entry.copy(
                            status = CloudUploadStatus.UPLOADED.name,
                            cloudVideoId = done.cloudVideoId,
                            eventId = done.eventId,
                            qrToken = done.qrToken ?: entry.qrToken,
                            viewingUrl = done.viewingUrl ?: entry.viewingUrl,
                            uploadedAt = done.uploadedAt ?: entry.uploadedAt,
                            expiresAt = done.expiresAt ?: entry.expiresAt,
                            nextRetryAtMs = null,
                            lastErrorCode = null,
                            lastErrorMessage = null,
                            updatedAtMs = timeProvider()
                        )
                    )
                }
                is CloudApiResult.HttpError -> handleHttpError(entry, completeRes)
                is CloudApiResult.NetworkError -> {
                    scheduleRetry(entry, "COMPLETE_NETWORK_ERROR", completeRes.message, null)
                }
            }
        } finally {
            synchronized(inFlightLock) {
                inFlightVideoIds.remove(videoId)
            }
        }
    }

    private suspend fun attemptCompleteOnly(entry: UploadQueueEntity, cloudVideoId: Long): Boolean {
        return when (val res = backendGateway.completeVideoUpload(cloudVideoId)) {
            is CloudApiResult.Success -> {
                val done = res.data
                uploadQueueDao.upsert(
                    entry.copy(
                        status = CloudUploadStatus.UPLOADED.name,
                        cloudVideoId = done.cloudVideoId,
                        eventId = done.eventId,
                        qrToken = done.qrToken ?: entry.qrToken,
                        viewingUrl = done.viewingUrl ?: entry.viewingUrl,
                        uploadedAt = done.uploadedAt ?: entry.uploadedAt,
                        expiresAt = done.expiresAt ?: entry.expiresAt,
                        nextRetryAtMs = null,
                        lastErrorCode = null,
                        lastErrorMessage = null,
                        updatedAtMs = timeProvider()
                    )
                )
                true
            }
            is CloudApiResult.HttpError -> {
                if (res.isUnauthorizedOrRevoked) {
                    handleTokenRevoked(entry)
                    true
                } else {
                    false
                }
            }
            is CloudApiResult.NetworkError -> {
                scheduleRetry(entry, "NETWORK_ERROR", res.message, null)
                true
            }
        }
    }

    private suspend fun handleHttpError(entry: UploadQueueEntity, error: CloudApiResult.HttpError) {
        if (error.isUnauthorizedOrRevoked) {
            handleTokenRevoked(entry)
            return
        }

        // `R2_OBJECT_MISSING` can happen if presigned upload aborted mid-stream; retry cleanly
        val isTransientConflict = error.httpCode == 409 && error.errorCode == "R2_OBJECT_MISSING"
        if (error.isRetryable || isTransientConflict) {
            scheduleRetry(entry, error.errorCode, error.message, error.retryAfterSeconds)
        } else {
            uploadQueueDao.upsert(
                entry.copy(
                    status = CloudUploadStatus.FAILED.name,
                    lastErrorCode = error.errorCode,
                    lastErrorMessage = error.message,
                    nextRetryAtMs = null,
                    updatedAtMs = timeProvider()
                )
            )
        }
    }

    private suspend fun handleTokenRevoked(entry: UploadQueueEntity) {
        credentialStore.markTokenRevoked()
        uploadQueueDao.upsert(
            entry.copy(
                status = CloudUploadStatus.FAILED.name,
                lastErrorCode = "INVALID_DEVICE_TOKEN",
                lastErrorMessage = "Device authorization revoked or expired. Re-pair kiosk in Admin Console.",
                nextRetryAtMs = null,
                updatedAtMs = timeProvider()
            )
        )
    }

    private suspend fun scheduleRetry(
        entry: UploadQueueEntity,
        errorCode: String,
        message: String,
        retryAfterSeconds: Long?
    ) {
        val now = timeProvider()
        if (entry.attemptCount >= MAX_AUTO_ATTEMPTS) {
            uploadQueueDao.upsert(
                entry.copy(
                    status = CloudUploadStatus.FAILED.name,
                    lastErrorCode = errorCode,
                    lastErrorMessage = "$message (Max automatic retries reached; tap Retry in Admin Console.)",
                    nextRetryAtMs = null,
                    updatedAtMs = now
                )
            )
            return
        }

        val backoffIndex = min(entry.attemptCount.coerceAtLeast(1) - 1, BACKOFF_DELAYS_MS.lastIndex)
        val computedDelayMs = BACKOFF_DELAYS_MS[backoffIndex]
        val retryAfterMs = retryAfterSeconds?.coerceAtLeast(5L)?.times(1000L)
        val delayMs = maxOf(computedDelayMs, retryAfterMs ?: 0L)

        uploadQueueDao.upsert(
            entry.copy(
                status = CloudUploadStatus.RETRY_PENDING.name,
                lastErrorCode = errorCode,
                lastErrorMessage = message,
                nextRetryAtMs = now + delayMs,
                updatedAtMs = now
            )
        )
    }

    private fun resolveLocalVideoFile(video: VideoEntity): File? {
        val path = video.localVideoPath.takeIf { it.isNotBlank() } ?: return null
        val candidate = if (path.startsWith("/")) File(path) else storageManager.getAbsoluteFile(path)
        return candidate
    }
}

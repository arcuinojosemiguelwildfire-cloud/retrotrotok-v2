package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.cloud.CloudApiEnvelope
import com.example.cloud.CloudApiResult
import com.example.cloud.CloudBackendGateway
import com.example.cloud.CloudCredentialStore
import com.example.cloud.CloudLogSanitizer
import com.example.cloud.CloudUploadManager
import com.example.cloud.CompleteVideoUploadResponseData
import com.example.cloud.ConnectivityObserver
import com.example.cloud.EncryptedTokenPayload
import com.example.cloud.EventInfoResponseData
import com.example.cloud.EventVideosResponseData
import com.example.cloud.HeartbeatRequest
import com.example.cloud.HeartbeatResponseData
import com.example.cloud.InitiateVideoUploadRequest
import com.example.cloud.InitiateVideoUploadResponseData
import com.example.cloud.PairDeviceRequest
import com.example.cloud.PairDeviceResponseData
import com.example.cloud.PairingActionResult
import com.example.cloud.R2DirectUploader
import com.example.cloud.R2UploadResult
import com.example.cloud.RetrofitCloudGateway
import com.example.cloud.Sha256Helper
import com.example.cloud.Tls12OkHttpFactory
import com.example.cloud.TokenCipher
import com.example.cloud.VideoStatusResponseData
import com.example.data.local.RetroTokDatabase
import com.example.data.local.entity.UploadQueueEntity
import com.example.data.local.entity.VideoEntity
import com.example.data.repository.VideoFeedRepositoryImpl
import com.example.model.CloudPairingStatus
import com.example.model.CloudUploadStatus
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import com.example.video.VideoThumbnailGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase11CloudIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: RetroTokDatabase
    private lateinit var storageManager: VideoStorageManager
    private val testDispatcher = UnconfinedTestDispatcher()

    /**
     * Deterministic test cipher mimicking AndroidKeyStore AES-GCM separation
     * without storing plaintext in SharedPreferences (reverses + prefixes bytes in test JVM).
     */
    private class TestAesGcmStandInCipher : TokenCipher {
        override fun encrypt(plaintext: String): EncryptedTokenPayload {
            val bytes = plaintext.toByteArray(Charsets.UTF_8).reversedArray()
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            return EncryptedTokenPayload(ivBase64 = "dGVzdC1pdjEy", cipherTextBase64 = b64)
        }

        override fun decrypt(ivBase64: String, cipherTextBase64: String): String? {
            return try {
                val decoded = android.util.Base64.decode(cipherTextBase64, android.util.Base64.NO_WRAP)
                String(decoded.reversedArray(), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    private class FakeConnectivityObserver(initialOnline: Boolean = true) : ConnectivityObserver {
        private val _isOnline = MutableStateFlow(initialOnline)
        override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
        override fun checkIsOnlineNow(): Boolean = _isOnline.value
        fun setOnline(online: Boolean) {
            _isOnline.value = online
        }
    }

    private class ScriptedGateway : CloudBackendGateway {
        var pairHandler: (PairDeviceRequest) -> CloudApiResult<PairDeviceResponseData> = {
            CloudApiResult.Success(
                PairDeviceResponseData(
                    deviceId = 7L,
                    deviceIdentifier = it.deviceIdentifier,
                    deviceName = it.deviceName,
                    deviceToken = "a".repeat(64),
                    pairedAt = "2026-11-22T20:00:00Z"
                )
            )
        }
        var heartbeatHandler: (HeartbeatRequest) -> CloudApiResult<HeartbeatResponseData> = {
            CloudApiResult.Success(
                HeartbeatResponseData(
                    deviceId = 7L,
                    lastSeenAt = "2026-11-22T20:01:00Z",
                    status = "REGISTERED"
                )
            )
        }
        var eventHandler: (Long) -> CloudApiResult<EventInfoResponseData> = { eventId ->
            CloudApiResult.Success(
                EventInfoResponseData(
                    id = eventId,
                    externalEventKey = "GALA-2026",
                    eventName = "Acme 40th Gala",
                    status = "ACTIVE",
                    maxVideos = 200,
                    activeVideoCount = 1,
                    availableVideoCount = 1,
                    remainingCapacity = 199
                )
            )
        }
        var initiateHandler: suspend (Long, String?, InitiateVideoUploadRequest) -> CloudApiResult<InitiateVideoUploadResponseData> = { eventId, _, req ->
            CloudApiResult.Success(
                InitiateVideoUploadResponseData(
                    cloudVideoId = 501L,
                    eventId = eventId,
                    kioskVideoId = req.kioskVideoId,
                    status = "PENDING_UPLOAD",
                    idempotentReplay = false,
                    alreadyAvailable = false,
                    uploadAttempt = 1,
                    storageKey = "events/$eventId/${req.kioskVideoId}.mp4",
                    uploadUrl = "https://r2.example.cloudflarestorage.com/retrotok/events/$eventId/${req.kioskVideoId}.mp4?X-Amz-Signature=sig123",
                    uploadHeaders = mapOf(
                        "Content-Type" to "video/mp4",
                        "Content-Length" to req.fileSize.toString()
                    ),
                    expectedFileSize = req.fileSize
                )
            )
        }
        var completeHandler: suspend (Long) -> CloudApiResult<CompleteVideoUploadResponseData> = { cloudVideoId ->
            CloudApiResult.Success(
                CompleteVideoUploadResponseData(
                    cloudVideoId = cloudVideoId,
                    eventId = 1L,
                    kioskVideoId = "vid-test-1",
                    status = "AVAILABLE",
                    alreadyCompleted = false,
                    qrToken = "qr_abc123",
                    viewingUrl = "https://wildfirexstream.com/retrotok/v/qr_abc123",
                    uploadedAt = "2026-11-22T20:05:00Z",
                    expiresAt = "2026-12-22T20:05:00Z"
                )
            )
        }
        var statusHandler: suspend (Long) -> CloudApiResult<VideoStatusResponseData> = { cloudVideoId ->
            CloudApiResult.Success(
                VideoStatusResponseData(
                    cloudVideoId = cloudVideoId,
                    eventId = 1L,
                    kioskVideoId = "vid-test-1",
                    status = "PENDING_UPLOAD"
                )
            )
        }

        val initiateCallCount = AtomicInteger(0)
        val completeCallCount = AtomicInteger(0)
        val statusCallCount = AtomicInteger(0)

        override suspend fun pairDevice(request: PairDeviceRequest) = pairHandler(request)
        override suspend fun sendHeartbeat(request: HeartbeatRequest) = heartbeatHandler(request)
        override suspend fun getEventInfo(eventId: Long) = eventHandler(eventId)
        override suspend fun initiateVideoUpload(
            eventId: Long,
            idempotencyKey: String?,
            request: InitiateVideoUploadRequest
        ): CloudApiResult<InitiateVideoUploadResponseData> {
            initiateCallCount.incrementAndGet()
            return initiateHandler(eventId, idempotencyKey, request)
        }
        override suspend fun completeVideoUpload(cloudVideoId: Long): CloudApiResult<CompleteVideoUploadResponseData> {
            completeCallCount.incrementAndGet()
            return completeHandler(cloudVideoId)
        }
        override suspend fun getVideoStatus(cloudVideoId: Long): CloudApiResult<VideoStatusResponseData> {
            statusCallCount.incrementAndGet()
            return statusHandler(cloudVideoId)
        }
        override suspend fun listEventVideos(eventId: Long, cursor: Long?, limit: Int) =
            CloudApiResult.Success(EventVideosResponseData(eventId = eventId))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("retrotok_cloud_credentials", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, RetroTokDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storageManager = VideoStorageManager(context)
        storageManager.publishedDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        storageManager.publishedDir.listFiles()?.forEach { it.delete() }
        context.getSharedPreferences("retrotok_cloud_credentials", Context.MODE_PRIVATE).edit().clear().commit()
        db.close()
        Dispatchers.resetMain()
    }

    private fun createMp4File(name: String, sizeBytes: Int = 2048): File {
        val file = File(storageManager.publishedDir, name)
        file.parentFile?.mkdirs()
        val data = ByteArray(sizeBytes) { (it % 251).toByte() }
        data[4] = 'f'.code.toByte()
        data[5] = 't'.code.toByte()
        data[6] = 'y'.code.toByte()
        data[7] = 'p'.code.toByte()
        file.writeBytes(data)
        return file
    }

    private suspend fun insertSampleVideo(
        videoId: String,
        file: File,
        durationSeconds: Int = 15
    ): VideoEntity {
        val entity = VideoEntity(
            videoId = videoId,
            nickname = "RetroStar",
            caption = "Grooving #RetroTok",
            hashtags = "#RetroTok",
            localVideoPath = "retrotok/videos/published/${file.name}",
            thumbnailPath = null,
            soundtrackTitle = "Neon Nights",
            soundtrackArtist = "Synth Crew",
            soundtrackId = "s1",
            effectType = "VHS_RETRO",
            duration = durationSeconds,
            resolutionWidth = 720,
            resolutionHeight = 1280,
            fileSize = file.length(),
            createdAt = 1000L,
            updatedAt = 1000L,
            publicationStatus = "PUBLISHED"
        )
        db.videoDao().insertVideo(entity)
        return entity
    }

    @Test
    fun test01_apiMoshiEnvelopeAndEndpointParsing() = runTest {
        var capturedPairAuthHeader: String? = "UNSET"
        var capturedHeartbeatAuthHeader: String? = null
        val authOkHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val orig = chain.request()
                val req = if (!orig.url.encodedPath.endsWith("/devices/pair")) {
                    orig.newBuilder().header("Authorization", "Bearer ${"b".repeat(64)}").build()
                } else {
                    orig
                }
                chain.proceed(req)
            }
            .addInterceptor { chain ->
                val req = chain.request()
                val header = req.header("Authorization")
                val json = when {
                    req.url.encodedPath.endsWith("/devices/pair") -> {
                        capturedPairAuthHeader = header
                        """{"ok":true,"data":{"device_id":12,"device_identifier":"kiosk-1","device_name":"Booth A","device_token":"${"b".repeat(64)}","paired_at":"2026-11-22 20:00:00"}}"""
                    }
                    req.url.encodedPath.endsWith("/devices/heartbeat") -> {
                        capturedHeartbeatAuthHeader = header
                        """{"ok":true,"data":{"device_id":12,"last_seen_at":"2026-11-22 20:01:00","status":"REGISTERED"}}"""
                    }
                    req.url.encodedPath.endsWith("/events/1") -> {
                        """{"ok":true,"data":{"id":1,"external_event_key":"EV-1","event_name":"Retro Gala","status":"ACTIVE","max_videos":100,"remaining_capacity":99}}"""
                    }
                    req.url.encodedPath.endsWith("/events/1/videos") -> {
                        """{"ok":true,"data":{"cloud_video_id":88,"event_id":1,"kiosk_video_id":"vid-1","status":"PENDING_UPLOAD","upload_url":"https://r2.example.com/obj.mp4?X-Amz-Signature=xyz","upload_headers":{"Content-Type":"video/mp4","Content-Length":"2048"},"expected_file_size":2048}}"""
                    }
                    req.url.encodedPath.endsWith("/videos/88/complete") -> {
                        """{"ok":true,"data":{"cloud_video_id":88,"event_id":1,"kiosk_video_id":"vid-1","status":"AVAILABLE","already_completed":false,"qr_token":"qr77","viewing_url":"https://wildfirexstream.com/retrotok/v/qr77"}}"""
                    }
                    else -> {
                        """{"ok":false,"error":{"code":"NOT_FOUND","message":"Missing"}}"""
                    }
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (req.url.encodedPath.endsWith("/status")) 404 else 200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val gateway = RetrofitCloudGateway(
            baseUrl = "https://wildfirexstream.com/retrotok/api/v1/",
            okHttpClient = authOkHttp
        )

        val pairRes = gateway.pairDevice(PairDeviceRequest("CODE1", "kiosk-1", "Booth A", "1.0", 23))
        assertTrue(pairRes is CloudApiResult.Success)
        assertNull(capturedPairAuthHeader)

        val hbRes = gateway.sendHeartbeat(HeartbeatRequest("1.0"))
        assertTrue(hbRes is CloudApiResult.Success)
        assertEquals("Bearer ${"b".repeat(64)}", capturedHeartbeatAuthHeader)

        val evRes = gateway.getEventInfo(1L)
        assertTrue(evRes is CloudApiResult.Success)
        assertEquals("Retro Gala", (evRes as CloudApiResult.Success).data.eventName)

        val initRes = gateway.initiateVideoUpload(1L, "idem-1", InitiateVideoUploadRequest("vid-1", 2048L, 15))
        assertTrue(initRes is CloudApiResult.Success)
        assertEquals("video/mp4", (initRes as CloudApiResult.Success).data.uploadHeaders?.get("Content-Type"))

        val completeRes = gateway.completeVideoUpload(88L)
        assertTrue(completeRes is CloudApiResult.Success)
        assertEquals("https://wildfirexstream.com/retrotok/v/qr77", (completeRes as CloudApiResult.Success).data.viewingUrl)

        val errRes = gateway.getVideoStatus(999L)
        assertTrue(errRes is CloudApiResult.HttpError)
        assertEquals("NOT_FOUND", (errRes as CloudApiResult.HttpError).errorCode)
    }

    @Test
    fun test02_devicePairingSuccessStoresEncryptedTokenNeverPlaintext() = runTest {
        val store = CloudCredentialStore(context, TestAesGcmStandInCipher())
        val gateway = ScriptedGateway()
        val r2 = R2DirectUploader(callFactory = Call.Factory { throw AssertionError("No R2 expected during pairing") })
        val connectivity = FakeConnectivityObserver(true)
        val manager = CloudUploadManager(
            credentialStore = store,
            backendGateway = gateway,
            r2Uploader = r2,
            videoDao = db.videoDao(),
            uploadQueueDao = db.uploadQueueDao(),
            storageManager = storageManager,
            connectivityObserver = connectivity,
            ioDispatcher = testDispatcher,
            autoStartObservers = false
        )

        val secretToken = "9f8e7d6c".repeat(8)
        gateway.pairHandler = { req ->
            CloudApiResult.Success(
                PairDeviceResponseData(
                    deviceId = 42L,
                    deviceIdentifier = req.deviceIdentifier,
                    deviceName = req.deviceName,
                    deviceToken = secretToken,
                    pairedAt = "2026-11-22T20:10:00Z"
                )
            )
        }

        val outcome = manager.pairAndVerifyEvent("PAIR-2026", 1L, "Ballroom Kiosk")
        assertTrue(outcome is PairingActionResult.Success)
        assertEquals(CloudPairingStatus.PAIRED, store.pairingState.value.pairingStatus)
        assertEquals("Acme 40th Gala", store.pairingState.value.pairedEventName)
        assertEquals(secretToken, store.getDeviceToken())

        // Verify SharedPreferences file does NOT contain plaintext device_token!
        val prefsMap = context.getSharedPreferences("retrotok_cloud_credentials", Context.MODE_PRIVATE).all
        val allValuesString = prefsMap.values.joinToString("|")
        assertFalse("Plaintext device token leaked into SharedPreferences!", allValuesString.contains(secretToken))
    }

    @Test
    fun test03_devicePairingFailureAndHttpsEnforcement() = runTest {
        var threwForHttp = false
        try {
            Tls12OkHttpFactory.requireHttpsBaseUrl("http://wildfirexstream.com/retrotok/api/v1/")
        } catch (_: IllegalArgumentException) {
            threwForHttp = true
        }
        assertTrue("HTTP base URL must be rejected", threwForHttp)

        val store = CloudCredentialStore(context, TestAesGcmStandInCipher())
        val gateway = ScriptedGateway().apply {
            pairHandler = {
                CloudApiResult.HttpError(401, "INVALID_PAIRING_CODE", "Pairing code is invalid or expired.")
            }
        }
        val manager = CloudUploadManager(
            credentialStore = store,
            backendGateway = gateway,
            r2Uploader = R2DirectUploader(callFactory = Call.Factory { throw AssertionError() }),
            videoDao = db.videoDao(),
            uploadQueueDao = db.uploadQueueDao(),
            storageManager = storageManager,
            connectivityObserver = FakeConnectivityObserver(true),
            ioDispatcher = testDispatcher,
            autoStartObservers = false
        )

        val result = manager.pairAndVerifyEvent("WRONGCODE", 1L, "Kiosk")
        assertTrue(result is PairingActionResult.Error)
        assertEquals(CloudPairingStatus.PAIRING_FAILED, store.pairingState.value.pairingStatus)
        assertNull(store.getDeviceToken())
    }

    @Test
    fun test04_revokedToken401ClearsTokenAndPreservesLocalVideo() = runTest {
        val store = CloudCredentialStore(context, TestAesGcmStandInCipher())
        store.savePairedCredentials("c".repeat(64), 7L, "kiosk-abc", "Booth", "2026-11-22", 1L)
        val file = createMp4File("pub_vid-revoked.mp4", 4096)
        insertSampleVideo("vid-revoked", file)

        val gateway = ScriptedGateway().apply {
            initiateHandler = { _, _, _ ->
                CloudApiResult.HttpError(401, "INVALID_DEVICE_TOKEN", "Device token revoked")
            }
        }
        val manager = CloudUploadManager(
            credentialStore = store,
            backendGateway = gateway,
            r2Uploader = R2DirectUploader(callFactory = Call.Factory { throw AssertionError() }),
            videoDao = db.videoDao(),
            uploadQueueDao = db.uploadQueueDao(),
            storageManager = storageManager,
            connectivityObserver = FakeConnectivityObserver(true),
            ioDispatcher = testDispatcher,
            minVideoBytesForCloud = 1024L,
            autoStartObservers = false
        )

        manager.enqueueAndMaybeUpload("vid-revoked")

        assertEquals(CloudPairingStatus.REVOKED, store.pairingState.value.pairingStatus)
        assertNull(store.getDeviceToken())
        val queueRow = db.uploadQueueDao().getByVideoId("vid-revoked")
        assertEquals(CloudUploadStatus.FAILED.name, queueRow?.status)
        assertTrue("Local MP4 must remain intact on auth failure", file.exists())
        assertNotNull(db.videoDao().getVideoById("vid-revoked"))
    }

    @Test
    fun test05_heartbeatSuccessAndFailure() = runTest {
        val store = CloudCredentialStore(context, TestAesGcmStandInCipher())
        store.savePairedCredentials("d".repeat(64), 7L, "kiosk-abc", "Booth", "2026-11-22", 1L)
        val gateway = ScriptedGateway()
        val manager = CloudUploadManager(
            credentialStore = store,
            backendGateway = gateway,
            r2Uploader = R2DirectUploader(callFactory = Call.Factory { throw AssertionError() }),
            videoDao = db.videoDao(),
            uploadQueueDao = db.uploadQueueDao(),
            storageManager = storageManager,
            connectivityObserver = FakeConnectivityObserver(true),
            ioDispatcher = testDispatcher,
            autoStartObservers = false
        )

        val ok = manager.sendHeartbeatAndRefreshEvent()
        assertTrue(ok is PairingActionResult.Success)
        assertEquals("2026-11-22T20:01:00Z", store.pairingState.value.lastHeartbeatAt)

        gateway.heartbeatHandler = { CloudApiResult.HttpError(401, "INVALID_DEVICE_TOKEN", "Revoked") }
        val revoked = manager.sendHeartbeatAndRefreshEvent()
        assertTrue(revoked is PairingActionResult.Error)
        assertEquals(CloudPairingStatus.REVOKED, store.pairingState.value.pairingStatus)
    }

    @Test
    fun test06_directR2PutExactHeadersFixedLengthNoCharsetNoBearerAuth() = runTest {
        val mp4 = createMp4File("pub_r2_exact.mp4", 8192)
        var capturedUrl: String? = null
        var capturedContentTypeHeader: String? = null
        var capturedBodyMediaType: Any? = "NOT_NULL"
        var capturedContentLength: Long = -1L
        var capturedAuthorizationHeader: String? = "UNSET"
        val bodyBuffer = Buffer()

        val mockR2HttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                capturedUrl = req.url.toString()
                capturedContentTypeHeader = req.header("Content-Type")
                capturedAuthorizationHeader = req.header("Authorization")
                capturedContentLength = req.body?.contentLength() ?: -99L
                capturedBodyMediaType = req.body?.contentType()
                req.body?.writeTo(bodyBuffer)

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()

        val uploader = R2DirectUploader(callFactory = mockR2HttpClient, requireHttps = true)
        val signedUrl = "https://bucket.r2.cloudflarestorage.com/retrotok/clip.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=deadbeef"
        val res = uploader.uploadFile(
            uploadUrl = signedUrl,
            uploadHeaders = mapOf(
                "Content-Type" to "video/mp4",
                "Content-Length" to mp4.length().toString()
            ),
            file = mp4
        )

        assertTrue(res is R2UploadResult.Success)
        assertEquals(signedUrl, capturedUrl)
        assertEquals("video/mp4", capturedContentTypeHeader)
        assertNull("Body MediaType must be null so OkHttp never adds charset=utf-8", capturedBodyMediaType)
        assertEquals(8192L, capturedContentLength)
        assertEquals(8192L, bodyBuffer.size)
        assertNull("Bearer Authorization must never be sent to R2", capturedAuthorizationHeader)

        // Ensure plaintext HTTP upload URL is blocked
        val httpRejected = uploader.uploadFile("http://insecure.example.com/clip.mp4", emptyMap(), mp4)
        assertTrue(httpRejected is R2UploadResult.Failure)
        assertEquals("R2_INVALID_URL", (httpRejected as R2UploadResult.Failure).errorCode)
    }

    @Test
    fun test07_sha256CalculationAccuracy() {
        val file = createMp4File("sha_check.mp4", 32768)
        val actual = Sha256Helper.computeFileSha256Hex(file)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(64, actual.length)
        assertEquals(expected, actual)
    }

    @Test
    fun test08_fullSuccessfulUploadFlowUpdatesRoomAndRepositoryFlow() = runTest {
        val store = CloudCredentialStore(context, TestAesGcmStandInCipher())
        store.savePairedCredentials("e".repeat(64), 7L, "kiosk-1", "Main Kiosk", "2026-11-22", 1L)
        val file = createMp4File("pub_full_ok.mp4", 4096)
        insertSampleVideo("vid-full-ok", file, durationSeconds = 18)

        val gateway = ScriptedGateway()
        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()

        val manager = CloudUploadManager(
            credentialStore = store,
            backendGateway = gateway,
            r2Uploader = R2DirectUploader(callFactory = okClient),
            videoDao = db.videoDao(),
            uploadQueueDao = db.uploadQueueDao(),
            storageManager = storageManager,
            connectivityObserver = FakeConnectivityObserver(true),
            ioDispatcher = testDispatcher,
            minVideoBytesForCloud = 1024L,
            autoStartObservers = false
        )

        manager.enqueueAndMaybeUpload("vid-full-ok")

        val row = db.uploadQueueDao().getByVideoId("vid-full-ok")
        assertNotNull(row)
        assertEquals(CloudUploadStatus.UPLOADED.name, row?.status)
        assertEquals(501L, row?.cloudVideoId)
        assertEquals("https://wildfirexstream.com/retrotok/v/qr_abc123", row?.viewingUrl)
        assertEquals(Sha256Helper.computeFileSha256Hex(file), row?.sha256Checksum)

        val repo = VideoFeedRepositoryImpl(
            videoDao = db.videoDao(),
            storageManager = storageManager,
            thumbnailGenerator = VideoThumbnailGenerator(context),
            videoInspector = VideoMuxerProcessor(context),
            uploadQueueDao = db.uploadQueueDao()
        )
        val feedVideos = repo.getFeedVideos().first()
        assertEquals(1, feedVideos.size)
        assertEquals(CloudUploadStatus.UPLOADED, feedVideos.first().cloudUploadSummary?.status)
        assertTrue("Local MP4 file must stay preserved after upload", file.exists())
    }

    @Test
    fun test09_offlineQueueAndReconnectAutoRetryWithBackoff() = runTest {
        val store = CloudCredentialStore(context, TestAesGcmStandInCipher())
        store.savePairedCredentials("f".repeat(64), 7L, "kiosk-1", "Kiosk", "2026-11-22", 1L)
        val file = createMp4File("pub_offline.mp4", 4096)
        insertSampleVideo("vid-offline", file)

        val connectivity = FakeConnectivityObserver(initialOnline = false)
        val gateway = ScriptedGateway()
        var currentTimeMs = 1_000_000L

        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()

        val manager = CloudUploadManager(
            credentialStore = store,
            backendGateway = gateway,
            r2Uploader = R2DirectUploader(callFactory = okClient),
            videoDao = db.videoDao(),
            uploadQueueDao = db.uploadQueueDao(),
            storageManager = storageManager,
            connectivityObserver = connectivity,
            ioDispatcher = testDispatcher,
            minVideoBytesForCloud = 1024L,
            timeProvider = { currentTimeMs },
            autoStartObservers = false
        )

        // Enqueue while offline -> stays QUEUED, 0 network calls
        manager.enqueueAndMaybeUpload("vid-offline")
        assertEquals(0, gateway.initiateCallCount.get())
        assertEquals(CloudUploadStatus.QUEUED.name, db.uploadQueueDao().getByVideoId("vid-offline")?.status)

        // Go online, simulate transient 503 error -> RETRY_PENDING with backoff delay
        connectivity.setOnline(true)
        gateway.initiateHandler = { _, _, _ ->
            CloudApiResult.HttpError(503, "SERVICE_UNAVAILABLE", "Temporary outage", retryAfterSeconds = 15)
        }
        manager.processPendingQueue(forceRetryDelayReset = false)
        val retryRow = db.uploadQueueDao().getByVideoId("vid-offline")!!
        assertEquals(CloudUploadStatus.RETRY_PENDING.name, retryRow.status)
        assertTrue((retryRow.nextRetryAtMs ?: 0L) > currentTimeMs)

        // Too early -> backoff skips retry
        val prevCount = gateway.initiateCallCount.get()
        manager.processPendingQueue(forceRetryDelayReset = false)
        assertEquals(prevCount, gateway.initiateCallCount.get())

        // Advance time past backoff and succeed
        currentTimeMs += 60_000L
        gateway.initiateHandler = { eventId, _, req ->
            CloudApiResult.Success(
                InitiateVideoUploadResponseData(
                    cloudVideoId = 502L,
                    eventId = eventId,
                    kioskVideoId = req.kioskVideoId,
                    status = "PENDING_UPLOAD",
                    uploadUrl = "https://r2.example.com/ok.mp4?sig=1",
                    uploadHeaders = mapOf("Content-Type" to "video/mp4"),
                    expectedFileSize = req.fileSize
                )
            )
        }
        manager.processPendingQueue(forceRetryDelayReset = false)
        assertEquals(CloudUploadStatus.UPLOADED.name, db.uploadQueueDao().getByVideoId("vid-offline")?.status)
    }

    @Test
    fun test10_duplicateAndConcurrentUploadPrevention() = runTest {
        val store = CloudCredentialStore(context, TestAesGcmStandInCipher())
        store.savePairedCredentials("g".repeat(64), 7L, "kiosk-1", "Kiosk", "2026-11-22", 1L)
        val file = createMp4File("pub_dup.mp4", 4096)
        insertSampleVideo("vid-dup", file)

        val gateway = ScriptedGateway().apply {
            initiateHandler = { eventId, _, req ->
                delay(50)
                CloudApiResult.Success(
                    InitiateVideoUploadResponseData(
                        cloudVideoId = 700L,
                        eventId = eventId,
                        kioskVideoId = req.kioskVideoId,
                        status = "PENDING_UPLOAD",
                        uploadUrl = "https://r2.example.com/dup.mp4?sig=1",
                        uploadHeaders = mapOf("Content-Type" to "video/mp4"),
                        expectedFileSize = req.fileSize
                    )
                )
            }
        }
        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()

        val manager = CloudUploadManager(
            credentialStore = store,
            backendGateway = gateway,
            r2Uploader = R2DirectUploader(callFactory = okClient),
            videoDao = db.videoDao(),
            uploadQueueDao = db.uploadQueueDao(),
            storageManager = storageManager,
            connectivityObserver = FakeConnectivityObserver(true),
            ioDispatcher = Dispatchers.Default,
            minVideoBytesForCloud = 1024L,
            autoStartObservers = false
        )

        // Fire two concurrent upload invocations for the exact same videoId
        listOf(
            async { manager.processSingleVideo("vid-dup", ignoreBackoff = true) },
            async { manager.processSingleVideo("vid-dup", ignoreBackoff = true) }
        ).awaitAll()

        assertEquals("Only ONE initiate call should run for concurrent triggers", 1, gateway.initiateCallCount.get())
        assertEquals(CloudUploadStatus.UPLOADED.name, db.uploadQueueDao().getByVideoId("vid-dup")?.status)

        // Subsequent trigger sees already UPLOADED and makes 0 extra calls
        manager.enqueueAndMaybeUpload("vid-dup")
        assertEquals(1, gateway.initiateCallCount.get())
    }

    @Test
    fun test11_interruptedUploadRecoveryViaStatusEndpoint() = runTest {
        val store = CloudCredentialStore(context, TestAesGcmStandInCipher())
        store.savePairedCredentials("h".repeat(64), 7L, "kiosk-1", "Kiosk", "2026-11-22", 1L)
        val file = createMp4File("pub_recover.mp4", 4096)
        insertSampleVideo("vid-recover", file)

        // Simulate app restart after an interrupted VERIFYING state with cloudVideoId already assigned
        db.uploadQueueDao().upsert(
            UploadQueueEntity(
                videoId = "vid-recover",
                status = CloudUploadStatus.VERIFYING.name,
                eventId = 1L,
                cloudVideoId = 888L
            )
        )

        val gateway = ScriptedGateway().apply {
            statusHandler = { cloudId ->
                CloudApiResult.Success(
                    VideoStatusResponseData(
                        cloudVideoId = cloudId,
                        eventId = 1L,
                        kioskVideoId = "vid-recover",
                        status = "AVAILABLE",
                        uploadedAt = "2026-11-22T20:20:00Z",
                        expiresAt = "2026-12-22T20:20:00Z"
                    )
                )
            }
        }

        val manager = CloudUploadManager(
            credentialStore = store,
            backendGateway = gateway,
            r2Uploader = R2DirectUploader(callFactory = Call.Factory { throw AssertionError("Must NOT re-upload R2 bytes when server already says AVAILABLE!") }),
            videoDao = db.videoDao(),
            uploadQueueDao = db.uploadQueueDao(),
            storageManager = storageManager,
            connectivityObserver = FakeConnectivityObserver(true),
            ioDispatcher = testDispatcher,
            minVideoBytesForCloud = 1024L,
            autoStartObservers = false
        )

        manager.processPendingQueue(forceRetryDelayReset = true)

        assertEquals(1, gateway.statusCallCount.get())
        assertEquals(0, gateway.initiateCallCount.get())
        val updated = db.uploadQueueDao().getByVideoId("vid-recover")
        assertEquals(CloudUploadStatus.UPLOADED.name, updated?.status)
    }

    @Test
    fun test12_roomMigration3To4PreservesSongsAndVideosAndCreatesUploadQueue() = runTest {
        val dbName = "migration_3_to_4_test.db"
        context.deleteDatabase(dbName)

        // Build exact v3 SQLite schema with existing song + video rows
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `songs` (
                                `id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL,
                                `album` TEXT NOT NULL, `releaseYear` INTEGER, `decade` TEXT NOT NULL,
                                `genre` TEXT NOT NULL, `durationMs` INTEGER NOT NULL,
                                `audioFileName` TEXT NOT NULL, `audioRelativePath` TEXT NOT NULL,
                                `artworkRelativePath` TEXT, `fileSizeBytes` INTEGER NOT NULL,
                                `sha256Hash` TEXT NOT NULL, `importedAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL,
                                `isAvailable` INTEGER NOT NULL, `licenseApprovalStatus` TEXT NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_songs_sha256Hash` ON `songs` (`sha256Hash`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_decade` ON `songs` (`decade`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_genre` ON `songs` (`genre`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_licenseApprovalStatus` ON `songs` (`licenseApprovalStatus`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_title_artist` ON `songs` (`title`, `artist`)")

                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `videos` (
                                `videoId` TEXT NOT NULL, `nickname` TEXT NOT NULL, `caption` TEXT NOT NULL,
                                `hashtags` TEXT NOT NULL, `localVideoPath` TEXT NOT NULL,
                                `thumbnailPath` TEXT, `soundtrackTitle` TEXT NOT NULL,
                                `soundtrackArtist` TEXT NOT NULL, `soundtrackId` TEXT,
                                `effectType` TEXT NOT NULL, `duration` INTEGER NOT NULL,
                                `resolutionWidth` INTEGER NOT NULL, `resolutionHeight` INTEGER NOT NULL,
                                `fileSize` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL DEFAULT 0,
                                `likeCount` INTEGER NOT NULL DEFAULT 0, `isLiked` INTEGER NOT NULL DEFAULT 0,
                                `isFeatured` INTEGER NOT NULL DEFAULT 0,
                                `publicationStatus` TEXT NOT NULL DEFAULT 'PUBLISHED',
                                `moderationStatus` TEXT NOT NULL DEFAULT 'VISIBLE',
                                `moderatedAt` INTEGER, `moderationReason` TEXT,
                                PRIMARY KEY(`videoId`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_createdAt` ON `videos` (`createdAt`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_isFavorite` ON `videos` (`isFavorite`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_isFeatured` ON `videos` (`isFeatured`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_likeCount` ON `videos` (`likeCount`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_publicationStatus` ON `videos` (`publicationStatus`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_soundtrackId` ON `videos` (`soundtrackId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_moderationStatus` ON `videos` (`moderationStatus`)")

                        db.execSQL(
                            """
                            INSERT INTO `songs` VALUES (
                                'song-v3', 'Blue Monday', 'New Order', 'Power',
                                1983, 'DECADE_80S', 'Synthpop', 200000, 'bm.mp3',
                                'retrotok/music/audio/bm.mp3', NULL, 8192, 'hashbm',
                                1000, 1000, 0, 1, 'APPROVED_FOR_EVENT_USE'
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO `videos` VALUES (
                                'vid-v3', 'LegacyDancer', 'Old clip', '#Retro',
                                'retrotok/videos/published/pub_v3.mp4', NULL,
                                'Blue Monday', 'New Order', 'song-v3', 'VHS_RETRO',
                                30, 720, 1280, 2048000, 1000, 1000, 1, 5, 1, 1,
                                'PUBLISHED', 'VISIBLE', NULL, NULL
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()

        val migratedDb = Room.databaseBuilder(context, RetroTokDatabase::class.java, dbName)
            .addMigrations(*RetroTokDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val songs = migratedDb.songDao().getAllSongs().first()
        val videos = migratedDb.videoDao().getAllVideosOnce()
        assertEquals(1, songs.size)
        assertEquals("Blue Monday", songs.first().title)
        assertEquals(1, videos.size)
        assertEquals("LegacyDancer", videos.first().nickname)

        migratedDb.uploadQueueDao().upsert(
            UploadQueueEntity(
                videoId = "vid-v3",
                status = CloudUploadStatus.LOCAL_ONLY.name
            )
        )
        assertEquals(1, migratedDb.uploadQueueDao().getAllOnce().size)
        migratedDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun test13_oversizedOrSmallFileFailsValidationWithoutDeletingLocalMp4() = runTest {
        val store = CloudCredentialStore(context, TestAesGcmStandInCipher())
        store.savePairedCredentials("i".repeat(64), 7L, "kiosk-1", "Kiosk", "2026-11-22", 1L)
        // Production minimum is 1 MiB (1_048_576 bytes); a 2048-byte file is below min for cloud
        val smallFile = createMp4File("pub_too_small.mp4", 2048)
        insertSampleVideo("vid-small", smallFile)

        val gateway = ScriptedGateway()
        val manager = CloudUploadManager(
            credentialStore = store,
            backendGateway = gateway,
            r2Uploader = R2DirectUploader(callFactory = Call.Factory { throw AssertionError() }),
            videoDao = db.videoDao(),
            uploadQueueDao = db.uploadQueueDao(),
            storageManager = storageManager,
            connectivityObserver = FakeConnectivityObserver(true),
            ioDispatcher = testDispatcher,
            autoStartObservers = false
        )

        manager.enqueueAndMaybeUpload("vid-small")
        val row = db.uploadQueueDao().getByVideoId("vid-small")
        assertEquals(CloudUploadStatus.FAILED.name, row?.status)
        assertEquals("INVALID_FILE_SIZE", row?.lastErrorCode)
        assertTrue("Local MP4 must NEVER be deleted on cloud validation failure", smallFile.exists())
        assertEquals(0, gateway.initiateCallCount.get())
    }

    @Test
    fun test14_completeVerificationFailureAndSanitizerRedaction() = runTest {
        val sanitized = CloudLogSanitizer.sanitize(
            "Auth failed for Bearer abcdef123456 on https://r2.cloudflarestorage.com/obj.mp4?X-Amz-Signature=secret123"
        )
        assertFalse(sanitized.contains("abcdef123456"))
        assertFalse(sanitized.contains("secret123"))
        assertTrue(sanitized.contains("Bearer [REDACTED]"))
        assertTrue(sanitized.contains("?[REDACTED]"))

        val store = CloudCredentialStore(context, TestAesGcmStandInCipher())
        store.savePairedCredentials("j".repeat(64), 7L, "kiosk-1", "Kiosk", "2026-11-22", 1L)
        val file = createMp4File("pub_complete_fail.mp4", 4096)
        insertSampleVideo("vid-comp-fail", file)

        val gateway = ScriptedGateway().apply {
            completeHandler = {
                CloudApiResult.HttpError(409, "R2_OBJECT_MISSING", "Uploaded file not found in R2.")
            }
        }
        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()

        val manager = CloudUploadManager(
            credentialStore = store,
            backendGateway = gateway,
            r2Uploader = R2DirectUploader(callFactory = okClient),
            videoDao = db.videoDao(),
            uploadQueueDao = db.uploadQueueDao(),
            storageManager = storageManager,
            connectivityObserver = FakeConnectivityObserver(true),
            ioDispatcher = testDispatcher,
            minVideoBytesForCloud = 1024L,
            autoStartObservers = false
        )

        manager.enqueueAndMaybeUpload("vid-comp-fail")
        val row = db.uploadQueueDao().getByVideoId("vid-comp-fail")
        assertEquals(CloudUploadStatus.RETRY_PENDING.name, row?.status)
        assertEquals("R2_OBJECT_MISSING", row?.lastErrorCode)
        assertTrue("Local MP4 preserved even when /complete reports R2_OBJECT_MISSING", file.exists())
    }
}

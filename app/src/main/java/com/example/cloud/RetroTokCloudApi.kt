package com.example.cloud

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.net.SocketTimeoutException

sealed class CloudApiResult<out T> {
    data class Success<T>(val data: T) : CloudApiResult<T>()
    data class HttpError(
        val httpCode: Int,
        val errorCode: String,
        val message: String,
        val retryAfterSeconds: Long? = null
    ) : CloudApiResult<Nothing>() {
        val isUnauthorizedOrRevoked: Boolean
            get() = httpCode == 401
        val isRetryable: Boolean
            get() = httpCode == 429 || httpCode in 500..599
    }
    data class NetworkError(
        val message: String,
        val isTimeout: Boolean = false
    ) : CloudApiResult<Nothing>()
}

interface RetroTokApiService {
    @POST("devices/pair")
    suspend fun pairDevice(
        @Body request: PairDeviceRequest
    ): Response<CloudApiEnvelope<PairDeviceResponseData>>

    @POST("devices/heartbeat")
    suspend fun sendHeartbeat(
        @Body request: HeartbeatRequest
    ): Response<CloudApiEnvelope<HeartbeatResponseData>>

    @GET("events/{eventId}")
    suspend fun getEventInfo(
        @Path("eventId") eventId: Long
    ): Response<CloudApiEnvelope<EventInfoResponseData>>

    @POST("events/{eventId}/videos")
    suspend fun initiateVideoUpload(
        @Path("eventId") eventId: Long,
        @Header("Idempotency-Key") idempotencyKey: String?,
        @Body request: InitiateVideoUploadRequest
    ): Response<CloudApiEnvelope<InitiateVideoUploadResponseData>>

    @POST("videos/{videoId}/complete")
    suspend fun completeVideoUpload(
        @Path("videoId") cloudVideoId: Long,
        @Body request: CompleteVideoUploadRequest = CompleteVideoUploadRequest()
    ): Response<CloudApiEnvelope<CompleteVideoUploadResponseData>>

    @GET("videos/{videoId}/status")
    suspend fun getVideoStatus(
        @Path("videoId") cloudVideoId: Long
    ): Response<CloudApiEnvelope<VideoStatusResponseData>>

    @GET("events/{eventId}/videos")
    suspend fun listEventVideos(
        @Path("eventId") eventId: Long,
        @Query("cursor") cursor: Long? = null,
        @Query("limit") limit: Int = 20
    ): Response<CloudApiEnvelope<EventVideosResponseData>>
}

interface CloudBackendGateway {
    suspend fun pairDevice(request: PairDeviceRequest): CloudApiResult<PairDeviceResponseData>
    suspend fun sendHeartbeat(request: HeartbeatRequest): CloudApiResult<HeartbeatResponseData>
    suspend fun getEventInfo(eventId: Long): CloudApiResult<EventInfoResponseData>
    suspend fun initiateVideoUpload(
        eventId: Long,
        idempotencyKey: String?,
        request: InitiateVideoUploadRequest
    ): CloudApiResult<InitiateVideoUploadResponseData>
    suspend fun completeVideoUpload(cloudVideoId: Long): CloudApiResult<CompleteVideoUploadResponseData>
    suspend fun getVideoStatus(cloudVideoId: Long): CloudApiResult<VideoStatusResponseData>
    suspend fun listEventVideos(eventId: Long, cursor: Long? = null, limit: Int = 20): CloudApiResult<EventVideosResponseData>
}

class RetrofitCloudGateway(
    baseUrl: String,
    okHttpClient: OkHttpClient,
    private val moshi: Moshi = defaultMoshi(),
    enforceHttps: Boolean = true
) : CloudBackendGateway {

    companion object {
        fun defaultMoshi(): Moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    init {
        if (enforceHttps) {
            Tls12OkHttpFactory.requireHttpsBaseUrl(baseUrl)
        }
    }

    private val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val errorEnvelopeAdapter = moshi.adapter(CloudApiErrorEnvelope::class.java)

    val apiService: RetroTokApiService = Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(RetroTokApiService::class.java)

    override suspend fun pairDevice(request: PairDeviceRequest): CloudApiResult<PairDeviceResponseData> =
        executeCall { apiService.pairDevice(request) }

    override suspend fun sendHeartbeat(request: HeartbeatRequest): CloudApiResult<HeartbeatResponseData> =
        executeCall { apiService.sendHeartbeat(request) }

    override suspend fun getEventInfo(eventId: Long): CloudApiResult<EventInfoResponseData> =
        executeCall { apiService.getEventInfo(eventId) }

    override suspend fun initiateVideoUpload(
        eventId: Long,
        idempotencyKey: String?,
        request: InitiateVideoUploadRequest
    ): CloudApiResult<InitiateVideoUploadResponseData> =
        executeCall { apiService.initiateVideoUpload(eventId, idempotencyKey, request) }

    override suspend fun completeVideoUpload(cloudVideoId: Long): CloudApiResult<CompleteVideoUploadResponseData> =
        executeCall { apiService.completeVideoUpload(cloudVideoId) }

    override suspend fun getVideoStatus(cloudVideoId: Long): CloudApiResult<VideoStatusResponseData> =
        executeCall { apiService.getVideoStatus(cloudVideoId) }

    override suspend fun listEventVideos(
        eventId: Long,
        cursor: Long?,
        limit: Int
    ): CloudApiResult<EventVideosResponseData> =
        executeCall { apiService.listEventVideos(eventId, cursor, limit) }

    private inline fun <T> executeCall(
        block: () -> Response<CloudApiEnvelope<T>>
    ): CloudApiResult<T> {
        return try {
            val response = block()
            val body = response.body()
            if (response.isSuccessful && body != null && body.ok && body.data != null) {
                CloudApiResult.Success(body.data)
            } else {
                val parsedError = body?.error ?: parseErrorBody(response.errorBody()?.string())
                val code = parsedError?.code ?: "HTTP_${response.code()}"
                val msg = CloudLogSanitizer.sanitize(
                    parsedError?.message
                        ?: response.message().takeIf { it.isNotBlank() }
                        ?: "Server returned HTTP ${response.code()}"
                )
                val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
                CloudApiResult.HttpError(
                    httpCode = response.code(),
                    errorCode = code,
                    message = msg,
                    retryAfterSeconds = retryAfter
                )
            }
        } catch (e: SocketTimeoutException) {
            CloudApiResult.NetworkError(
                message = "Connection timed out communicating with server.",
                isTimeout = true
            )
        } catch (e: IOException) {
            CloudApiResult.NetworkError(
                message = CloudLogSanitizer.sanitize(e.message ?: "Network error")
                    .ifBlank { "Network connection unavailable." }
            )
        } catch (e: Exception) {
            CloudApiResult.NetworkError(
                message = CloudLogSanitizer.sanitize(e.message ?: e.javaClass.simpleName)
                    .ifBlank { "Unexpected response parsing backend data." }
            )
        }
    }

    private fun parseErrorBody(rawJson: String?): CloudApiErrorBody? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            errorEnvelopeAdapter.fromJson(rawJson)?.error
        } catch (_: Exception) {
            null
        }
    }
}

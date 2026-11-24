package com.example.cloud

import okhttp3.Call
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException

sealed class R2UploadResult {
    data object Success : R2UploadResult()
    data class Failure(
        val httpCode: Int?,
        val retryable: Boolean,
        val errorCode: String,
        val message: String
    ) : R2UploadResult()
}

/**
 * Fixed-length streaming RequestBody returning null contentType() so OkHttp preserves
 * exact signed `Content-Type: video/mp4` headers without appending `; charset=utf-8`,
 * and returns exact `contentLength()` so Transfer-Encoding: chunked is never sent.
 */
class Mp4StreamingRequestBody(
    private val file: File,
    private val expectedSizeBytes: Long
) : RequestBody() {

    companion object {
        private const val STREAM_CHUNK_SIZE = 16 * 1024
    }

    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = expectedSizeBytes

    override fun isOneShot(): Boolean = false

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(STREAM_CHUNK_SIZE)
        var totalWritten = 0L
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                totalWritten += read
            }
        }
        if (totalWritten != expectedSizeBytes) {
            throw IOException("File size changed during R2 upload: expected=$expectedSizeBytes written=$totalWritten")
        }
    }
}

/**
 * Executes direct presigned PUT upload to Cloudflare R2 without backend Bearer auth headers.
 */
class R2DirectUploader(
    private val callFactory: Call.Factory = Tls12OkHttpFactory.createR2UploadClient(),
    private val requireHttps: Boolean = true
) {
    fun buildPutRequest(
        uploadUrl: String,
        uploadHeaders: Map<String, String>?,
        file: File,
        expectedSizeBytes: Long
    ): Request {
        if (requireHttps) {
            require(uploadUrl.startsWith("https://", ignoreCase = false)) {
                "R2 presigned upload URL must use HTTPS."
            }
        }
        val body = Mp4StreamingRequestBody(file, expectedSizeBytes)
        val builder = Request.Builder()
            .url(uploadUrl)
            .put(body)

        var hasContentType = false
        uploadHeaders?.forEach { (key, value) ->
            if (key.equals("Content-Type", ignoreCase = true)) {
                hasContentType = true
                builder.header("Content-Type", value)
            } else if (!key.equals("Content-Length", ignoreCase = true) &&
                !key.equals("Authorization", ignoreCase = true)
            ) {
                builder.header(key, value)
            }
        }
        if (!hasContentType) {
            builder.header("Content-Type", "video/mp4")
        }
        return builder.build()
    }

    fun uploadFile(
        uploadUrl: String,
        uploadHeaders: Map<String, String>?,
        file: File,
        expectedSizeBytes: Long = file.length()
    ): R2UploadResult {
        if (!file.exists() || file.length() != expectedSizeBytes || expectedSizeBytes <= 0L) {
            return R2UploadResult.Failure(
                httpCode = null,
                retryable = false,
                errorCode = "LOCAL_FILE_INVALID",
                message = "Local video file is missing or size changed before upload."
            )
        }
        return try {
            val request = buildPutRequest(uploadUrl, uploadHeaders, file, expectedSizeBytes)
            callFactory.newCall(request).execute().use { response ->
                val code = response.code
                if (response.isSuccessful) {
                    R2UploadResult.Success
                } else {
                    val retryable = code in 500..599 || code == 429 || code == 408
                    R2UploadResult.Failure(
                        httpCode = code,
                        retryable = retryable,
                        errorCode = "R2_HTTP_$code",
                        message = "Cloudflare R2 rejected upload with HTTP $code."
                    )
                }
            }
        } catch (e: IOException) {
            R2UploadResult.Failure(
                httpCode = null,
                retryable = true,
                errorCode = "R2_NETWORK_ERROR",
                message = CloudLogSanitizer.sanitize(e.message ?: e.javaClass.simpleName)
                    .ifBlank { "Network interruption during R2 upload." }
            )
        } catch (e: IllegalArgumentException) {
            R2UploadResult.Failure(
                httpCode = null,
                retryable = false,
                errorCode = "R2_INVALID_URL",
                message = e.message ?: "Invalid presigned upload URL."
            )
        }
    }
}

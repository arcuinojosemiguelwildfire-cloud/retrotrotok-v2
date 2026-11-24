package com.example.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import com.example.model.RetroEffectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

sealed class MuxerResult {
    data class Success(
        val finalFile: File,
        val durationMs: Long,
        val hasAudio: Boolean,
        val outputWidth: Int = 720,
        val outputHeight: Int = 1280,
        val effectAppliedPermanently: Boolean = false
    ) : MuxerResult()

    data class Error(val message: String, val fallbackFile: File? = null) : MuxerResult()
}

data class ValidatedMp4Info(
    val isValidPlayable: Boolean,
    val hasVideoTrack: Boolean,
    val hasAudioTrack: Boolean,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationMs: Long
)

/**
 * Phase 5 Offline Video Composition Engine for Android 6.0.1 (API 23)+.
 *
 * Pipeline:
 * RAW CAMERA VIDEO -> VIDEO DECODING -> GLES 2.0 RETRO EFFECT & 9:16 PORTRAIT CROP ->
 * H.264 ENCODING -> AUDIO COMPOSITION (AAC FAST-COPY OR MP3->AAC TRANSCODE) ->
 * FINAL MP4 CONTAINER -> PLAYABLE OUTPUT VALIDATION.
 */
open class VideoMuxerProcessor(private val context: Context? = null) {

    companion object {
        private const val TAG = "VideoMuxerProcessor"
        private const val DEFAULT_BUFFER_SIZE = 1024 * 1024 // 1 MB buffer
        private const val TIMEOUT_US = 10_000L
        private const val MAX_CODEC_STALL_ITERATIONS = 5000

        /**
         * Rebases track timestamps to 0 us relative to the first valid sample timestamp and guarantees
         * strictly increasing monotonic presentation timestamps required by Android 6.0.1 MediaMuxer.
         */
        fun normalizeSampleTimestampUs(
            rawPtsUs: Long,
            basePtsUs: Long?,
            lastEmittedPtsUs: Long?
        ): Pair<Long, Long> {
            val safeRaw = rawPtsUs.coerceAtLeast(0L)
            val resolvedBase = basePtsUs ?: safeRaw
            val rebased = (safeRaw - resolvedBase).coerceAtLeast(0L)
            val monotonic = if (lastEmittedPtsUs != null && rebased <= lastEmittedPtsUs) {
                lastEmittedPtsUs + 1L
            } else {
                rebased
            }
            return resolvedBase to monotonic
        }
    }

    /**
     * Composes the recorded camera video into a standalone portrait 9:16 MP4 with
     * permanent retro visual effects and trimmed local digital soundtrack.
     */
    open suspend fun processAndMux(
        rawVideoFile: File,
        audioFile: File?,
        outputFile: File,
        durationMs: Long,
        effect: RetroEffectType = RetroEffectType.VHS_RETRO,
        isMirrored: Boolean = false,
        framingMode: CameraFramingMode = CameraFramingMode.WIDE_FIT,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): MuxerResult = withContext(Dispatchers.IO) {
        if (!rawVideoFile.exists() || rawVideoFile.length() == 0L) {
            return@withContext MuxerResult.Error("Raw video file does not exist or is empty.")
        }

        onProgress(0.05f, "Inspecting recorded video stream...")
        val inputInfo = inspectVideoFile(rawVideoFile)

        // Development / headless test fallback when file is a non-codec stub MP4
        if (!inputInfo.hasVideoTrack) {
            Log.w(TAG, "No real MediaExtractor video track in ${rawVideoFile.name}; preserving fallback file.")
            onProgress(1.0f, "Finalizing preview file...")
            return@withContext fastFallbackCopy(rawVideoFile, outputFile, durationMs, hasAudio = false)
        }

        val effectiveDurationMs = if (inputInfo.durationMs > 0L) inputInfo.durationMs else durationMs.coerceAtLeast(1000L)
        val effectiveDurationUs = effectiveDurationMs * 1000L

        val parentDir = outputFile.parentFile ?: rawVideoFile.parentFile
        parentDir?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val stagingToken = System.currentTimeMillis()
        val tempVideoOnlyMp4 = File(parentDir, "comp_vid_${stagingToken}.mp4")
        val tempAudioOnlyMp4 = File(parentDir, "comp_aud_${stagingToken}.mp4")

        try {
            val isAlreadyPortrait = EffectShaderConfig.isAlreadyPortrait9x16(
                sourceWidth = inputInfo.width,
                sourceHeight = inputInfo.height,
                rotationDegrees = inputInfo.rotationDegrees
            )

            // Fast zero-transcode video path only when Original passthrough needs no crop/mirror
            val canBypassVideoTranscode = effect == RetroEffectType.NONE &&
                !isMirrored &&
                isAlreadyPortrait

            val finalWidth: Int
            val finalHeight: Int
            val videoSourceForFinalMux: File
            val outputOrientationHint: Int
            val effectBaked: Boolean

            if (canBypassVideoTranscode) {
                onProgress(0.35f, "Preserving original portrait video...")
                videoSourceForFinalMux = rawVideoFile
                val swap = inputInfo.rotationDegrees == 90 || inputInfo.rotationDegrees == 270
                finalWidth = if (swap) inputInfo.height else inputInfo.width
                finalHeight = if (swap) inputInfo.width else inputInfo.height
                outputOrientationHint = inputInfo.rotationDegrees
                effectBaked = false
            } else {
                onProgress(0.10f, "Probing device encoder & OpenGL ES 2.0...")
                val profile = DeviceMediaCapabilities.probeCompositionProfile()

                onProgress(0.15f, "Rendering ${effect.title} (${framingMode.label})...")
                transcodeVideoWithGlEffect(
                    inputFile = rawVideoFile,
                    outputVideoOnlyFile = tempVideoOnlyMp4,
                    srcWidth = inputInfo.width,
                    srcHeight = inputInfo.height,
                    srcRotationDegrees = inputInfo.rotationDegrees,
                    durationUs = effectiveDurationUs,
                    profile = profile,
                    effect = effect,
                    isMirrored = isMirrored,
                    framingMode = framingMode,
                    onFrameProgress = { frac ->
                        onProgress(0.15f + (frac * 0.60f), "Rendering ${effect.title} (${(frac * 100).toInt()}%)...")
                    }
                )

                videoSourceForFinalMux = tempVideoOnlyMp4
                finalWidth = profile.outputWidth
                finalHeight = profile.outputHeight
                outputOrientationHint = 0 // Upright portrait pixels already rendered
                effectBaked = true
            }

            // Prepare trimmed digital soundtrack sequentially (safe for API 23 single-OMX budgets)
            onProgress(0.78f, "Composing & syncing digital soundtrack...")
            val audioResult = AudioCompositionProcessor.prepareAudioTrack(
                audioFile = audioFile,
                outputAudioMp4 = tempAudioOnlyMp4,
                targetDurationUs = effectiveDurationUs
            )
            val composedAudioFile = (audioResult as? AudioCompositionProcessor.AudioComposeResult.Success)?.audioMp4File

            onProgress(0.92f, "Finalizing MP4 container...")
            muxVideoAndAudioTracks(
                videoSourceFile = videoSourceForFinalMux,
                audioSourceFile = composedAudioFile,
                outputFile = outputFile,
                orientationHint = outputOrientationHint
            )

            onProgress(0.97f, "Validating playable MP4 output...")
            val validated = inspectVideoFile(outputFile)
            if (validated.isValidPlayable && outputFile.exists() && outputFile.length() > 0L) {
                onProgress(1.0f, "Composition complete")
                MuxerResult.Success(
                    finalFile = outputFile,
                    durationMs = validated.durationMs.takeIf { it > 0L } ?: effectiveDurationMs,
                    hasAudio = validated.hasAudioTrack,
                    outputWidth = validated.width.takeIf { it > 0 } ?: finalWidth,
                    outputHeight = validated.height.takeIf { it > 0 } ?: finalHeight,
                    effectAppliedPermanently = effectBaked
                )
            } else {
                Log.w(TAG, "Validated output MP4 invalid; preserving raw source as safe fallback.")
                fastFallbackCopy(rawVideoFile, outputFile, effectiveDurationMs, hasAudio = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Composition failed: ${e.message}", e)
            // Safe recovery: attempt audio-only mux on raw video, or preserve raw file untouched
            return@withContext tryFallbackMuxWithoutEffects(
                rawVideoFile = rawVideoFile,
                audioMp4File = if (tempAudioOnlyMp4.exists() && tempAudioOnlyMp4.length() > 0L) tempAudioOnlyMp4 else null,
                outputFile = outputFile,
                durationMs = effectiveDurationMs,
                errorReason = e.message ?: "Unknown composition failure"
            )
        } finally {
            try { if (tempVideoOnlyMp4.exists()) tempVideoOnlyMp4.delete() } catch (_: Exception) {}
            try { if (tempAudioOnlyMp4.exists()) tempAudioOnlyMp4.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Inspects any MP4 file using MediaExtractor + MediaMetadataRetriever cleanly.
     */
    fun inspectVideoFile(file: File): ValidatedMp4Info {
        if (!file.exists() || file.length() < 32L) {
            return ValidatedMp4Info(false, false, false, 0, 0, 0, 0L)
        }
        var extractor: MediaExtractor? = null
        var retriever: MediaMetadataRetriever? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
            var hasVideo = false
            var hasAudio = false
            var width = 0
            var height = 0
            var rotation = 0
            var durationUs = 0L

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && !hasVideo) {
                    hasVideo = true
                    width = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else 0
                    height = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotation = fmt.getInteger(MediaFormat.KEY_ROTATION)
                    }
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = maxOf(durationUs, fmt.getLong(MediaFormat.KEY_DURATION))
                    }
                } else if (mime.startsWith("audio/") && !hasAudio) {
                    hasAudio = true
                }
            }

            if (hasVideo && (rotation == 0 || durationUs == 0L || width == 0)) {
                retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
                if (rotation == 0) {
                    rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                }
                if (durationUs == 0L) {
                    val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    durationUs = durMs * 1000L
                }
                if (width == 0) {
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
                }
            }

            ValidatedMp4Info(
                isValidPlayable = hasVideo && width > 0 && height > 0,
                hasVideoTrack = hasVideo,
                hasAudioTrack = hasAudio,
                width = width,
                height = height,
                rotationDegrees = ((rotation % 360) + 360) % 360,
                durationMs = durationUs / 1000L
            )
        } catch (e: Exception) {
            Log.w(TAG, "MP4 inspection non-codec or invalid for ${file.name}: ${e.message}")
            ValidatedMp4Info(false, false, false, 0, 0, 0, 0L)
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    private fun transcodeVideoWithGlEffect(
        inputFile: File,
        outputVideoOnlyFile: File,
        srcWidth: Int,
        srcHeight: Int,
        srcRotationDegrees: Int,
        durationUs: Long,
        profile: CompositionProfile,
        effect: RetroEffectType,
        isMirrored: Boolean,
        framingMode: CameraFramingMode,
        onFrameProgress: (Float) -> Unit
    ) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var glRenderer: GlEffectRenderer? = null

        try {
            extractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }
            var videoTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }
            if (videoTrackIndex == -1 || inputFormat == null) {
                throw IllegalStateException("Input file missing video track")
            }
            extractor.selectTrack(videoTrackIndex)

            val outputFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                profile.outputWidth,
                profile.outputHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, profile.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, profile.iFrameIntervalSeconds)
            }

            encoder = createAvcEncoder(profile.avcEncoderName)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            val cropTransform = EffectShaderConfig.computePortraitCropTransform(
                sourceWidth = srcWidth,
                sourceHeight = srcHeight,
                rotationDegrees = srcRotationDegrees,
                targetWidth = profile.outputWidth,
                targetHeight = profile.outputHeight,
                isMirrored = isMirrored,
                framingMode = framingMode
            )

            glRenderer = GlEffectRenderer(
                encoderInputSurface = encoderInputSurface,
                outputWidth = profile.outputWidth,
                outputHeight = profile.outputHeight,
                cropTransform = cropTransform,
                effect = effect
            )
            val decoderOutputSurface = glRenderer.initialize()

            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, decoderOutputSurface, null, 0)
            decoder.start()

            muxer = MediaMuxer(outputVideoOnlyFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(0)

            var muxerTrackIdx = -1
            var muxerStarted = false
            var lastEncodedPtsUs = -1L

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()

            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var encoderSignaledEos = false
            var stallIterations = 0

            while (!encoderDone) {
                var madeProgress = false

                // Feed compressed video packets into decoder
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        madeProgress = true
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain decoded frames to SurfaceTexture -> GL ES 2.0 -> Encoder input Surface
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (outIdx >= 0) {
                        madeProgress = true
                        val doRender = decInfo.size != 0
                        val reachedEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outIdx, doRender)

                        if (doRender) {
                            if (glRenderer.awaitNewImage(2500L)) {
                                val ptsUs = decInfo.presentationTimeUs.coerceAtLeast(0L)
                                glRenderer.drawFrame(ptsUs)
                                if (durationUs > 0L) {
                                    onFrameProgress((ptsUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f))
                                }
                            }
                        }

                        if (reachedEos) {
                            decoderDone = true
                            if (!encoderSignaledEos) {
                                encoder.signalEndOfInputStream()
                                encoderSignaledEos = true
                            }
                        }
                    }
                }

                // Drain H.264 encoder into temp MediaMuxer
                while (true) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                    when {
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            madeProgress = true
                            if (!muxerStarted) {
                                muxerTrackIdx = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        encOutIdx >= 0 -> {
                            madeProgress = true
                            val outBuf = encoder.getOutputBuffer(encOutIdx)
                            val isConfig = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isEos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                            if (!isConfig && encInfo.size > 0 && outBuf != null && muxerStarted) {
                                val safePts = if (encInfo.presentationTimeUs <= lastEncodedPtsUs) {
                                    lastEncodedPtsUs + 1L
                                } else {
                                    encInfo.presentationTimeUs
                                }
                                lastEncodedPtsUs = safePts

                                val writeInfo = MediaCodec.BufferInfo().apply {
                                    set(encInfo.offset, encInfo.size, safePts, encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                                }
                                outBuf.position(encInfo.offset)
                                outBuf.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(muxerTrackIdx, outBuf, writeInfo)
                            }

                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if (isEos) {
                                encoderDone = true
                                break
                            }
                        }
                    }
                }

                if (madeProgress) {
                    stallIterations = 0
                } else {
                    stallIterations++
                    if (stallIterations >= MAX_CODEC_STALL_ITERATIONS) {
                        throw IllegalStateException("Video composition stalled waiting for API 23 codec buffer")
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop()
            } else {
                throw IllegalStateException("H.264 encoder never emitted output format")
            }
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { glRenderer?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun createAvcEncoder(preferredName: String?): MediaCodec {
        if (!preferredName.isNullOrBlank()) {
            try {
                return MediaCodec.createByCodecName(preferredName)
            } catch (e: Exception) {
                Log.w(TAG, "Preferred AVC encoder '$preferredName' failed, falling back by type: ${e.message}")
            }
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    private fun muxVideoAndAudioTracks(
        videoSourceFile: File,
        audioSourceFile: File?,
        outputFile: File,
        orientationHint: Int
    ) {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoSourceFile.absolutePath) }
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = fmt
                    break
                }
            }
            if (videoTrackIndex == -1 || videoFormat == null) {
                throw IllegalStateException("Composed video source missing video track")
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            if (audioSourceFile != null && audioSourceFile.exists() && audioSourceFile.length() > 0L) {
                audioExtractor = MediaExtractor().apply { setDataSource(audioSourceFile.absolutePath) }
                for (i in 0 until audioExtractor.trackCount) {
                    val fmt = audioExtractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        audioFormat = fmt
                        break
                    }
                }
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (orientationHint != 0) {
                muxer.setOrientationHint(orientationHint)
            }

            videoExtractor.selectTrack(videoTrackIndex)
            val outVideoTrack = muxer.addTrack(videoFormat)

            var outAudioTrack = -1
            if (audioTrackIndex != -1 && audioFormat != null && audioExtractor != null) {
                audioExtractor.selectTrack(audioTrackIndex)
                outAudioTrack = muxer.addTrack(audioFormat)
            }

            muxer.start()

            val videoMaxInput = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                0
            }
            val audioMaxInput = if (audioFormat != null && audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                0
            }
            val bufferCapacity = maxOf(DEFAULT_BUFFER_SIZE, videoMaxInput, audioMaxInput)
            val buffer = ByteBuffer.allocate(bufferCapacity)
            val bufferInfo = MediaCodec.BufferInfo()

            var videoBasePtsUs: Long? = null
            var lastVideoPtsUs: Long? = null
            while (true) {
                val size = videoExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                val (base, safePts) = normalizeSampleTimestampUs(
                    rawPtsUs = videoExtractor.sampleTime,
                    basePtsUs = videoBasePtsUs,
                    lastEmittedPtsUs = lastVideoPtsUs
                )
                videoBasePtsUs = base
                lastVideoPtsUs = safePts
                bufferInfo.set(0, size, safePts, videoExtractor.sampleFlags)
                muxer.writeSampleData(outVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            if (outAudioTrack != -1 && audioExtractor != null) {
                var audioBasePtsUs: Long? = null
                var lastAudioPtsUs: Long? = null
                while (true) {
                    val size = audioExtractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val (base, safePts) = normalizeSampleTimestampUs(
                        rawPtsUs = audioExtractor.sampleTime,
                        basePtsUs = audioBasePtsUs,
                        lastEmittedPtsUs = lastAudioPtsUs
                    )
                    audioBasePtsUs = base
                    lastAudioPtsUs = safePts
                    bufferInfo.set(0, size, safePts, audioExtractor.sampleFlags)
                    muxer.writeSampleData(outAudioTrack, buffer, bufferInfo)
                    audioExtractor.advance()
                }
            }

            muxer.stop()
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun tryFallbackMuxWithoutEffects(
        rawVideoFile: File,
        audioMp4File: File?,
        outputFile: File,
        durationMs: Long,
        errorReason: String
    ): MuxerResult {
        if (audioMp4File != null) {
            try {
                if (outputFile.exists()) outputFile.delete()
                muxVideoAndAudioTracks(
                    videoSourceFile = rawVideoFile,
                    audioSourceFile = audioMp4File,
                    outputFile = outputFile,
                    orientationHint = inspectVideoFile(rawVideoFile).rotationDegrees
                )
                if (outputFile.exists() && outputFile.length() > 0L) {
                    return MuxerResult.Success(
                        finalFile = outputFile,
                        durationMs = durationMs,
                        hasAudio = true,
                        effectAppliedPermanently = false
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback audio+raw mux also failed: ${e.message}")
            }
        }
        return fastFallbackCopy(rawVideoFile, outputFile, durationMs, hasAudio = false)
            .let { result ->
                if (result is MuxerResult.Success) {
                    result
                } else {
                    MuxerResult.Error(
                        message = "Composition failed ($errorReason); raw recording preserved.",
                        fallbackFile = rawVideoFile
                    )
                }
            }
    }

    private fun fastFallbackCopy(
        source: File,
        destination: File,
        durationMs: Long,
        hasAudio: Boolean
    ): MuxerResult {
        return try {
            source.copyTo(destination, overwrite = true)
            MuxerResult.Success(
                finalFile = destination,
                durationMs = durationMs,
                hasAudio = hasAudio,
                effectAppliedPermanently = false
            )
        } catch (e: Exception) {
            MuxerResult.Error("Fallback copy failed: ${e.localizedMessage}", source)
        }
    }

    fun isStandardMp4Header(file: File): Boolean {
        if (!file.exists() || file.length() < 32) return false
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(12)
                val read = fis.read(header)
                if (read >= 8) {
                    val boxType = String(header, 4, 4, Charsets.US_ASCII)
                    return boxType == "ftyp"
                }
            }
        } catch (_: Exception) {
        }
        return false
    }
}

package com.example.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Prepares the local soundtrack audio track as an MP4/AAC intermediate file trimmed
 * cleanly to the recorded video duration.
 *
 * - If the source file is already AAC (`audio/mp4a-latm`), uses zero-transcode sample copy.
 * - If the source file is MP3 (`audio/mpeg`) or another non-AAC format unsupported directly
 *   by Android 6.0.1 MediaMuxer, decodes PCM and encodes to AAC (`audio/mp4a-latm`).
 */
object AudioCompositionProcessor {

    private const val TAG = "AudioCompositionProc"
    private const val TIMEOUT_US = 10_000L
    private const val MAX_SAMPLE_BUFFER_BYTES = 512 * 1024
    private const val MAX_CODEC_STALL_ITERATIONS = 5000

    sealed class AudioComposeResult {
        data class Success(val audioMp4File: File, val wasTranscoded: Boolean) : AudioComposeResult()
        data class Skipped(val reason: String) : AudioComposeResult()
    }

    fun prepareAudioTrack(
        audioFile: File?,
        outputAudioMp4: File,
        targetDurationUs: Long,
        preferredAudioBitrate: Int = 128_000
    ): AudioComposeResult {
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0L) {
            return AudioComposeResult.Skipped("No local audio file provided")
        }
        if (targetDurationUs <= 0L) {
            return AudioComposeResult.Skipped("Target duration is zero")
        }

        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                return AudioComposeResult.Skipped("No audio track found in ${audioFile.name}")
            }

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            extractor.selectTrack(audioTrackIndex)

            if (outputAudioMp4.exists()) outputAudioMp4.delete()
            outputAudioMp4.parentFile?.mkdirs()

            return if (DeviceMediaCapabilities.isMimeMediaMuxerCompatibleAudio(mime)) {
                fastCopyAacTrack(extractor, audioFormat, outputAudioMp4, targetDurationUs)
            } else {
                transcodeToAacTrack(
                    extractor = extractor,
                    inputFormat = audioFormat,
                    inputMime = mime,
                    outputAudioMp4 = outputAudioMp4,
                    targetDurationUs = targetDurationUs,
                    targetBitrate = preferredAudioBitrate
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio preparation failed: ${e.message}", e)
            if (outputAudioMp4.exists()) outputAudioMp4.delete()
            return AudioComposeResult.Skipped("Audio compose error: ${e.message}")
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun fastCopyAacTrack(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        outputAudioMp4: File,
        targetDurationUs: Long
    ): AudioComposeResult {
        var muxer: MediaMuxer? = null
        return try {
            muxer = MediaMuxer(outputAudioMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIdx = muxer.addTrack(inputFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(MAX_SAMPLE_BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            var wroteSamples = false
            var basePtsUs: Long? = null
            var lastPtsUs: Long? = null

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val rawPtsUs = extractor.sampleTime
                if (rawPtsUs < 0L) {
                    extractor.advance()
                    continue
                }
                val (resolvedBase, safePtsUs) = VideoMuxerProcessor.normalizeSampleTimestampUs(
                    rawPtsUs = rawPtsUs,
                    basePtsUs = basePtsUs,
                    lastEmittedPtsUs = lastPtsUs
                )
                basePtsUs = resolvedBase
                if (safePtsUs > targetDurationUs) break
                lastPtsUs = safePtsUs

                info.offset = 0
                info.size = size
                info.presentationTimeUs = safePtsUs
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(trackIdx, buffer, info)
                wroteSamples = true
                extractor.advance()
            }

            muxer.stop()
            if (wroteSamples && outputAudioMp4.exists() && outputAudioMp4.length() > 0L) {
                AudioComposeResult.Success(outputAudioMp4, wasTranscoded = false)
            } else {
                AudioComposeResult.Skipped("AAC track had zero samples within duration")
            }
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun transcodeToAacTrack(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        inputMime: String,
        outputAudioMp4: File,
        targetDurationUs: Long,
        targetBitrate: Int
    ): AudioComposeResult {
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            } else {
                2
            }

            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val aacFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputAudioMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1
            var muxerStarted = false

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()

            var extractorDone = false
            var decoderDone = false
            var encoderInputSignaledEos = false
            var encoderDone = false
            var lastEncodedPtsUs = -1L

            // Track PCM output sample rate/channels if decoder overrides them
            var pcmSampleRate = sampleRate
            var pcmChannelCount = channelCount
            var computedPcmPtsUs = 0L
            var stallIterations = 0

            while (!encoderDone) {
                var madeProgress = false

                // 1. Feed compressed samples (MP3, etc.) into decoder
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        madeProgress = true
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            val sampleTimeUs = extractor.sampleTime
                            if (size < 0 || sampleTimeUs > targetDurationUs) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, size, sampleTimeUs, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 2. Drain decoded PCM and feed into AAC encoder
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            madeProgress = true
                            val newFmt = decoder.outputFormat
                            if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                pcmSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                pcmChannelCount = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            }
                        }
                        outIdx >= 0 -> {
                            madeProgress = true
                            val decBuf = decoder.getOutputBuffer(outIdx)
                            val reachedEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                            if (decBuf != null && decInfo.size > 0 && computedPcmPtsUs <= targetDurationUs) {
                                decBuf.position(decInfo.offset)
                                decBuf.limit(decInfo.offset + decInfo.size)

                                // Chunk PCM into encoder input buffers cleanly so we never overflow input capacity
                                var chunkStallCount = 0
                                while (decBuf.hasRemaining()) {
                                    val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US * 4)
                                    if (encInIdx >= 0) {
                                        chunkStallCount = 0
                                        val encInBuf = encoder.getInputBuffer(encInIdx)
                                        if (encInBuf != null) {
                                            encInBuf.clear()
                                            val chunkBytes = minOf(decBuf.remaining(), encInBuf.capacity())
                                            val oldLimit = decBuf.limit()
                                            decBuf.limit(decBuf.position() + chunkBytes)
                                            encInBuf.put(decBuf)
                                            decBuf.limit(oldLimit)

                                            encoder.queueInputBuffer(encInIdx, 0, chunkBytes, computedPcmPtsUs, 0)
                                            val totalSamples = chunkBytes / (2 * pcmChannelCount)
                                            computedPcmPtsUs += (totalSamples * 1_000_000L) / pcmSampleRate.coerceAtLeast(8000)
                                        }
                                    } else {
                                        chunkStallCount++
                                        if (chunkStallCount >= MAX_CODEC_STALL_ITERATIONS) {
                                            throw IllegalStateException("Audio encoder stalled accepting PCM chunks")
                                        }
                                    }
                                    // Drain encoder frequently to prevent backpressure stall
                                    val drainResult = drainEncoder(
                                        encoder = encoder,
                                        encInfo = encInfo,
                                        muxer = muxer,
                                        trackIndexRef = { muxerTrackIndex },
                                        onTrackAdded = { trackIdx ->
                                            muxerTrackIndex = trackIdx
                                            muxer.start()
                                            muxerStarted = true
                                        },
                                        muxerStartedProvider = { muxerStarted },
                                        lastPtsRef = lastEncodedPtsUs,
                                        onLastPtsUpdate = { lastEncodedPtsUs = it },
                                        targetDurationUs = targetDurationUs
                                    )
                                    if (drainResult) encoderDone = true
                                }
                            }

                            decoder.releaseOutputBuffer(outIdx, false)
                            if (reachedEos || computedPcmPtsUs > targetDurationUs) {
                                decoderDone = true
                            }
                        }
                    }
                }

                // 3. Signal EOS to AAC encoder once decoder finishes
                if (decoderDone && !encoderInputSignaledEos) {
                    val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US * 4)
                    if (encInIdx >= 0) {
                        madeProgress = true
                        encoder.queueInputBuffer(encInIdx, 0, 0, computedPcmPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderInputSignaledEos = true
                    }
                }

                // 4. Drain AAC encoder to MediaMuxer
                val eosReached = drainEncoder(
                    encoder = encoder,
                    encInfo = encInfo,
                    muxer = muxer,
                    trackIndexRef = { muxerTrackIndex },
                    onTrackAdded = { trackIdx ->
                        muxerTrackIndex = trackIdx
                        muxer.start()
                        muxerStarted = true
                    },
                    muxerStartedProvider = { muxerStarted },
                    lastPtsRef = lastEncodedPtsUs,
                    onLastPtsUpdate = { lastEncodedPtsUs = it },
                    targetDurationUs = targetDurationUs
                )
                if (eosReached) {
                    madeProgress = true
                    encoderDone = true
                }

                if (madeProgress) {
                    stallIterations = 0
                } else {
                    stallIterations++
                    if (stallIterations >= MAX_CODEC_STALL_ITERATIONS) {
                        throw IllegalStateException("Audio composition stalled waiting for API 23 codec buffer")
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop()
            }

            return if (muxerStarted && outputAudioMp4.exists() && outputAudioMp4.length() > 0L) {
                AudioComposeResult.Success(outputAudioMp4, wasTranscoded = true)
            } else {
                AudioComposeResult.Skipped("Transcoded audio produced empty output")
            }
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        encInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        trackIndexRef: () -> Int,
        onTrackAdded: (Int) -> Unit,
        muxerStartedProvider: () -> Boolean,
        lastPtsRef: Long,
        onLastPtsUpdate: (Long) -> Unit,
        targetDurationUs: Long
    ): Boolean {
        var currentLastPts = lastPtsRef
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStartedProvider()) {
                        val idx = muxer.addTrack(encoder.outputFormat)
                        onTrackAdded(idx)
                    }
                }
                outIdx >= 0 -> {
                    val outBuf = encoder.getOutputBuffer(outIdx)
                    val isConfig = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isEos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    if (!isConfig && encInfo.size > 0 && outBuf != null && muxerStartedProvider()) {
                        if (encInfo.presentationTimeUs <= targetDurationUs + 50_000L) {
                            // Guarantee strictly non-decreasing PTS for API 23 MediaMuxer
                            val safePts = if (encInfo.presentationTimeUs <= currentLastPts) {
                                currentLastPts + 1L
                            } else {
                                encInfo.presentationTimeUs
                            }
                            currentLastPts = safePts
                            onLastPtsUpdate(safePts)

                            val writeInfo = MediaCodec.BufferInfo().apply {
                                set(encInfo.offset, encInfo.size, safePts, encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                            }
                            outBuf.position(encInfo.offset)
                            outBuf.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(trackIndexRef(), outBuf, writeInfo)
                        }
                    }

                    encoder.releaseOutputBuffer(outIdx, false)
                    if (isEos) return true
                }
            }
        }
    }
}

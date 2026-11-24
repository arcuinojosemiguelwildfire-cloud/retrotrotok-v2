package com.example.data.repository

import android.net.Uri
import android.util.Log
import com.example.data.local.dao.UploadQueueDao
import com.example.data.local.dao.VideoDao
import com.example.data.local.entity.UploadQueueEntity
import com.example.data.local.entity.VideoEntity
import com.example.model.CloudUploadSummary
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.model.VideoItem
import com.example.video.StagedImportedVideoResult
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import com.example.video.VideoThumbnailGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Production Room-backed repository for RetroTok event feed videos.
 * Enforces safe publishing transactions, local thumbnail generation, storage/DB reconciliation,
 * local kiosk likes/favorites, SAF MP4 video import, and safe asset deletion.
 */
class VideoFeedRepositoryImpl(
    private val videoDao: VideoDao,
    private val storageManager: VideoStorageManager,
    private val thumbnailGenerator: VideoThumbnailGenerator,
    private val videoInspector: VideoMuxerProcessor,
    private val uploadQueueDao: UploadQueueDao? = null
) : VideoFeedRepository {

    companion object {
        private const val TAG = "VideoFeedRepoImpl"
        private const val STATUS_PUBLISHED = "PUBLISHED"
        private const val STATUS_IMPORTED = "IMPORTED"
        private const val STATUS_MISSING_FILE = "MISSING_FILE"
        private const val MODERATION_VISIBLE = "VISIBLE"
        private const val MODERATION_HIDDEN = "HIDDEN"
    }

    private val interactionMutex = Mutex()
    private val transactionMutex = Mutex()

    override fun getFeedVideos(): Flow<List<VideoItem>> {
        val queueFlow = uploadQueueDao?.observeAll()
        return if (queueFlow == null) {
            videoDao.getPublishedVideos().map { entities ->
                entities.map { it.toVideoItem(checkFileHealth = true) }
            }
        } else {
            combine(videoDao.getPublishedVideos(), queueFlow) { entities, uploads ->
                val uploadMap = uploads.associate { it.videoId to it.toSummary() }
                entities.map { it.toVideoItem(checkFileHealth = true, cloudUploadSummary = uploadMap[it.videoId]) }
            }
        }
    }

    override fun getAllAdminVideos(): Flow<List<VideoItem>> {
        val queueFlow = uploadQueueDao?.observeAll()
        return if (queueFlow == null) {
            videoDao.getAllVideos().map { entities ->
                entities.map { it.toVideoItem(checkFileHealth = true) }
            }
        } else {
            combine(videoDao.getAllVideos(), queueFlow) { entities, uploads ->
                val uploadMap = uploads.associate { it.videoId to it.toSummary() }
                entities.map { it.toVideoItem(checkFileHealth = true, cloudUploadSummary = uploadMap[it.videoId]) }
            }
        }
    }

    override fun searchVideos(query: String, sort: VideoSearchSort): Flow<List<VideoItem>> {
        val trimmed = query.trim()
        val baseFlow = if (trimmed.isBlank()) {
            videoDao.getPublishedVideos()
        } else {
            videoDao.searchPublishedVideos(trimmed)
        }
        val queueFlow = uploadQueueDao?.observeAll()
        return if (queueFlow == null) {
            baseFlow.map { entities -> sortAndMap(entities, sort, emptyMap()) }
        } else {
            combine(baseFlow, queueFlow) { entities, uploads ->
                val uploadMap = uploads.associate { it.videoId to it.toSummary() }
                sortAndMap(entities, sort, uploadMap)
            }
        }
    }

    private fun sortAndMap(
        entities: List<VideoEntity>,
        sort: VideoSearchSort,
        uploadMap: Map<String, CloudUploadSummary>
    ): List<VideoItem> {
        val filtered = when (sort) {
            VideoSearchSort.FAVORITES -> entities.filter { it.isFavorite }
            else -> entities
        }
        val sorted = when (sort) {
            VideoSearchSort.NEWEST -> filtered.sortedByDescending { it.createdAt }
            VideoSearchSort.MOST_LIKED -> filtered.sortedWith(
                compareByDescending<VideoEntity> { it.likeCount }
                    .thenByDescending { it.createdAt }
            )
            VideoSearchSort.FAVORITES -> filtered.sortedByDescending { it.createdAt }
            VideoSearchSort.SOUNDTRACK -> filtered.sortedWith(
                compareBy<VideoEntity> { it.soundtrackTitle.lowercase() }
                    .thenByDescending { it.createdAt }
            )
        }
        return sorted.map { it.toVideoItem(checkFileHealth = true, cloudUploadSummary = uploadMap[it.videoId]) }
    }

    override suspend fun getFeedVideosPaged(limit: Int, offset: Int): List<VideoItem> {
        return videoDao.getPublishedVideosPaged(limit, offset).map { it.toVideoItem(checkFileHealth = true) }
    }

    override suspend fun toggleLike(videoId: String): VideoItem? = interactionMutex.withLock {
        val entity = videoDao.getVideoById(videoId) ?: return@withLock null
        val newIsLiked = !entity.isLiked
        val newLikeCount = if (newIsLiked) entity.likeCount + 1 else (entity.likeCount - 1).coerceAtLeast(0)
        val now = System.currentTimeMillis()
        videoDao.updateLikeState(videoId, newIsLiked, newLikeCount, now)
        entity.copy(isLiked = newIsLiked, likeCount = newLikeCount, updatedAt = now)
            .toVideoItem(checkFileHealth = true)
    }

    override suspend fun ensureLiked(videoId: String): VideoItem? = interactionMutex.withLock {
        val entity = videoDao.getVideoById(videoId) ?: return@withLock null
        if (entity.isLiked) {
            return@withLock entity.toVideoItem(checkFileHealth = true)
        }
        val newCount = entity.likeCount + 1
        val now = System.currentTimeMillis()
        videoDao.updateLikeState(videoId, isLiked = true, likeCount = newCount, updatedAt = now)
        entity.copy(isLiked = true, likeCount = newCount, updatedAt = now)
            .toVideoItem(checkFileHealth = true)
    }

    override suspend fun toggleFavorite(videoId: String): VideoItem? = interactionMutex.withLock {
        val entity = videoDao.getVideoById(videoId) ?: return@withLock null
        val newFav = !entity.isFavorite
        val now = System.currentTimeMillis()
        videoDao.updateFavoriteState(videoId, newFav, now)
        entity.copy(isFavorite = newFav, updatedAt = now)
            .toVideoItem(checkFileHealth = true)
    }

    override suspend fun toggleFeatured(videoId: String) {
        interactionMutex.withLock {
            val entity = videoDao.getVideoById(videoId) ?: return@withLock
            videoDao.updateFeaturedState(videoId, !entity.isFeatured, System.currentTimeMillis())
        }
    }

    override suspend fun moderateVideo(videoId: String, hide: Boolean, reason: String?) {
        interactionMutex.withLock {
            val now = System.currentTimeMillis()
            if (hide) {
                val cleanReason = reason?.trim()?.ifBlank { null } ?: "Hidden by organizer"
                videoDao.updateModerationState(
                    videoId = videoId,
                    moderationStatus = MODERATION_HIDDEN,
                    moderatedAt = now,
                    moderationReason = cleanReason,
                    updatedAt = now
                )
            } else {
                videoDao.updateModerationState(
                    videoId = videoId,
                    moderationStatus = MODERATION_VISIBLE,
                    moderatedAt = now,
                    moderationReason = "Restored by organizer",
                    updatedAt = now
                )
            }
        }
    }

    override suspend fun publishVideo(newVideo: VideoItem) {
        transactionMutex.withLock {
            val relPath = newVideo.localVideoPath ?: ""
            val absFile = if (relPath.isNotBlank()) storageManager.getAbsoluteFile(relPath) else null
            val thumbPath = newVideo.thumbnailPath ?: if (absFile != null && absFile.exists()) {
                thumbnailGenerator.generateThumbnail(absFile, newVideo.id)
            } else {
                null
            }
            val now = System.currentTimeMillis()
            val entity = VideoEntity(
                videoId = newVideo.id,
                nickname = newVideo.creatorNickname.ifBlank { "RetroDancer" },
                caption = newVideo.caption,
                hashtags = newVideo.hashtags.ifBlank { "#RetroTok" },
                localVideoPath = relPath,
                thumbnailPath = thumbPath,
                soundtrackTitle = newVideo.song.title,
                soundtrackArtist = newVideo.song.artist,
                soundtrackId = newVideo.song.id.takeIf { it.isNotBlank() },
                effectType = newVideo.effect.name,
                duration = newVideo.durationSeconds,
                resolutionWidth = newVideo.resolutionWidth,
                resolutionHeight = newVideo.resolutionHeight,
                fileSize = if (newVideo.fileSizeBytes > 0L) newVideo.fileSizeBytes else (absFile?.length() ?: 0L),
                createdAt = newVideo.createdAt,
                updatedAt = now,
                isFavorite = newVideo.isFavorited,
                likeCount = newVideo.likeCount,
                isLiked = newVideo.isLiked,
                isFeatured = newVideo.isFeatured,
                publicationStatus = newVideo.publicationStatus,
                moderationStatus = newVideo.moderationStatus,
                moderatedAt = newVideo.moderatedAt,
                moderationReason = newVideo.moderationReason
            )
            videoDao.insertVideo(entity)
        }
    }

    /**
     * Safe 7-step publishing transaction:
     * 1. Validate final composed MP4
     * 2. Generate portrait thumbnail locally
     * 3. Prepare publication metadata
     * 4. Promote video atomically from temp/ to published/
     * 5. Insert Room VideoEntity record
     * 6. Verify & return Result
     */
    override suspend fun publishRecordedVideo(
        sourceFile: File,
        nickname: String,
        caption: String,
        hashtags: String,
        song: SongItem,
        effect: RetroEffectType,
        durationSeconds: Int
    ): PublishVideoResult = withContext(Dispatchers.IO) {
        transactionMutex.withLock {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                return@withLock PublishVideoResult.Error("Composed video file is missing or empty.")
            }

            // 1. Validate MP4 structure / stream
            val mp4Info = videoInspector.inspectVideoFile(sourceFile)
            val isValidContainer = mp4Info.isValidPlayable || videoInspector.isStandardMp4Header(sourceFile)
            if (!isValidContainer) {
                return@withLock PublishVideoResult.Error("Final video failed MP4 validation.")
            }

            val videoId = "vid-${UUID.randomUUID().toString().take(12)}"

            // 2. Generate local portrait thumbnail before promotion (failure does not abort publication)
            val thumbnailRelPath = thumbnailGenerator.generateThumbnail(sourceFile, videoId)

            // 3. Prepare metadata
            val cleanNickname = nickname.trim().ifBlank { "RetroDancer" }
            val cleanHashtags = hashtags.trim().ifBlank { "#RetroTok #${song.releaseYear ?: "Retro"}" }
            val cleanCaption = caption.trim().ifBlank { "Dancing to ${song.title}! $cleanHashtags" }
            val resolvedWidth = mp4Info.width.takeIf { it > 0 } ?: 720
            val resolvedHeight = mp4Info.height.takeIf { it > 0 } ?: 1280
            val resolvedDuration = (mp4Info.durationMs / 1000L).toInt().takeIf { it > 0 } ?: durationSeconds
            val now = System.currentTimeMillis()

            // 4. Promote from temporary storage to published storage
            val publishedRelPath = storageManager.promoteToPublished(sourceFile, videoId)
            val publishedAbsFile = storageManager.getAbsoluteFile(publishedRelPath)
            if (!publishedAbsFile.exists() || publishedAbsFile.length() == 0L) {
                return@withLock PublishVideoResult.Error("Failed to commit published video file.")
            }

            // 5. Insert Room record
            val entity = VideoEntity(
                videoId = videoId,
                nickname = cleanNickname,
                caption = cleanCaption,
                hashtags = cleanHashtags,
                localVideoPath = publishedRelPath,
                thumbnailPath = thumbnailRelPath,
                soundtrackTitle = song.title,
                soundtrackArtist = song.artist,
                soundtrackId = song.id.takeIf { it.isNotBlank() },
                effectType = effect.name,
                duration = resolvedDuration,
                resolutionWidth = resolvedWidth,
                resolutionHeight = resolvedHeight,
                fileSize = publishedAbsFile.length(),
                createdAt = now,
                updatedAt = now,
                isFavorite = false,
                likeCount = 0,
                isLiked = false,
                isFeatured = false,
                publicationStatus = STATUS_PUBLISHED
            )

            try {
                videoDao.insertVideo(entity)
                PublishVideoResult.Success(entity.toVideoItem(checkFileHealth = true))
            } catch (e: Exception) {
                Log.e(TAG, "Room insert failed after file promotion; file preserved at $publishedRelPath", e)
                PublishVideoResult.Error("Database write error; video preserved safely for recovery.")
            }
        }
    }

    override suspend fun importLocalVideo(
        uri: Uri,
        nickname: String,
        caption: String,
        hashtags: String
    ): ImportVideoResult = withContext(Dispatchers.IO) {
        transactionMutex.withLock {
            val videoId = "imp-${UUID.randomUUID().toString().take(12)}"
            when (val staged = storageManager.importAndValidateVideoFromUri(uri, videoId)) {
                is StagedImportedVideoResult.Error -> {
                    ImportVideoResult.Error(staged.message)
                }
                is StagedImportedVideoResult.Success -> {
                    val thumbPath = thumbnailGenerator.generateThumbnail(staged.importedFile, videoId)
                    val cleanNick = nickname.trim().ifBlank { "EventGuest" }
                    val cleanTags = hashtags.trim().ifBlank { "#RetroTok #Imported" }
                    val cleanCaption = caption.trim().ifBlank { "Shared dance highlight $cleanTags" }
                    val now = System.currentTimeMillis()

                    val entity = VideoEntity(
                        videoId = videoId,
                        nickname = cleanNick,
                        caption = cleanCaption,
                        hashtags = cleanTags,
                        localVideoPath = staged.relativePath,
                        thumbnailPath = thumbPath,
                        soundtrackTitle = "Original Audio",
                        soundtrackArtist = cleanNick,
                        soundtrackId = null,
                        effectType = RetroEffectType.NONE.name,
                        duration = staged.durationSeconds,
                        resolutionWidth = staged.width,
                        resolutionHeight = staged.height,
                        fileSize = staged.fileSizeBytes,
                        createdAt = now,
                        updatedAt = now,
                        isFavorite = false,
                        likeCount = 0,
                        isLiked = false,
                        isFeatured = false,
                        publicationStatus = STATUS_IMPORTED
                    )
                    videoDao.insertVideo(entity)
                    ImportVideoResult.Success(entity.toVideoItem(checkFileHealth = true))
                }
            }
        }
    }

    /**
     * Safe Admin deletion:
     * 1. Removes Room record
     * 2. Removes published/imported MP4
     * 3. Removes thumbnail JPEG
     * 4. Preserves unrelated videos and original music catalog files
     */
    override suspend fun deleteVideo(videoId: String) {
        transactionMutex.withLock {
            val entity = videoDao.getVideoById(videoId)
            videoDao.deleteVideoById(videoId)
            uploadQueueDao?.deleteByVideoId(videoId)
            if (entity != null) {
                storageManager.deletePublishedAssets(
                    localVideoPath = entity.localVideoPath,
                    thumbnailPath = entity.thumbnailPath
                )
            }
        }
    }

    /**
     * Reconciles filesystem state and Room database without deleting valid published files:
     * - Recovers published/imported MP4s missing Room records
     * - Flags DB records whose local MP4 is missing or zero-byte
     * - Regenerates missing thumbnails for valid videos
     */
    override suspend fun reconcileStorageAndDatabase(): ReconciliationReport = withContext(Dispatchers.IO) {
        transactionMutex.withLock {
            var recoveredFiles = 0
            var flaggedMissing = 0
            var regeneratedThumbs = 0

            val existingRecords = videoDao.getAllVideosOnce()
            val trackedPaths = existingRecords.map { it.localVideoPath }.toSet()

            // 1. Check existing DB records for missing files or missing thumbnails
            for (record in existingRecords) {
                val videoFile = storageManager.getAbsoluteFile(record.localVideoPath)
                val fileOk = videoFile.exists() && videoFile.length() > 32L
                if (!fileOk) {
                    if (record.publicationStatus != STATUS_MISSING_FILE) {
                        videoDao.updatePublicationStatus(record.videoId, STATUS_MISSING_FILE)
                        flaggedMissing++
                    }
                } else {
                    if (record.publicationStatus == STATUS_MISSING_FILE) {
                        val restoredStatus = if (record.videoId.startsWith("imp-")) STATUS_IMPORTED else STATUS_PUBLISHED
                        videoDao.updatePublicationStatus(record.videoId, restoredStatus)
                    }
                    val thumbExists = record.thumbnailPath?.let { storageManager.getAbsoluteFile(it).exists() } ?: false
                    if (!thumbExists) {
                        val newThumb = thumbnailGenerator.generateThumbnail(videoFile, record.videoId)
                        if (newThumb != null) {
                            videoDao.updateThumbnailPath(record.videoId, newThumb)
                            regeneratedThumbs++
                        }
                    }
                }
            }

            // 2. Recover valid published/imported MP4 files that lack a Room record
            val filesOnDisk = storageManager.listPublishedAndImportedMp4Files()
            for (file in filesOnDisk) {
                val folder = if (file.parentFile?.name == "imported") "imported" else "published"
                val relPath = "retrotok/videos/$folder/${file.name}"
                if (relPath !in trackedPaths && file.length() > 32L) {
                    val info = videoInspector.inspectVideoFile(file)
                    if (info.isValidPlayable || videoInspector.isStandardMp4Header(file)) {
                        val recId = file.nameWithoutExtension
                            .removePrefix("pub_")
                            .removePrefix("imp_")
                            .ifBlank { "rec-${UUID.randomUUID().toString().take(8)}" }
                        val thumbPath = thumbnailGenerator.generateThumbnail(file, recId)
                        val now = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                        val recoveredEntity = VideoEntity(
                            videoId = recId,
                            nickname = "RetroDancer",
                            caption = "Recovered event video #RetroTok",
                            hashtags = "#RetroTok #Recovered",
                            localVideoPath = relPath,
                            thumbnailPath = thumbPath,
                            soundtrackTitle = "Event Soundtrack",
                            soundtrackArtist = "RetroTok",
                            soundtrackId = null,
                            effectType = RetroEffectType.NONE.name,
                            duration = (info.durationMs / 1000L).toInt().coerceAtLeast(1),
                            resolutionWidth = info.width.takeIf { it > 0 } ?: 720,
                            resolutionHeight = info.height.takeIf { it > 0 } ?: 1280,
                            fileSize = file.length(),
                            createdAt = now,
                            updatedAt = System.currentTimeMillis(),
                            isFavorite = false,
                            likeCount = 0,
                            isLiked = false,
                            isFeatured = false,
                            publicationStatus = if (folder == "imported") STATUS_IMPORTED else STATUS_PUBLISHED
                        )
                        videoDao.insertVideo(recoveredEntity)
                        recoveredFiles++
                    }
                }
            }

            // 3. Count orphaned thumbnails (don't auto-delete unless admin triggers cleanup)
            val referencedThumbs = videoDao.getAllVideosOnce().mapNotNull { it.thumbnailPath }.toSet()
            val orphanCount = storageManager.thumbnailsDir.listFiles()?.count { thumbFile ->
                val rel = "retrotok/videos/thumbnails/${thumbFile.name}"
                thumbFile.isFile && rel !in referencedThumbs && thumbFile.absolutePath !in referencedThumbs
            } ?: 0

            ReconciliationReport(
                recoveredOrphanFiles = recoveredFiles,
                flaggedMissingRecords = flaggedMissing,
                regeneratedThumbnails = regeneratedThumbs,
                orphanedThumbnailsCount = orphanCount
            )
        }
    }

    override suspend fun purgeOrphanedThumbnails(): Int {
        val referenced = videoDao.getAllVideosOnce().mapNotNull { it.thumbnailPath }.toSet()
        return storageManager.purgeOrphanedThumbnails(referenced)
    }

    private fun VideoEntity.toVideoItem(
        checkFileHealth: Boolean,
        cloudUploadSummary: CloudUploadSummary? = null
    ): VideoItem {
        val effect = RetroEffectType.values().firstOrNull { it.name == effectType } ?: RetroEffectType.NONE
        val healthy = if (checkFileHealth) {
            val file = if (localVideoPath.isNotBlank()) storageManager.getAbsoluteFile(localVideoPath) else null
            file != null && file.exists() && file.length() > 32L && publicationStatus != STATUS_MISSING_FILE
        } else {
            publicationStatus != STATUS_MISSING_FILE
        }

        return VideoItem(
            id = videoId,
            creatorNickname = nickname,
            caption = caption,
            song = SongItem(
                id = soundtrackId ?: "",
                title = soundtrackTitle,
                artist = soundtrackArtist,
                album = "",
                releaseYear = null,
                decade = RetroDecade.DECADE_UNKNOWN,
                genre = "Retro",
                durationSeconds = duration
            ),
            effect = effect,
            durationSeconds = duration,
            likeCount = likeCount,
            favoriteCount = if (isFavorite) 1 else 0,
            isLiked = isLiked,
            isFavorited = isFavorite,
            isFeatured = isFeatured,
            createdAt = createdAt,
            localVideoPath = localVideoPath,
            hashtags = hashtags,
            thumbnailPath = thumbnailPath,
            resolutionWidth = resolutionWidth,
            resolutionHeight = resolutionHeight,
            fileSizeBytes = fileSize,
            updatedAt = updatedAt,
            publicationStatus = publicationStatus,
            isFileHealthy = healthy,
            moderationStatus = moderationStatus,
            moderatedAt = moderatedAt,
            moderationReason = moderationReason,
            cloudUploadSummary = cloudUploadSummary
        )
    }
}

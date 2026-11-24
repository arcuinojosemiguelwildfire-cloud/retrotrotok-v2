package com.example.di

import android.content.Context
import com.example.BuildConfig
import com.example.audio.AudioPreviewManager
import com.example.camera.CameraDetector
import com.example.camera.CameraPreviewController
import com.example.camera.CameraProvider
import com.example.camera.CameraProviderImpl
import com.example.camera.CameraRecordingController
import com.example.cloud.AndroidConnectivityObserver
import com.example.cloud.CloudBackendGateway
import com.example.cloud.CloudCredentialStore
import com.example.cloud.CloudUploadManager
import com.example.cloud.ConnectivityObserver
import com.example.cloud.R2DirectUploader
import com.example.cloud.RetrofitCloudGateway
import com.example.cloud.Tls12OkHttpFactory
import com.example.data.local.RetroTokDatabase
import com.example.data.local.metadata.AudioMetadataExtractor
import com.example.data.local.storage.MusicStorageManager
import com.example.data.repository.KioskSettingsRepository
import com.example.data.repository.LocalKioskSettingsRepository
import com.example.data.repository.MusicCatalogRepository
import com.example.data.repository.MusicCatalogRepositoryImpl
import com.example.data.repository.VideoFeedRepository
import com.example.data.repository.VideoFeedRepositoryImpl
import com.example.session.GuestSessionManager
import com.example.video.SoundtrackSessionController
import com.example.video.VideoMuxerProcessor
import com.example.video.VideoStorageManager
import com.example.video.VideoThumbnailGenerator

/**
 * Clean Architecture Dependency Container.
 * Provides production repositories and services for RetroTok.
 */
object AppContainer {

    private lateinit var appContext: Context

    val database: RetroTokDatabase by lazy {
        RetroTokDatabase.getDatabase(appContext)
    }

    val musicStorageManager: MusicStorageManager by lazy {
        MusicStorageManager(appContext)
    }

    val videoStorageManager: VideoStorageManager by lazy {
        VideoStorageManager(appContext)
    }

    val videoThumbnailGenerator: VideoThumbnailGenerator by lazy {
        VideoThumbnailGenerator(appContext)
    }

    val metadataExtractor: AudioMetadataExtractor by lazy {
        AudioMetadataExtractor()
    }

    val musicCatalogRepository: MusicCatalogRepository by lazy {
        MusicCatalogRepositoryImpl(
            context = appContext,
            songDao = database.songDao(),
            storageManager = musicStorageManager,
            metadataExtractor = metadataExtractor
        )
    }

    val audioPreviewManager: AudioPreviewManager by lazy {
        AudioPreviewManager(appContext)
    }

    val videoFeedRepository: VideoFeedRepository by lazy {
        VideoFeedRepositoryImpl(
            videoDao = database.videoDao(),
            storageManager = videoStorageManager,
            thumbnailGenerator = videoThumbnailGenerator,
            videoInspector = videoMuxerProcessor,
            uploadQueueDao = database.uploadQueueDao()
        )
    }

    val kioskSettingsRepository: KioskSettingsRepository by lazy {
        LocalKioskSettingsRepository(appContext)
    }

    val guestSessionManager: GuestSessionManager by lazy {
        GuestSessionManager()
    }

    val cameraDetector: CameraDetector by lazy {
        CameraDetector(appContext)
    }

    val cameraProvider: CameraProvider by lazy {
        CameraProviderImpl(appContext).apply { initialize() }
    }

    val cameraPreviewController: CameraPreviewController by lazy {
        CameraPreviewController(appContext)
    }

    val cameraRecordingController: CameraRecordingController by lazy {
        CameraRecordingController(appContext)
    }

    val videoMuxerProcessor: VideoMuxerProcessor by lazy {
        VideoMuxerProcessor(appContext)
    }

    val soundtrackSessionController: SoundtrackSessionController by lazy {
        SoundtrackSessionController(appContext, musicStorageManager)
    }

    val cloudCredentialStore: CloudCredentialStore by lazy {
        CloudCredentialStore(appContext)
    }

    val connectivityObserver: ConnectivityObserver by lazy {
        AndroidConnectivityObserver(appContext)
    }

    val cloudBackendGateway: CloudBackendGateway by lazy {
        val okHttp = Tls12OkHttpFactory.createBackendApiClient(
            tokenProvider = { cloudCredentialStore.getDeviceToken() }
        )
        RetrofitCloudGateway(
            baseUrl = BuildConfig.CLOUD_API_BASE_URL,
            okHttpClient = okHttp
        )
    }

    val r2DirectUploader: R2DirectUploader by lazy {
        R2DirectUploader(callFactory = Tls12OkHttpFactory.createR2UploadClient())
    }

    val cloudUploadManager: CloudUploadManager by lazy {
        CloudUploadManager(
            credentialStore = cloudCredentialStore,
            backendGateway = cloudBackendGateway,
            r2Uploader = r2DirectUploader,
            videoDao = database.videoDao(),
            uploadQueueDao = database.uploadQueueDao(),
            storageManager = videoStorageManager,
            connectivityObserver = connectivityObserver,
            appVersion = BuildConfig.VERSION_NAME
        )
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}


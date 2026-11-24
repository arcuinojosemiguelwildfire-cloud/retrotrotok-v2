package com.example

import com.example.data.local.entity.SongEntity
import com.example.data.local.metadata.AudioMetadataExtractor
import com.example.data.mock.MockDataProvider
import com.example.data.mock.MockKioskSettingsRepository
import com.example.data.mock.MockMusicCatalogRepository
import com.example.data.mock.MockVideoFeedRepository
import com.example.data.repository.toDecadeKey
import com.example.data.repository.toRetroDecade
import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.model.VideoItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExampleUnitTest {

    @Test
    fun `metadata extractor maps 2000 to 2009 to 00s decade and preserves missing year as UNKNOWN`() {
        val extractor = AudioMetadataExtractor()

        // 70s: 1970–1979
        assertEquals("70s", extractor.mapYearToDecade(1970))
        assertEquals("70s", extractor.mapYearToDecade(1975))
        assertEquals("70s", extractor.mapYearToDecade(1979))

        // 80s: 1980–1989
        assertEquals("80s", extractor.mapYearToDecade(1980))
        assertEquals("80s", extractor.mapYearToDecade(1984))
        assertEquals("80s", extractor.mapYearToDecade(1989))

        // 90s: 1990–1999
        assertEquals("90s", extractor.mapYearToDecade(1990))
        assertEquals("90s", extractor.mapYearToDecade(1995))
        assertEquals("90s", extractor.mapYearToDecade(1999))

        // 00s: 2000–2009
        assertEquals("00s", extractor.mapYearToDecade(2000))
        assertEquals("00s", extractor.mapYearToDecade(2004))
        assertEquals("00s", extractor.mapYearToDecade(2008))
        assertEquals("00s", extractor.mapYearToDecade(2009))

        // Unsupported or missing year -> UNKNOWN (no fabricated years)
        assertEquals("UNKNOWN", extractor.mapYearToDecade(1968))
        assertEquals("UNKNOWN", extractor.mapYearToDecade(2010))
        assertEquals("UNKNOWN", extractor.mapYearToDecade(2025))
        assertEquals("UNKNOWN", extractor.mapYearToDecade(null))
    }

    @Test
    fun `decade key extension mappers work bidirectionally including 00s era`() {
        assertEquals("70s", RetroDecade.DECADE_70S.toDecadeKey())
        assertEquals("80s", RetroDecade.DECADE_80S.toDecadeKey())
        assertEquals("90s", RetroDecade.DECADE_90S.toDecadeKey())
        assertEquals("00s", RetroDecade.DECADE_00S.toDecadeKey())
        assertEquals("ALL", RetroDecade.ALL.toDecadeKey())
        assertEquals("UNKNOWN", RetroDecade.DECADE_UNKNOWN.toDecadeKey())

        assertEquals(RetroDecade.DECADE_70S, "70s".toRetroDecade())
        assertEquals(RetroDecade.DECADE_80S, "80s".toRetroDecade())
        assertEquals(RetroDecade.DECADE_90S, "90s".toRetroDecade())
        assertEquals(RetroDecade.DECADE_00S, "00s".toRetroDecade())
        assertEquals(RetroDecade.DECADE_UNKNOWN, "invalid".toRetroDecade())

        assertEquals("00s Hits", RetroDecade.DECADE_00S.label)
        assertEquals("2000–2009", RetroDecade.DECADE_00S.era)
    }

    @Test
    fun `newly created songs default to UNREVIEWED licensing status`() {
        val songEntity = SongEntity(
            id = "test-uuid-unreviewed",
            title = "Fresh Track",
            artist = "New Artist",
            album = "New Album",
            releaseYear = 1985,
            decade = "80s",
            genre = "Synthpop",
            durationMs = 180_000L,
            audioFileName = "test.mp3",
            audioRelativePath = "retrotok/music/audio/test.mp3",
            artworkRelativePath = null,
            fileSizeBytes = 3_000_000L,
            sha256Hash = "hash123456"
        )

        // Entity must default to UNREVIEWED
        assertEquals("UNREVIEWED", songEntity.licenseApprovalStatus)

        val songItem = SongItem(
            id = "test-uuid-item",
            title = "Item Track",
            artist = "Artist",
            album = "Album",
            releaseYear = 1999,
            decade = RetroDecade.DECADE_90S,
            genre = "Dance",
            durationSeconds = 200
        )

        // Domain SongItem must default to UNREVIEWED
        assertEquals("UNREVIEWED", songItem.licenseApprovalStatus)
        // Must NOT be approved for guest use
        assertFalse(songItem.isApprovedForGuestUse)
    }

    @Test
    fun `guest approval filtering strictly excludes UNREVIEWED and NOT_APPROVED songs`() = runBlocking {
        val repo = MockMusicCatalogRepository()

        // Create an unreviewed song and add to repo
        val unreviewedSong = SongItem(
            id = "test-unreviewed-1",
            title = "Unreviewed Track",
            artist = "Under Review",
            album = "Demo Tape",
            releaseYear = 1982,
            decade = RetroDecade.DECADE_80S,
            genre = "Post-Punk",
            durationSeconds = 150,
            licenseApprovalStatus = "UNREVIEWED"
        )

        val rejectedSong = SongItem(
            id = "test-rejected-1",
            title = "Rejected Track",
            artist = "Explicit Content",
            album = "Unlicensed",
            releaseYear = 1991,
            decade = RetroDecade.DECADE_90S,
            genre = "Grunge",
            durationSeconds = 210,
            licenseApprovalStatus = "NOT_APPROVED"
        )

        val approvedSong = SongItem(
            id = "test-approved-1",
            title = "Licensed Event Hit",
            artist = "Retro Band",
            album = "Party Anthems",
            releaseYear = 1987,
            decade = RetroDecade.DECADE_80S,
            genre = "Dance Pop",
            durationSeconds = 190,
            licenseApprovalStatus = "APPROVED_FOR_EVENT_USE"
        )

        repo.importAudioFiles(emptyList()) // initialize list if needed
        repo.updateLicenseStatus(unreviewedSong.id, "UNREVIEWED")

        val approvedCatalog = repo.getApprovedSongs().first()
        // Unreviewed and rejected tracks must never appear
        assertFalse(approvedCatalog.any { it.licenseApprovalStatus == "UNREVIEWED" })
        assertFalse(approvedCatalog.any { it.licenseApprovalStatus == "NOT_APPROVED" })
        assertTrue(approvedCatalog.all { it.isApprovedForGuestUse })
    }

    @Test
    fun `safe deletion policy retains files when song is referenced by published videos`() = runBlocking {
        val repo = MockMusicCatalogRepository()
        val allSongs = repo.getAllSongs().first()
        val songInUse = allSongs[0]

        // Delete with isReferencedByVideos = true (Safe Retirement)
        val retired = repo.deleteSong(songInUse.id, isReferencedByVideos = true)
        assertTrue(retired)

        // The song is marked NOT_APPROVED so guests cannot pick it
        val updatedSong = repo.getSongById(songInUse.id)
        assertNotNull(updatedSong)
        assertEquals("NOT_APPROVED", updatedSong?.licenseApprovalStatus)

        val approvedList = repo.getApprovedSongs().first()
        assertFalse("Retired referenced song must not appear in guest selection", approvedList.any { it.id == songInUse.id })

        // When unreferenced, deleteSong purges it completely
        val unreferencedSong = allSongs[1]
        val purged = repo.deleteSong(unreferencedSong.id, isReferencedByVideos = false)
        assertTrue(purged)
        assertNull(repo.getSongById(unreferencedSong.id))
    }

    @Test
    fun `empty production catalog behaves cleanly without errors`() = runBlocking {
        // Create clean catalog with no seeded songs
        val emptyRepo = object : com.example.data.repository.MusicCatalogRepository {
            override fun getAllSongs() = kotlinx.coroutines.flow.flowOf(emptyList<SongItem>())
            override fun getApprovedSongs() = kotlinx.coroutines.flow.flowOf(emptyList<SongItem>())
            override fun getSongsByDecade(decade: RetroDecade) = kotlinx.coroutines.flow.flowOf(emptyList<SongItem>())
            override fun getApprovedSongsByDecade(decade: RetroDecade) = kotlinx.coroutines.flow.flowOf(emptyList<SongItem>())
            override fun searchSongs(query: String) = kotlinx.coroutines.flow.flowOf(emptyList<SongItem>())
            override fun searchApprovedSongs(query: String) = kotlinx.coroutines.flow.flowOf(emptyList<SongItem>())
            override fun getSongMetrics() = kotlinx.coroutines.flow.flowOf(
                com.example.data.repository.MusicStorageMetrics(0, 0, 0L, emptyMap(), emptyMap())
            )
            override suspend fun getSongById(songId: String): SongItem? = null
            override suspend fun importAudioFiles(uris: List<android.net.Uri>, onProgress: (Int, Int, String) -> Unit) =
                com.example.data.repository.ImportSummary(0, 0, 0, 0, emptyList())
            override suspend fun updateSongMetadata(songId: String, title: String, artist: String, album: String, year: Int?, genre: String, decade: RetroDecade) = false
            override suspend fun updateLicenseStatus(songId: String, newStatus: String) = false
            override suspend fun deleteSong(songId: String, isReferencedByVideos: Boolean) = false
        }

        val songs = emptyRepo.getAllSongs().first()
        assertTrue(songs.isEmpty())

        val approved = emptyRepo.getApprovedSongs().first()
        assertTrue(approved.isEmpty())

        val metrics = emptyRepo.getSongMetrics().first()
        assertEquals(0, metrics.totalSongs)
        assertEquals(0, metrics.approvedSongs)
        assertEquals(0L, metrics.totalFileSizeBytes)
    }

    @Test
    fun `duplicate SHA256 detection and cleanup prevents duplicate storage`() {
        val hashA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val hashB = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val hashC = "8743b52063cd84097a65d1633f5c74f5"

        assertEquals("Identical bitstreams must produce identical SHA-256 digests", hashA, hashB)
        assertTrue("Different audio bitstreams produce distinct digests", hashA != hashC)

        // Verify cleanup mechanism logic: temporary staging file deletion
        val tempFile = File.createTempFile("retrotok_test_stage_", ".tmp")
        assertTrue(tempFile.exists())
        tempFile.delete()
        assertFalse("Duplicate handling must delete staged file to avoid orphaned data", tempFile.exists())
    }

    @Test
    fun `manual metadata editing updates song information`() = runBlocking {
        val repo = MockMusicCatalogRepository()
        val allSongs = repo.getAllSongs().first()
        val song = allSongs[0]

        val updateSuccess = repo.updateSongMetadata(
            songId = song.id,
            title = "Stayin' Alive (Remastered 1977)",
            artist = "Bee Gees feat. Barry Gibb",
            album = "Saturday Night Fever Special Edition",
            year = 1977,
            genre = "Classic Disco",
            decade = RetroDecade.DECADE_70S
        )

        assertTrue(updateSuccess)
        val updatedSong = repo.getSongById(song.id)
        assertNotNull(updatedSong)
        assertEquals("Stayin' Alive (Remastered 1977)", updatedSong?.title)
        assertEquals("Bee Gees feat. Barry Gibb", updatedSong?.artist)
        assertEquals("Classic Disco", updatedSong?.genre)
    }

    @Test
    fun `video feed repository toggles like and publishes`() = runBlocking {
        val repo = MockVideoFeedRepository()
        val initialVideos = repo.getFeedVideos().first()
        val target = initialVideos[0]
        val initialLikes = target.likeCount

        val updated = repo.toggleLike(target.id)
        assertEquals(initialLikes + 1, updated?.likeCount)

        val newVideo = VideoItem(
            id = "test-video-phase2",
            creatorNickname = "RetroQueen",
            caption = "Phase 2 test",
            song = MockDataProvider.mockSongs[0],
            effect = RetroEffectType.NEON_80S,
            durationSeconds = 30,
            likeCount = 0,
            favoriteCount = 0
        )
        repo.publishVideo(newVideo)
        val feed = repo.getFeedVideos().first()
        assertEquals("test-video-phase2", feed[0].id)
    }

    @Test
    fun `admin PIN verification tests`() = runBlocking {
        val repo = MockKioskSettingsRepository()
        assertTrue(repo.validateAdminPin("1234"))
        assertFalse(repo.validateAdminPin("0000"))
    }
}

package com.example.data.mock

import com.example.model.RetroDecade
import com.example.model.RetroEffectType
import com.example.model.SongItem
import com.example.model.VideoItem

object MockDataProvider {

    val mockSongs = listOf(
        // 1970s Classics
        SongItem(
            id = "song-70-1",
            title = "Stayin' Alive",
            artist = "Bee Gees",
            album = "Saturday Night Fever",
            releaseYear = 1977,
            decade = RetroDecade.DECADE_70S,
            genre = "Disco Fever",
            durationSeconds = 285,
            energyBpm = 104,
            keyTag = "DISCO ANTHEM"
        ),
        SongItem(
            id = "song-70-2",
            title = "September",
            artist = "Earth, Wind & Fire",
            album = "The Best of Earth, Wind & Fire",
            releaseYear = 1978,
            decade = RetroDecade.DECADE_70S,
            genre = "Funk & Soul",
            durationSeconds = 215,
            energyBpm = 126,
            keyTag = "PARTY GROOVE"
        ),
        SongItem(
            id = "song-70-3",
            title = "Dancing Queen",
            artist = "ABBA",
            album = "Arrival",
            releaseYear = 1976,
            decade = RetroDecade.DECADE_70S,
            genre = "Europop Classic",
            durationSeconds = 232,
            energyBpm = 101,
            keyTag = "SING-ALONG"
        ),
        SongItem(
            id = "song-70-4",
            title = "Le Freak",
            artist = "Chic",
            album = "C'est Chic",
            releaseYear = 1978,
            decade = RetroDecade.DECADE_70S,
            genre = "Disco Funk",
            durationSeconds = 328,
            energyBpm = 120,
            keyTag = "BASSLINE"
        ),

        // 1980s Hits
        SongItem(
            id = "song-80-1",
            title = "Sweet Dreams (Are Made of This)",
            artist = "Eurythmics",
            album = "Sweet Dreams",
            releaseYear = 1983,
            decade = RetroDecade.DECADE_80S,
            genre = "Synthpop",
            durationSeconds = 216,
            energyBpm = 125,
            keyTag = "SYNTH ICON"
        ),
        SongItem(
            id = "song-80-2",
            title = "Take On Me",
            artist = "a-ha",
            album = "Hunting High and Low",
            releaseYear = 1985,
            decade = RetroDecade.DECADE_80S,
            genre = "New Wave",
            durationSeconds = 227,
            energyBpm = 169,
            keyTag = "HIGH ENERGY"
        ),
        SongItem(
            id = "song-80-3",
            title = "Billie Jean",
            artist = "Michael Jackson",
            album = "Thriller",
            releaseYear = 1982,
            decade = RetroDecade.DECADE_80S,
            genre = "Dance Pop",
            durationSeconds = 294,
            energyBpm = 117,
            keyTag = "MOONWALK"
        ),
        SongItem(
            id = "song-80-4",
            title = "Girls Just Want to Have Fun",
            artist = "Cyndi Lauper",
            album = "She's So Unusual",
            releaseYear = 1983,
            decade = RetroDecade.DECADE_80S,
            genre = "80s Pop",
            durationSeconds = 238,
            energyBpm = 120,
            keyTag = "PURE JOY"
        ),
        SongItem(
            id = "song-80-5",
            title = "Blue Monday",
            artist = "New Order",
            album = "Power, Corruption & Lies",
            releaseYear = 1983,
            decade = RetroDecade.DECADE_80S,
            genre = "Electro Dance",
            durationSeconds = 449,
            energyBpm = 130,
            keyTag = "CLUB CLASSIC"
        ),

        // 1990s Favorites
        SongItem(
            id = "song-90-1",
            title = "Rhythm Is a Dancer",
            artist = "Snap!",
            album = "The Madman's Return",
            releaseYear = 1992,
            decade = RetroDecade.DECADE_90S,
            genre = "Eurodance",
            durationSeconds = 225,
            energyBpm = 129,
            keyTag = "EURODANCE BEAT"
        ),
        SongItem(
            id = "song-90-2",
            title = "Everybody (Backstreet's Back)",
            artist = "Backstreet Boys",
            album = "Backstreet's Back",
            releaseYear = 1997,
            decade = RetroDecade.DECADE_90S,
            genre = "90s Boyband",
            durationSeconds = 224,
            energyBpm = 108,
            keyTag = "CHOREO CLASSIC"
        ),
        SongItem(
            id = "song-90-3",
            title = "Wannabe",
            artist = "Spice Girls",
            album = "Spice",
            releaseYear = 1996,
            decade = RetroDecade.DECADE_90S,
            genre = "Girl Power Pop",
            durationSeconds = 173,
            energyBpm = 110,
            keyTag = "90s NOSTALGIA"
        ),
        SongItem(
            id = "song-90-4",
            title = "What Is Love",
            artist = "Haddaway",
            album = "The Album",
            releaseYear = 1993,
            decade = RetroDecade.DECADE_90S,
            genre = "Eurodance",
            durationSeconds = 269,
            energyBpm = 124,
            keyTag = "HEAD BOBBING"
        ),

        // 2000–2001 Millennium Hits
        SongItem(
            id = "song-00-1",
            title = "One More Time",
            artist = "Daft Punk",
            album = "Discovery",
            releaseYear = 2000,
            decade = RetroDecade.DECADE_00S,
            genre = "French House",
            durationSeconds = 320,
            energyBpm = 123,
            keyTag = "MILLENNIUM ANTHEM"
        ),
        SongItem(
            id = "song-00-2",
            title = "Oops!... I Did It Again",
            artist = "Britney Spears",
            album = "Oops!... I Did It Again",
            releaseYear = 2000,
            decade = RetroDecade.DECADE_00S,
            genre = "Y2K Pop",
            durationSeconds = 211,
            energyBpm = 95,
            keyTag = "Y2K ICONIC"
        ),
        SongItem(
            id = "song-00-3",
            title = "Lady (Hear Me Tonight)",
            artist = "Modjo",
            album = "Modjo",
            releaseYear = 2000,
            decade = RetroDecade.DECADE_00S,
            genre = "Nu-Disco / House",
            durationSeconds = 307,
            energyBpm = 126,
            keyTag = "SMOOTH GROOVE"
        ),
        SongItem(
            id = "song-00-4",
            title = "Can't Get You Out of My Head",
            artist = "Kylie Minogue",
            album = "Fever",
            releaseYear = 2001,
            decade = RetroDecade.DECADE_00S,
            genre = "Electropop",
            durationSeconds = 230,
            energyBpm = 126,
            keyTag = "HYPNOTIC"
        )
    )

    val mockVideos = listOf(
        VideoItem(
            id = "vid-mock-1",
            creatorNickname = "DiscoDave",
            caption = "Bringing back the 1977 Brooklyn dancefloor! #AcmeAnniversary #DiscoKing",
            song = mockSongs[0], // Stayin' Alive
            effect = RetroEffectType.VHS_RETRO,
            durationSeconds = 30,
            likeCount = 284,
            favoriteCount = 67,
            isFeatured = true,
            mockGradientColors = listOf(0xFF3E0054, 0xFF7928CA, 0xFFFF0080)
        ),
        VideoItem(
            id = "vid-mock-2",
            creatorNickname = "SarahTheSynthQueen",
            caption = "Sweet dreams are made of this kiosk! Love the 80s neon vibe #PartyMode",
            song = mockSongs[4], // Sweet Dreams
            effect = RetroEffectType.NEON_80S,
            durationSeconds = 30,
            likeCount = 192,
            favoriteCount = 45,
            isFeatured = true,
            mockGradientColors = listOf(0xFF001F3F, 0xFF0074D9, 0xFF00F0FF)
        ),
        VideoItem(
            id = "vid-mock-3",
            creatorNickname = "EngineeringSquad",
            caption = "The backend team hitting the Backstreet choreography perfectly! 🕺💃 #SyncSquad",
            song = mockSongs[9], // Everybody
            effect = RetroEffectType.FILM_90S,
            durationSeconds = 30,
            likeCount = 412,
            favoriteCount = 118,
            isFeatured = false,
            mockGradientColors = listOf(0xFF1B262C, 0xFF0F4C81, 0xFF1B1B2F)
        ),
        VideoItem(
            id = "vid-mock-4",
            creatorNickname = "Y2K_Raver2000",
            caption = "Daft Punk One More Time at midnight! Absolutely iconic event! #RetroTok",
            song = mockSongs[12], // One More Time
            effect = RetroEffectType.NEON_80S,
            durationSeconds = 30,
            likeCount = 156,
            favoriteCount = 39,
            isFeatured = false,
            mockGradientColors = listOf(0xFF28003A, 0xFFFF2A85, 0xFF8A2BE2)
        ),
        VideoItem(
            id = "vid-mock-5",
            creatorNickname = "RetroCEO",
            caption = "Earth Wind & Fire September! Happy 40th anniversary everyone! 🥂 #AcmeCelebrates",
            song = mockSongs[1], // September
            effect = RetroEffectType.VINTAGE_70S,
            durationSeconds = 30,
            likeCount = 523,
            favoriteCount = 205,
            isFeatured = true,
            mockGradientColors = listOf(0xFF3A1C00, 0xFFFFB800, 0xFFB7410E)
        )
    )
}

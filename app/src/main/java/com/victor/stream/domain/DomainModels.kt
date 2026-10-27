package com.victor.stream.domain

import com.victor.stream.core.FailReason
import kotlinx.coroutines.flow.Flow

enum class MediaKind {
    MOVIE,
    TV,
    ANIME
}

enum class IdType {
    TMDB,
    ANILIST,
    MAL
}

enum class StreamMimeType {
    HLS,
    DASH,
    MP4,
    UNKNOWN
}

enum class PlayOrigin {
    USER_PICK,
    RESUME,
    AUTO_NEXT,
    MANUAL_SERVER,
    FAILOVER
}

data class BridgedIds(
    val tmdbId: Int? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val romajiTitle: String? = null,
    val totalEpisodes: Int? = null
)

data class SeasonInfo(
    val seasonNumber: Int,
    val name: String,
    val posterUrl: String
)

data class EpisodeRef(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String = "Episode $episodeNumber",
    val overview: String = "",
    val stillUrl: String = ""
)

data class MediaItemModel(
    val id: Int,
    val kind: MediaKind,
    val title: String,
    val romajiTitle: String? = null,
    val overview: String,
    val posterUrl: String,
    val rating: Double = 8.0,
    val releaseYear: String = "2024",
    val genreIds: List<Int> = emptyList(),
    val originalLanguage: String = "en",
    val tmdbId: Int? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val totalEpisodes: Int = 24,
    val animeFormat: String = "TV",
    val seasons: List<SeasonInfo> = emptyList()
) {
    val isSeries: Boolean
        get() = kind == MediaKind.TV || (kind == MediaKind.ANIME && animeFormat != "MOVIE")

    val isAnime: Boolean
        get() = kind == MediaKind.ANIME || id >= 100_000_000

    val badgeLabel: String
        get() = when {
            isAnime -> "SUB"
            isSeries -> "SERIES"
            else -> "MOVIE"
        }

    val typeHeaderLabel: String
        get() = when {
            isAnime && isSeries -> "ANIME ($animeFormat • SUB)"
            isAnime && !isSeries -> "ANIME MOVIE (SUB)"
            isSeries -> "TV SERIES"
            else -> "MOVIE"
        }
}

data class SubtitleTrack(
    val url: String,
    val label: String,
    val language: String = "en",
    val format: String = "srt"
)

data class SkipSegmentModel(
    val startMs: Long,
    val endMs: Long,
    val label: String,
    // true when we had no real intro/credits data and estimated the window.
    // Guessed segments offer a manual "Skip" button but are never auto-skipped.
    val isGuessed: Boolean = false
)

data class ServerCandidate(
    val providerId: String,
    val label: String,
    val embedUrl: String,
    val headers: Map<String, String>,
    val omitReferer: Boolean = false,
    val requiresPopup: Boolean = false,
    val isInstantTrusted: Boolean = false,
    val timeoutMs: Long = 8_000L,
    val hedgeDelayMs: Long = 3_000L
)

data class ResolvePlan(
    val media: MediaItemModel,
    val episode: EpisodeRef?,
    val candidates: List<ServerCandidate>,
    val preferredProviderId: String? = null
)

data class ResolvedStream(
    val streamUrl: String,
    val mimeType: StreamMimeType,
    val headers: Map<String, String>,
    val subtitles: List<SubtitleTrack>,
    val providerId: String,
    val serverLabel: String,
    val episode: EpisodeRef?,
    val capturedAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = System.currentTimeMillis() + 20 * 60_000L
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

data class PlaybackSession(
    val id: String,
    val media: MediaItemModel,
    val episode: EpisodeRef?,
    val plan: ResolvePlan,
    val stream: ResolvedStream,
    val startAtMs: Long,
    val origin: PlayOrigin,
    val failedProviderIds: Set<String> = emptySet()
)

data class WatchProgressEntry(
    val media: MediaItemModel,
    val positionMs: Long,
    val durationMs: Long,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val lastProviderId: String? = null
)

sealed interface ResolverEvent {
    data class Progress(val index: Int, val total: Int, val label: String) : ResolverEvent
    data class Captured(val stream: ResolvedStream) : ResolverEvent
    data class CandidateFailed(val providerId: String, val reason: FailReason) : ResolverEvent
    data class AllExhausted(val triedProviderIds: List<String>) : ResolverEvent
}

interface StreamResolver {
    fun resolve(plan: ResolvePlan, harvestAll: Boolean = false): Flow<ResolverEvent>
    fun cancelActive()
}
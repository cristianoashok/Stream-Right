package com.victor.stream.providers

import com.victor.stream.core.Logger
import com.victor.stream.domain.BridgedIds
import com.victor.stream.domain.EpisodeRef
import com.victor.stream.domain.IdType
import com.victor.stream.domain.MediaItemModel
import com.victor.stream.domain.MediaKind
import com.victor.stream.domain.ResolvePlan
import com.victor.stream.domain.ResolvedStream
import com.victor.stream.domain.ServerCandidate
import com.victor.stream.domain.SubtitleTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// ==========================================
// 1. HEADER PROFILES & PROVIDER DESCRIPTORS
// ==========================================

data class HeaderProfile(
    val referer: String? = null,
    val origin: String? = null,
    val userAgent: String? = null,
    val omitReferer: Boolean = false
)

enum class ProviderTrust {
    INSTANT,
    SETTLE,
    VERIFY
}

data class ProviderDescriptor(
    val id: String,
    val label: String,
    val supportedKinds: Set<MediaKind>,
    val idType: IdType,
    val movieUrlTemplate: String? = null,
    val seriesUrlTemplate: String? = null,
    val headerProfile: HeaderProfile,
    val requiresPopup: Boolean = false,
    val trust: ProviderTrust = ProviderTrust.SETTLE,
    val timeoutMs: Long = 8_000L,
    val hedgeDelayMs: Long = 3_000L,
    val baseReliability: Float = 0.85f
)

// ==========================================
// 2. DECLARATIVE PROVIDER REGISTRY
// ==========================================

class ProviderRegistry {
    private val runtimeScores = ConcurrentHashMap<String, Float>()
    @Volatile var lastWorkingGeneralProviderId: String = "vidlink"

    private val descriptors: List<ProviderDescriptor> = listOf(
        // --- MOVIE & TV PROVIDERS ---
        ProviderDescriptor(
            id = "embedwave",
            label = "EmbedWave (Instant • No Ads)",
            supportedKinds = setOf(MediaKind.MOVIE, MediaKind.TV),
            idType = IdType.TMDB,
            movieUrlTemplate = "https://embedwave.cc/embed/movie/{id}?autoplay=true",
            seriesUrlTemplate = "https://embedwave.cc/embed/tv/{id}/{s}/{e}?autoplay=true",
            headerProfile = HeaderProfile(referer = null, origin = null, omitReferer = true),
            requiresPopup = false,
            trust = ProviderTrust.INSTANT,
            timeoutMs = 7_500L,
            hedgeDelayMs = 2_800L,
            baseReliability = 0.95f
        ),
        ProviderDescriptor(
            id = "vidlink",
            label = "VidLink Pro (Instant HLS)",
            supportedKinds = setOf(MediaKind.MOVIE, MediaKind.TV),
            idType = IdType.TMDB,
            movieUrlTemplate = "https://vidlink.pro/movie/{id}?autoplay=true",
            seriesUrlTemplate = "https://vidlink.pro/tv/{id}/{s}/{e}?autoplay=true&fallback=true",
            headerProfile = HeaderProfile("https://vidlink.pro/", "https://vidlink.pro"),
            requiresPopup = false,
            trust = ProviderTrust.INSTANT,
            timeoutMs = 8_000L,
            hedgeDelayMs = 3_000L,
            baseReliability = 0.94f
        ),
        ProviderDescriptor(
            id = "vidfast",
            label = "VidFast (Instant HD)",
            supportedKinds = setOf(MediaKind.MOVIE, MediaKind.TV),
            idType = IdType.TMDB,
            movieUrlTemplate = "https://vidfast.pro/movie/{id}?autoPlay=true",
            seriesUrlTemplate = "https://vidfast.pro/tv/{id}/{s}/{e}?autoPlay=true",
            headerProfile = HeaderProfile("https://vidfast.pro/", "https://vidfast.pro"),
            requiresPopup = true,
            trust = ProviderTrust.SETTLE,
            timeoutMs = 8_000L,
            hedgeDelayMs = 3_000L,
            baseReliability = 0.90f
        ),
        ProviderDescriptor(
            id = "videasy",
            label = "Videasy (TMDB Direct)",
            supportedKinds = setOf(MediaKind.MOVIE, MediaKind.TV),
            idType = IdType.TMDB,
            movieUrlTemplate = "https://player.videasy.net/movie/{id}?autoplay=true",
            seriesUrlTemplate = "https://player.videasy.net/tv/{id}/{s}/{e}?autoplay=true",
            headerProfile = HeaderProfile("https://player.videasy.net/", "https://player.videasy.net"),
            requiresPopup = true,
            trust = ProviderTrust.SETTLE,
            timeoutMs = 8_000L,
            hedgeDelayMs = 3_000L,
            baseReliability = 0.88f
        ),
        ProviderDescriptor(
            id = "embed_su",
            label = "Embed.su (Filemoon / Viper)",
            supportedKinds = setOf(MediaKind.MOVIE, MediaKind.TV),
            idType = IdType.TMDB,
            movieUrlTemplate = "https://embed.su/embed/movie/{id}?autoplay=true",
            seriesUrlTemplate = "https://embed.su/embed/tv/{id}/{s}/{e}?autoplay=true",
            headerProfile = HeaderProfile("https://embed.su/", "https://embed.su"),
            requiresPopup = true,
            trust = ProviderTrust.SETTLE,
            timeoutMs = 8_500L,
            hedgeDelayMs = 3_200L,
            baseReliability = 0.86f
        ),
        ProviderDescriptor(
            id = "vidora",
            label = "Vidora (Fast Direct)",
            supportedKinds = setOf(MediaKind.MOVIE, MediaKind.TV),
            idType = IdType.TMDB,
            movieUrlTemplate = "https://vidora.su/movie/{id}?autoplay=true",
            seriesUrlTemplate = "https://vidora.su/tv/{id}/{s}/{e}?autoplay=true",
            headerProfile = HeaderProfile("https://vidora.su/", "https://vidora.su"),
            requiresPopup = true,
            trust = ProviderTrust.SETTLE,
            timeoutMs = 8_000L,
            hedgeDelayMs = 3_000L,
            baseReliability = 0.84f
        ),
        ProviderDescriptor(
            id = "vidsrc_cc",
            label = "VidSrc V2 (Multi-Server)",
            supportedKinds = setOf(MediaKind.MOVIE, MediaKind.TV),
            idType = IdType.TMDB,
            movieUrlTemplate = "https://vidsrc.cc/v2/embed/movie/{id}?autoPlay=true",
            seriesUrlTemplate = "https://vidsrc.cc/v2/embed/tv/{id}/{s}/{e}?autoPlay=true",
            headerProfile = HeaderProfile("https://vidsrc.cc/", "https://vidsrc.cc"),
            requiresPopup = true,
            trust = ProviderTrust.SETTLE,
            timeoutMs = 8_500L,
            hedgeDelayMs = 3_200L,
            baseReliability = 0.82f
        ),

        // --- ANIME (SUB) PROVIDERS ---
        ProviderDescriptor(
            id = "megaplay_ani_sub",
            label = "MegaPlay AniList (Sub)",
            supportedKinds = setOf(MediaKind.ANIME),
            idType = IdType.ANILIST,
            movieUrlTemplate = "https://megaplay.buzz/stream/ani/{id}/1/sub?autoplay=true",
            seriesUrlTemplate = "https://megaplay.buzz/stream/ani/{id}/{e}/sub?autoplay=true",
            headerProfile = HeaderProfile("https://megaplay.buzz/", "https://megaplay.buzz"),
            requiresPopup = false,
            trust = ProviderTrust.INSTANT,
            timeoutMs = 7_500L,
            hedgeDelayMs = 2_500L,
            baseReliability = 0.96f
        ),
        ProviderDescriptor(
            id = "megavid_ani_sub",
            label = "Megavid AniList (Sub)",
            supportedKinds = setOf(MediaKind.ANIME),
            idType = IdType.ANILIST,
            movieUrlTemplate = "https://megavid.buzz/stream/ani/{id}/1/sub?autoplay=true",
            seriesUrlTemplate = "https://megavid.buzz/stream/ani/{id}/{e}/sub?autoplay=true",
            headerProfile = HeaderProfile("https://megavid.buzz/", "https://megavid.buzz"),
            requiresPopup = false,
            trust = ProviderTrust.INSTANT,
            timeoutMs = 7_500L,
            hedgeDelayMs = 2_500L,
            baseReliability = 0.93f
        ),
        ProviderDescriptor(
            id = "animeplay_ani_sub",
            label = "AnimePlay AniList (Sub)",
            supportedKinds = setOf(MediaKind.ANIME),
            idType = IdType.ANILIST,
            movieUrlTemplate = "https://animeplay.cfd/stream/ani/{id}/1/sub?autoplay=true",
            seriesUrlTemplate = "https://animeplay.cfd/stream/ani/{id}/{e}/sub?autoplay=true",
            headerProfile = HeaderProfile("https://animeplay.cfd/", "https://animeplay.cfd"),
            requiresPopup = false,
            trust = ProviderTrust.INSTANT,
            timeoutMs = 7_500L,
            hedgeDelayMs = 2_500L,
            baseReliability = 0.91f
        ),
        ProviderDescriptor(
            id = "megaplay_mal_sub",
            label = "MegaPlay MAL (Sub)",
            supportedKinds = setOf(MediaKind.ANIME),
            idType = IdType.MAL,
            movieUrlTemplate = "https://megaplay.buzz/stream/mal/{id}/1/sub?autoplay=true",
            seriesUrlTemplate = "https://megaplay.buzz/stream/mal/{id}/{e}/sub?autoplay=true",
            headerProfile = HeaderProfile("https://megaplay.buzz/", "https://megaplay.buzz"),
            requiresPopup = false,
            trust = ProviderTrust.INSTANT,
            timeoutMs = 7_500L,
            hedgeDelayMs = 2_500L,
            baseReliability = 0.90f
        ),
        ProviderDescriptor(
            id = "megavid_mal_sub",
            label = "Megavid MAL (Sub)",
            supportedKinds = setOf(MediaKind.ANIME),
            idType = IdType.MAL,
            movieUrlTemplate = "https://megavid.buzz/stream/mal/{id}/1/sub?autoplay=true",
            seriesUrlTemplate = "https://megavid.buzz/stream/mal/{id}/{e}/sub?autoplay=true",
            headerProfile = HeaderProfile("https://megavid.buzz/", "https://megavid.buzz"),
            requiresPopup = false,
            trust = ProviderTrust.INSTANT,
            timeoutMs = 7_500L,
            hedgeDelayMs = 2_500L,
            baseReliability = 0.88f
        ),
        ProviderDescriptor(
            id = "animeplay_mal_sub",
            label = "AnimePlay MAL (Sub)",
            supportedKinds = setOf(MediaKind.ANIME),
            idType = IdType.MAL,
            movieUrlTemplate = "https://animeplay.cfd/stream/mal/{id}/1/sub?autoplay=true",
            seriesUrlTemplate = "https://animeplay.cfd/stream/mal/{id}/{e}/sub?autoplay=true",
            headerProfile = HeaderProfile("https://animeplay.cfd/", "https://animeplay.cfd"),
            requiresPopup = false,
            trust = ProviderTrust.INSTANT,
            timeoutMs = 7_500L,
            hedgeDelayMs = 2_500L,
            baseReliability = 0.87f
        ),
        ProviderDescriptor(
            id = "supaplay_ani_sub",
            label = "SupaPlay AniList (Sub)",
            supportedKinds = setOf(MediaKind.ANIME),
            idType = IdType.ANILIST,
            movieUrlTemplate = "https://supaplay.fun/stream/ani/{id}/1/sub?autoplay=true",
            seriesUrlTemplate = "https://supaplay.fun/stream/ani/{id}/{e}/sub?autoplay=true",
            headerProfile = HeaderProfile("https://supaplay.fun/", "https://supaplay.fun"),
            requiresPopup = false,
            trust = ProviderTrust.INSTANT,
            timeoutMs = 7_500L,
            hedgeDelayMs = 2_500L,
            baseReliability = 0.85f
        ),
        ProviderDescriptor(
            id = "vidlink_anime_fallback",
            label = "VidLink Anime Fallback (Sub)",
            supportedKinds = setOf(MediaKind.ANIME),
            idType = IdType.TMDB,
            movieUrlTemplate = "https://vidlink.pro/movie/{id}?autoplay=true",
            seriesUrlTemplate = "https://vidlink.pro/tv/{id}/{s}/{e}?autoplay=true&fallback=true",
            headerProfile = HeaderProfile("https://vidlink.pro/", "https://vidlink.pro"),
            requiresPopup = false,
            trust = ProviderTrust.INSTANT,
            timeoutMs = 8_000L,
            hedgeDelayMs = 3_000L,
            baseReliability = 0.83f
        )
    )

    fun getAll(): List<ProviderDescriptor> = descriptors

    fun getById(id: String): ProviderDescriptor? = descriptors.firstOrNull { it.id == id }

    fun getForKind(kind: MediaKind): List<ProviderDescriptor> {
        return descriptors
            .filter { kind in it.supportedKinds }
            .sortedByDescending { descriptor ->
                val boost = if (descriptor.id == lastWorkingGeneralProviderId) 0.25f else 0f
                getReliability(descriptor.id) + boost
            }
    }

    fun getReliability(providerId: String): Float {
        val base = getById(providerId)?.baseReliability ?: 0.8f
        return runtimeScores[providerId] ?: base
    }

    fun recordOutcome(providerId: String, success: Boolean) {
        val current = getReliability(providerId)
        val updated = if (success) {
            (current + 0.04f).coerceAtMost(0.99f)
        } else {
            (current - 0.12f).coerceAtLeast(0.10f)
        }
        runtimeScores[providerId] = updated
        if (success) {
            val desc = getById(providerId)
            if (desc != null && (MediaKind.MOVIE in desc.supportedKinds || MediaKind.TV in desc.supportedKinds)) {
                lastWorkingGeneralProviderId = providerId
            }
        }
    }

    fun enrichHostLabel(streamUrl: String, fallbackLabel: String): String {
        val l = streamUrl.lowercase()
        val host = when {
            l.contains("embedwave") -> "EmbedWave"
            l.contains("megaplay") || l.contains("megacloud") -> "MegaPlay"
            l.contains("megavid") -> "Megavid"
            l.contains("animeplay") -> "AnimePlay"
            l.contains("supaplay") -> "SupaPlay"
            l.contains("vidlink") -> "VidLink"
            l.contains("vidfast") -> "VidFast"
            l.contains("vidora") -> "Vidora"
            l.contains("videasy") -> "Videasy"
            l.contains("filemoon") || l.contains("moonmov") -> "Filemoon"
            l.contains("streamwish") || l.contains("sfastwish") -> "Streamwish"
            l.contains("vidhide") || l.contains("filelions") -> "VidHide"
            l.contains("voe.sx") || l.contains("voe-network") -> "VOE"
            l.contains("streamtape") || l.contains("tapecontent") -> "Streamtape"
            else -> null
        }
        return if (host != null && !fallbackLabel.contains(host, ignoreCase = true)) {
            "$fallbackLabel ($host)"
        } else {
            fallbackLabel
        }
    }
}

// ==========================================
// 3. ID BRIDGE (TMDB <-> ANILIST <-> MAL)
// ==========================================

class IdBridge(
    private val remoteResolver: (suspend (MediaItemModel) -> BridgedIds?)? = null
) {
    private data class KnownAnimeEntry(
        val anilistId: Int,
        val malId: Int,
        val romajiTitle: String,
        val totalEpisodes: Int
    )

    private val knownTmdbMap = mapOf(
        30984 to KnownAnimeEntry(269, 269, "Bleach", 366),
        95479 to KnownAnimeEntry(113415, 40748, "Jujutsu Kaisen", 47),
        65930 to KnownAnimeEntry(21459, 31964, "Boku no Hero Academia", 159),
        31911 to KnownAnimeEntry(5114, 5114, "Fullmetal Alchemist: Brotherhood", 64),
        46298 to KnownAnimeEntry(11061, 11061, "Hunter x Hunter (2011)", 148),
        37854 to KnownAnimeEntry(21, 21, "One Piece", 1122),
        85937 to KnownAnimeEntry(101922, 38000, "Kimetsu no Yaiba", 63),
        1429 to KnownAnimeEntry(16498, 16498, "Shingeki no Kyojin", 89),
        46260 to KnownAnimeEntry(20, 20, "Naruto", 220),
        31910 to KnownAnimeEntry(1735, 1735, "Naruto: Shippuuden", 500),
        127532 to KnownAnimeEntry(151807, 52299, "Ore dake Level Up na Ken", 24),
        209867 to KnownAnimeEntry(154587, 52991, "Sousou no Frieren", 28),
        240411 to KnownAnimeEntry(171018, 57334, "Dandadan", 12),
        114410 to KnownAnimeEntry(127230, 44511, "Chainsaw Man", 12),
        13916 to KnownAnimeEntry(1535, 1535, "Death Note", 37),
        207468 to KnownAnimeEntry(153288, 52588, "Kaijuu 8-gou", 12),
        130392 to KnownAnimeEntry(137822, 49596, "Blue Lock", 38),
        120089 to KnownAnimeEntry(140960, 50265, "Spy x Family", 37),
        73223 to KnownAnimeEntry(97940, 34572, "Black Clover", 170),
        12971 to KnownAnimeEntry(813, 813, "Dragon Ball Z", 291),
        62715 to KnownAnimeEntry(21175, 30694, "Dragon Ball Super", 131),
        88803 to KnownAnimeEntry(105333, 38691, "Dr. Stone", 57),
        88046 to KnownAnimeEntry(105310, 38671, "Enen no Shouboutai", 48),
        204832 to KnownAnimeEntry(151801, 52211, "Mashle", 24),
        221200 to KnownAnimeEntry(163270, 54900, "Wind Breaker", 13),
        63926 to KnownAnimeEntry(21087, 30276, "One Punch Man", 24),
        67075 to KnownAnimeEntry(21507, 32182, "Mob Psycho 100", 37),
        60863 to KnownAnimeEntry(20464, 20583, "Haikyuu!!", 85),
        88802 to KnownAnimeEntry(101348, 37521, "Vinland Saga", 48),
        61374 to KnownAnimeEntry(20605, 22319, "Tokyo Ghoul", 48),
        105248 to KnownAnimeEntry(120377, 42310, "Cyberpunk: Edgerunners", 10),
        65942 to KnownAnimeEntry(21274, 31240, "Re:Zero kara Hajimeru Isekai Seikatsu", 66),
        45782 to KnownAnimeEntry(11757, 11757, "Sword Art Online", 96),
        42509 to KnownAnimeEntry(9253, 9253, "Steins;Gate", 24),
        31724 to KnownAnimeEntry(1575, 1575, "Code Geass: Hangyaku no Lelouch", 50),
        30983 to KnownAnimeEntry(19, 19, "Monster", 74),
        30991 to KnownAnimeEntry(1, 1, "Cowboy Bebop", 26),
        890 to KnownAnimeEntry(30, 30, "Shin Seiki Evangelion", 26),
        45790 to KnownAnimeEntry(14719, 14719, "JoJo no Kimyou na Bouken", 190),
        30981 to KnownAnimeEntry(918, 918, "Gintama", 367),
        94664 to KnownAnimeEntry(108465, 39535, "Mushoku Tensei: Isekai Ittara Honki Dasu", 48),
        82684 to KnownAnimeEntry(101280, 37430, "Tensei shitara Slime Datta Ken", 72),
        64196 to KnownAnimeEntry(20832, 29803, "Overlord", 52),
        72517 to KnownAnimeEntry(98659, 35507, "Youkoso Jitsuryoku Shijou Shugi no Kyoushitsu e", 38),
        203165 to KnownAnimeEntry(151970, 52347, "Shangri-La Frontier", 50),
        255661 to KnownAnimeEntry(177709, 58939, "Sakamoto Days", 12)
    )

    private val seasonSpecificIds = mapOf(
        Pair(30984, 2) to Pair(116674, 41467),
        Pair(95479, 2) to Pair(145064, 51009),
        Pair(65930, 2) to Pair(21856, 33486),
        Pair(65930, 3) to Pair(100166, 36456),
        Pair(65930, 4) to Pair(104276, 38408),
        Pair(65930, 5) to Pair(117193, 41587),
        Pair(65930, 6) to Pair(139630, 49918),
        Pair(65930, 7) to Pair(163139, 54789),
        Pair(85937, 2) to Pair(142329, 47778),
        Pair(85937, 3) to Pair(145139, 51019),
        Pair(85937, 4) to Pair(166240, 55701),
        Pair(127532, 2) to Pair(176496, 58567),
        Pair(1429, 2) to Pair(20958, 25777),
        Pair(1429, 3) to Pair(99147, 35760),
        Pair(1429, 4) to Pair(110277, 40028)
    )

    private val cache = ConcurrentHashMap<Int, BridgedIds>()

    fun lookupStaticTmdb(cleanTmdbId: Int): BridgedIds? {
        val entry = knownTmdbMap[cleanTmdbId] ?: return null
        return BridgedIds(
            tmdbId = cleanTmdbId,
            anilistId = entry.anilistId,
            malId = entry.malId,
            romajiTitle = entry.romajiTitle,
            totalEpisodes = entry.totalEpisodes
        )
    }

    suspend fun resolveIds(media: MediaItemModel, seasonNumber: Int = 1): BridgedIds {
        val cleanTmdb = media.tmdbId ?: (if (media.id >= 100_000_000) media.id - 100_000_000 else media.id)
        if (seasonNumber > 1) {
            seasonSpecificIds[Pair(cleanTmdb, seasonNumber)]?.let { (aniSeasonId, malSeasonId) ->
                return BridgedIds(
                    tmdbId = cleanTmdb,
                    anilistId = aniSeasonId,
                    malId = malSeasonId,
                    romajiTitle = media.romajiTitle ?: media.title,
                    totalEpisodes = media.totalEpisodes
                )
            }
        }

        cache[media.id]?.let { return it }

        knownTmdbMap[cleanTmdb]?.let { known ->
            val bridged = BridgedIds(
                tmdbId = cleanTmdb,
                anilistId = known.anilistId,
                malId = known.malId,
                romajiTitle = known.romajiTitle,
                totalEpisodes = known.totalEpisodes
            )
            cache[media.id] = bridged
            return bridged
        }

        if (media.anilistId != null && media.malId != null && media.anilistId != cleanTmdb) {
            val existing = BridgedIds(
                tmdbId = cleanTmdb,
                anilistId = media.anilistId,
                malId = media.malId,
                romajiTitle = media.romajiTitle ?: media.title,
                totalEpisodes = media.totalEpisodes
            )
            cache[media.id] = existing
            return existing
        }

        val remote = try {
            remoteResolver?.invoke(media)
        } catch (e: Exception) {
            Logger.w("IdBridge", "Remote ID resolution failed for ${media.title}", e)
            null
        }

        val finalBridged = BridgedIds(
            tmdbId = remote?.tmdbId ?: cleanTmdb,
            anilistId = remote?.anilistId ?: media.anilistId ?: cleanTmdb,
            malId = remote?.malId ?: media.malId ?: remote?.anilistId ?: cleanTmdb,
            romajiTitle = remote?.romajiTitle ?: media.romajiTitle ?: media.title,
            totalEpisodes = remote?.totalEpisodes ?: media.totalEpisodes
        )
        cache[media.id] = finalBridged
        return finalBridged
    }
}

// ==========================================
// 4. CANDIDATE PLANNER
// ==========================================

class CandidatePlanner(
    private val registry: ProviderRegistry,
    private val idBridge: IdBridge
) {
    suspend fun buildPlan(
        media: MediaItemModel,
        episode: EpisodeRef?,
        forceProviderId: String? = null,
        excludedProviderIds: Set<String> = emptySet()
    ): ResolvePlan {
        val seasonNum = (episode?.seasonNumber ?: 1).coerceAtLeast(1)
        val episodeNum = (episode?.episodeNumber ?: 1).coerceAtLeast(1)

        val bridged = if (media.isAnime) {
            idBridge.resolveIds(media, seasonNum)
        } else {
            BridgedIds(tmdbId = media.tmdbId ?: media.id)
        }

        val descriptors = if (forceProviderId != null) {
            listOfNotNull(registry.getById(forceProviderId))
        } else {
            registry.getForKind(media.kind).filter { it.id !in excludedProviderIds }
        }

        val candidates = mutableListOf<ServerCandidate>()

        for (desc in descriptors) {
            val targetId = when (desc.idType) {
                IdType.TMDB -> bridged.tmdbId ?: media.tmdbId ?: media.id
                IdType.ANILIST -> bridged.anilistId ?: media.anilistId
                IdType.MAL -> bridged.malId ?: media.malId ?: bridged.anilistId
            } ?: continue

            if (targetId <= 0) continue

            val template = if (media.isSeries) desc.seriesUrlTemplate else desc.movieUrlTemplate
            if (template.isNullOrBlank()) continue

            val url = template
                .replace("{id}", targetId.toString())
                .replace("{s}", seasonNum.toString())
                .replace("{e}", episodeNum.toString())

            val headers = mutableMapOf<String, String>()
            if (!desc.headerProfile.omitReferer) {
                desc.headerProfile.referer?.let { headers["Referer"] = it }
                desc.headerProfile.origin?.let { headers["Origin"] = it }
            }
            desc.headerProfile.userAgent?.let { headers["User-Agent"] = it }

            candidates.add(
                ServerCandidate(
                    providerId = desc.id,
                    label = desc.label,
                    embedUrl = url,
                    headers = headers,
                    omitReferer = desc.headerProfile.omitReferer,
                    requiresPopup = desc.requiresPopup,
                    isInstantTrusted = desc.trust == ProviderTrust.INSTANT,
                    timeoutMs = desc.timeoutMs,
                    hedgeDelayMs = desc.hedgeDelayMs
                )
            )
        }

        return ResolvePlan(
            media = media,
            episode = episode,
            candidates = candidates.distinctBy { it.embedUrl },
            preferredProviderId = forceProviderId ?: registry.lastWorkingGeneralProviderId
        )
    }
}

// ==========================================
// 5. STREAM CACHE (TTL + NEGATIVE CACHE + SUBTITLES)
// ==========================================

class StreamCache {
    private val resolvedStreams = ConcurrentHashMap<String, ResolvedStream>()
    private val verifiedLists = ConcurrentHashMap<String, CopyOnWriteArrayList<ResolvedStream>>()
    private val subtitleCache = ConcurrentHashMap<String, List<SubtitleTrack>>()
    @Volatile var autoSkipEnabled: Boolean = true

    private fun mediaEpisodeKey(mediaId: Int, season: Int?, episode: Int?): String =
        "${mediaId}_${season ?: 0}_${episode ?: 0}"

    fun put(mediaId: Int, season: Int?, episode: Int?, stream: ResolvedStream) {
        val baseKey = mediaEpisodeKey(mediaId, season, episode)
        resolvedStreams["${baseKey}_${stream.providerId}"] = stream
        if (stream.subtitles.isNotEmpty()) {
            subtitleCache[baseKey] = (stream.subtitles + (subtitleCache[baseKey] ?: emptyList())).distinctBy { it.url }
        }
        val list = verifiedLists.getOrPut(baseKey) { CopyOnWriteArrayList() }
        val existingIndex = list.indexOfFirst { it.providerId == stream.providerId }
        if (existingIndex >= 0) {
            list[existingIndex] = stream
        } else {
            list.add(stream)
        }
    }

    fun get(mediaId: Int, season: Int?, episode: Int?, providerId: String): ResolvedStream? {
        val baseKey = mediaEpisodeKey(mediaId, season, episode)
        val fullKey = "${baseKey}_$providerId"
        val cached = resolvedStreams[fullKey] ?: return null
        if (cached.isExpired()) {
            resolvedStreams.remove(fullKey)
            verifiedLists[baseKey]?.removeAll { it.providerId == providerId }
            return null
        }
        return cached
    }

    fun getAllVerified(mediaId: Int, season: Int?, episode: Int?): List<ResolvedStream> {
        val baseKey = mediaEpisodeKey(mediaId, season, episode)
        val list = verifiedLists[baseKey] ?: return emptyList()
        val now = System.currentTimeMillis()
        list.removeAll { it.isExpired(now) }
        return list.toList()
    }

    fun evict(mediaId: Int, season: Int?, episode: Int?, providerId: String) {
        val baseKey = mediaEpisodeKey(mediaId, season, episode)
        resolvedStreams.remove("${baseKey}_$providerId")
        verifiedLists[baseKey]?.removeAll { it.providerId == providerId }
    }

    fun putSubtitles(mediaId: Int, season: Int?, episode: Int?, subs: List<SubtitleTrack>) {
        val baseKey = mediaEpisodeKey(mediaId, season, episode)
        val merged = (subs + (subtitleCache[baseKey] ?: emptyList())).distinctBy { it.url }
        subtitleCache[baseKey] = merged
    }

    fun getSubtitles(mediaId: Int, season: Int?, episode: Int?): List<SubtitleTrack> {
        val baseKey = mediaEpisodeKey(mediaId, season, episode)
        return subtitleCache[baseKey] ?: emptyList()
    }
}
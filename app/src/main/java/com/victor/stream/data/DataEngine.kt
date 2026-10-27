package com.victor.stream.data

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.victor.stream.BuildConfig
import com.victor.stream.core.Logger
import com.victor.stream.domain.BridgedIds
import com.victor.stream.domain.CastMember
import com.victor.stream.domain.EpisodeRef
import com.victor.stream.domain.ProductionCompanyRef
import com.victor.stream.domain.MediaItemModel
import com.victor.stream.domain.MediaKind
import com.victor.stream.domain.PlaybackSession
import com.victor.stream.domain.SeasonInfo
import com.victor.stream.domain.SkipSegmentModel
import com.victor.stream.domain.SubtitleTrack
import com.victor.stream.domain.WatchProgressEntry
import com.victor.stream.providers.IdBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ==========================================
// 1. NETWORK DTOs & RETROFIT INTERFACES
// ==========================================

data class TmdbListResponse(val results: List<TmdbMediaDto> = emptyList())

data class TmdbMediaDto(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("media_type") val mediaType: String? = null,
    @SerializedName("vote_average") val voteAverage: Double? = 8.0,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("genre_ids") val genreIds: List<Int>? = emptyList(),
    @SerializedName("original_language") val originalLanguage: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("production_companies") val productionCompanies: List<TmdbCompanyDto>? = null,
    val seasons: List<TmdbSeasonDto>? = null
)

data class TmdbCompanyDto(
    val id: Int,
    val name: String? = null,
    @SerializedName("logo_path") val logoPath: String? = null
)

data class TmdbCreditsResponse(val cast: List<TmdbCastDto> = emptyList())

data class TmdbCastDto(
    val id: Int,
    val name: String? = null,
    val character: String? = null,
    @SerializedName("profile_path") val profilePath: String? = null
)

data class TmdbPersonCreditsResponse(val cast: List<TmdbMediaDto> = emptyList())

data class TmdbSeasonDto(
    @SerializedName("season_number") val seasonNumber: Int,
    val name: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null
)

data class TmdbEpisodeListResponse(val episodes: List<TmdbEpisodeDto> = emptyList())

data class TmdbEpisodeDto(
    val id: Int,
    @SerializedName("episode_number") val episodeNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerializedName("still_path") val stillPath: String? = null
)

data class TmdbTokenResponse(
    val success: Boolean = false,
    @SerializedName("request_token") val requestToken: String? = null
)

data class TmdbLoginRequest(
    val username: String,
    val password: String,
    @SerializedName("request_token") val requestToken: String
)

data class TmdbSessionRequest(
    @SerializedName("request_token") val requestToken: String
)

data class TmdbSessionResponse(
    val success: Boolean = false,
    @SerializedName("session_id") val sessionId: String? = null
)

data class TmdbAccountResponse(
    val id: Int = 0,
    val username: String = "",
    val name: String? = null
)

data class TmdbWatchlistRequest(
    @SerializedName("media_type") val mediaType: String,
    @SerializedName("media_id") val mediaId: Int,
    val watchlist: Boolean
)

data class TmdbStatusResponse(
    val success: Boolean? = true,
    @SerializedName("status_code") val statusCode: Int? = 1
)

interface TmdbRetrofitApi {
    @GET("trending/all/day")
    suspend fun getTrendingAll(@Query("api_key") apiKey: String): TmdbListResponse

    @GET("trending/movie/day")
    suspend fun getTrendingMovies(@Query("api_key") apiKey: String): TmdbListResponse

    @GET("movie/popular")
    suspend fun getPopularMovies(@Query("api_key") apiKey: String): TmdbListResponse

    @GET("movie/top_rated")
    suspend fun getTopRatedMovies(@Query("api_key") apiKey: String): TmdbListResponse

    @GET("trending/tv/day")
    suspend fun getTrendingTv(@Query("api_key") apiKey: String): TmdbListResponse

    @GET("tv/popular")
    suspend fun getPopularTv(@Query("api_key") apiKey: String): TmdbListResponse

    @GET("tv/top_rated")
    suspend fun getTopRatedTv(@Query("api_key") apiKey: String): TmdbListResponse

    @GET("tv/{tv_id}")
    suspend fun getTvDetails(@Path("tv_id") tvId: Int, @Query("api_key") apiKey: String): TmdbMediaDto

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(@Path("movie_id") movieId: Int, @Query("api_key") apiKey: String): TmdbMediaDto

    @GET("movie/{movie_id}/credits")
    suspend fun getMovieCredits(@Path("movie_id") movieId: Int, @Query("api_key") apiKey: String): TmdbCreditsResponse

    @GET("tv/{tv_id}/credits")
    suspend fun getTvCredits(@Path("tv_id") tvId: Int, @Query("api_key") apiKey: String): TmdbCreditsResponse

    @GET("person/{person_id}/combined_credits")
    suspend fun getPersonCredits(@Path("person_id") personId: Int, @Query("api_key") apiKey: String): TmdbPersonCreditsResponse

    @GET("tv/{tv_id}/season/{season_number}")
    suspend fun getSeasonEpisodes(
        @Path("tv_id") tvId: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String
    ): TmdbEpisodeListResponse

    @GET("search/multi")
    suspend fun searchMulti(@Query("api_key") apiKey: String, @Query("query") query: String): TmdbListResponse

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String? = null,
        @Query("primary_release_year") primaryReleaseYear: Int? = null,
        @Query("with_original_language") withOriginalLanguage: String? = null,
        @Query("sort_by") sortBy: String? = null,
        @Query("with_companies") withCompanies: String? = null
    ): TmdbListResponse

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("api_key") apiKey: String,
        @Query("with_genres") withGenres: String? = null,
        @Query("first_air_date_year") firstAirDateYear: Int? = null,
        @Query("with_original_language") withOriginalLanguage: String? = null,
        @Query("sort_by") sortBy: String? = null,
        @Query("with_companies") withCompanies: String? = null
    ): TmdbListResponse

    @GET("authentication/token/new")
    suspend fun createRequestToken(@Query("api_key") apiKey: String): TmdbTokenResponse

    @POST("authentication/token/validate_with_login")
    suspend fun validateTokenWithLogin(@Query("api_key") apiKey: String, @Body body: TmdbLoginRequest): TmdbTokenResponse

    @POST("authentication/session/new")
    suspend fun createSession(@Query("api_key") apiKey: String, @Body body: TmdbSessionRequest): TmdbSessionResponse

    @GET("account")
    suspend fun getAccountDetails(@Query("api_key") apiKey: String, @Query("session_id") sessionId: String): TmdbAccountResponse

    @GET("account/{account_id}/watchlist/movies")
    suspend fun getWatchlistMovies(
        @Path("account_id") accountId: Int,
        @Query("api_key") apiKey: String,
        @Query("session_id") sessionId: String
    ): TmdbListResponse

    @GET("account/{account_id}/watchlist/tv")
    suspend fun getWatchlistTv(
        @Path("account_id") accountId: Int,
        @Query("api_key") apiKey: String,
        @Query("session_id") sessionId: String
    ): TmdbListResponse

    @POST("account/{account_id}/watchlist")
    suspend fun updateWatchlist(
        @Path("account_id") accountId: Int,
        @Query("api_key") apiKey: String,
        @Query("session_id") sessionId: String,
        @Body body: TmdbWatchlistRequest
    ): TmdbStatusResponse
}

interface AniListRetrofitApi {
    @POST(".")
    suspend fun postGraphQl(
        @Header("Authorization") authHeader: String? = null,
        @Body body: JsonObject
    ): JsonObject
}

data class AniSkipResponseDto(
    val found: Boolean = false,
    val results: List<AniSkipResultDto>? = emptyList()
)

data class AniSkipResultDto(
    val skipType: String? = null,
    val interval: AniSkipIntervalDto? = null
)

data class AniSkipIntervalDto(
    val startTime: Double = 0.0,
    val endTime: Double = 0.0
)

interface AniSkipRetrofitApi {
    @GET("v2/skip-times/{malId}/{episode}")
    suspend fun getSkipTimes(
        @Path("malId") malId: Int,
        @Path("episode") episode: Int,
        @Query("types[]") types: List<String> = listOf("op", "ed"),
        @Query("episodeLength") episodeLength: Long = 1420L
    ): AniSkipResponseDto
}

private val ipv4Dns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val ipv4 = all.filterIsInstance<Inet4Address>()
        return ipv4.ifEmpty { all }
    }
}

private val sharedOkHttp: OkHttpClient = OkHttpClient.Builder()
    .dns(ipv4Dns)
    .retryOnConnectionFailure(true)
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .writeTimeout(12, TimeUnit.SECONDS)
    .build()

object NetworkApis {
    val tmdb: TmdbRetrofitApi = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(sharedOkHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TmdbRetrofitApi::class.java)

    val aniList: AniListRetrofitApi = Retrofit.Builder()
        .baseUrl("https://graphql.anilist.co/")
        .client(sharedOkHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AniListRetrofitApi::class.java)

    val aniSkip: AniSkipRetrofitApi = Retrofit.Builder()
        .baseUrl("https://api.aniskip.com/")
        .client(sharedOkHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AniSkipRetrofitApi::class.java)
}

// ==========================================
// 2. SESSION STORE (Fix #3: No URLs in Nav Routes)
// ==========================================

class SessionStore {
    private val sessions = ConcurrentHashMap<String, PlaybackSession>()
    private val episodeListCache = ConcurrentHashMap<String, List<EpisodeRef>>()

    fun put(session: PlaybackSession) {
        sessions[session.id] = session
    }

    fun get(sessionId: String): PlaybackSession? = sessions[sessionId]

    fun update(sessionId: String, transform: (PlaybackSession) -> PlaybackSession): PlaybackSession? {
        val current = sessions[sessionId] ?: return null
        val updated = transform(current)
        sessions[updated.id] = updated
        if (updated.id != sessionId) {
            sessions.remove(sessionId)
        }
        return updated
    }

    fun putSeasonEpisodes(mediaId: Int, seasonNumber: Int, episodes: List<EpisodeRef>) {
        episodeListCache["${mediaId}_$seasonNumber"] = episodes
    }

    fun getSeasonEpisodes(mediaId: Int, seasonNumber: Int): List<EpisodeRef> {
        return episodeListCache["${mediaId}_$seasonNumber"] ?: emptyList()
    }
}

// ==========================================
// 3. PREFS STORE (Accounts, Watch Later & Continue Watching)
// ==========================================

data class TmdbAuthState(
    val sessionId: String? = null,
    val accountId: Int = 0,
    val username: String? = null
)

data class AniListAuthState(
    val token: String? = null,
    val userId: Int = 0,
    val username: String? = null
)

class PrefsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("stream_prefs_v2", Context.MODE_PRIVATE)
    private val legacyPrefs = context.applicationContext.getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getTmdbAuth(): TmdbAuthState {
        val sess = prefs.getString("tmdb_session_id", null) ?: legacyPrefs.getString("tmdb_session_id", null)
        val acct = prefs.getInt("tmdb_account_id", 0).takeIf { it > 0 } ?: legacyPrefs.getInt("tmdb_account_id", 0)
        val user = prefs.getString("tmdb_username", null) ?: legacyPrefs.getString("tmdb_username", null)
        return TmdbAuthState(sess, acct, user)
    }

    fun saveTmdbAuth(sessionId: String?, accountId: Int, username: String?) {
        prefs.edit {
            if (sessionId.isNullOrBlank()) {
                remove("tmdb_session_id")
                remove("tmdb_account_id")
                remove("tmdb_username")
            } else {
                putString("tmdb_session_id", sessionId)
                putInt("tmdb_account_id", accountId)
                putString("tmdb_username", username)
            }
        }
        legacyPrefs.edit {
            if (sessionId.isNullOrBlank()) {
                remove("tmdb_session_id")
                remove("tmdb_account_id")
                remove("tmdb_username")
            } else {
                putString("tmdb_session_id", sessionId)
                putInt("tmdb_account_id", accountId)
                putString("tmdb_username", username)
            }
        }
    }

    fun getAniListAuth(): AniListAuthState {
        val token = prefs.getString("anilist_token", null) ?: legacyPrefs.getString("anilist_token", null)
        val uid = prefs.getInt("anilist_user_id", 0).takeIf { it > 0 } ?: legacyPrefs.getInt("anilist_user_id", 0)
        val uname = prefs.getString("anilist_username", null) ?: legacyPrefs.getString("anilist_username", null)
        return AniListAuthState(token, uid, uname)
    }

    fun saveAniListAuth(token: String?, userId: Int, username: String?) {
        val clean = token?.let { extractCleanAniListToken(it) }
        prefs.edit {
            if (clean.isNullOrBlank()) {
                remove("anilist_token")
                remove("anilist_user_id")
                remove("anilist_username")
            } else {
                putString("anilist_token", clean)
                putInt("anilist_user_id", userId)
                putString("anilist_username", username)
            }
        }
        legacyPrefs.edit {
            if (clean.isNullOrBlank()) {
                remove("anilist_token")
                remove("anilist_user_id")
                remove("anilist_username")
            } else {
                putString("anilist_token", clean)
                putInt("anilist_user_id", userId)
                putString("anilist_username", username)
            }
        }
    }

    fun getWatchLater(): List<MediaItemModel> {
        val json = prefs.getString("watch_later_v2", null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<MediaItemModel>>() {}.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addToWatchLater(item: MediaItemModel): List<MediaItemModel> {
        val current = getWatchLater().toMutableList()
        if (current.none { it.id == item.id }) {
            current.add(0, item)
            prefs.edit { putString("watch_later_v2", gson.toJson(current)) }
        }
        return current
    }

    fun removeFromWatchLater(mediaId: Int): List<MediaItemModel> {
        val current = getWatchLater().toMutableList()
        current.removeAll { it.id == mediaId }
        prefs.edit { putString("watch_later_v2", gson.toJson(current)) }
        return current
    }

    private fun episodeKey(mediaId: Int, seasonNum: Int?, episodeNum: Int?): String {
        return if (seasonNum != null && episodeNum != null) {
            "ep_pos_${mediaId}_s${seasonNum}_e${episodeNum}"
        } else {
            "ep_pos_${mediaId}_movie"
        }
    }

    fun getEpisodePositionMs(mediaId: Int, seasonNum: Int?, episodeNum: Int?): Long {
        val key = episodeKey(mediaId, seasonNum, episodeNum)
        val saved = prefs.getLong(key, 0L)
        if (saved > 0L) return saved
        return getContinueWatching().firstOrNull {
            it.media.id == mediaId && it.seasonNumber == seasonNum && it.episodeNumber == episodeNum
        }?.positionMs ?: 0L
    }

    fun clearEpisodePosition(mediaId: Int, seasonNum: Int?, episodeNum: Int?) {
        prefs.edit { remove(episodeKey(mediaId, seasonNum, episodeNum)) }
    }

    fun getContinueWatching(): List<WatchProgressEntry> {
        val json = prefs.getString("continue_watching_v2", null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<WatchProgressEntry>>() {}.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveContinueProgress(entry: WatchProgressEntry) {
        val epKey = episodeKey(entry.media.id, entry.seasonNumber, entry.episodeNumber)
        if (entry.durationMs > 0L && entry.positionMs >= entry.durationMs - 15_000L) {
            prefs.edit { remove(epKey) }
            return
        }
        if (entry.positionMs < 3_000L) return

        prefs.edit { putLong(epKey, entry.positionMs) }

        val list = getContinueWatching().toMutableList()
        list.removeAll { it.media.id == entry.media.id }
        list.add(0, entry)
        prefs.edit { putString("continue_watching_v2", gson.toJson(list.take(30))) }
    }

    fun removeFromContinueWatching(mediaId: Int): List<WatchProgressEntry> {
        val list = getContinueWatching().toMutableList()
        list.removeAll { it.media.id == mediaId }
        prefs.edit { putString("continue_watching_v2", gson.toJson(list)) }
        return list
    }

    companion object {
        const val ANILIST_OAUTH_URL = "https://anilist.co/api/v2/oauth/authorize?client_id=19551&response_type=token"

        fun extractCleanAniListToken(rawInput: String): String {
            var cleaned = rawInput.trim()
            if (cleaned.contains("access_token=")) {
                cleaned = cleaned.substringAfter("access_token=").substringBefore("&")
            }
            return cleaned.removePrefix("Bearer ").removePrefix("bearer ").trim().replace(Regex("\\s+"), "")
        }
    }
}

// ==========================================
// 4. CATALOG & METADATA REPOSITORY (TMDB + ANILIST + ANISKIP + WYZIE)
// ==========================================

val GENRE_MAP: Map<Int, String> = mapOf(
    28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
    80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
    14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
    9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi", 10770 to "TV Movie",
    53 to "Thriller", 10752 to "War", 37 to "Western"
)

class CatalogRepository(
    private val prefsStore: PrefsStore,
    private val idBridge: IdBridge
) {
    private val mediaMemoryCache = ConcurrentHashMap<Int, MediaItemModel>()
    private val seasonEpisodesMemoryCache = ConcurrentHashMap<String, List<EpisodeRef>>()

    private val fallbackSampleList = listOf(
        MediaItemModel(
            id = 693134,
            kind = MediaKind.MOVIE,
            title = "Dune: Part Two",
            overview = "Follow the mythic journey of Paul Atreides as he unites with Chani and the Fremen.",
            posterUrl = "https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg",
            rating = 8.3,
            releaseYear = "2024",
            genreIds = listOf(878, 12),
            tmdbId = 693134
        ),
        MediaItemModel(
            id = 66732,
            kind = MediaKind.TV,
            title = "Stranger Things",
            overview = "When a young boy vanishes, a small town uncovers a mystery involving secret experiments.",
            posterUrl = "https://image.tmdb.org/t/p/w500/49WJfeN0moxb9IPfGn8AIqMGskD.jpg",
            rating = 8.6,
            releaseYear = "2016",
            genreIds = listOf(18, 9648, 878),
            tmdbId = 66732
        )
    )

    init {
        fallbackSampleList.forEach { mediaMemoryCache[it.id] = it }
    }

    fun putMedia(item: MediaItemModel) {
        mediaMemoryCache[item.id] = item
    }

    fun putAllMedia(items: List<MediaItemModel>) {
        items.forEach { mediaMemoryCache[it.id] = it }
    }

    fun getMedia(id: Int): MediaItemModel? = mediaMemoryCache[id]

    fun getFallbackSamples(): List<MediaItemModel> = fallbackSampleList

    // Today's date as yyyy-MM-dd (ISO dates compare correctly as plain strings).
    private val todayIso: String by lazy {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    }

    /** True only if the title has already been released/aired (hide upcoming ones). */
    private fun TmdbMediaDto.isReleased(): Boolean {
        val date = (releaseDate ?: firstAirDate)?.trim().orEmpty()
        if (date.length < 10) return false // no real date yet => not released
        return date <= todayIso
    }

    fun mapTmdbDtoToDomain(dto: TmdbMediaDto, forcedMediaType: String? = null): MediaItemModel {
        val rawTitle = dto.title ?: dto.name ?: "Untitled"
        val isSeries = (forcedMediaType ?: dto.mediaType) == "tv" || (dto.title == null && dto.name != null)
        val cleanTmdbId = if (dto.id >= 100_000_000) dto.id - 100_000_000 else dto.id
        val isJapaneseAnime = (dto.genreIds?.contains(16) == true && dto.originalLanguage == "ja") || dto.id >= 100_000_000

        val dateStr = dto.releaseDate ?: dto.firstAirDate ?: "2024-01-01"
        val yearStr = if (dateStr.length >= 4) dateStr.take(4) else "2024"
        val fullPoster = if (dto.posterPath?.startsWith("http") == true) {
            dto.posterPath
        } else {
            "https://image.tmdb.org/t/p/w500${dto.posterPath.orEmpty()}"
        }
        val fullBackdrop = when {
            dto.backdropPath?.startsWith("http") == true -> dto.backdropPath
            !dto.backdropPath.isNullOrBlank() -> "https://image.tmdb.org/t/p/w780${dto.backdropPath}"
            else -> ""
        }

        val mappedSeasons = dto.seasons?.map { s ->
            val sPoster = if (s.posterPath?.startsWith("http") == true) {
                s.posterPath
            } else if (!s.posterPath.isNullOrBlank()) {
                "https://image.tmdb.org/t/p/w500${s.posterPath}"
            } else {
                fullPoster
            }
            SeasonInfo(
                seasonNumber = s.seasonNumber,
                name = s.name ?: "Season ${s.seasonNumber}",
                posterUrl = sPoster
            )
        } ?: emptyList()

        return if (isJapaneseAnime) {
            val staticBridge = idBridge.lookupStaticTmdb(cleanTmdbId)
            val model = MediaItemModel(
                id = 100_000_000 + cleanTmdbId,
                kind = MediaKind.ANIME,
                title = rawTitle,
                romajiTitle = staticBridge?.romajiTitle ?: rawTitle,
                overview = dto.overview?.replace(Regex("<[^>]*>"), "")?.trim().takeUnless { it.isNullOrBlank() } ?: "No description available.",
                posterUrl = fullPoster,
                rating = dto.voteAverage ?: 8.0,
                releaseYear = yearStr,
                genreIds = dto.genreIds ?: listOf(16),
                originalLanguage = "ja",
                tmdbId = cleanTmdbId,
                anilistId = staticBridge?.anilistId ?: cleanTmdbId,
                malId = staticBridge?.malId,
                totalEpisodes = staticBridge?.totalEpisodes ?: 24,
                animeFormat = if (isSeries) "TV" else "MOVIE",
                backdropUrl = fullBackdrop,
                seasons = mappedSeasons
            )
            mediaMemoryCache[model.id] = model
            model
        } else {
            val model = MediaItemModel(
                id = cleanTmdbId,
                kind = if (isSeries) MediaKind.TV else MediaKind.MOVIE,
                title = rawTitle,
                overview = dto.overview?.replace(Regex("<[^>]*>"), "")?.trim().takeUnless { it.isNullOrBlank() } ?: "No description available.",
                posterUrl = fullPoster,
                rating = dto.voteAverage ?: 8.0,
                releaseYear = yearStr,
                genreIds = dto.genreIds ?: emptyList(),
                originalLanguage = dto.originalLanguage ?: "en",
                tmdbId = cleanTmdbId,
                backdropUrl = fullBackdrop,
                seasons = mappedSeasons
            )
            mediaMemoryCache[model.id] = model
            model
        }
    }

    suspend fun resolveRemoteAnimeIds(media: MediaItemModel): BridgedIds? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.TMDB_API_KEY
        var cleanTmdb = media.tmdbId
        if (cleanTmdb == null && apiKey.isNotBlank()) {
            try {
                val match = NetworkApis.tmdb.searchMulti(apiKey, media.title).results.firstOrNull {
                    it.mediaType != "person" && (it.genreIds?.contains(16) == true || it.originalLanguage == "ja")
                }
                if (match != null) cleanTmdb = match.id
            } catch (_: Exception) {}
        }

        if (cleanTmdb != null) {
            idBridge.lookupStaticTmdb(cleanTmdb)?.let { return@withContext it }
        }

        try {
            val query = """
                query (${'$'}search: String) {
                  Media(search: ${'$'}search, type: ANIME, isAdult: false) {
                    id idMal episodes title { romaji english }
                  }
                }
            """.trimIndent()
            val vars = JsonObject().apply { addProperty("search", media.title) }
            val payload = JsonObject().apply {
                addProperty("query", query)
                add("variables", vars)
            }
            val res = NetworkApis.aniList.postGraphQl(null, payload)
            val m = res.getAsJsonObject("data")?.getAsJsonObject("Media")
            if (m != null) {
                val aniId = m.get("id")?.takeIf { !it.isJsonNull }?.asInt
                val malId = m.get("idMal")?.takeIf { !it.isJsonNull }?.asInt
                val eps = m.get("episodes")?.takeIf { !it.isJsonNull }?.asInt
                val romaji = m.getAsJsonObject("title")?.get("romaji")?.takeIf { !it.isJsonNull }?.asString
                return@withContext BridgedIds(
                    tmdbId = cleanTmdb,
                    anilistId = aniId,
                    malId = malId,
                    romajiTitle = romaji,
                    totalEpisodes = eps
                )
            }
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "AniList GraphQL lookup failed for ${media.title}", e)
        }
        return@withContext null
    }

    suspend fun fetchHomeCatalog(): HomeCatalogBundle = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.TMDB_API_KEY
        if (apiKey.isBlank()) {
            return@withContext HomeCatalogBundle(
                trendingAll = fallbackSampleList,
                trendingMovies = fallbackSampleList.filter { !it.isSeries },
                popularMovies = fallbackSampleList.filter { !it.isSeries },
                topRatedMovies = fallbackSampleList.filter { !it.isSeries },
                trendingSeries = fallbackSampleList.filter { it.isSeries },
                popularSeries = fallbackSampleList.filter { it.isSeries },
                topRatedSeries = fallbackSampleList.filter { it.isSeries },
                trendingAnime = emptyList(),
                popularAnime = emptyList()
            )
        }

        val tAllDef = async { runCatching { NetworkApis.tmdb.getTrendingAll(apiKey).results }.getOrDefault(emptyList()) }
        val tMovDef = async { runCatching { NetworkApis.tmdb.getTrendingMovies(apiKey).results }.getOrDefault(emptyList()) }
        val pMovDef = async { runCatching { NetworkApis.tmdb.getPopularMovies(apiKey).results }.getOrDefault(emptyList()) }
        val trMovDef = async { runCatching { NetworkApis.tmdb.getTopRatedMovies(apiKey).results }.getOrDefault(emptyList()) }
        val tTvDef = async { runCatching { NetworkApis.tmdb.getTrendingTv(apiKey).results }.getOrDefault(emptyList()) }
        val pTvDef = async { runCatching { NetworkApis.tmdb.getPopularTv(apiKey).results }.getOrDefault(emptyList()) }
        val trTvDef = async { runCatching { NetworkApis.tmdb.getTopRatedTv(apiKey).results }.getOrDefault(emptyList()) }
        val tAniDef = async { fetchTrendingAnime() }
        val pAniDef = async { fetchPopularAnime() }

        fun List<TmdbMediaDto>.toDomainList(forcedType: String? = null): List<MediaItemModel> =
            filter { !it.posterPath.isNullOrBlank() && it.isReleased() }.map { mapTmdbDtoToDomain(it, forcedType) }

        HomeCatalogBundle(
            trendingAll = tAllDef.await().toDomainList(),
            trendingMovies = tMovDef.await().toDomainList("movie"),
            popularMovies = pMovDef.await().toDomainList("movie"),
            topRatedMovies = trMovDef.await().toDomainList("movie"),
            trendingSeries = tTvDef.await().toDomainList("tv"),
            popularSeries = pTvDef.await().toDomainList("tv"),
            topRatedSeries = trTvDef.await().toDomainList("tv"),
            trendingAnime = tAniDef.await(),
            popularAnime = pAniDef.await()
        )
    }

    suspend fun fetchTrendingAnime(): List<MediaItemModel> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.TMDB_API_KEY
        if (apiKey.isBlank()) return@withContext emptyList()
        try {
            NetworkApis.tmdb.discoverTv(
                apiKey = apiKey,
                withGenres = "16",
                withOriginalLanguage = "ja",
                sortBy = "popularity.desc"
            ).results
                .filter { !it.posterPath.isNullOrBlank() && it.isReleased() }
                .map { mapTmdbDtoToDomain(it, "tv") }
                .distinctBy { it.title.lowercase() }
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "Failed to load trending anime", e)
            emptyList()
        }
    }

    suspend fun fetchPopularAnime(): List<MediaItemModel> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.TMDB_API_KEY
        if (apiKey.isBlank()) return@withContext emptyList()
        try {
            NetworkApis.tmdb.discoverTv(
                apiKey = apiKey,
                withGenres = "16",
                withOriginalLanguage = "ja",
                sortBy = "vote_count.desc"
            ).results
                .filter { !it.posterPath.isNullOrBlank() && it.isReleased() }
                .map { mapTmdbDtoToDomain(it, "tv") }
                .distinctBy { it.title.lowercase() }
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "Failed to load popular anime", e)
            emptyList()
        }
    }

    suspend fun fetchTvDetailsAndSeasons(media: MediaItemModel): MediaItemModel = withContext(Dispatchers.IO) {
        if (media.seasons.isNotEmpty()) return@withContext media
        mediaMemoryCache[media.id]?.takeIf { it.seasons.isNotEmpty() }?.let { return@withContext it }

        val apiKey = BuildConfig.TMDB_API_KEY
        val cleanTmdbId = media.tmdbId ?: (if (media.id >= 100_000_000) media.id - 100_000_000 else media.id)
        if (!media.isSeries || apiKey.isBlank() || cleanTmdbId <= 0) return@withContext media

        try {
            val dto = NetworkApis.tmdb.getTvDetails(cleanTmdbId, apiKey)
            val allSeasons = dto.seasons ?: emptyList()
            val validSeasons = allSeasons.filter { it.seasonNumber > 0 }.ifEmpty { allSeasons }.map { s ->
                val poster = if (s.posterPath?.startsWith("http") == true) {
                    s.posterPath
                } else if (!s.posterPath.isNullOrBlank()) {
                    "https://image.tmdb.org/t/p/w500${s.posterPath}"
                } else {
                    media.posterUrl
                }
                SeasonInfo(
                    seasonNumber = s.seasonNumber,
                    name = s.name ?: "Season ${s.seasonNumber}",
                    posterUrl = poster
                )
            }
            val updated = media.copy(seasons = validSeasons)
            mediaMemoryCache[updated.id] = updated
            updated
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "Failed to load TV seasons for ${media.title}", e)
            media
        }
    }

    suspend fun fetchSeasonEpisodes(media: MediaItemModel, seasonNumber: Int): List<EpisodeRef> = withContext(Dispatchers.IO) {
        val cacheKey = "${media.id}_$seasonNumber"
        seasonEpisodesMemoryCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

        val apiKey = BuildConfig.TMDB_API_KEY
        val cleanTmdbId = media.tmdbId ?: (if (media.id >= 100_000_000) media.id - 100_000_000 else media.id)
        if (apiKey.isNotBlank() && cleanTmdbId > 0) {
            try {
                val res = NetworkApis.tmdb.getSeasonEpisodes(cleanTmdbId, seasonNumber, apiKey)
                if (res.episodes.isNotEmpty()) {
                    val mapped = res.episodes.map { ep ->
                        val still = if (ep.stillPath?.startsWith("http") == true) {
                            ep.stillPath
                        } else if (!ep.stillPath.isNullOrBlank()) {
                            "https://image.tmdb.org/t/p/w500${ep.stillPath}"
                        } else {
                            media.posterUrl
                        }
                        EpisodeRef(
                            seasonNumber = seasonNumber,
                            episodeNumber = ep.episodeNumber,
                            title = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber}",
                            overview = ep.overview?.takeIf { it.isNotBlank() } ?: "Stream ${media.title} Episode ${ep.episodeNumber}",
                            stillUrl = still
                        )
                    }
                    seasonEpisodesMemoryCache[cacheKey] = mapped
                    return@withContext mapped
                }
            } catch (e: Exception) {
                Logger.w("CatalogRepo", "Failed to fetch episodes for ${media.title} S$seasonNumber", e)
            }
        }

        val fallbackCount = media.totalEpisodes.coerceAtLeast(1)
        val fallbackList = (1..fallbackCount).map { epNum ->
            EpisodeRef(
                seasonNumber = seasonNumber,
                episodeNumber = epNum,
                title = "Episode $epNum",
                overview = "Stream ${media.title} Episode $epNum",
                stillUrl = media.posterUrl
            )
        }
        seasonEpisodesMemoryCache[cacheKey] = fallbackList
        fallbackList
    }

    suspend fun searchCatalog(
        query: String,
        selectedType: String,
        selectedGenre: String,
        selectedYear: String
    ): List<MediaItemModel> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.TMDB_API_KEY
        if (apiKey.isBlank()) return@withContext emptyList()
        val combined = mutableListOf<MediaItemModel>()

        try {
            if (query.isNotBlank()) {
                val q = query.trim()
                val rawResults = NetworkApis.tmdb.searchMulti(apiKey, q).results
                    .filter { it.mediaType != "person" && !it.posterPath.isNullOrBlank() && it.isReleased() }
                    .map { mapTmdbDtoToDomain(it) }
                combined.addAll(rawResults)
            } else {
                val genreId = GENRE_MAP.entries.firstOrNull { it.value == selectedGenre }?.key?.toString()
                val yearInt = if (selectedYear != "All") selectedYear.toIntOrNull() else null
                if (selectedType == "Anime") {
                    combined.addAll(fetchPopularAnime())
                } else {
                    if (selectedType == "All") {
                        combined.addAll(fetchTrendingAnime())
                    }
                    if (selectedType == "All" || selectedType == "Movies") {
                        val movs = NetworkApis.tmdb.discoverMovies(apiKey, genreId, yearInt).results
                            .filter { !it.posterPath.isNullOrBlank() && it.isReleased() }
                            .map { mapTmdbDtoToDomain(it, "movie") }
                        combined.addAll(movs)
                    }
                    if (selectedType == "All" || selectedType == "Series") {
                        val tvs = NetworkApis.tmdb.discoverTv(apiKey, genreId, yearInt).results
                            .filter { !it.posterPath.isNullOrBlank() && it.isReleased() }
                            .map { mapTmdbDtoToDomain(it, "tv") }
                        combined.addAll(tvs)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "Search failed for query=$query", e)
        }

        val genreKey = GENRE_MAP.entries.firstOrNull { it.value == selectedGenre }?.key
        combined.distinctBy { it.id }.filter { item ->
            val matchesType = when (selectedType) {
                "Movies" -> !item.isSeries && !item.isAnime
                "Series" -> item.isSeries && !item.isAnime
                "Anime" -> item.isAnime
                else -> true
            }
            val matchesGenre = if (selectedGenre == "All" || item.isAnime || genreKey == null) {
                true
            } else {
                item.genreIds.contains(genreKey)
            }
            val matchesYear = if (selectedYear == "All") true else item.releaseYear == selectedYear
            matchesType && matchesGenre && matchesYear
        }
    }

    // --- Cloud Account Sync (TMDB & AniList) ---

    suspend fun verifyAniListToken(rawToken: String): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val clean = PrefsStore.extractCleanAniListToken(rawToken)
        if (clean.isBlank()) return@withContext null
        try {
            val body = JsonObject().apply { addProperty("query", "query { Viewer { id name } }") }
            val res = NetworkApis.aniList.postGraphQl("Bearer $clean", body)
            val viewer = res.getAsJsonObject("data")?.getAsJsonObject("Viewer")
            if (viewer != null) {
                val id = viewer.get("id")?.asInt ?: 0
                val name = viewer.get("name")?.asString ?: "AniList User"
                if (id > 0) return@withContext Pair(id, name)
            }
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "AniList token verification failed", e)
        }
        return@withContext null
    }

    suspend fun loginToTmdb(username: String, password: String): Triple<String, Int, String>? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.TMDB_API_KEY
        if (apiKey.isBlank()) return@withContext null
        try {
            val tokenRes = NetworkApis.tmdb.createRequestToken(apiKey)
            val reqToken = tokenRes.requestToken ?: return@withContext null
            val validated = NetworkApis.tmdb.validateTokenWithLogin(
                apiKey,
                TmdbLoginRequest(username.trim(), password, reqToken)
            )
            val sessionRes = NetworkApis.tmdb.createSession(
                apiKey,
                TmdbSessionRequest(validated.requestToken ?: reqToken)
            )
            val sessId = sessionRes.sessionId ?: return@withContext null
            val acct = NetworkApis.tmdb.getAccountDetails(apiKey, sessId)
            Triple(sessId, acct.id, acct.username)
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "TMDB login failed", e)
            null
        }
    }

    suspend fun fetchUserAniList(status: String): List<MediaItemModel> = withContext(Dispatchers.IO) {
        val auth = prefsStore.getAniListAuth()
        if (auth.userId <= 0 || auth.token.isNullOrBlank()) return@withContext emptyList()
        val list = mutableListOf<MediaItemModel>()
        try {
            val query = """
                query (${'$'}userId: Int, ${'$'}status: MediaListStatus) {
                  MediaListCollection(userId: ${'$'}userId, type: ANIME, status: ${'$'}status, sort: UPDATED_TIME_DESC) {
                    lists {
                      entries {
                        media {
                          id idMal format episodes title { english romaji } description averageScore seasonYear coverImage { extraLarge large }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()
            val vars = JsonObject().apply {
                addProperty("userId", auth.userId)
                addProperty("status", status)
            }
            val payload = JsonObject().apply {
                addProperty("query", query)
                add("variables", vars)
            }
            val res = NetworkApis.aniList.postGraphQl("Bearer ${auth.token}", payload)
            val listsArr = res.getAsJsonObject("data")
                ?.getAsJsonObject("MediaListCollection")
                ?.getAsJsonArray("lists")
            if (listsArr != null) {
                for (l in listsArr) {
                    val entries = l.asJsonObject.getAsJsonArray("entries") ?: continue
                    for (e in entries) {
                        val m = e.asJsonObject.getAsJsonObject("media") ?: continue
                        parseAniListGraphObj(m)?.let {
                            mediaMemoryCache[it.id] = it
                            list.add(it)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "Failed to fetch AniList collection for status=$status", e)
        }
        list.distinctBy { it.id }
    }

    private fun parseAniListGraphObj(m: JsonObject): MediaItemModel? {
        return try {
            val aniId = m.get("id")?.asInt ?: return null
            val malId = m.get("idMal")?.takeIf { !it.isJsonNull }?.asInt
            val fmt = m.get("format")?.takeIf { !it.isJsonNull }?.asString ?: "TV"
            val titleObj = m.getAsJsonObject("title")
            val eng = titleObj?.get("english")?.takeIf { !it.isJsonNull }?.asString
            val romaji = titleObj?.get("romaji")?.takeIf { !it.isJsonNull }?.asString ?: (eng ?: "Untitled")
            val desc = m.get("description")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                .replace(Regex("<[^>]*>"), "").trim()
            val coverObj = m.getAsJsonObject("coverImage")
            val poster = coverObj?.get("extraLarge")?.takeIf { !it.isJsonNull }?.asString
                ?: coverObj?.get("large")?.takeIf { !it.isJsonNull }?.asString
                ?: return null
            val eps = m.get("episodes")?.takeIf { !it.isJsonNull }?.asInt?.coerceAtLeast(1) ?: 24
            val score = m.get("averageScore")?.takeIf { !it.isJsonNull }?.let { it.asDouble / 10.0 } ?: 8.0
            val year = m.get("seasonYear")?.takeIf { !it.isJsonNull }?.asInt ?: 2024

            MediaItemModel(
                id = 100_000_000 + aniId,
                kind = MediaKind.ANIME,
                title = eng ?: romaji,
                romajiTitle = romaji,
                overview = desc.ifBlank { "No synopsis available." },
                posterUrl = poster,
                rating = score,
                releaseYear = year.toString(),
                genreIds = listOf(16),
                originalLanguage = "ja",
                anilistId = aniId,
                malId = malId,
                totalEpisodes = eps,
                animeFormat = fmt
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchTmdbWatchlist(): Pair<List<MediaItemModel>, List<MediaItemModel>> = withContext(Dispatchers.IO) {
        val auth = prefsStore.getTmdbAuth()
        val apiKey = BuildConfig.TMDB_API_KEY
        if (auth.sessionId.isNullOrBlank() || auth.accountId <= 0 || apiKey.isBlank()) {
            return@withContext Pair(emptyList(), emptyList())
        }
        try {
            val movDef = async { NetworkApis.tmdb.getWatchlistMovies(auth.accountId, apiKey, auth.sessionId).results }
            val tvDef = async { NetworkApis.tmdb.getWatchlistTv(auth.accountId, apiKey, auth.sessionId).results }
            val movs = movDef.await().map { mapTmdbDtoToDomain(it, "movie") }
            val tvs = tvDef.await().map { mapTmdbDtoToDomain(it, "tv") }
            (movs + tvs).forEach { prefsStore.addToWatchLater(it) }
            Pair(movs, tvs)
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "Failed to fetch TMDB watchlist", e)
            Pair(emptyList(), emptyList())
        }
    }

    suspend fun syncProgressToCloud(media: MediaItemModel, inWatchlist: Boolean, watchedEpisode: Int? = null) = withContext(Dispatchers.IO) {
        try {
            if (media.isAnime) {
                val aniAuth = prefsStore.getAniListAuth()
                if (!aniAuth.token.isNullOrBlank()) {
                    val bridged = idBridge.resolveIds(media, 1)
                    val aniId = bridged.anilistId ?: media.anilistId ?: return@withContext
                    val status = if (watchedEpisode != null && watchedEpisode > 0) "CURRENT" else "PLANNING"
                    val mutation = """
                        mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}progress: Int) {
                          SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress) { id }
                        }
                    """.trimIndent()
                    val vars = JsonObject().apply {
                        addProperty("mediaId", aniId)
                        addProperty("status", status)
                        addProperty("progress", (watchedEpisode ?: 1).coerceAtLeast(1))
                    }
                    val payload = JsonObject().apply {
                        addProperty("query", mutation)
                        add("variables", vars)
                    }
                    NetworkApis.aniList.postGraphQl("Bearer ${aniAuth.token}", payload)
                }
            } else {
                val tmdbAuth = prefsStore.getTmdbAuth()
                val apiKey = BuildConfig.TMDB_API_KEY
                if (!tmdbAuth.sessionId.isNullOrBlank() && tmdbAuth.accountId > 0 && apiKey.isNotBlank()) {
                    val mediaType = if (media.isSeries) "tv" else "movie"
                    val tmdbId = media.tmdbId ?: media.id
                    NetworkApis.tmdb.updateWatchlist(
                        accountId = tmdbAuth.accountId,
                        apiKey = apiKey,
                        sessionId = tmdbAuth.sessionId,
                        body = TmdbWatchlistRequest(mediaType = mediaType, mediaId = tmdbId, watchlist = inWatchlist)
                    )
                }
            }
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "Cloud sync failed for ${media.title}", e)
        }
    }

    // --- Subtitles (Wyzie) & Intro/Outro Skip Segments (AniSkip + TheIntroDB) ---

    suspend fun fetchSubtitlesForMedia(media: MediaItemModel, episode: EpisodeRef?): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        val cleanTmdbId = media.tmdbId ?: (if (media.id >= 100_000_000) media.id - 100_000_000 else media.id)
        if (cleanTmdbId <= 0) return@withContext emptyList()
        val s = (episode?.seasonNumber ?: 1).coerceAtLeast(1)
        val e = (episode?.episodeNumber ?: 1).coerceAtLeast(1)

        val endpoints = if (media.isSeries) {
            listOf(
                "https://sub.wyzie.ru/search?id=$cleanTmdbId&season=$s&episode=$e",
                "https://sub.wyzie.ru/search?id=$cleanTmdbId&season=$s&episode=$e&source=all"
            )
        } else {
            listOf(
                "https://sub.wyzie.ru/search?id=$cleanTmdbId",
                "https://sub.wyzie.ru/search?id=$cleanTmdbId&source=all"
            )
        }

        val tracks = mutableListOf<SubtitleTrack>()
        for (urlStr in endpoints) {
            if (tracks.isNotEmpty()) break
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0 Safari/537.36")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (body.startsWith("[")) {
                        val arr = JSONArray(body)
                        val seenCounts = mutableMapOf<String, Int>()
                        for (i in 0 until minOf(arr.length(), 25)) {
                            val obj = arr.getJSONObject(i)
                            val subUrl = obj.optString("url", "")
                            val lang = obj.optString("language", "en").lowercase()
                            val disp = obj.optString("display", lang.uppercase())
                            val fmt = obj.optString("format", if (subUrl.contains(".vtt", true)) "vtt" else "srt").lowercase()
                            if (subUrl.startsWith("http")) {
                                val c = (seenCounts[disp] ?: 0) + 1
                                seenCounts[disp] = c
                                val label = if (c == 1) disp else "$disp #$c"
                                tracks.add(SubtitleTrack(subUrl, label, lang, fmt))
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        tracks.sortedWith(compareByDescending<SubtitleTrack> { it.language.startsWith("en") }.thenBy { it.label })
    }

    suspend fun fetchSkipSegments(
        media: MediaItemModel,
        episode: EpisodeRef?,
        totalDurationMs: Long
    ): List<SkipSegmentModel> = withContext(Dispatchers.IO) {
        val seasonNum = (episode?.seasonNumber ?: 1).coerceAtLeast(1)
        val episodeNum = (episode?.episodeNumber ?: 1).coerceAtLeast(1)

        if (media.isAnime) {
            val bridged = idBridge.resolveIds(media, seasonNum)
            val malId = bridged.malId ?: media.malId
            if (malId != null && malId > 0) {
                try {
                    val lenSec = if (totalDurationMs > 60_000L) (totalDurationMs / 1000L) else 1420L
                    val res = NetworkApis.aniSkip.getSkipTimes(malId, episodeNum, listOf("op", "ed"), lenSec)
                    if (res.found && !res.results.isNullOrEmpty()) {
                        return@withContext res.results.mapNotNull { item ->
                            val interval = item.interval ?: return@mapNotNull null
                            val startMs = (interval.startTime * 1000.0).toLong()
                            val endMs = (interval.endTime * 1000.0).toLong()
                            if (endMs > startMs + 5000L) {
                                val label = if (item.skipType == "op") "Intro" else "Credits"
                                SkipSegmentModel(startMs, endMs, label)
                            } else null
                        }
                    }
                } catch (_: Exception) {}
            }
            return@withContext emptyList()
        }

        if (!media.isSeries) return@withContext emptyList()
        val segments = mutableListOf<SkipSegmentModel>()
        val tmdbId = media.tmdbId ?: media.id

        try {
            val introDbUrl = "https://api.theintrodb.org/v1/media?tmdb_id=$tmdbId&season=$seasonNum&episode=$episodeNum"
            val conn = (URL(introDbUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                json.optJSONObject("intro")?.let { introObj ->
                    val startMs = introObj.optLong("start_ms", -1L)
                    val endMs = introObj.optLong("end_ms", -1L)
                    if (startMs >= 0L && endMs > startMs + 5000L) {
                        segments.add(SkipSegmentModel(startMs, endMs, "Intro"))
                    }
                }
                val outroObj = json.optJSONObject("outro") ?: json.optJSONObject("credits")
                if (outroObj != null) {
                    val startMs = outroObj.optLong("start_ms", -1L)
                    val endMs = outroObj.optLong("end_ms", totalDurationMs)
                    if (startMs > 60_000L && endMs > startMs + 5000L) {
                        segments.add(SkipSegmentModel(startMs, endMs, "Credits"))
                    }
                }
            }
        } catch (_: Exception) {}

        if (totalDurationMs > 300_000L && segments.none { it.label == "Credits" }) {
            val creditsStart = (totalDurationMs - 45_000L).coerceAtLeast(totalDurationMs * 85 / 100)
            // Estimated (no real data): show a Skip button, but don't auto-jump on it.
            segments.add(SkipSegmentModel(creditsStart, totalDurationMs - 2000L, "Credits", isGuessed = true))
        }

        segments
    }

    // ==========================================
    // CAST & PRODUCTION (TMDB credits / company)
    // ==========================================

    private fun tmdbCleanId(media: MediaItemModel): Int =
        media.tmdbId ?: (if (media.id >= 100_000_000) media.id - 100_000_000 else media.id)

    /** Top-billed cast with headshots for a movie or series. */
    suspend fun fetchCast(media: MediaItemModel): List<CastMember> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.TMDB_API_KEY
        val id = tmdbCleanId(media)
        if (apiKey.isBlank() || id <= 0) return@withContext emptyList()
        try {
            val credits = if (media.isSeries) {
                NetworkApis.tmdb.getTvCredits(id, apiKey)
            } else {
                NetworkApis.tmdb.getMovieCredits(id, apiKey)
            }
            credits.cast.take(20).mapNotNull { c ->
                val name = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val photo = when {
                    c.profilePath?.startsWith("http") == true -> c.profilePath
                    !c.profilePath.isNullOrBlank() -> "https://image.tmdb.org/t/p/w185${c.profilePath}"
                    else -> ""
                }
                CastMember(id = c.id, name = name, character = c.character.orEmpty(), profileUrl = photo)
            }
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "Failed to fetch cast for ${media.title}", e)
            emptyList()
        }
    }

    /** Production/studio companies for a movie or series. */
    suspend fun fetchProductionCompanies(media: MediaItemModel): List<ProductionCompanyRef> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.TMDB_API_KEY
        val id = tmdbCleanId(media)
        if (apiKey.isBlank() || id <= 0) return@withContext emptyList()
        try {
            val detail = if (media.isSeries) {
                NetworkApis.tmdb.getTvDetails(id, apiKey)
            } else {
                NetworkApis.tmdb.getMovieDetails(id, apiKey)
            }
            (detail.productionCompanies ?: emptyList()).mapNotNull { co ->
                val name = co.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val logo = when {
                    co.logoPath?.startsWith("http") == true -> co.logoPath
                    !co.logoPath.isNullOrBlank() -> "https://image.tmdb.org/t/p/w185${co.logoPath}"
                    else -> ""
                }
                ProductionCompanyRef(id = co.id, name = name, logoUrl = logo)
            }
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "Failed to fetch companies for ${media.title}", e)
            emptyList()
        }
    }

    /** Everything a given actor/person has appeared in (released only), newest first. */
    suspend fun fetchPersonFilmography(personId: Int): List<MediaItemModel> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.TMDB_API_KEY
        if (apiKey.isBlank() || personId <= 0) return@withContext emptyList()
        try {
            NetworkApis.tmdb.getPersonCredits(personId, apiKey).cast
                .filter { !it.posterPath.isNullOrBlank() && it.isReleased() }
                .sortedByDescending { (it.releaseDate ?: it.firstAirDate).orEmpty() }
                .map { mapTmdbDtoToDomain(it) }
                .distinctBy { it.id }
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "Failed to fetch filmography for person=$personId", e)
            emptyList()
        }
    }

    /** Movies and shows from a given production company (released only), most popular first. */
    suspend fun fetchCompanyTitles(companyId: Int): List<MediaItemModel> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.TMDB_API_KEY
        if (apiKey.isBlank() || companyId <= 0) return@withContext emptyList()
        try {
            val movDef = async {
                runCatching {
                    NetworkApis.tmdb.discoverMovies(apiKey, sortBy = "popularity.desc", withCompanies = companyId.toString()).results
                }.getOrDefault(emptyList())
            }
            val tvDef = async {
                runCatching {
                    NetworkApis.tmdb.discoverTv(apiKey, sortBy = "popularity.desc", withCompanies = companyId.toString()).results
                }.getOrDefault(emptyList())
            }
            val movs = movDef.await().filter { !it.posterPath.isNullOrBlank() && it.isReleased() }.map { mapTmdbDtoToDomain(it, "movie") }
            val tvs = tvDef.await().filter { !it.posterPath.isNullOrBlank() && it.isReleased() }.map { mapTmdbDtoToDomain(it, "tv") }
            (movs + tvs).distinctBy { it.id }
        } catch (e: Exception) {
            Logger.w("CatalogRepo", "Failed to fetch titles for company=$companyId", e)
            emptyList()
        }
    }
}

data class HomeCatalogBundle(
    val trendingAll: List<MediaItemModel>,
    val trendingMovies: List<MediaItemModel>,
    val popularMovies: List<MediaItemModel>,
    val topRatedMovies: List<MediaItemModel>,
    val trendingSeries: List<MediaItemModel>,
    val popularSeries: List<MediaItemModel>,
    val topRatedSeries: List<MediaItemModel>,
    val trendingAnime: List<MediaItemModel>,
    val popularAnime: List<MediaItemModel>
)
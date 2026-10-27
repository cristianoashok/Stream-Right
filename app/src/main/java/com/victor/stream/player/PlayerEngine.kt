package com.victor.stream.player

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.victor.stream.core.Logger
import com.victor.stream.data.CatalogRepository
import com.victor.stream.data.PrefsStore
import com.victor.stream.domain.MediaItemModel
import com.victor.stream.domain.ResolvedStream
import com.victor.stream.domain.SkipSegmentModel
import com.victor.stream.domain.StreamMimeType
import com.victor.stream.domain.SubtitleTrack
import com.victor.stream.domain.WatchProgressEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ==========================================
// 1. PLAYER STATE & QUALITY MODELS
// ==========================================

data class VideoQualityOption(
    val label: String,
    val shortLabel: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val forceHighest: Boolean
)

val VIDEO_QUALITY_OPTIONS = listOf(
    VideoQualityOption("High Quality (1080p)", "1080p HD", 3840, 2160, true),
    VideoQualityOption("720p HD", "720p", 1280, 720, false),
    VideoQualityOption("480p SD", "480p", 854, 480, false),
    VideoQualityOption("360p Data Saver", "360p", 640, 360, false),
    VideoQualityOption("Auto", "Auto", Int.MAX_VALUE, Int.MAX_VALUE, false)
)

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val secondsRemaining: Long = 999L,
    val activeServerLabel: String = "Server 1",
    val activeProviderId: String = "default",
    val availableSubtitles: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleIndex: Int = 0, // -1 = Off, -2 = Embedded, >= 0 = External
    val hasEmbeddedCaptions: Boolean = false,
    val selectedQuality: VideoQualityOption = VIDEO_QUALITY_OPTIONS.first(),
    val playbackSpeed: Float = 1.0f,
    val selectedAudioMode: String = "Default (Original Audio)",
    val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val activeSkipSegment: SkipSegmentModel? = null,
    val playerErrorMessage: String? = null
)

// ==========================================
// 2. PROGRESS TRACKER (Invariant #11: Single Debounced Owner)
// ==========================================

class ProgressTracker(
    private val prefsStore: PrefsStore,
    private val catalogRepository: CatalogRepository,
    private val scope: CoroutineScope
) {
    private var syncedCloudThisSession = false

    fun resetCloudSyncFlag() {
        syncedCloudThisSession = false
    }

    fun recordProgress(
        media: MediaItemModel,
        positionMs: Long,
        durationMs: Long,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        providerId: String?
    ) {
        if (positionMs < 3_000L) return
        prefsStore.saveContinueProgress(
            WatchProgressEntry(
                media = media,
                positionMs = positionMs,
                durationMs = durationMs,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                lastProviderId = providerId
            )
        )

        if (!syncedCloudThisSession && positionMs > 15_000L) {
            syncedCloudThisSession = true
            scope.launch(Dispatchers.IO) {
                catalogRepository.syncProgressToCloud(
                    media = media,
                    inWatchlist = true,
                    watchedEpisode = episodeNumber ?: 1
                )
            }
        }
    }
}

// ==========================================
// 3. PLAYBACK CONTROLLER (Invariant #6 & #7: State Machine with Idempotent prepare())
// ==========================================

@UnstableApi
class PlaybackController(
    context: Context,
    private val prefsStore: PrefsStore,
    private val catalogRepository: CatalogRepository,
    private val onPlaybackError: (failedProviderId: String, errorMsg: String) -> Unit,
    private val onEpisodeEnded: () -> Unit,
    private val onEnterCreditsWindow: () -> Unit,
    private val onPlaybackReady: () -> Unit
) {
    private val tag = "PlaybackController"
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val progressTracker = ProgressTracker(prefsStore, catalogRepository, controllerScope)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context.applicationContext).setDataSourceFactory(httpDataSourceFactory))
        .build()

    private var currentMedia: MediaItemModel? = null
    private var currentStreamUrl: String? = null
    private var currentSubtitleUrl: String? = null
    private var currentSeasonNumber: Int? = null
    private var currentEpisodeNumber: Int? = null
    private var currentEpisodeTitle: String? = null
    private var skipSegments: List<SkipSegmentModel> = emptyList()
    private val autoSkippedTypes = mutableSetOf<String>()
    private var tickerJob: Job? = null
    private var loggedPlaybackStartUrl: String? = null

    // ---- Subtitle selection ----
    // Until the viewer opens the Captions menu and picks something, subtitles default
    // to English (never a random foreign track). Once they pick, we remember THAT track
    // by URL so reordering the list later can't silently switch the language on them.
    private var userPickedSubtitle = false
    private var preferredSubtitleUrl: String? = null
    private var preferredSubtitleLanguage: String? = null
    private var embeddedSubtitleSelected = false // viewer chose the stream's built-in captions

    /** English (or clearly English-labelled) tracks first, then the rest alphabetically. */
    private fun sortSubsEnglishFirst(list: List<SubtitleTrack>): List<SubtitleTrack> =
        list.sortedWith(
            compareByDescending<SubtitleTrack> { isEnglish(it) }.thenBy { it.label.lowercase() }
        )

    private fun isEnglish(track: SubtitleTrack): Boolean {
        val lang = track.language.lowercase()
        return lang == "en" || lang == "eng" || lang.startsWith("en-") ||
                track.label.contains("english", ignoreCase = true)
    }

    /**
     * Decides which subtitle index should be active for [list].
     * Returns (index, track) — index -1 means "Off" (shown instead of a wrong language).
     */
    private fun resolveSubtitleSelection(list: List<SubtitleTrack>): Pair<Int, SubtitleTrack?> {
        if (userPickedSubtitle && embeddedSubtitleSelected) return -2 to null // built-in captions
        if (list.isEmpty()) return -1 to null
        if (userPickedSubtitle) {
            if (preferredSubtitleUrl == null && preferredSubtitleLanguage == null) {
                return -1 to null // user chose Off
            }
            // Same stream: match the exact track. Different episode (URL changed):
            // fall back to the same language so their choice carries over.
            val byUrl = preferredSubtitleUrl?.let { url -> list.indexOfFirst { it.url == url } } ?: -1
            if (byUrl >= 0) return byUrl to list[byUrl]
            val byLang = preferredSubtitleLanguage?.let { lang ->
                list.indexOfFirst { it.language.equals(lang, ignoreCase = true) }
            } ?: -1
            return if (byLang >= 0) byLang to list[byLang] else -1 to null
        }
        // Auto: English if we have it, otherwise Off (don't guess a foreign language)
        val enIdx = list.indexOfFirst { isEnglish(it) }
        return if (enIdx >= 0) enIdx to list[enIdx] else -1 to null
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && currentStreamUrl != null && currentStreamUrl != loggedPlaybackStartUrl) {
                loggedPlaybackStartUrl = currentStreamUrl
                Logger.i(tag, "PLAYBACK STARTED (video is rendering) provider=${_state.value.activeProviderId}")
            }
            _state.update {
                it.copy(
                    isPlaying = isPlaying,
                    isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING
                )
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val hasEmbedded = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
            _state.update { it.copy(hasEmbeddedCaptions = hasEmbedded) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    isPlaying = exoPlayer.isPlaying
                )
            }
            if (playbackState == Player.STATE_READY) {
                _state.update { it.copy(playerErrorMessage = null, durationMs = exoPlayer.duration.coerceAtLeast(0L)) }
                onPlaybackReady()
            } else if (playbackState == Player.STATE_ENDED) {
                onEpisodeEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val providerId = _state.value.activeProviderId
            Logger.e(tag, "ExoPlayer error on provider=$providerId url=$currentStreamUrl: ${error.errorCodeName}", error)
            exoPlayer.stop()
            onPlaybackError(providerId, "${error.errorCodeName}: ${error.message}")
        }
    }

    init {
        exoPlayer.addListener(playerListener)
        startPositionTicker()
    }

    private fun startPositionTicker() {
        tickerJob?.cancel()
        tickerJob = controllerScope.launch {
            var tickCount = 0
            while (isActive) {
                val dur = exoPlayer.duration.coerceAtLeast(0L)
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val remSec = if (dur > 60_000L && pos > 0L) ((dur - pos) / 1000L).coerceAtLeast(0L) else 999L

                val activeSeg = if (dur > 60_000L) {
                    skipSegments.firstOrNull { pos in it.startMs..(it.endMs - 1000L) }
                } else null

                _state.update {
                    it.copy(
                        currentPositionMs = pos,
                        durationMs = dur,
                        secondsRemaining = remSec,
                        activeSkipSegment = activeSeg
                    )
                }

                if (dur > 60_000L && (activeSeg?.label == "Credits" || remSec in 1..75)) {
                    onEnterCreditsWindow()
                }

                // Debounced progress persistence every ~4 seconds
                tickCount++
                if (tickCount % 5 == 0 && pos > 3_000L) {
                    currentMedia?.let { media ->
                        progressTracker.recordProgress(
                            media = media,
                            positionMs = pos,
                            durationMs = dur,
                            seasonNumber = currentSeasonNumber,
                            episodeNumber = currentEpisodeNumber,
                            episodeTitle = currentEpisodeTitle,
                            providerId = _state.value.activeProviderId
                        )
                    }
                }
                delay(800L)
            }
        }
    }

    // Invariant #7: Idempotent prepare()
    fun prepare(
        media: MediaItemModel,
        stream: ResolvedStream,
        startAtMs: Long,
        episodeTitle: String? = null
    ) {
        val mergedSubs = sortSubsEnglishFirst(
            (stream.subtitles + _state.value.availableSubtitles).distinctBy { it.url }
        )
        val (selectedIdx, activeSub) = resolveSubtitleSelection(mergedSubs)

        // If the exact same stream URL + subtitle track is already loaded, do not re-prepare!
        if (currentStreamUrl == stream.streamUrl &&
            currentSubtitleUrl == activeSub?.url &&
            exoPlayer.playbackState != Player.STATE_IDLE
        ) {
            _state.update {
                it.copy(
                    availableSubtitles = mergedSubs,
                    activeServerLabel = stream.serverLabel,
                    activeProviderId = stream.providerId
                )
            }
            return
        }

        val isSameEpisode = currentMedia?.id == media.id &&
                currentSeasonNumber == stream.episode?.seasonNumber &&
                currentEpisodeNumber == stream.episode?.episodeNumber

        val resumePos = if (isSameEpisode && exoPlayer.currentPosition > 3_000L) {
            exoPlayer.currentPosition
        } else {
            startAtMs
        }

        if (!isSameEpisode) {
            progressTracker.resetCloudSyncFlag()
            autoSkippedTypes.clear()
            skipSegments = emptyList()
        }

        currentMedia = media
        currentStreamUrl = stream.streamUrl
        currentSubtitleUrl = activeSub?.url
        currentSeasonNumber = stream.episode?.seasonNumber
        currentEpisodeNumber = stream.episode?.episodeNumber
        currentEpisodeTitle = episodeTitle ?: stream.episode?.title

        _state.update {
            it.copy(
                activeServerLabel = stream.serverLabel,
                activeProviderId = stream.providerId,
                availableSubtitles = mergedSubs,
                selectedSubtitleIndex = selectedIdx,
                playerErrorMessage = null
            )
        }

        val defaultUa = stream.headers["User-Agent"]
            ?: "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        httpDataSourceFactory.setUserAgent(defaultUa)
        httpDataSourceFactory.setDefaultRequestProperties(stream.headers)

        val subtitleConfigs = if (activeSub != null) {
            val subMime = if (activeSub.format.equals("vtt", true) || activeSub.url.contains(".vtt", true)) {
                MimeTypes.TEXT_VTT
            } else {
                MimeTypes.APPLICATION_SUBRIP
            }
            listOf(
                MediaItem.SubtitleConfiguration.Builder(activeSub.url.toUri())
                    .setMimeType(subMime)
                    .setLanguage(activeSub.language)
                    .setLabel(activeSub.label)
                    // DEFAULT only. FORCED told ExoPlayer this was a "forced narrative" track
                    // (foreign-dialogue only), which interacted badly with embedded captions
                    // and could show the wrong language.
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )
        } else {
            emptyList()
        }

        val itemBuilder = MediaItem.Builder()
            .setUri(stream.streamUrl.toUri())
            .setSubtitleConfigurations(subtitleConfigs)

        // Fix #4 & #5: Only set explicit MIME when known; let ExoPlayer auto-detect when UNKNOWN
        when (stream.mimeType) {
            StreamMimeType.HLS -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            StreamMimeType.DASH -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            StreamMimeType.MP4 -> itemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
            StreamMimeType.UNKNOWN -> { /* Omit setMimeType so ExoPlayer sniffs headers */ }
        }

        Logger.i(tag, "Preparing ExoPlayer: url=${stream.streamUrl} mime=${stream.mimeType} seekMs=$resumePos")

        exoPlayer.setMediaItem(itemBuilder.build())
        exoPlayer.seekTo(resumePos.coerceAtLeast(0L))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        applyTrackSelectionParameters()
    }

    fun updateSubtitlesList(newSubtitles: List<SubtitleTrack>) {
        val merged = sortSubsEnglishFirst(
            (newSubtitles + _state.value.availableSubtitles).distinctBy { it.url }
        )
        if (merged.size == _state.value.availableSubtitles.size &&
            merged.map { it.url } == _state.value.availableSubtitles.map { it.url }
        ) return

        val (idx, activeSub) = resolveSubtitleSelection(merged)
        _state.update { it.copy(availableSubtitles = merged, selectedSubtitleIndex = idx) }

        // If the track that SHOULD be showing (e.g. the English one that just loaded from
        // the subtitle API, after we first started on a non-English resolver track) isn't the
        // one currently loaded, switch to it now.
        if (activeSub != null && activeSub.url != currentSubtitleUrl) {
            loadSubtitleIntoPlayer(activeSub)
        } else {
            applyTrackSelectionParameters()
        }
    }

    /** Reloads the current video with [sub] as its external subtitle, preserving position. */
    private fun loadSubtitleIntoPlayer(sub: SubtitleTrack) {
        val streamUrl = currentStreamUrl ?: return
        val savedPos = exoPlayer.currentPosition.coerceAtLeast(0L)
        currentSubtitleUrl = sub.url
        val subMime = if (sub.format.equals("vtt", true) || sub.url.contains(".vtt", true)) {
            MimeTypes.TEXT_VTT
        } else {
            MimeTypes.APPLICATION_SUBRIP
        }
        val subConfig = MediaItem.SubtitleConfiguration.Builder(sub.url.toUri())
            .setMimeType(subMime)
            .setLanguage(sub.language)
            .setLabel(sub.label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val currentUri = exoPlayer.currentMediaItem?.localConfiguration?.uri ?: streamUrl.toUri()
        val currentMime = exoPlayer.currentMediaItem?.localConfiguration?.mimeType
        val builder = MediaItem.Builder()
            .setUri(currentUri)
            .setSubtitleConfigurations(listOf(subConfig))
        if (currentMime != null) builder.setMimeType(currentMime)
        exoPlayer.setMediaItem(builder.build(), savedPos)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        applyTrackSelectionParameters()
    }

    fun setSkipSegments(segments: List<SkipSegmentModel>) {
        skipSegments = segments
    }

    fun hasAlreadyAutoSkipped(label: String): Boolean = label in autoSkippedTypes

    fun markAutoSkipped(label: String) {
        autoSkippedTypes.add(label)
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** Called when the viewer taps a caption option in the menu. */
    fun selectSubtitleIndex(index: Int) {
        userPickedSubtitle = true
        embeddedSubtitleSelected = index == -2
        val targetSub = if (index >= 0) _state.value.availableSubtitles.getOrNull(index) else null
        preferredSubtitleUrl = targetSub?.url
        preferredSubtitleLanguage = targetSub?.language

        _state.update { it.copy(selectedSubtitleIndex = index) }
        applyTrackSelectionParameters()

        if (targetSub != null && targetSub.url != currentSubtitleUrl && currentStreamUrl != null && currentMedia != null) {
            loadSubtitleIntoPlayer(targetSub)
        } else if (index < 0) {
            // Off (or embedded): nothing external to load, just disable external text
            currentSubtitleUrl = null
            applyTrackSelectionParameters()
        }
    }

    fun setQuality(quality: VideoQualityOption) {
        _state.update { it.copy(selectedQuality = quality) }
        applyTrackSelectionParameters()
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(playbackSpeed = speed) }
        exoPlayer.setPlaybackSpeed(speed)
    }

    fun setAudioMode(mode: String) {
        _state.update { it.copy(selectedAudioMode = mode) }
        applyTrackSelectionParameters()
    }

    fun cycleResizeMode(): String {
        val nextMode = when (_state.value.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        _state.update { it.copy(resizeMode = nextMode) }
        return when (nextMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom: Crop to Fill"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Zoom: Stretch to Screen"
            else -> "Zoom: Fit to Screen"
        }
    }

    fun setErrorMessage(message: String?) {
        _state.update { it.copy(playerErrorMessage = message) }
    }

    private fun applyTrackSelectionParameters() {
        val s = _state.value
        val subsDisabled = s.selectedSubtitleIndex == -1
        val activeSub = if (s.selectedSubtitleIndex >= 0) s.availableSubtitles.getOrNull(s.selectedSubtitleIndex) else null

        val paramsBuilder = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(s.selectedQuality.maxWidth, s.selectedQuality.maxHeight)
            .setForceHighestSupportedBitrate(s.selectedQuality.forceHighest)
            .setPreferredTextLanguage(activeSub?.language ?: "en")
            // false: don't let a stream's unlabelled ("undetermined") embedded caption track
            // get picked over the external subtitle the viewer actually chose — that was a
            // cause of the wrong language appearing while English was selected.
            .setSelectUndeterminedTextLanguage(false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subsDisabled)

        if (currentMedia?.isAnime == true && s.selectedAudioMode != "English Boost") {
            paramsBuilder.setPreferredAudioLanguages("ja", "jpn", "jap", "und")
        } else if (s.selectedAudioMode == "English Boost") {
            paramsBuilder.setPreferredAudioLanguages("en", "eng")
        }

        exoPlayer.trackSelectionParameters = paramsBuilder.build()
        exoPlayer.setPlaybackSpeed(s.playbackSpeed)
    }

    fun saveCurrentProgressNow() {
        val pos = exoPlayer.currentPosition
        val dur = exoPlayer.duration
        val media = currentMedia ?: return
        if (pos > 3_000L) {
            progressTracker.recordProgress(
                media = media,
                positionMs = pos,
                durationMs = dur,
                seasonNumber = currentSeasonNumber,
                episodeNumber = currentEpisodeNumber,
                episodeTitle = currentEpisodeTitle,
                providerId = _state.value.activeProviderId
            )
        }
    }

    fun release() {
        saveCurrentProgressNow()
        tickerJob?.cancel()
        controllerScope.cancel()
        exoPlayer.removeListener(playerListener)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.release()
    }
}
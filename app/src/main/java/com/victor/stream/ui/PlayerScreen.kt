package com.victor.stream.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.victor.stream.di.ServiceLocator
import com.victor.stream.domain.EpisodeRef
import com.victor.stream.domain.PlayOrigin
import com.victor.stream.domain.PlaybackSession
import com.victor.stream.domain.ResolvePlan
import com.victor.stream.domain.ResolvedStream
import com.victor.stream.domain.ResolverEvent
import com.victor.stream.domain.ServerCandidate
import com.victor.stream.player.PlaybackController
import com.victor.stream.player.VIDEO_QUALITY_OPTIONS
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun isTvDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
private fun KeepScreenAwake(enabled: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(enabled, context, view) {
        val window = context.findActivity()?.window
        if (enabled) {
            view.keepScreenOn = true
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { view.keepScreenOn = false }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    sessionId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val isTv = remember(context) { isTvDevice(context) }
    val scope = rememberCoroutineScope()

    val initialSession = remember(sessionId) { ServiceLocator.sessionStore.get(sessionId) }
    if (initialSession == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var activeSession by remember(sessionId) { mutableStateOf(initialSession) }
    val media = activeSession.media
    val activeEpisode = activeSession.episode

    val seasonEpisodes = remember(media.id, activeEpisode?.seasonNumber) {
        ServiceLocator.sessionStore.getSeasonEpisodes(media.id, activeEpisode?.seasonNumber ?: 1)
    }

    val nextEpisodeObj = remember(media.isSeries, activeEpisode, seasonEpisodes) {
        if (!media.isSeries || activeEpisode == null) null
        else seasonEpisodes.sortedBy { it.episodeNumber }.firstOrNull { it.episodeNumber > activeEpisode.episodeNumber }
    }

    val prevEpisodeObj = remember(media.isSeries, activeEpisode, seasonEpisodes) {
        if (!media.isSeries || activeEpisode == null) null
        else seasonEpisodes.sortedByDescending { it.episodeNumber }.firstOrNull { it.episodeNumber < activeEpisode.episodeNumber }
    }

    var controllerVisible by remember { mutableStateOf(true) }
    var showRightSettingsSheet by remember { mutableStateOf(false) }
    var settingsTab by remember { mutableStateOf("Main") }
    var statusToastLabel by remember { mutableStateOf<String?>(null) }
    var autoSkipEnabled by remember { mutableStateOf(ServiceLocator.streamCache.autoSkipEnabled) }

    var isResolvingOverlay by remember { mutableStateOf(false) }
    var resolveOverlayText by remember { mutableStateOf("Switching server...") }
    var activeResolverJob by remember { mutableStateOf<Job?>(null) }
    var backgroundHarvestJob by remember { mutableStateOf<Job?>(null) }

    var preloadedNextStream by remember(activeEpisode) { mutableStateOf<ResolvedStream?>(null) }
    var preloadAttempted by remember(activeEpisode) { mutableStateOf(false) }

    var verifiedServersVersion by remember { mutableIntStateOf(0) }

    val candidateServersList = remember(activeSession, verifiedServersVersion) {
        activeSession.plan.candidates
    }

    LaunchedEffect(statusToastLabel) {
        if (statusToastLabel != null) {
            delay(1800L)
            statusToastLabel = null
        }
    }

    DisposableEffect(activity) {
        val prevOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val window = activity?.window
        val insetsController = if (window != null) WindowCompat.getInsetsController(window, window.decorView) else null
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            activity?.requestedOrientation = prevOrientation
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    lateinit var playbackController: PlaybackController

    fun resolveSingleCandidateOrPlan(
        planToRun: ResolvePlan,
        targetEpisode: EpisodeRef?,
        startAtMs: Long,
        origin: PlayOrigin
    ) {
        backgroundHarvestJob?.cancel()
        activeResolverJob?.cancel()
        ServiceLocator.streamResolver.cancelActive()
        isResolvingOverlay = true

        activeResolverJob = scope.launch {
            ServiceLocator.resolveStreamUseCase.resolve(planToRun, harvestAll = false).collect { event ->
                when (event) {
                    is ResolverEvent.Progress -> {
                        resolveOverlayText = "Loading ${event.label} (${event.index}/${event.total})…"
                    }
                    is ResolverEvent.Captured -> {
                        isResolvingOverlay = false
                        val updatedSession = activeSession.copy(
                            episode = targetEpisode,
                            stream = event.stream,
                            startAtMs = startAtMs,
                            origin = origin
                        )
                        ServiceLocator.sessionStore.put(updatedSession)
                        activeSession = updatedSession
                        verifiedServersVersion++
                        playbackController.prepare(
                            media = media,
                            stream = event.stream,
                            startAtMs = startAtMs,
                            episodeTitle = targetEpisode?.title
                        )
                    }
                    is ResolverEvent.CandidateFailed -> {}
                    is ResolverEvent.AllExhausted -> {
                        isResolvingOverlay = false
                        playbackController.setErrorMessage(
                            "Stream unavailable on selected server. Open Settings (⚙) to pick another server."
                        )
                    }
                }
            }
        }
    }

    fun triggerEpisodeTransition(targetEp: EpisodeRef, preloaded: ResolvedStream?) {
        ServiceLocator.prefsStore.clearEpisodePosition(
            media.id,
            activeEpisode?.seasonNumber,
            activeEpisode?.episodeNumber
        )
        ServiceLocator.prefsStore.clearEpisodePosition(
            media.id,
            targetEp.seasonNumber,
            targetEp.episodeNumber
        )
        playbackController.saveCurrentProgressNow()

        if (preloaded != null) {
            scope.launch {
                val nextPlan = ServiceLocator.resolveStreamUseCase.buildPlan(media, targetEp)
                val nextSession = activeSession.copy(
                    episode = targetEp,
                    plan = nextPlan,
                    stream = preloaded,
                    startAtMs = 0L,
                    origin = PlayOrigin.AUTO_NEXT,
                    failedProviderIds = emptySet()
                )
                ServiceLocator.sessionStore.put(nextSession)
                activeSession = nextSession
                playbackController.prepare(
                    media = media,
                    stream = preloaded,
                    startAtMs = 0L,
                    episodeTitle = targetEp.title
                )
            }
        } else {
            scope.launch {
                val nextPlan = ServiceLocator.resolveStreamUseCase.buildPlan(media, targetEp)
                activeSession = activeSession.copy(
                    episode = targetEp,
                    plan = nextPlan,
                    startAtMs = 0L,
                    failedProviderIds = emptySet()
                )
                resolveOverlayText = "Loading S${targetEp.seasonNumber}:E${targetEp.episodeNumber}..."
                resolveSingleCandidateOrPlan(
                    planToRun = nextPlan,
                    targetEpisode = targetEp,
                    startAtMs = 0L,
                    origin = PlayOrigin.AUTO_NEXT
                )
            }
        }
    }

    playbackController = remember {
        PlaybackController(
            context = context,
            prefsStore = ServiceLocator.prefsStore,
            catalogRepository = ServiceLocator.catalogRepository,
            onPlaybackError = { failedProviderId, _ ->
                if (!isResolvingOverlay) {
                    val (backupSession, nextCandidateToSniff) = ServiceLocator.serverFailoverUseCase(
                        currentSession = activeSession,
                        failedProviderId = failedProviderId
                    )
                    if (backupSession != null && nextCandidateToSniff == null) {
                        val resumePos = playbackController.exoPlayer.currentPosition.coerceAtLeast(activeSession.startAtMs)
                        activeSession = backupSession.copy(startAtMs = resumePos)
                        ServiceLocator.sessionStore.put(activeSession)
                        statusToastLabel = "Switched to ${backupSession.stream.serverLabel}"
                        playbackController.prepare(
                            media = media,
                            stream = backupSession.stream,
                            startAtMs = resumePos,
                            episodeTitle = activeEpisode?.title
                        )
                    } else if (backupSession != null && nextCandidateToSniff != null) {
                        val resumePos = playbackController.exoPlayer.currentPosition.coerceAtLeast(activeSession.startAtMs)
                        activeSession = backupSession
                        val remainingCandidates = activeSession.plan.candidates.filter {
                            it.providerId !in backupSession.failedProviderIds
                        }
                        val failoverPlan = activeSession.plan.copy(candidates = remainingCandidates)
                        resolveOverlayText = "Switching to ${nextCandidateToSniff.label}..."
                        resolveSingleCandidateOrPlan(
                            planToRun = failoverPlan,
                            targetEpisode = activeEpisode,
                            startAtMs = resumePos,
                            origin = PlayOrigin.FAILOVER
                        )
                    } else {
                        playbackController.setErrorMessage(
                            "All automatic servers failed. Open Settings (⚙) to select a server manually."
                        )
                    }
                }
            },
            onEpisodeEnded = {
                if (media.isSeries && nextEpisodeObj != null) {
                    triggerEpisodeTransition(nextEpisodeObj, preloadedNextStream)
                }
            },
            onEnterCreditsWindow = {
                if (media.isSeries && nextEpisodeObj != null && !preloadAttempted && preloadedNextStream == null && !isResolvingOverlay) {
                    preloadAttempted = true
                    backgroundHarvestJob?.cancel()
                    scope.launch {
                        val nextPlan = ServiceLocator.resolveStreamUseCase.buildPlan(media, nextEpisodeObj)
                        ServiceLocator.resolveStreamUseCase.resolve(nextPlan, harvestAll = false).collect { ev ->
                            if (ev is ResolverEvent.Captured) {
                                preloadedNextStream = ev.stream
                            }
                        }
                    }
                }
            },
            onPlaybackReady = {
                // Once main playback is ready, harvest remaining servers in the background for 0ms instant switching
                if (backgroundHarvestJob == null && !isResolvingOverlay) {
                    val remaining = activeSession.plan.candidates.filter {
                        it.providerId != activeSession.stream.providerId &&
                                it.providerId !in activeSession.failedProviderIds
                    }
                    if (remaining.isNotEmpty()) {
                        backgroundHarvestJob = scope.launch {
                            val bgPlan = activeSession.plan.copy(candidates = remaining)
                            ServiceLocator.resolveStreamUseCase.resolve(bgPlan, harvestAll = true).collect { ev ->
                                if (ev is ResolverEvent.Captured) {
                                    verifiedServersVersion++
                                    playbackController.updateSubtitlesList(ev.stream.subtitles)
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    val playerState by playbackController.state.collectAsState()

    KeepScreenAwake(enabled = isTv || playerState.isPlaying || playerState.isBuffering || isResolvingOverlay)

    // Prepare initial stream and load metadata subtitles + skip segments
    LaunchedEffect(activeSession.id, activeSession.stream.streamUrl) {
        playbackController.prepare(
            media = media,
            stream = activeSession.stream,
            startAtMs = activeSession.startAtMs,
            episodeTitle = activeEpisode?.title
        )
    }

    LaunchedEffect(media.id, activeEpisode?.seasonNumber, activeEpisode?.episodeNumber) {
        val fetchedSubs = ServiceLocator.catalogRepository.fetchSubtitlesForMedia(media, activeEpisode)
        if (fetchedSubs.isNotEmpty()) {
            ServiceLocator.streamCache.putSubtitles(
                media.id,
                activeEpisode?.seasonNumber,
                activeEpisode?.episodeNumber,
                fetchedSubs
            )
            playbackController.updateSubtitlesList(fetchedSubs)
        }
        delay(1500L)
        val dur = playbackController.exoPlayer.duration.coerceAtLeast(0L)
        val segs = ServiceLocator.catalogRepository.fetchSkipSegments(media, activeEpisode, dur)
        playbackController.setSkipSegments(segs)
    }

    // Handle auto-skip when entering Intro / Credits segment
    LaunchedEffect(playerState.activeSkipSegment, autoSkipEnabled) {
        val seg = playerState.activeSkipSegment ?: return@LaunchedEffect
        if (autoSkipEnabled && !playbackController.hasAlreadyAutoSkipped(seg.label)) {
            playbackController.markAutoSkipped(seg.label)
            if (seg.label == "Intro") {
                playbackController.seekTo(seg.endMs)
                statusToastLabel = "⏭ Auto-Skipped Intro"
            } else if (seg.label == "Credits" && nextEpisodeObj != null && preloadedNextStream != null) {
                statusToastLabel = "⏭ Auto-Skipping Credits to Next Episode"
                triggerEpisodeTransition(nextEpisodeObj, preloadedNextStream)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeResolverJob?.cancel()
            backgroundHarvestJob?.cancel()
            ServiceLocator.streamResolver.cancelActive()
            playbackController.release()
        }
    }

    fun bindPlayerViewButtons(playerView: PlayerView) {
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
            settingsTab = "Main"
            showRightSettingsSheet = true
        }
        val subBtn = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)
        subBtn?.apply {
            isEnabled = true
            alpha = 1f
            setOnClickListener {
                settingsTab = "Captions"
                showRightSettingsSheet = true
            }
        }

        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_next)?.apply {
            val hasNext = media.isSeries && nextEpisodeObj != null
            visibility = if (media.isSeries) View.VISIBLE else View.GONE
            isEnabled = hasNext
            isClickable = hasNext
            alpha = if (hasNext) 1.0f else 0.35f
            setOnClickListener {
                if (hasNext && nextEpisodeObj != null) {
                    triggerEpisodeTransition(nextEpisodeObj, preloadedNextStream)
                }
            }
        }

        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_prev)?.apply {
            val hasPrev = media.isSeries && prevEpisodeObj != null
            visibility = if (media.isSeries) View.VISIBLE else View.GONE
            isEnabled = hasPrev
            isClickable = hasPrev
            alpha = if (hasPrev) 1.0f else 0.35f
            setOnClickListener {
                if (hasPrev && prevEpisodeObj != null) {
                    triggerEpisodeTransition(prevEpisodeObj, null)
                }
            }
        }

        val parentGroup = subBtn?.parent as? ViewGroup
        if (parentGroup != null && parentGroup.findViewWithTag<View>("custom_zoom_btn") == null) {
            val zoomBtn = TextView(playerView.context).apply {
                tag = "custom_zoom_btn"
                text = "⛶"
                setTextColor(AndroidColor.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                val padH = (10 * playerView.context.resources.displayMetrics.density).toInt()
                setPadding(padH, 0, padH, 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    statusToastLabel = playbackController.cycleResizeMode()
                }
            }
            val subIndex = parentGroup.indexOfChild(subBtn)
            val lp = subBtn.layoutParams ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            parentGroup.addView(zoomBtn, subIndex, lp)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playbackController.exoPlayer
                    useController = true
                    resizeMode = playerState.resizeMode
                    keepScreenOn = true
                    setShowSubtitleButton(true)
                    setShowNextButton(media.isSeries)
                    setShowPreviousButton(media.isSeries)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controllerVisible = (visibility == View.VISIBLE)
                        if (visibility == View.VISIBLE) {
                            post { bindPlayerViewButtons(this) }
                        }
                    })
                    post { bindPlayerViewButtons(this) }
                }
            },
            update = { playerView ->
                playerView.resizeMode = playerState.resizeMode
                playerView.post { bindPlayerViewButtons(playerView) }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = statusToastLabel != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xCC181818),
                border = BorderStroke(1.dp, Color(0xFFE50914))
            ) {
                Text(
                    text = statusToastLabel ?: "",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = controllerVisible && !showRightSettingsSheet,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x66000000))
                ) {
                    Text("←", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text(
                        text = media.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (media.isSeries && activeEpisode != null) {
                        Text(
                            text = "S${activeEpisode.seasonNumber} E${activeEpisode.episodeNumber} • ${activeEpisode.title}",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = playerState.activeSkipSegment != null && !isResolvingOverlay && !showRightSettingsSheet,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 138.dp)
        ) {
            val seg = playerState.activeSkipSegment
            if (seg != null) {
                Button(
                    onClick = {
                        if (seg.label == "Credits" && nextEpisodeObj != null && preloadedNextStream != null) {
                            triggerEpisodeTransition(nextEpisodeObj, preloadedNextStream)
                        } else {
                            playbackController.seekTo(seg.endMs)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xE6222222)),
                    border = BorderStroke(1.5.dp, Color.White),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "⏭ Skip ${seg.label}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = media.isSeries &&
                    nextEpisodeObj != null &&
                    preloadedNextStream != null &&
                    playerState.secondsRemaining in 1..75 &&
                    !isResolvingOverlay &&
                    !showRightSettingsSheet,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 78.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xEE181818),
                border = BorderStroke(1.5.dp, Color(0xFFE50914))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column {
                        Text(
                            text = "Up Next in ${playerState.secondsRemaining}s (Buffered)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (nextEpisodeObj != null) {
                            Text(
                                text = "S${nextEpisodeObj.seasonNumber} E${nextEpisodeObj.episodeNumber} • ${nextEpisodeObj.title}",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Button(
                        onClick = {
                            if (nextEpisodeObj != null) {
                                triggerEpisodeTransition(nextEpisodeObj, preloadedNextStream)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("⏭ Next Episode", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showRightSettingsSheet,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showRightSettingsSheet = false }
            )
        }

        AnimatedVisibility(
            visible = showRightSettingsSheet,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .width(310.dp)
                    .fillMaxHeight()
                    .background(Color(0xF2161616))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (settingsTab != "Main") {
                            Text(
                                text = "←",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { settingsTab = "Main" }
                                    .padding(end = 4.dp)
                            )
                        }
                        Text(
                            text = when (settingsTab) {
                                "Quality" -> "Quality"
                                "Audio" -> "Audio"
                                "Captions" -> "Captions"
                                "Speed" -> "Speed"
                                "Servers" -> "Servers (${candidateServersList.size})"
                                else -> "Settings"
                            },
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "✕",
                        color = Color.White,
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { showRightSettingsSheet = false }
                            .padding(6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                when (settingsTab) {
                    "Main" -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                RightDrawerMainItem(
                                    iconText = "HD",
                                    isBoxedIcon = true,
                                    title = "Quality",
                                    subtitle = playerState.selectedQuality.shortLabel
                                ) { settingsTab = "Quality" }
                            }
                            item {
                                RightDrawerMainItem(
                                    iconText = "🔊",
                                    isBoxedIcon = false,
                                    title = "Audio",
                                    subtitle = playerState.selectedAudioMode
                                ) { settingsTab = "Audio" }
                            }
                            item {
                                val capLabel = when {
                                    playerState.selectedSubtitleIndex == -1 -> "Off"
                                    playerState.availableSubtitles.isNotEmpty() ->
                                        playerState.availableSubtitles.getOrNull(playerState.selectedSubtitleIndex)?.label ?: "English"
                                    playerState.hasEmbeddedCaptions -> "Embedded CC"
                                    else -> "Off"
                                }
                                RightDrawerMainItem(
                                    iconText = "CC",
                                    isBoxedIcon = true,
                                    title = "Captions",
                                    subtitle = capLabel
                                ) { settingsTab = "Captions" }
                            }
                            item {
                                val speedLabel = if (playerState.playbackSpeed == 1.0f) "Normal" else "${playerState.playbackSpeed}x"
                                RightDrawerMainItem(
                                    iconText = "⏲",
                                    isBoxedIcon = false,
                                    title = "Speed",
                                    subtitle = speedLabel
                                ) { settingsTab = "Speed" }
                            }
                            item {
                                RightDrawerMainItem(
                                    iconText = "⏭",
                                    isBoxedIcon = false,
                                    title = "Auto-Skip Intro/Outro",
                                    subtitle = if (autoSkipEnabled) "Enabled" else "Disabled"
                                ) {
                                    autoSkipEnabled = !autoSkipEnabled
                                    ServiceLocator.streamCache.autoSkipEnabled = autoSkipEnabled
                                }
                            }
                            item {
                                RightDrawerMainItem(
                                    iconText = "🖥",
                                    isBoxedIcon = false,
                                    title = "Servers (${candidateServersList.size})",
                                    subtitle = playerState.activeServerLabel
                                ) { settingsTab = "Servers" }
                            }
                        }
                    }

                    "Quality" -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(VIDEO_QUALITY_OPTIONS) { _, qOpt ->
                                RightDrawerSubOption(
                                    label = qOpt.label,
                                    isSelected = playerState.selectedQuality.label == qOpt.label
                                ) {
                                    playbackController.setQuality(qOpt)
                                    settingsTab = "Main"
                                }
                            }
                        }
                    }

                    "Audio" -> {
                        val audioModes = listOf("Default (Original Audio)", "English Boost")
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(audioModes) { _, mode ->
                                RightDrawerSubOption(
                                    label = mode,
                                    isSelected = playerState.selectedAudioMode == mode
                                ) {
                                    playbackController.setAudioMode(mode)
                                    settingsTab = "Main"
                                }
                            }
                        }
                    }

                    "Captions" -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                RightDrawerSubOption(
                                    label = "Off",
                                    isSelected = playerState.selectedSubtitleIndex == -1
                                ) {
                                    playbackController.selectSubtitleIndex(-1)
                                    settingsTab = "Main"
                                }
                            }
                            if (playerState.hasEmbeddedCaptions) {
                                item {
                                    RightDrawerSubOption(
                                        label = "Embedded Stream Captions",
                                        isSelected = playerState.selectedSubtitleIndex == -2
                                    ) {
                                        playbackController.selectSubtitleIndex(-2)
                                        settingsTab = "Main"
                                    }
                                }
                            }
                            itemsIndexed(playerState.availableSubtitles) { idx, sub ->
                                RightDrawerSubOption(
                                    label = sub.label,
                                    isSelected = playerState.selectedSubtitleIndex == idx
                                ) {
                                    playbackController.selectSubtitleIndex(idx)
                                    settingsTab = "Main"
                                }
                            }
                        }
                    }

                    "Speed" -> {
                        val speeds = listOf(
                            0.5f to "0.5x",
                            0.75f to "0.75x",
                            1.0f to "Normal (1x)",
                            1.25f to "1.25x",
                            1.5f to "1.5x",
                            2.0f to "2.0x"
                        )
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(speeds) { _, (spdVal, spdText) ->
                                RightDrawerSubOption(
                                    label = spdText,
                                    isSelected = playerState.playbackSpeed == spdVal
                                ) {
                                    playbackController.setSpeed(spdVal)
                                    settingsTab = "Main"
                                }
                            }
                        }
                    }

                    "Servers" -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(candidateServersList) { _, candidate: ServerCandidate ->
                                val isCurrent = playerState.activeProviderId == candidate.providerId
                                val cachedStream = ServiceLocator.resolveStreamUseCase.getCachedStream(
                                    media.id,
                                    activeEpisode,
                                    candidate.providerId
                                )
                                val displayLabel = if (cachedStream != null) {
                                    "${candidate.label} ⚡"
                                } else {
                                    candidate.label
                                }
                                RightDrawerSubOption(
                                    label = displayLabel,
                                    isSelected = isCurrent
                                ) {
                                    showRightSettingsSheet = false
                                    val savedPos = playbackController.exoPlayer.currentPosition.coerceAtLeast(0L)
                                    if (cachedStream != null) {
                                        val updatedSession = activeSession.copy(
                                            stream = cachedStream,
                                            startAtMs = savedPos,
                                            origin = PlayOrigin.MANUAL_SERVER
                                        )
                                        ServiceLocator.sessionStore.put(updatedSession)
                                        activeSession = updatedSession
                                        statusToastLabel = "Switched to ${cachedStream.serverLabel}"
                                        playbackController.prepare(
                                            media = media,
                                            stream = cachedStream,
                                            startAtMs = savedPos,
                                            episodeTitle = activeEpisode?.title
                                        )
                                    } else {
                                        val singlePlan = activeSession.plan.copy(candidates = listOf(candidate))
                                        resolveOverlayText = "Loading ${candidate.label}..."
                                        resolveSingleCandidateOrPlan(
                                            planToRun = singlePlan,
                                            targetEpisode = activeEpisode,
                                            startAtMs = savedPos,
                                            origin = PlayOrigin.MANUAL_SERVER
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isResolvingOverlay) {
            ResolvingOverlay(
                statusText = resolveOverlayText,
                onCancel = {
                    activeResolverJob?.cancel()
                    ServiceLocator.streamResolver.cancelActive()
                    isResolvingOverlay = false
                }
            )
        }

        if (playerState.playerErrorMessage != null) {
            Text(
                text = playerState.playerErrorMessage!!,
                color = Color(0xFFFF5252),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 58.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun RightDrawerMainItem(
    iconText: String,
    isBoxedIcon: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isBoxedIcon) {
            Box(
                modifier = Modifier
                    .size(width = 28.dp, height = 22.dp)
                    .border(1.5.dp, Color.White, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = iconText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
        } else {
            Box(
                modifier = Modifier.size(width = 28.dp, height = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = iconText, color = Color.White, fontSize = 16.sp)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(text = subtitle, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(text = "›", color = Color.Gray, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
fun RightDrawerSubOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFF2B2B2B) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else Color.LightGray,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Text(text = "✓", color = Color(0xFFE50914), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
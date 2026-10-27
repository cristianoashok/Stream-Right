package com.victor.stream.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.victor.stream.data.GENRE_MAP
import com.victor.stream.data.PrefsStore
import com.victor.stream.di.ServiceLocator
import com.victor.stream.domain.EpisodeRef
import com.victor.stream.domain.MediaItemModel
import com.victor.stream.domain.PlayOrigin
import com.victor.stream.domain.PlaybackSession
import com.victor.stream.domain.ResolverEvent
import com.victor.stream.domain.SeasonInfo
import com.victor.stream.domain.WatchProgressEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ==========================================
// 1. SHARED UI COMPONENTS (PosterCard, Carousel, ResolvingOverlay)
// ==========================================

@Composable
fun ResolvingOverlay(
    statusText: String,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFFE50914))
                Text(
                    text = statusText,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel", color = Color.LightGray)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueWatchingRow(
    continueList: List<WatchProgressEntry>,
    onContinueClick: (WatchProgressEntry) -> Unit,
    onRemoveEntry: (Int) -> Unit
) {
    var showPopupFor by remember { mutableStateOf<WatchProgressEntry?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Continue Watching",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(continueList) { entry ->
                var isFocused by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .width(115.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            if (isFocused) 2.dp else 0.dp,
                            if (isFocused) Color.White else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusable()
                        .combinedClickable(
                            onClick = { onContinueClick(entry) },
                            onLongClick = { showPopupFor = entry }
                        )
                ) {
                    Box {
                        AsyncImage(
                            model = entry.media.posterUrl,
                            contentDescription = entry.media.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(165.dp)
                                .background(Color.DarkGray)
                        )
                        if (entry.seasonNumber != null && entry.episodeNumber != null) {
                            Text(
                                text = "S${entry.seasonNumber}:E${entry.episodeNumber}",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(5.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xCC000000))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                        if (entry.durationMs > 0L) {
                            LinearProgressIndicator(
                                progress = {
                                    (entry.positionMs.toFloat() / entry.durationMs.toFloat()).coerceIn(0f, 1f)
                                },
                                color = Color(0xFFE50914),
                                trackColor = Color.DarkGray,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .align(Alignment.BottomCenter)
                            )
                        }
                    }
                    Text(
                        text = entry.media.title,
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp, bottom = 2.dp)
                    )
                }
            }
        }
    }

    if (showPopupFor != null) {
        AlertDialog(
            onDismissRequest = { showPopupFor = null },
            title = { Text("Options") },
            text = { Text("Remove \"${showPopupFor?.media?.title}\" from Continue Watching?") },
            confirmButton = {
                Button(
                    onClick = {
                        showPopupFor?.let { onRemoveEntry(it.media.id) }
                        showPopupFor = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
                ) { Text("Remove", color = Color.White) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPopupFor = null }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }
}

@Composable
fun MediaCarouselRow(
    title: String,
    items: List<MediaItemModel>,
    showRankBadge: Boolean = false,
    onMediaClick: (MediaItemModel) -> Unit,
    onWatchLaterChanged: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(items) { index, item ->
                PosterCard(
                    item = item,
                    rankNumber = if (showRankBadge) index + 1 else null,
                    isWatchLaterScreen = false,
                    onClick = { onMediaClick(item) },
                    onWatchLaterChanged = onWatchLaterChanged,
                    modifier = Modifier.width(115.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    item: MediaItemModel,
    rankNumber: Int? = null,
    isWatchLaterScreen: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
    onWatchLaterChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isFocused by remember { mutableStateOf(false) }
    var showWatchLaterPopup by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                if (isFocused) 2.dp else 0.dp,
                if (isFocused) Color.White else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showWatchLaterPopup = true }
            )
    ) {
        Box {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(165.dp)
                    .background(Color.DarkGray)
            )
            if (rankNumber != null) {
                Text(
                    text = "#$rankNumber",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE50914))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Text(
                text = item.badgeLabel,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Text(
            text = item.title,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp, bottom = 2.dp)
        )
    }

    if (showWatchLaterPopup) {
        AlertDialog(
            onDismissRequest = { showWatchLaterPopup = false },
            title = { Text(item.title) },
            text = {
                Text(
                    if (isWatchLaterScreen) "Remove this item from Watch Later?"
                    else "Add this item to your Watch Later list?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isWatchLaterScreen) {
                            ServiceLocator.prefsStore.removeFromWatchLater(item.id)
                            scope.launch {
                                ServiceLocator.catalogRepository.syncProgressToCloud(item, inWatchlist = false)
                            }
                        } else {
                            ServiceLocator.prefsStore.addToWatchLater(item)
                            scope.launch {
                                ServiceLocator.catalogRepository.syncProgressToCloud(item, inWatchlist = true)
                            }
                        }
                        showWatchLaterPopup = false
                        onWatchLaterChanged()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
                ) {
                    Text(if (isWatchLaterScreen) "Remove" else "Watch Later", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWatchLaterPopup = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }
}

// ==========================================
// 2. HOME SCREEN & SIDEBAR NAVIGATION
// ==========================================

@Composable
fun HomeScreenWithSidebar(
    selectedSection: String,
    onSelectSection: (String) -> Unit,
    isLoading: Boolean,
    trendingAll: List<MediaItemModel>,
    trendingMovies: List<MediaItemModel>,
    popularMovies: List<MediaItemModel>,
    topRatedMovies: List<MediaItemModel>,
    trendingSeries: List<MediaItemModel>,
    popularSeries: List<MediaItemModel>,
    topRatedSeries: List<MediaItemModel>,
    trendingAnime: List<MediaItemModel>,
    popularAnime: List<MediaItemModel>,
    anilistWatchingList: List<MediaItemModel>,
    anilistPlanningList: List<MediaItemModel>,
    tmdbWatchlistMovies: List<MediaItemModel>,
    tmdbWatchlistSeries: List<MediaItemModel>,
    onOpenSearchScreen: () -> Unit,
    onOpenSettingsScreen: () -> Unit,
    onMediaClick: (MediaItemModel) -> Unit,
    onContinueClick: (WatchProgressEntry) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var continueList by remember { mutableStateOf(ServiceLocator.prefsStore.getContinueWatching()) }
    var watchLaterList by remember { mutableStateOf(ServiceLocator.prefsStore.getWatchLater()) }
    var tmdbAuth by remember { mutableStateOf(ServiceLocator.prefsStore.getTmdbAuth()) }
    var aniAuth by remember { mutableStateOf(ServiceLocator.prefsStore.getAniListAuth()) }

    LaunchedEffect(selectedSection, drawerState.isOpen) {
        continueList = ServiceLocator.prefsStore.getContinueWatching()
        watchLaterList = ServiceLocator.prefsStore.getWatchLater()
        tmdbAuth = ServiceLocator.prefsStore.getTmdbAuth()
        aniAuth = ServiceLocator.prefsStore.getAniListAuth()
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color(0x99000000),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF161616),
                modifier = Modifier.width(265.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Stream",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    Surface(
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                onOpenSearchScreen()
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xFF242424),
                        border = BorderStroke(1.dp, Color(0xFF383838)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(text = "🔍", fontSize = 14.sp)
                            Text(
                                text = "Search TMDB & Anime...",
                                color = Color(0xFF9E9E9E),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    HorizontalDivider(color = Color(0xFF2C2C2C), modifier = Modifier.padding(vertical = 4.dp))

                    val sections = listOf(
                        "Home" to "Home",
                        "Movies" to "Movies",
                        "Series" to "TV Series",
                        "Anime" to "Anime (Sub)",
                        "WatchLater" to "Watch Later (${watchLaterList.size})"
                    )
                    sections.forEach { (key, label) ->
                        NavigationDrawerItem(
                            label = { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                            selected = selectedSection == key,
                            onClick = {
                                onSelectSection(key)
                                scope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Color(0xFFE50914),
                                unselectedContainerColor = Color.Transparent,
                                selectedTextColor = Color.White,
                                unselectedTextColor = Color.LightGray
                            )
                        )
                    }

                    HorizontalDivider(color = Color(0xFF2C2C2C), modifier = Modifier.padding(vertical = 4.dp))

                    val linkedCount = listOfNotNull(tmdbAuth.sessionId, aniAuth.token).size
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text("⚙ Settings & Accounts", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (linkedCount > 0) "$linkedCount/2 Accounts Linked" else "TMDB & AniList Login",
                                    fontSize = 11.sp,
                                    color = if (linkedCount > 0) Color(0xFF4CAF50) else Color.Gray
                                )
                            }
                        },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                onOpenSettingsScreen()
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color(0xFF242424),
                            unselectedTextColor = Color.White
                        )
                    )
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        scope.launch {
                            if (drawerState.isClosed) drawerState.open() else drawerState.close()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(36.dp)
                ) {
                    Text(text = "☰", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = when (selectedSection) {
                        "Movies" -> "Movies"
                        "Series" -> "TV Series"
                        "Anime" -> "Anime (Sub)"
                        "WatchLater" -> "Watch Later"
                        else -> "Stream"
                    },
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFE50914))
                }
            } else {
                when (selectedSection) {
                    "WatchLater" -> {
                        if (watchLaterList.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Your Watch Later list is empty.\nLong press any poster to add items here!",
                                    color = Color.Gray,
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 110.dp),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(watchLaterList) { item ->
                                    PosterCard(
                                        item = item,
                                        isWatchLaterScreen = true,
                                        onClick = { onMediaClick(item) },
                                        onWatchLaterChanged = {
                                            watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    "Anime" -> {
                        AnimeCatalogSection(
                            trendingAnime = trendingAnime,
                            popularAnime = popularAnime,
                            anilistWatchingList = anilistWatchingList,
                            anilistPlanningList = anilistPlanningList,
                            onAnimeClick = onMediaClick,
                            onWatchLaterChanged = {
                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                            }
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            when (selectedSection) {
                                "Home" -> {
                                    if (continueList.isNotEmpty()) {
                                        item {
                                            ContinueWatchingRow(
                                                continueList = continueList,
                                                onContinueClick = onContinueClick,
                                                onRemoveEntry = { id ->
                                                    continueList = ServiceLocator.prefsStore.removeFromContinueWatching(id)
                                                }
                                            )
                                        }
                                    }
                                    if (watchLaterList.isNotEmpty()) {
                                        item {
                                            MediaCarouselRow(
                                                title = "Watch Later / Watchlist",
                                                items = watchLaterList,
                                                showRankBadge = false,
                                                onMediaClick = onMediaClick,
                                                onWatchLaterChanged = {
                                                    watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                                }
                                            )
                                        }
                                    }
                                    item {
                                        MediaCarouselRow(
                                            title = "Trending Movies & TV Shows",
                                            items = trendingAll,
                                            showRankBadge = false,
                                            onMediaClick = onMediaClick,
                                            onWatchLaterChanged = {
                                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                            }
                                        )
                                    }
                                    if (trendingAnime.isNotEmpty()) {
                                        item {
                                            MediaCarouselRow(
                                                title = "Trending Anime (Sub)",
                                                items = trendingAnime,
                                                showRankBadge = false,
                                                onMediaClick = onMediaClick,
                                                onWatchLaterChanged = {
                                                    watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                                }
                                            )
                                        }
                                    }
                                    item {
                                        MediaCarouselRow(
                                            title = "Trending Movies Today",
                                            items = trendingMovies,
                                            showRankBadge = false,
                                            onMediaClick = onMediaClick,
                                            onWatchLaterChanged = {
                                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                            }
                                        )
                                    }
                                    item {
                                        MediaCarouselRow(
                                            title = "Trending TV Series Today",
                                            items = trendingSeries,
                                            showRankBadge = false,
                                            onMediaClick = onMediaClick,
                                            onWatchLaterChanged = {
                                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                            }
                                        )
                                    }
                                }

                                "Movies" -> {
                                    val movieContinue = continueList.filter { !it.media.isSeries && !it.media.isAnime }
                                    if (movieContinue.isNotEmpty()) {
                                        item {
                                            ContinueWatchingRow(
                                                continueList = movieContinue,
                                                onContinueClick = onContinueClick,
                                                onRemoveEntry = { id ->
                                                    continueList = ServiceLocator.prefsStore.removeFromContinueWatching(id)
                                                }
                                            )
                                        }
                                    }
                                    val movieWatchLater = (tmdbWatchlistMovies + watchLaterList.filter { !it.isSeries && !it.isAnime }).distinctBy { it.id }
                                    if (movieWatchLater.isNotEmpty()) {
                                        item {
                                            MediaCarouselRow(
                                                title = "Movies Watchlist (TMDB Synced)",
                                                items = movieWatchLater,
                                                showRankBadge = false,
                                                onMediaClick = onMediaClick,
                                                onWatchLaterChanged = {
                                                    watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                                }
                                            )
                                        }
                                    }
                                    item {
                                        MediaCarouselRow(
                                            title = "Top 10 Movies Today",
                                            items = trendingMovies.take(10),
                                            showRankBadge = true,
                                            onMediaClick = onMediaClick,
                                            onWatchLaterChanged = {
                                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                            }
                                        )
                                    }
                                    item {
                                        MediaCarouselRow(
                                            title = "Current Trending Movies",
                                            items = trendingMovies,
                                            showRankBadge = false,
                                            onMediaClick = onMediaClick,
                                            onWatchLaterChanged = {
                                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                            }
                                        )
                                    }
                                    item {
                                        MediaCarouselRow(
                                            title = "Top Rated Movies",
                                            items = topRatedMovies,
                                            showRankBadge = false,
                                            onMediaClick = onMediaClick,
                                            onWatchLaterChanged = {
                                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                            }
                                        )
                                    }
                                    item {
                                        MediaCarouselRow(
                                            title = "Popular Movies",
                                            items = popularMovies,
                                            showRankBadge = false,
                                            onMediaClick = onMediaClick,
                                            onWatchLaterChanged = {
                                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                            }
                                        )
                                    }
                                }

                                "Series" -> {
                                    val seriesContinue = continueList.filter { it.media.isSeries && !it.media.isAnime }
                                    if (seriesContinue.isNotEmpty()) {
                                        item {
                                            ContinueWatchingRow(
                                                continueList = seriesContinue,
                                                onContinueClick = onContinueClick,
                                                onRemoveEntry = { id ->
                                                    continueList = ServiceLocator.prefsStore.removeFromContinueWatching(id)
                                                }
                                            )
                                        }
                                    }
                                    val seriesWatchLater = (tmdbWatchlistSeries + watchLaterList.filter { it.isSeries && !it.isAnime }).distinctBy { it.id }
                                    if (seriesWatchLater.isNotEmpty()) {
                                        item {
                                            MediaCarouselRow(
                                                title = "Series Watchlist (TMDB Synced)",
                                                items = seriesWatchLater,
                                                showRankBadge = false,
                                                onMediaClick = onMediaClick,
                                                onWatchLaterChanged = {
                                                    watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                                }
                                            )
                                        }
                                    }
                                    item {
                                        MediaCarouselRow(
                                            title = "Top 10 TV Series Today",
                                            items = trendingSeries.take(10),
                                            showRankBadge = true,
                                            onMediaClick = onMediaClick,
                                            onWatchLaterChanged = {
                                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                            }
                                        )
                                    }
                                    item {
                                        MediaCarouselRow(
                                            title = "Current Trending TV Series",
                                            items = trendingSeries,
                                            showRankBadge = false,
                                            onMediaClick = onMediaClick,
                                            onWatchLaterChanged = {
                                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                            }
                                        )
                                    }
                                    item {
                                        MediaCarouselRow(
                                            title = "Top Rated TV Series",
                                            items = topRatedSeries,
                                            showRankBadge = false,
                                            onMediaClick = onMediaClick,
                                            onWatchLaterChanged = {
                                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                            }
                                        )
                                    }
                                    item {
                                        MediaCarouselRow(
                                            title = "Popular TV Series",
                                            items = popularSeries,
                                            showRankBadge = false,
                                            onMediaClick = onMediaClick,
                                            onWatchLaterChanged = {
                                                watchLaterList = ServiceLocator.prefsStore.getWatchLater()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnimeCatalogSection(
    trendingAnime: List<MediaItemModel>,
    popularAnime: List<MediaItemModel>,
    anilistWatchingList: List<MediaItemModel>,
    anilistPlanningList: List<MediaItemModel>,
    onAnimeClick: (MediaItemModel) -> Unit,
    onWatchLaterChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var localTrending by remember(trendingAnime) { mutableStateOf(trendingAnime) }
    var localPopular by remember(popularAnime) { mutableStateOf(popularAnime) }
    var isFetchingNow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (localTrending.isEmpty() || localPopular.isEmpty()) {
            isFetchingNow = true
            val tDef = async(Dispatchers.IO) { ServiceLocator.catalogRepository.fetchTrendingAnime() }
            val pDef = async(Dispatchers.IO) { ServiceLocator.catalogRepository.fetchPopularAnime() }
            val tRes = tDef.await()
            val pRes = pDef.await()
            if (tRes.isNotEmpty()) localTrending = tRes
            if (pRes.isNotEmpty()) localPopular = pRes
            isFetchingNow = false
        }
    }

    if (isFetchingNow && localTrending.isEmpty() && localPopular.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFFE50914))
                Text("Loading Anime Catalog (SUB)...", color = Color.LightGray, fontSize = 14.sp)
            }
        }
    } else if (localTrending.isEmpty() && localPopular.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Could not load Anime Catalog.", color = Color.LightGray, fontSize = 14.sp)
                Button(
                    onClick = {
                        isFetchingNow = true
                        scope.launch {
                            localTrending = ServiceLocator.catalogRepository.fetchTrendingAnime()
                            localPopular = ServiceLocator.catalogRepository.fetchPopularAnime()
                            isFetchingNow = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
                ) {
                    Text("Retry Loading Anime", color = Color.White)
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (anilistWatchingList.isNotEmpty()) {
                item {
                    MediaCarouselRow(
                        title = "Currently Watching (AniList Synced)",
                        items = anilistWatchingList,
                        showRankBadge = false,
                        onMediaClick = onAnimeClick,
                        onWatchLaterChanged = onWatchLaterChanged
                    )
                }
            }
            if (anilistPlanningList.isNotEmpty()) {
                item {
                    MediaCarouselRow(
                        title = "Planning / Watchlist (AniList Synced)",
                        items = anilistPlanningList,
                        showRankBadge = false,
                        onMediaClick = onAnimeClick,
                        onWatchLaterChanged = onWatchLaterChanged
                    )
                }
            }
            if (localTrending.isNotEmpty()) {
                item {
                    MediaCarouselRow(
                        title = "Top 10 Trending Anime (Sub)",
                        items = localTrending.take(10),
                        showRankBadge = true,
                        onMediaClick = onAnimeClick,
                        onWatchLaterChanged = onWatchLaterChanged
                    )
                }
                item {
                    MediaCarouselRow(
                        title = "Trending Anime (Sub)",
                        items = localTrending,
                        showRankBadge = false,
                        onMediaClick = onAnimeClick,
                        onWatchLaterChanged = onWatchLaterChanged
                    )
                }
            }
            if (localPopular.isNotEmpty()) {
                item {
                    MediaCarouselRow(
                        title = "All-Time Popular Anime (Sub)",
                        items = localPopular,
                        showRankBadge = false,
                        onMediaClick = onAnimeClick,
                        onWatchLaterChanged = onWatchLaterChanged
                    )
                }
            }
        }
    }
}

// ==========================================
// 3. UNIFIED MEDIA & ANIME DETAILS SCREEN
//    (Uses StreamResolver Flow -> Creates PlaybackSession -> Navigates by sessionId)
// ==========================================

@Composable
fun MediaDetailsScreen(
    initialMedia: MediaItemModel,
    onBack: () -> Unit,
    onNavigateToSession: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var media by remember(initialMedia.id) { mutableStateOf(initialMedia) }
    var inWatchLater by remember(media.id) {
        mutableStateOf(ServiceLocator.prefsStore.getWatchLater().any { it.id == media.id })
    }
    val continueEntry = remember(media.id) {
        ServiceLocator.prefsStore.getContinueWatching().firstOrNull { it.media.id == media.id }
    }

    var seasonsList by remember { mutableStateOf<List<SeasonInfo>>(initialMedia.seasons) }
    var selectedSeasonNum by remember { mutableIntStateOf(continueEntry?.seasonNumber ?: 1) }
    var episodesList by remember { mutableStateOf<List<EpisodeRef>>(emptyList()) }
    var isEpisodesLoading by remember { mutableStateOf(false) }

    var selectedChunkIndex by remember { mutableIntStateOf(0) }
    var seasonSortAscending by remember { mutableStateOf(true) }
    var episodeSortAscending by remember { mutableStateOf(true) }

    var isResolving by remember { mutableStateOf(false) }
    var resolveStatusText by remember { mutableStateOf("Resolving stream...") }
    var activeResolveJob by remember { mutableStateOf<Job?>(null) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialMedia.id) {
        if (media.isSeries) {
            val enriched = ServiceLocator.catalogRepository.fetchTvDetailsAndSeasons(media)
            media = enriched
            seasonsList = enriched.seasons
            if (seasonsList.isNotEmpty() && seasonsList.none { it.seasonNumber == selectedSeasonNum }) {
                selectedSeasonNum = seasonsList.first().seasonNumber
            }
        }
    }

    LaunchedEffect(media.id, selectedSeasonNum) {
        if (media.isSeries) {
            isEpisodesLoading = true
            val loaded = ServiceLocator.catalogRepository.fetchSeasonEpisodes(media, selectedSeasonNum)
            episodesList = loaded
            selectedChunkIndex = 0
            ServiceLocator.sessionStore.putSeasonEpisodes(media.id, selectedSeasonNum, loaded)
            isEpisodesLoading = false
        }
    }

    val episodeChunks = remember(episodesList) { episodesList.chunked(50) }

    fun startStreamResolution(episode: EpisodeRef?, startFromZero: Boolean, origin: PlayOrigin) {
        activeResolveJob?.cancel()
        ServiceLocator.streamResolver.cancelActive()
        isResolving = true
        resolveStatusText = if (episode != null) {
            "Connecting servers for ${media.title} S${episode.seasonNumber}:E${episode.episodeNumber}..."
        } else {
            "Connecting servers for ${media.title}..."
        }

        activeResolveJob = scope.launch {
            val plan = ServiceLocator.resolveStreamUseCase.buildPlan(media = media, episode = episode)
            if (plan.candidates.isEmpty()) {
                isResolving = false
                errorDialogMessage = "No compatible streaming servers found for \"${media.title}\"."
                return@launch
            }

            ServiceLocator.resolveStreamUseCase.resolve(plan, harvestAll = false).collect { event ->
                when (event) {
                    is ResolverEvent.Progress -> {
                        resolveStatusText = "Loading ${event.label} (${event.index}/${event.total})…"
                    }
                    is ResolverEvent.Captured -> {
                        isResolving = false
                        val savedPos = if (startFromZero) {
                            0L
                        } else {
                            ServiceLocator.prefsStore.getEpisodePositionMs(
                                media.id,
                                episode?.seasonNumber,
                                episode?.episodeNumber
                            )
                        }
                        val session = PlaybackSession(
                            id = UUID.randomUUID().toString(),
                            media = media,
                            episode = episode,
                            plan = plan,
                            stream = event.stream,
                            startAtMs = savedPos,
                            origin = origin
                        )
                        ServiceLocator.sessionStore.put(session)
                        if (media.isAnime) {
                            scope.launch(Dispatchers.IO) {
                                ServiceLocator.catalogRepository.syncProgressToCloud(
                                    media = media,
                                    inWatchlist = true,
                                    watchedEpisode = episode?.episodeNumber ?: 1
                                )
                            }
                        }
                        onNavigateToSession(session.id)
                    }
                    is ResolverEvent.CandidateFailed -> {
                        // Resolver automatically advances to the next candidate
                    }
                    is ResolverEvent.AllExhausted -> {
                        isResolving = false
                        errorDialogMessage = "Could not find an active stream across servers for \"${media.title}\". Please try again."
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeResolveJob?.cancel()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("← Back", color = Color.White, fontSize = 13.sp)
                }

                if (media.isAnime) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0x33E50914),
                        border = BorderStroke(1.dp, Color(0xFFE50914))
                    ) {
                        Text(
                            text = "🇯🇵 SUBTITLED (ORIGINAL AUDIO)",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        if (inWatchLater) {
                            ServiceLocator.prefsStore.removeFromWatchLater(media.id)
                            inWatchLater = false
                            scope.launch {
                                ServiceLocator.catalogRepository.syncProgressToCloud(media, inWatchlist = false)
                            }
                        } else {
                            ServiceLocator.prefsStore.addToWatchLater(media)
                            inWatchLater = true
                            scope.launch {
                                ServiceLocator.catalogRepository.syncProgressToCloud(media, inWatchlist = true)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (inWatchLater) Color(0xFF3A3A3A) else Color(0xFF2C2C2C)
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = if (inWatchLater) "✓ Saved" else "+ Watch Later",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.width(120.dp)
                ) {
                    AsyncImage(
                        model = media.posterUrl,
                        contentDescription = media.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(120.dp)
                            .height(175.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.DarkGray)
                    )
                    if (continueEntry != null) {
                        Button(
                            onClick = {
                                val epRef = if (media.isSeries) {
                                    EpisodeRef(
                                        seasonNumber = continueEntry.seasonNumber ?: 1,
                                        episodeNumber = continueEntry.episodeNumber ?: 1,
                                        title = continueEntry.episodeTitle ?: "Episode ${continueEntry.episodeNumber ?: 1}"
                                    )
                                } else null
                                startStreamResolution(epRef, startFromZero = false, origin = PlayOrigin.RESUME)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                        ) {
                            Text(
                                text = if (continueEntry.seasonNumber != null && continueEntry.episodeNumber != null) {
                                    "▶ Resume S${continueEntry.seasonNumber}:E${continueEntry.episodeNumber}"
                                } else {
                                    "▶ Resume"
                                },
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "${media.typeHeaderLabel} • ${media.releaseYear} • ★ ${String.format("%.1f", media.rating)}",
                        color = Color(0xFFE50914),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = media.title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (!media.romajiTitle.isNullOrBlank() && media.romajiTitle != media.title) {
                        Text(
                            text = media.romajiTitle!!,
                            color = Color.Gray,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = media.overview,
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!media.isSeries) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                startStreamResolution(
                                    episode = null,
                                    startFromZero = true,
                                    origin = PlayOrigin.USER_PICK
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text(
                                text = if (media.isAnime) "▶ Play Movie (SUB)" else "▶ Play Movie",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (media.isSeries) {
                HorizontalDivider(color = Color.DarkGray)

                if (seasonsList.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Seasons", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = { seasonSortAscending = !seasonSortAscending },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = if (seasonSortAscending) "1 → ${seasonsList.size}" else "${seasonsList.size} → 1",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                    }

                    val sortedSeasons = if (seasonSortAscending) {
                        seasonsList.sortedBy { it.seasonNumber }
                    } else {
                        seasonsList.sortedByDescending { it.seasonNumber }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(sortedSeasons) { season ->
                            val isSelected = selectedSeasonNum == season.seasonNumber
                            var isFocused by remember { mutableStateOf(false) }
                            Column(
                                modifier = Modifier
                                    .width(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF2C1A1A) else Color(0xFF1A1A1A))
                                    .border(
                                        if (isSelected || isFocused) 2.dp else 0.dp,
                                        if (isSelected) Color(0xFFE50914) else Color.White,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .focusable()
                                    .clickable { selectedSeasonNum = season.seasonNumber }
                            ) {
                                AsyncImage(
                                    model = season.posterUrl,
                                    contentDescription = season.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(130.dp)
                                        .background(Color.DarkGray)
                                )
                                Text(
                                    text = season.name,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                        }
                    }
                }

                if (episodeChunks.size > 1) {
                    Text(text = "Episode Ranges", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(episodeChunks) { idx, chunk ->
                            val firstEp = chunk.first().episodeNumber
                            val lastEp = chunk.last().episodeNumber
                            val isSelected = selectedChunkIndex == idx
                            Button(
                                onClick = { selectedChunkIndex = idx },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) Color(0xFFE50914) else Color(0xFF242424)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("EP $firstEp – $lastEp", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                val currentChunk = episodeChunks.getOrElse(selectedChunkIndex) { episodesList }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Episodes (${episodesList.size})",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { episodeSortAscending = !episodeSortAscending },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = if (episodeSortAscending) "Ascending ↑" else "Descending ↓",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                }

                if (isEpisodesLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFFE50914))
                    }
                } else {
                    val sortedEpisodes = if (episodeSortAscending) {
                        currentChunk.sortedBy { it.episodeNumber }
                    } else {
                        currentChunk.sortedByDescending { it.episodeNumber }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(sortedEpisodes) { ep ->
                            var epFocused by remember { mutableStateOf(false) }
                            val epSavedPos = remember(media.id, selectedSeasonNum, ep.episodeNumber) {
                                ServiceLocator.prefsStore.getEpisodePositionMs(
                                    media.id,
                                    selectedSeasonNum,
                                    ep.episodeNumber
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .width(145.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1A1A1A))
                                    .border(
                                        if (epFocused) 2.dp else 0.dp,
                                        if (epFocused) Color.White else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .onFocusChanged { epFocused = it.isFocused }
                                    .focusable()
                                    .clickable {
                                        val startFresh = epSavedPos <= 3_000L
                                        startStreamResolution(
                                            episode = ep,
                                            startFromZero = startFresh,
                                            origin = PlayOrigin.USER_PICK
                                        )
                                    }
                            ) {
                                Box {
                                    AsyncImage(
                                        model = ep.stillUrl.ifBlank { media.posterUrl },
                                        contentDescription = ep.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(95.dp)
                                            .background(Color.DarkGray)
                                    )
                                    Text(
                                        text = "EP ${ep.episodeNumber}",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(5.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFE50914))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                    if (epSavedPos > 3_000L) {
                                        Text(
                                            text = "RESUME",
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(4.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xCC4CAF50))
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = ep.title,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = ep.overview,
                                        color = Color.LightGray,
                                        fontSize = 10.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isResolving) {
            ResolvingOverlay(
                statusText = resolveStatusText,
                onCancel = {
                    activeResolveJob?.cancel()
                    ServiceLocator.streamResolver.cancelActive()
                    isResolving = false
                }
            )
        }

        if (errorDialogMessage != null) {
            AlertDialog(
                onDismissRequest = { errorDialogMessage = null },
                title = { Text("Stream Not Found") },
                text = { Text(errorDialogMessage ?: "") },
                confirmButton = {
                    Button(
                        onClick = { errorDialogMessage = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
                    ) { Text("OK", color = Color.White) }
                }
            )
        }
    }
}

// ==========================================
// 4. SEARCH SCREEN
// ==========================================

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onMediaClick: (MediaItemModel) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var queryInput by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaItemModel>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }

    var selectedType by remember { mutableStateOf("All") }
    var selectedGenre by remember { mutableStateOf("All") }
    var selectedYear by remember { mutableStateOf("All") }

    var typeExpanded by remember { mutableStateOf(false) }
    var genreExpanded by remember { mutableStateOf(false) }
    var yearExpanded by remember { mutableStateOf(false) }

    fun performSearch() {
        focusManager.clearFocus()
        keyboardController?.hide()
        isSearching = true
        hasSearched = true
        scope.launch {
            val results = ServiceLocator.catalogRepository.searchCatalog(
                query = queryInput,
                selectedType = selectedType,
                selectedGenre = selectedGenre,
                selectedYear = selectedYear
            )
            searchResults = results
            isSearching = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedButton(
            onClick = {
                focusManager.clearFocus()
                keyboardController?.hide()
                onBack()
            },
            modifier = Modifier.height(36.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text("← Back", color = Color.White, fontSize = 13.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = queryInput,
                onValueChange = { queryInput = it },
                placeholder = {
                    Text(
                        "Search movies, series, or anime...",
                        color = Color(0xFF888888),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Text("🔍", fontSize = 15.sp) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1E1E1E),
                    unfocusedContainerColor = Color(0xFF1E1E1E),
                    focusedBorderColor = Color(0xFFE50914),
                    unfocusedBorderColor = Color(0xFF383838),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            )
            Button(
                onClick = { performSearch() },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
                contentPadding = PaddingValues(horizontal = 18.dp),
                modifier = Modifier.height(52.dp)
            ) {
                Text("Search", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterDropdownPill(
                label = "Type",
                selectedValue = selectedType,
                expanded = typeExpanded,
                onExpandedChange = { typeExpanded = it },
                options = listOf("All", "Movies", "Series", "Anime"),
                onSelect = {
                    selectedType = it
                    performSearch()
                },
                modifier = Modifier.weight(1f)
            )
            FilterDropdownPill(
                label = "Genre",
                selectedValue = selectedGenre,
                expanded = genreExpanded,
                onExpandedChange = { genreExpanded = it },
                options = listOf("All") + GENRE_MAP.values.sorted(),
                onSelect = {
                    selectedGenre = it
                    performSearch()
                },
                modifier = Modifier.weight(1.3f)
            )
            FilterDropdownPill(
                label = "Year",
                selectedValue = selectedYear,
                expanded = yearExpanded,
                onExpandedChange = { yearExpanded = it },
                options = listOf("All") + (2026 downTo 2005).map { it.toString() },
                onSelect = {
                    selectedYear = it
                    performSearch()
                },
                modifier = Modifier.weight(0.95f)
            )
        }

        HorizontalDivider(color = Color(0xFF2C2C2C))

        if (isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFE50914))
            }
        } else if (!hasSearched) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Search TMDB & Anime or filter by Type, Genre & Year.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        } else if (searchResults.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No results found matching your filters.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(searchResults) { item ->
                    PosterCard(
                        item = item,
                        isWatchLaterScreen = false,
                        onClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onMediaClick(item)
                        },
                        onWatchLaterChanged = {}
                    )
                }
            }
        }
    }
}

@Composable
fun FilterDropdownPill(
    label: String,
    selectedValue: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = selectedValue != "All"
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isActive) Color(0xFF2C1A1A) else Color(0xFF1E1E1E))
                    .border(1.dp, if (isActive) Color(0xFFE50914) else Color(0xFF383838), RoundedCornerShape(10.dp))
                    .clickable { onExpandedChange(true) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedValue,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "▾",
                    color = if (isActive) Color(0xFFE50914) else Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .background(Color(0xFF1E1E1E))
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                color = if (option == selectedValue) Color(0xFFE50914) else Color.White,
                                fontWeight = if (option == selectedValue) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        },
                        onClick = {
                            onSelect(option)
                            onExpandedChange(false)
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// 5. SETTINGS & ACCOUNTS SCREEN (TMDB + ANILIST)
// ==========================================

@Composable
fun SettingsScreen(
    onAccountChanged: () -> Unit,
    onBack: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var tmdbAuth by remember { mutableStateOf(ServiceLocator.prefsStore.getTmdbAuth()) }
    var tmdbUsernameInput by remember { mutableStateOf("") }
    var tmdbPasswordInput by remember { mutableStateOf("") }
    var isTmdbLoading by remember { mutableStateOf(false) }
    var tmdbStatusMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Settings & Accounts", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onBack()
                },
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("← Done", color = Color.White, fontSize = 13.sp)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("TMDB Account", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("Syncs Movies & Series Watchlists", color = Color.Gray, fontSize = 12.sp)
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (tmdbAuth.sessionId != null) Color(0x334CAF50) else Color(0x33FF9800)
                    ) {
                        Text(
                            text = if (tmdbAuth.sessionId != null) "CONNECTED" else "NOT LINKED",
                            color = if (tmdbAuth.sessionId != null) Color(0xFF4CAF50) else Color(0xFFFF9800),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (tmdbAuth.sessionId != null) {
                    Text(
                        text = "Signed in as: ${tmdbAuth.username ?: "TMDB User"}",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                    OutlinedButton(
                        onClick = {
                            ServiceLocator.prefsStore.saveTmdbAuth(null, 0, null)
                            tmdbAuth = ServiceLocator.prefsStore.getTmdbAuth()
                            tmdbStatusMessage = "Logged out of TMDB."
                            onAccountChanged()
                        },
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Log Out of TMDB", color = Color(0xFFFF5252), fontSize = 12.sp)
                    }
                } else {
                    OutlinedTextField(
                        value = tmdbUsernameInput,
                        onValueChange = { tmdbUsernameInput = it },
                        placeholder = { Text("TMDB Username", color = Color.Gray, fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tmdbPasswordInput,
                        onValueChange = { tmdbPasswordInput = it },
                        placeholder = { Text("TMDB Password", color = Color.Gray, fontSize = 13.sp) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (tmdbUsernameInput.isBlank() || tmdbPasswordInput.isBlank()) {
                                tmdbStatusMessage = "Please enter both TMDB username and password."
                                return@Button
                            }
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            isTmdbLoading = true
                            tmdbStatusMessage = null
                            scope.launch {
                                val res = ServiceLocator.catalogRepository.loginToTmdb(tmdbUsernameInput, tmdbPasswordInput)
                                isTmdbLoading = false
                                if (res != null) {
                                    ServiceLocator.prefsStore.saveTmdbAuth(res.first, res.second, res.third)
                                    tmdbAuth = ServiceLocator.prefsStore.getTmdbAuth()
                                    tmdbPasswordInput = ""
                                    tmdbStatusMessage = "Successfully linked TMDB account (${res.third})!"
                                    onAccountChanged()
                                } else {
                                    tmdbStatusMessage = "TMDB Login failed: Check your username & password."
                                }
                            }
                        },
                        enabled = !isTmdbLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        if (isTmdbLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                        } else {
                            Text("Sign In to TMDB", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (tmdbStatusMessage != null) {
                    Text(text = tmdbStatusMessage!!, color = Color.LightGray, fontSize = 12.sp)
                }
            }
        }

        AniListSettingsCard(onAccountChanged = onAccountChanged)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AniListSettingsCard(onAccountChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var aniState by remember { mutableStateOf(ServiceLocator.prefsStore.getAniListAuth()) }
    var tokenInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showOAuthModal by remember { mutableStateOf(false) }

    fun verifyToken(raw: String) {
        val cleaned = PrefsStore.extractCleanAniListToken(raw)
        if (cleaned.isBlank()) {
            statusMessage = "Invalid token."
            return
        }
        isLoading = true
        statusMessage = "Verifying with AniList..."
        scope.launch {
            val viewer = ServiceLocator.catalogRepository.verifyAniListToken(cleaned)
            isLoading = false
            if (viewer != null) {
                ServiceLocator.prefsStore.saveAniListAuth(cleaned, viewer.first, viewer.second)
                aniState = ServiceLocator.prefsStore.getAniListAuth()
                tokenInput = ""
                statusMessage = "Connected as ${viewer.second}!"
                onAccountChanged()
            } else {
                statusMessage = "Token rejected by AniList."
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("AniList Account", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Syncs Watching & Planning Anime Lists", color = Color.Gray, fontSize = 12.sp)
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (aniState.token != null) Color(0x334CAF50) else Color(0x33FF9800)
                ) {
                    Text(
                        text = if (aniState.token != null) "CONNECTED" else "NOT LINKED",
                        color = if (aniState.token != null) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (aniState.token != null) {
                Text(
                    text = "Signed in as: ${aniState.username ?: "AniList User"}",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                OutlinedButton(
                    onClick = {
                        ServiceLocator.prefsStore.saveAniListAuth(null, 0, null)
                        aniState = ServiceLocator.prefsStore.getAniListAuth()
                        statusMessage = "Logged out of AniList."
                        onAccountChanged()
                    },
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Log Out of AniList", color = Color.LightGray, fontSize = 12.sp)
                }
            } else {
                Button(
                    onClick = { showOAuthModal = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF02A9FF)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text("🔐 1-Tap In-App AniList Login", color = Color.White, fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    placeholder = { Text("Or paste AniList token here", color = Color.Gray, fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { verifyToken(tokenInput) },
                    enabled = !isLoading && tokenInput.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                ) {
                    Text("Verify Pasted Token", color = Color.White)
                }
            }
            if (statusMessage != null) {
                Text(text = statusMessage!!, color = Color(0xFF4CAF50), fontSize = 12.sp)
            }
        }
    }

    if (showOAuthModal) {
        Dialog(
            onDismissRequest = { showOAuthModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF161616)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF222222))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("AniList OAuth Login", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showOAuthModal = false }) {
                            Text("✕ Close", color = Color.LightGray)
                        }
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val reqUrl = request?.url?.toString() ?: return false
                                        if (reqUrl.contains("access_token=")) {
                                            showOAuthModal = false
                                            verifyToken(reqUrl)
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        if (url != null && url.contains("access_token=")) {
                                            showOAuthModal = false
                                            verifyToken(url)
                                        }
                                    }
                                }
                                loadUrl(PrefsStore.ANILIST_OAUTH_URL)
                            }
                        }
                    )
                }
            }
        }
    }
}
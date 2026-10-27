package com.victor.stream

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.victor.stream.core.DebugCapture
import com.victor.stream.di.ServiceLocator
import com.victor.stream.domain.MediaItemModel
import com.victor.stream.resolver.WebViewStreamResolver
import com.victor.stream.ui.HomeScreenWithSidebar
import com.victor.stream.ui.MediaDetailsScreen
import com.victor.stream.ui.CompanyTitlesScreen
import com.victor.stream.ui.PersonFilmographyScreen
import com.victor.stream.ui.PlayerScreen
import com.victor.stream.ui.SearchScreen
import com.victor.stream.ui.SettingsScreen
import com.victor.stream.ui.RemoteKeyRouter
import com.victor.stream.ui.TvFocusMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(applicationContext)
        DebugCapture.attach(applicationContext)
        (ServiceLocator.streamResolver as? WebViewStreamResolver)?.attachHostToActivity(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    AppNavHost()
                }
            }
        }
    }

    // Give the video player first look at remote buttons (seek, play/pause, back...)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = RemoteKeyRouter.playerHandler
        if (handler != null && handler(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        (ServiceLocator.streamResolver as? WebViewStreamResolver)?.attachHostToActivity(this)
    }
}

private fun NavHostController.navigateBackSafely() {
    if (previousBackStackEntry != null) {
        popBackStack()
    }
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val catalogRepo = ServiceLocator.catalogRepository

    var selectedSection by remember { mutableStateOf("Home") }
    // Remembers which poster had remote focus so it gets it back after Back
    val homeFocusMemory = remember { TvFocusMemory() }
    var isLoading by remember { mutableStateOf(true) }
    var syncTriggerCount by remember { mutableIntStateOf(0) }

    var trendingAll by remember { mutableStateOf(catalogRepo.getFallbackSamples()) }
    var trendingMovies by remember { mutableStateOf(catalogRepo.getFallbackSamples().filter { !it.isSeries }) }
    var popularMovies by remember { mutableStateOf(catalogRepo.getFallbackSamples().filter { !it.isSeries }) }
    var topRatedMovies by remember { mutableStateOf(catalogRepo.getFallbackSamples().filter { !it.isSeries }) }
    var trendingSeries by remember { mutableStateOf(catalogRepo.getFallbackSamples().filter { it.isSeries }) }
    var popularSeries by remember { mutableStateOf(catalogRepo.getFallbackSamples().filter { it.isSeries }) }
    var topRatedSeries by remember { mutableStateOf(catalogRepo.getFallbackSamples().filter { it.isSeries }) }
    var trendingAnime by remember { mutableStateOf<List<MediaItemModel>>(emptyList()) }
    var popularAnime by remember { mutableStateOf<List<MediaItemModel>>(emptyList()) }

    var anilistWatchingList by remember { mutableStateOf<List<MediaItemModel>>(emptyList()) }
    var anilistPlanningList by remember { mutableStateOf<List<MediaItemModel>>(emptyList()) }
    var tmdbWatchlistMovies by remember { mutableStateOf<List<MediaItemModel>>(emptyList()) }
    var tmdbWatchlistSeries by remember { mutableStateOf<List<MediaItemModel>>(emptyList()) }

    fun openDetailsForMedia(item: MediaItemModel) {
        catalogRepo.putMedia(item)
        navController.navigate("details/${item.id}") {
            launchSingleTop = true
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        val bundle = catalogRepo.fetchHomeCatalog()
        trendingAll = bundle.trendingAll
        trendingMovies = bundle.trendingMovies
        popularMovies = bundle.popularMovies
        topRatedMovies = bundle.topRatedMovies
        trendingSeries = bundle.trendingSeries
        popularSeries = bundle.popularSeries
        topRatedSeries = bundle.topRatedSeries
        trendingAnime = bundle.trendingAnime
        popularAnime = bundle.popularAnime
        isLoading = false
    }

    LaunchedEffect(selectedSection, syncTriggerCount) {
        withContext(Dispatchers.IO) {
            val aniAuth = ServiceLocator.prefsStore.getAniListAuth()
            if (!aniAuth.token.isNullOrBlank() && aniAuth.userId > 0) {
                val watchingDef = async { catalogRepo.fetchUserAniList("CURRENT") }
                val planningDef = async { catalogRepo.fetchUserAniList("PLANNING") }
                anilistWatchingList = watchingDef.await()
                anilistPlanningList = planningDef.await()
            } else {
                anilistWatchingList = emptyList()
                anilistPlanningList = emptyList()
            }

            val tmdbAuth = ServiceLocator.prefsStore.getTmdbAuth()
            if (!tmdbAuth.sessionId.isNullOrBlank() && tmdbAuth.accountId > 0) {
                val (wlMovies, wlSeries) = catalogRepo.fetchTmdbWatchlist()
                tmdbWatchlistMovies = wlMovies
                tmdbWatchlistSeries = wlSeries
            } else {
                tmdbWatchlistMovies = emptyList()
                tmdbWatchlistSeries = emptyList()
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "main",
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        composable("main") {
            HomeScreenWithSidebar(
                selectedSection = selectedSection,
                onSelectSection = { selectedSection = it },
                isLoading = isLoading,
                trendingAll = trendingAll,
                trendingMovies = trendingMovies,
                popularMovies = popularMovies,
                topRatedMovies = topRatedMovies,
                trendingSeries = trendingSeries,
                popularSeries = popularSeries,
                topRatedSeries = topRatedSeries,
                trendingAnime = trendingAnime,
                popularAnime = popularAnime,
                anilistWatchingList = anilistWatchingList,
                anilistPlanningList = anilistPlanningList,
                tmdbWatchlistMovies = tmdbWatchlistMovies,
                tmdbWatchlistSeries = tmdbWatchlistSeries,
                onOpenSearchScreen = {
                    if (navController.currentDestination?.route != "search") {
                        navController.navigate("search") { launchSingleTop = true }
                    }
                },
                onOpenSettingsScreen = {
                    if (navController.currentDestination?.route != "settings") {
                        navController.navigate("settings") { launchSingleTop = true }
                    }
                },
                onMediaClick = { item -> openDetailsForMedia(item) },
                onContinueClick = { entry -> openDetailsForMedia(entry.media) },
                focusMemory = homeFocusMemory
            )
        }

        composable("search") {
            SearchScreen(
                onBack = { navController.navigateBackSafely() },
                onMediaClick = { item -> openDetailsForMedia(item) }
            )
        }

        composable("settings") {
            SettingsScreen(
                onAccountChanged = { syncTriggerCount += 1 },
                onBack = { navController.navigateBackSafely() }
            )
        }

        composable(
            route = "details/{mediaId}",
            arguments = listOf(navArgument("mediaId") { type = NavType.IntType })
        ) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getInt("mediaId") ?: 0
            val mediaItem = catalogRepo.getMedia(mediaId) ?: catalogRepo.getFallbackSamples().first()
            MediaDetailsScreen(
                initialMedia = mediaItem,
                onBack = {
                    syncTriggerCount += 1
                    navController.navigateBackSafely()
                },
                onNavigateToSession = { sessionId ->
                    navController.navigate("player/$sessionId") {
                        launchSingleTop = true
                    }
                },
                onOpenPerson = { personId, personName ->
                    navController.navigate("person/$personId/${android.net.Uri.encode(personName)}") {
                        launchSingleTop = true
                    }
                },
                onOpenCompany = { companyId, companyName ->
                    navController.navigate("company/$companyId/${android.net.Uri.encode(companyName)}") {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route = "person/{personId}/{personName}",
            arguments = listOf(
                navArgument("personId") { type = NavType.IntType },
                navArgument("personName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val personId = backStackEntry.arguments?.getInt("personId") ?: 0
            val personName = backStackEntry.arguments?.getString("personName").orEmpty()
            PersonFilmographyScreen(
                personId = personId,
                personName = personName,
                onBack = { navController.navigateBackSafely() },
                onMediaClick = { item -> openDetailsForMedia(item) }
            )
        }

        composable(
            route = "company/{companyId}/{companyName}",
            arguments = listOf(
                navArgument("companyId") { type = NavType.IntType },
                navArgument("companyName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val companyId = backStackEntry.arguments?.getInt("companyId") ?: 0
            val companyName = backStackEntry.arguments?.getString("companyName").orEmpty()
            CompanyTitlesScreen(
                companyId = companyId,
                companyName = companyName,
                onBack = { navController.navigateBackSafely() },
                onMediaClick = { item -> openDetailsForMedia(item) }
            )
        }

        composable(
            route = "player/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
            PlayerScreen(
                sessionId = sessionId,
                onBack = {
                    syncTriggerCount += 1
                    navController.navigateBackSafely()
                }
            )
        }
    }
}
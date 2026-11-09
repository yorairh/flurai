package com.flurai.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavBackStackEntry
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.flurai.app.core.auth.AuthUser
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.study.StudySource
import com.flurai.app.ui.explore.ExploreScreen
import com.flurai.app.ui.home.HomeScreen
import com.flurai.app.ui.learn.LearnScreen
import com.flurai.app.ui.library.LibraryScreen
import com.flurai.app.ui.library.WordDetailScreen
import com.flurai.app.ui.profile.EditProfileScreen
import com.flurai.app.ui.profile.LicencesScreen
import com.flurai.app.ui.profile.ProfileScreen
import com.flurai.app.ui.progress.ProgressScreen
import com.flurai.app.ui.progress.WordBrowserScreen
import com.flurai.app.ui.settings.SettingsScreen
import com.flurai.app.ui.study.ReviewScreen
import com.flurai.app.ui.tests.TestsHubScreen
import com.flurai.app.ui.tests.QuizScreen
import com.flurai.app.ui.tests.QuizKind
import com.flurai.app.ui.grammar.GrammarDetailScreen
import com.flurai.app.ui.grammar.GrammarListScreen
import com.flurai.app.ui.reading.ReadingScreen
import com.flurai.app.ui.explore.VideosScreen
import com.flurai.app.ui.explore.MediaScreen
import com.flurai.app.ui.explore.MediaDetailScreen
import com.flurai.app.ui.study.VocabHubScreen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable object HomeRoute
@Serializable object LearnRoute
@Serializable object ExploreRoute
@Serializable object ProfileRoute
@Serializable object EditProfileRoute
@Serializable object LibraryRoute
@Serializable data class WordRoute(val id: Int)
@Serializable object LicencesRoute
@Serializable object VocabHubRoute
@Serializable object ProgressRoute
@Serializable object WordBrowserRoute
@Serializable object SettingsRoute
@Serializable object TestsRoute
@Serializable object GrammarListRoute
@Serializable object ReadingRoute
@Serializable object VideosRoute
@Serializable object MediaRoute
@Serializable data class MediaDetailRoute(val deckId: Int)
@Serializable data class GrammarRoute(val id: Int)

/** [kind] is a QuizKind.encode() value. */
@Serializable data class QuizRoute(val kind: String)

/** [source] is a StudySource.encode() value. */
@Serializable data class StudyRoute(val source: String)

private enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
) {
    Home(HomeRoute, HomeRoute::class, "Home", Icons.Filled.Home),
    Learn(LearnRoute, LearnRoute::class, "Learn", Icons.Filled.Star),
    Explore(ExploreRoute, ExploreRoute::class, "Explore", Icons.Filled.PlayArrow),
    Profile(ProfileRoute, ProfileRoute::class, "Me", Icons.Filled.Person),
}

@Composable
fun FluRaiAppShell(user: AuthUser, profile: StudyProfile, onSignOut: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = bottomBar@{
            // Reviews get the whole screen.
            if (currentDestination?.hasRoute(StudyRoute::class) == true || currentDestination?.hasRoute(QuizRoute::class) == true) return@bottomBar
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.hasRoute(destination.routeClass) ||
                            (destination == TopLevelDestination.Profile && (it.hasRoute(EditProfileRoute::class) || it.hasRoute(LicencesRoute::class) || it.hasRoute(SettingsRoute::class))) ||
                            (destination == TopLevelDestination.Home && (it.hasRoute(ProgressRoute::class) || it.hasRoute(WordBrowserRoute::class))) ||
                            (destination == TopLevelDestination.Learn && (it.hasRoute(LibraryRoute::class) || it.hasRoute(WordRoute::class) || it.hasRoute(VocabHubRoute::class) || it.hasRoute(TestsRoute::class) || it.hasRoute(GrammarListRoute::class) || it.hasRoute(GrammarRoute::class) || it.hasRoute(ReadingRoute::class))) ||
                            (destination == TopLevelDestination.Explore && (it.hasRoute(VideosRoute::class) || it.hasRoute(MediaRoute::class) || it.hasRoute(MediaDetailRoute::class)))
                    } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 } },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) },
            // The back gesture would otherwise shrink the screen like a minimising window.
            predictivePopEnterTransition = { fadeIn(tween(220)) },
            predictivePopExitTransition = { fadeOut(tween(160)) },
        ) {
            screen<HomeRoute> {
                HomeScreen(
                    user = user,
                    profile = profile,
                    onStudy = { navController.navigate(StudyRoute(StudySource.Auto.encode())) },
                    onOpenProgress = { navController.navigate(ProgressRoute) },
                )
            }
            screen<LearnRoute> {
                LearnScreen(
                    onOpenFlashcards = { navController.navigate(VocabHubRoute) },
                    onOpenLibrary = { navController.navigate(LibraryRoute) },
                    onOpenTests = { navController.navigate(TestsRoute) },
                    onOpenGrammar = { navController.navigate(GrammarListRoute) },
                    onOpenReading = { navController.navigate(ReadingRoute) },
                )
            }
            screen<VocabHubRoute> {
                VocabHubScreen(
                    onBack = { navController.popBackStack() },
                    onStudy = { navController.navigate(StudyRoute(it.encode())) },
                )
            }
            screen<ProgressRoute> {
                ProgressScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWord = { navController.navigate(WordRoute(it)) },
                    onBrowseWords = { navController.navigate(WordBrowserRoute) },
                    onStudy = { navController.navigate(StudyRoute(StudySource.Auto.encode())) },
                )
            }
            screen<WordBrowserRoute> {
                WordBrowserScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWord = { navController.navigate(WordRoute(it)) },
                )
            }
            screen<ReadingRoute> {
                ReadingScreen(
                    onBack = { navController.popBackStack() },
                    onCheck = { navController.navigate(QuizRoute(QuizKind.Reading(it).encode())) },
                    onOpenWord = { navController.navigate(WordRoute(it)) },
                )
            }
            screen<GrammarListRoute> {
                GrammarListScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPoint = { navController.navigate(GrammarRoute(it)) },
                    onPractice = { navController.navigate(QuizRoute(QuizKind.Grammar(it).encode())) },
                )
            }
            screen<GrammarRoute> {
                GrammarDetailScreen(
                    onBack = { navController.popBackStack() },
                    onPractice = { jlpt, id -> navController.navigate(QuizRoute(QuizKind.Grammar(jlpt, id).encode())) },
                )
            }
            screen<TestsRoute> {
                TestsHubScreen(
                    onBack = { navController.popBackStack() },
                    onStart = { navController.navigate(QuizRoute(it.encode())) },
                )
            }
            screen<QuizRoute> { QuizScreen(onBack = { navController.popBackStack() }) }
            screen<StudyRoute> { ReviewScreen(onBack = { navController.popBackStack() }) }
            screen<ExploreRoute> { ExploreScreen(onOpenVideos = { navController.navigate(VideosRoute) }, onOpenMedia = { navController.navigate(MediaRoute) }) }
            screen<MediaRoute> {
                MediaScreen(onBack = { navController.popBackStack() }, onOpen = { navController.navigate(MediaDetailRoute(it)) })
            }
            screen<MediaDetailRoute> { MediaDetailScreen(onBack = { navController.popBackStack() }) }
            screen<VideosRoute> { VideosScreen(onBack = { navController.popBackStack() }) }
            screen<ProfileRoute> {
                ProfileScreen(
                    user = user,
                    profile = profile,
                    onEditProfile = { navController.navigate(EditProfileRoute) },
                    onOpenProgress = { navController.navigate(ProgressRoute) },
                    onOpenLicences = { navController.navigate(LicencesRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    onSignOut = onSignOut,
                )
            }
            screen<LibraryRoute> {
                LibraryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWord = { navController.navigate(WordRoute(it)) },
                )
            }
            screen<WordRoute> { WordDetailScreen(onBack = { navController.popBackStack() }) }
            screen<SettingsRoute> {
                SettingsScreen(
                    profile = profile,
                    onBack = { navController.popBackStack() },
                    onEditProfile = { navController.navigate(EditProfileRoute) },
                )
            }
            screen<LicencesRoute> { LicencesScreen(onBack = { navController.popBackStack() }) }
            screen<EditProfileRoute> {
                EditProfileScreen(user = user, profile = profile, onDone = { navController.popBackStack() })
            }
        }
    }
}


/**
 * A destination with an opaque background. Without it the screen underneath shows through while a
 * screen slides or shrinks away (the "window minimising on top of the page" look when going back).
 */
private inline fun <reified T : Any> NavGraphBuilder.screen(noinline content: @Composable (NavBackStackEntry) -> Unit) {
    composable<T> { entry ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content(entry) }
    }
}

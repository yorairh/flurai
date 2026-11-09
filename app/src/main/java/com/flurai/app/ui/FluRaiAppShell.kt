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
                            (destination == TopLevelDestination.Learn && (it.hasRoute(LibraryRoute::class) || it.hasRoute(WordRoute::class) || it.hasRoute(VocabHubRoute::class) || it.hasRoute(TestsRoute::class)))
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
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    user = user,
                    profile = profile,
                    onStudy = { navController.navigate(StudyRoute(StudySource.Auto.encode())) },
                    onOpenProgress = { navController.navigate(ProgressRoute) },
                )
            }
            composable<LearnRoute> {
                LearnScreen(
                    onOpenFlashcards = { navController.navigate(VocabHubRoute) },
                    onOpenLibrary = { navController.navigate(LibraryRoute) },
                    onOpenTests = { navController.navigate(TestsRoute) },
                )
            }
            composable<VocabHubRoute> {
                VocabHubScreen(
                    onBack = { navController.popBackStack() },
                    onStudy = { navController.navigate(StudyRoute(it.encode())) },
                )
            }
            composable<ProgressRoute> {
                ProgressScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWord = { navController.navigate(WordRoute(it)) },
                    onBrowseWords = { navController.navigate(WordBrowserRoute) },
                    onStudy = { navController.navigate(StudyRoute(StudySource.Auto.encode())) },
                )
            }
            composable<WordBrowserRoute> {
                WordBrowserScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWord = { navController.navigate(WordRoute(it)) },
                )
            }
            composable<TestsRoute> {
                TestsHubScreen(
                    onBack = { navController.popBackStack() },
                    onStart = { navController.navigate(QuizRoute(it.encode())) },
                )
            }
            composable<QuizRoute> { QuizScreen(onBack = { navController.popBackStack() }) }
            composable<StudyRoute> { ReviewScreen(onBack = { navController.popBackStack() }) }
            composable<ExploreRoute> { ExploreScreen() }
            composable<ProfileRoute> {
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
            composable<LibraryRoute> {
                LibraryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWord = { navController.navigate(WordRoute(it)) },
                )
            }
            composable<WordRoute> { WordDetailScreen(onBack = { navController.popBackStack() }) }
            composable<SettingsRoute> {
                SettingsScreen(
                    profile = profile,
                    onBack = { navController.popBackStack() },
                    onEditProfile = { navController.navigate(EditProfileRoute) },
                )
            }
            composable<LicencesRoute> { LicencesScreen(onBack = { navController.popBackStack() }) }
            composable<EditProfileRoute> {
                EditProfileScreen(user = user, profile = profile, onDone = { navController.popBackStack() })
            }
        }
    }
}

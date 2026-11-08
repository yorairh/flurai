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
import com.flurai.app.ui.explore.ExploreScreen
import com.flurai.app.ui.home.HomeScreen
import com.flurai.app.ui.learn.LearnScreen
import com.flurai.app.ui.profile.EditProfileScreen
import com.flurai.app.ui.profile.ProfileScreen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable object HomeRoute
@Serializable object LearnRoute
@Serializable object ExploreRoute
@Serializable object ProfileRoute
@Serializable object EditProfileRoute

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
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.hasRoute(destination.routeClass) ||
                            (destination == TopLevelDestination.Profile && it.hasRoute(EditProfileRoute::class))
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
            composable<HomeRoute> { HomeScreen(user, profile) }
            composable<LearnRoute> { LearnScreen() }
            composable<ExploreRoute> { ExploreScreen() }
            composable<ProfileRoute> {
                ProfileScreen(
                    user = user,
                    profile = profile,
                    onEditProfile = { navController.navigate(EditProfileRoute) },
                    onSignOut = onSignOut,
                )
            }
            composable<EditProfileRoute> {
                EditProfileScreen(user = user, profile = profile, onDone = { navController.popBackStack() })
            }
        }
    }
}

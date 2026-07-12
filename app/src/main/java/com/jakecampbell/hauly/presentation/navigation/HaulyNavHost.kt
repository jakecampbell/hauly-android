package com.jakecampbell.hauly.presentation.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jakecampbell.hauly.presentation.onboarding.OnboardingScreen
import com.jakecampbell.hauly.presentation.recipes.RecipeDetailScreen
import com.jakecampbell.hauly.presentation.recipes.RecipesScreen
import com.jakecampbell.hauly.presentation.settings.SettingsScreen
import com.jakecampbell.hauly.presentation.shopping.ShoppingScreen
import androidx.compose.runtime.getValue

object Routes {
    const val ONBOARDING = "onboarding"
    const val SHOPPING = "shopping"
    const val RECIPES = "recipes"
    const val SETTINGS = "settings"
    const val RECIPE_DETAIL = "recipe/{recipeId}"

    fun recipeDetail(recipeId: String) = "recipe/$recipeId"
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.SHOPPING, "Shopping", Icons.Filled.ShoppingCart),
    BottomDestination(Routes.RECIPES, "Recipes", Icons.AutoMirrored.Filled.MenuBook),
    BottomDestination(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun HaulyNavHost(startConfigured: Boolean, networkBusy: Boolean) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomDestinations.map { it.route }

    Box {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        bottomDestinations.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute == destination.route,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(destination.icon, contentDescription = destination.label) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = if (startConfigured) Routes.SHOPPING else Routes.ONBOARDING,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onCompleted = {
                            navController.navigate(Routes.SHOPPING) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.SHOPPING) {
                    ShoppingScreen(snackbarHostState = snackbarHostState)
                }
                composable(Routes.RECIPES) {
                    RecipesScreen(
                        snackbarHostState = snackbarHostState,
                        onRecipeClick = { navController.navigate(Routes.recipeDetail(it)) },
                    )
                }
                composable(Routes.RECIPE_DETAIL) {
                    RecipeDetailScreen(
                        snackbarHostState = snackbarHostState,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(snackbarHostState = snackbarHostState)
                }
            }
        }

        // Global network activity indicator: a thin bar under the status bar
        // whenever any Notion request is in flight, foreground or background.
        AnimatedVisibility(
            visible = networkBusy,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
    }
}

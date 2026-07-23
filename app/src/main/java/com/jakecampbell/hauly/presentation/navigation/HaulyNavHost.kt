package com.jakecampbell.hauly.presentation.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jakecampbell.hauly.presentation.onboarding.OnboardingScreen
import com.jakecampbell.hauly.presentation.recipes.RecipeDetailScreen
import com.jakecampbell.hauly.presentation.recipes.RecipesScreen
import com.jakecampbell.hauly.presentation.recipes.cook.CookModeViewModel
import com.jakecampbell.hauly.presentation.recipes.cook.playTimerSound
import com.jakecampbell.hauly.presentation.recipes.cook.startTimerVibration
import com.jakecampbell.hauly.presentation.recipes.cook.stopTimerVibration
import com.jakecampbell.hauly.presentation.settings.SettingsScreen
import com.jakecampbell.hauly.presentation.shopping.ShoppingScreen
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch

object Routes {
    const val ONBOARDING = "onboarding"
    /** The swipeable pager of the two list tabs (Shopping / Recipes). */
    const val HOME = "home"
    const val SHOPPING = "shopping"
    const val RECIPES = "recipes"
    const val SETTINGS = "settings"
}

private data class HomePage(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * The swipeable pager pages, in swipe order left→right. Settings is deliberately
 * NOT a page — it's a separate tab reached only by tapping its bottom-bar item.
 */
private val homePages = listOf(
    HomePage(Routes.SHOPPING, "Shopping", Icons.Filled.ShoppingCart),
    HomePage(Routes.RECIPES, "Recipes", Icons.AutoMirrored.Filled.MenuBook),
)

@Composable
fun HaulyNavHost(startConfigured: Boolean, networkBusy: Boolean) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Cook mode (R8.18): keep the screen awake while any recipe is cooking, and
    // sound/vibrate the alarm when a countdown finishes — both regardless of which
    // page the user is on, so this lives high up here.
    val cookViewModel: CookModeViewModel = hiltViewModel()
    val anyCooking by cookViewModel.anyCooking.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(anyCooking) {
        view.keepScreenOn = anyCooking
        onDispose { view.keepScreenOn = false }
    }
    val appContext = LocalContext.current
    // The notification tone plays once per completion...
    LaunchedEffect(Unit) {
        cookViewModel.completions.collect { playTimerSound(appContext) }
    }
    // ...while the vibration runs continuously until every finished timer is reset (R8.20).
    val anyFinished by cookViewModel.anyFinished.collectAsStateWithLifecycle()
    DisposableEffect(anyFinished) {
        if (anyFinished) startTimerVibration(appContext)
        onDispose { stopTimerVibration(appContext) }
    }

    // Swipe position for the two list tabs, hoisted so the bottom bar (kept in
    // the Scaffold so snackbars sit above it) and the pager stay in sync.
    val pagerState = rememberPagerState(pageCount = { homePages.size })
    val scope = rememberCoroutineScope()

    // The recipe currently open, if any. It lives inside the Recipes pager page
    // (page 1 shows the detail when set, the list otherwise), so it stays open
    // while the user swipes to Shopping or visits Settings, and the pager's own
    // horizontal swipe keeps working on it. Cleared only when the recipe is
    // closed (back or delete). Saved so it survives rotation/process death.
    var openRecipeId by rememberSaveable { mutableStateOf<String?>(null) }

    // Select a list tab: animate the pager if already home, otherwise pop back
    // to home (from Settings) and jump the pager to it. Tapping Recipes lands on
    // page 1, which shows the open recipe (if any) rather than the list.
    fun selectPage(index: Int) {
        if (currentRoute == Routes.HOME) {
            scope.launch { pagerState.animateScrollToPage(index) }
        } else {
            scope.launch { pagerState.scrollToPage(index) }
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // System back closes the open recipe while it's the visible page (the
    // Recipes list slides back into page 1); elsewhere back behaves normally.
    BackHandler(
        enabled = openRecipeId != null &&
            currentRoute == Routes.HOME &&
            pagerState.currentPage == homePages.indexOfFirst { it.route == Routes.RECIPES },
    ) {
        openRecipeId = null
    }

    Box {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // The bar shows on the pager (including an open recipe, which
                // lives in the Recipes page) and on Settings.
                if (currentRoute == Routes.HOME || currentRoute == Routes.SETTINGS) {
                    NavigationBar {
                        homePages.forEachIndexed { index, page ->
                            NavigationBarItem(
                                selected = currentRoute == Routes.HOME && pagerState.currentPage == index,
                                onClick = { selectPage(index) },
                                icon = { Icon(page.icon, contentDescription = page.label) },
                                label = { Text(page.label) },
                            )
                        }
                        NavigationBarItem(
                            selected = currentRoute == Routes.SETTINGS,
                            onClick = {
                                if (currentRoute != Routes.SETTINGS) {
                                    navController.navigate(Routes.SETTINGS) {
                                        popUpTo(Routes.HOME) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = if (startConfigured) Routes.HOME else Routes.ONBOARDING,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onCompleted = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.HOME) {
                    HomePager(
                        pagerState = pagerState,
                        snackbarHostState = snackbarHostState,
                        openRecipeId = openRecipeId,
                        onOpenRecipe = { openRecipeId = it },
                        onCloseRecipe = { openRecipeId = null },
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

/**
 * The two list tabs. Horizontal swipes move between Shopping and Recipes; the
 * bottom bar reflects and drives the same [pagerState]. Each page's ViewModel is
 * scoped to the home back-stack entry, so data survives swiping even though the
 * off-screen page composition is disposed.
 *
 * The Recipes page shows the open recipe's detail ([openRecipeId]) in place of
 * the list — so the detail is a first-class pager page: the bottom bar stays up,
 * swiping to Shopping still works, and there is no list-then-detail flash.
 */
@Composable
private fun HomePager(
    pagerState: androidx.compose.foundation.pager.PagerState,
    snackbarHostState: SnackbarHostState,
    openRecipeId: String?,
    onOpenRecipe: (String) -> Unit,
    onCloseRecipe: () -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        when (homePages[page].route) {
            Routes.SHOPPING -> ShoppingScreen(snackbarHostState = snackbarHostState)
            Routes.RECIPES ->
                if (openRecipeId != null) {
                    RecipeDetailScreen(
                        recipeId = openRecipeId,
                        snackbarHostState = snackbarHostState,
                        onBack = onCloseRecipe,
                    )
                } else {
                    RecipesScreen(
                        snackbarHostState = snackbarHostState,
                        onRecipeClick = onOpenRecipe,
                    )
                }
        }
    }
}

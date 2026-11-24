package bg.evolution.callrecorder.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import bg.evolution.callrecorder.AppGraph
import bg.evolution.callrecorder.core.AppLocale
import bg.evolution.callrecorder.ui.home.HomeScreen
import bg.evolution.callrecorder.ui.home.HomeViewModel
import bg.evolution.callrecorder.ui.player.PlayerScreen
import bg.evolution.callrecorder.ui.player.PlayerViewModel
import bg.evolution.callrecorder.ui.settings.AppsScreen
import bg.evolution.callrecorder.ui.settings.SettingsScreen
import bg.evolution.callrecorder.ui.settings.SettingsViewModel
import bg.evolution.callrecorder.ui.setup.SetupScreen
import bg.evolution.callrecorder.ui.theme.CallRecorderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openRecording = MutableStateFlow<Long?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        if (savedInstanceState == null) {
            handleIntent(intent)
            graph.updates.checkOnStart()
        }
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            CallRecorderTheme(settings.themeMode, settings.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation(graph, settings.onboardingDone, openRecording)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_RECORDING_ID, 0L) ?: 0L
        if (id > 0) openRecording.value = id
    }

    companion object {
        const val EXTRA_RECORDING_ID = "recording_id"
    }
}

private object Routes {
    const val ONBOARDING = "onboarding"
    const val SETUP = "setup"
    const val HOME = "home"
    const val PLAYER = "player/{id}"
    const val SETTINGS = "settings"
    const val APPS = "apps"
    fun player(id: Long) = "player/$id"
}

/** Pops only when the screen is fully shown, so a double tap on "back" can't empty the stack. */
private fun NavHostController.safePop() {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) popBackStack()
}

private fun NavHostController.safeNavigate(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        navigate(route) { launchSingleTop = true }
    }
}

@Composable
private fun AppNavigation(graph: AppGraph, onboardingDone: Boolean, openRecording: MutableStateFlow<Long?>) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val start = remember { if (onboardingDone) Routes.HOME else Routes.ONBOARDING }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val pending by openRecording.collectAsStateWithLifecycle()

    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        openRecording.value = null
        if (nav.currentDestination?.route == Routes.ONBOARDING) {
            scope.launch { graph.settings.setOnboardingDone(true) }
            nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
        }
        nav.navigate(Routes.player(id)) { launchSingleTop = true }
    }

    val duration = 320
    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(duration, easing = FastOutSlowInEasing)) { it / 6 } +
                fadeIn(tween(duration))
        },
        exitTransition = { fadeOut(tween(duration / 2)) + slideOutHorizontally(tween(duration)) { -it / 10 } },
        popEnterTransition = { fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { -it / 10 } },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(duration, easing = FastOutSlowInEasing)) { it / 6 } +
                fadeOut(tween(duration / 2))
        },
    ) {
        composable(Routes.ONBOARDING) {
            SetupScreen(
                onboarding = true,
                recordMode = settings.recordMode,
                onRecordMode = { m -> scope.launch { graph.settings.setRecordMode(m) } },
                onDone = {
                    scope.launch { graph.settings.setOnboardingDone(true) }
                    nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                },
                onBack = {},
            )
        }
        composable(Routes.SETUP) {
            SetupScreen(
                onboarding = false,
                recordMode = settings.recordMode,
                onRecordMode = { m -> scope.launch { graph.settings.setRecordMode(m) } },
                onDone = nav::safePop,
                onBack = nav::safePop,
            )
        }
        composable(Routes.HOME) {
            val vm = viewModel { HomeViewModel(graph) }
            HomeScreen(
                vm = vm,
                onOpenRecording = { nav.safeNavigate(Routes.player(it)) },
                onOpenSettings = { nav.safeNavigate(Routes.SETTINGS) },
                onOpenSetup = { nav.safeNavigate(Routes.SETUP) },
            )
        }
        composable(Routes.PLAYER, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            val vm = viewModel(key = "player-$id") { PlayerViewModel(graph, id) }
            PlayerScreen(vm, onBack = nav::safePop)
        }
        composable(Routes.SETTINGS) {
            val vm = viewModel { SettingsViewModel(graph) }
            SettingsScreen(
                vm = vm,
                onBack = nav::safePop,
                onOpenApps = { nav.safeNavigate(Routes.APPS) },
                onOpenSetup = { nav.safeNavigate(Routes.SETUP) },
            )
        }
        composable(Routes.APPS) {
            val vm = viewModel { SettingsViewModel(graph) }
            AppsScreen(vm, onBack = nav::safePop)
        }
    }
}

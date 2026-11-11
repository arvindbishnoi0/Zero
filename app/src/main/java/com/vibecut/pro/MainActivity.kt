package com.vibecut.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vibecut.pro.ui.editor.EditorScreen
import com.vibecut.pro.ui.screens.HomeStudioScreen
import com.vibecut.pro.ui.screens.OnboardingScreen
import com.vibecut.pro.ui.theme.VibeCutProTheme
import com.vibecut.pro.ui.viewmodel.EditorViewModel
import com.vibecut.pro.ui.viewmodel.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(0xFF0B0D12.toInt())
        )
        setContent {
            VibeCutProTheme {
                VibeCutNavigationApp()
            }
        }
    }
}

@Composable
fun VibeCutNavigationApp() {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = hiltViewModel()
    val prefs by homeViewModel.preferences.collectAsState()

    val startRoute = if (prefs.onboardingCompleted) "home" else "onboarding"

    NavHost(
        navController = navController,
        startDestination = startRoute
    ) {
        composable("onboarding") {
            OnboardingScreen(
                onFinish = {
                    homeViewModel.completeOnboarding()
                    navController.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        composable("home") {
            HomeStudioScreen(
                viewModel = homeViewModel,
                onOpenEditor = { projectId ->
                    navController.navigate("editor/$projectId")
                }
            )
        }

        composable(
            route = "editor/{projectId}",
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            val editorViewModel: EditorViewModel = hiltViewModel()
            EditorScreen(
                projectId = projectId,
                viewModel = editorViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

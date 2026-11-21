package com.codecapture.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.codecapture.di.AppContainer
import com.codecapture.ui.screens.DeviceManagerScreen
import com.codecapture.ui.screens.MainCaptureScreen
import com.codecapture.ui.screens.ReviewCorrectionScreen
import com.codecapture.ui.screens.SessionGalleryScreen
import com.codecapture.ui.viewmodel.CaptureViewModel
import com.codecapture.ui.viewmodel.ReviewViewModel
import com.codecapture.ui.viewmodel.SessionsViewModel

object Routes {
    const val CAPTURE = "capture"
    const val DEVICES = "devices"
    const val SESSIONS = "sessions"
    const val REVIEW = "review/{sessionId}"
    fun reviewRoute(sessionId: String) = "review/$sessionId"
}

@Composable
fun CodeCaptureNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val captureVm: CaptureViewModel = viewModel(factory = CaptureViewModel.factory(container))
    val sessionsVm: SessionsViewModel = viewModel(factory = SessionsViewModel.factory(container))
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in setOf(Routes.CAPTURE, Routes.DEVICES, Routes.SESSIONS)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.CAPTURE,
                        onClick = { navController.navigate(Routes.CAPTURE) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Capture") },
                        label = { Text("Capture") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.DEVICES,
                        onClick = { navController.navigate(Routes.DEVICES) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Bluetooth, contentDescription = "Computers") },
                        label = { Text("Computers") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SESSIONS,
                        onClick = { navController.navigate(Routes.SESSIONS) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Sessions") },
                        label = { Text("Sessions") }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CAPTURE,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.CAPTURE) {
                MainCaptureScreen(
                    viewModel = captureVm,
                    cameraManager = container.cameraCaptureManager,
                    onOpenDevices = { navController.navigate(Routes.DEVICES) },
                    onOpenSessions = { navController.navigate(Routes.SESSIONS) }
                )
            }
            composable(Routes.DEVICES) {
                DeviceManagerScreen(viewModel = captureVm)
            }
            composable(Routes.SESSIONS) {
                SessionGalleryScreen(
                    viewModel = sessionsVm,
                    onOpenReview = { sessionId ->
                        navController.navigate(Routes.reviewRoute(sessionId))
                    }
                )
            }
            composable(
                route = Routes.REVIEW,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
                val reviewVm: ReviewViewModel = viewModel(
                    key = "review_$sessionId",
                    factory = ReviewViewModel.factory(container, sessionId)
                )
                ReviewCorrectionScreen(
                    viewModel = reviewVm,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

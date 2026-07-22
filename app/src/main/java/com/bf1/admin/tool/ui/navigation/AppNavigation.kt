package com.bf1.admin.tool.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.bf1.admin.tool.ui.login.LoginScreen
import com.bf1.admin.tool.ui.login.ManualLoginScreen
import com.bf1.admin.tool.ui.login.WebViewLoginScreen
import com.bf1.admin.tool.ui.home.HomeScreen

object Routes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val WEBVIEW_LOGIN = "webview_login"
    const val MANUAL_LOGIN = "manual_login"
}

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToLogin = { navController.navigate(Routes.LOGIN) }
            )
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                onNavigateToWebView = { navController.navigate(Routes.WEBVIEW_LOGIN) },
                onNavigateToManual = { navController.navigate(Routes.MANUAL_LOGIN) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.WEBVIEW_LOGIN) {
            WebViewLoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.MANUAL_LOGIN) {
            ManualLoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}

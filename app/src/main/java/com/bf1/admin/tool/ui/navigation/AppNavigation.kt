package com.bf1.admin.tool.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.bf1.admin.tool.ui.account.AccountDetailScreen
import com.bf1.admin.tool.ui.admin.AdminScreen
import com.bf1.admin.tool.ui.login.LoginScreen
import com.bf1.admin.tool.ui.login.ManualLoginScreen
import com.bf1.admin.tool.ui.login.WebViewLoginScreen
import com.bf1.admin.tool.ui.server.AddServerScreen

object Routes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val WEBVIEW_LOGIN = "webview_login"
    const val MANUAL_LOGIN = "manual_login"
    const val ADD_SERVER = "add_server"
    const val ADMIN = "admin"
    const val ACCOUNT_DETAIL = "account_detail/{accountId}"
}

private const val SERVER_ADDED_MESSAGE_KEY = "server_added_message"

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { backStackEntry ->
            val serverAddedMessage = backStackEntry.savedStateHandle
                .getStateFlow<String?>(SERVER_ADDED_MESSAGE_KEY, null)
                .collectAsState()
            AdminScreen(
                onNavigateToLogin = { navController.navigate(Routes.LOGIN) },
                onNavigateToAddServer = { navController.navigate(Routes.ADD_SERVER) },
                onNavigateToAccountDetail = { accountId ->
                    navController.navigate("account_detail/$accountId")
                },
                serverAddedMessage = serverAddedMessage.value,
                onServerAddedMessageShown = {
                    backStackEntry.savedStateHandle[SERVER_ADDED_MESSAGE_KEY] = null
                }
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
        composable(Routes.ADD_SERVER) {
            AddServerScreen(
                onBack = { navController.popBackStack() },
                onServerAdded = { serverName ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        SERVER_ADDED_MESSAGE_KEY,
                        "已添加服务器：$serverName"
                    )
                    navController.popBackStack()
                }
            )
        }
        composable(
            route = Routes.ACCOUNT_DETAIL,
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getLong("accountId") ?: return@composable
            AccountDetailScreen(
                accountId = accountId,
                onNavigateToLogin = { navController.navigate(Routes.LOGIN) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}

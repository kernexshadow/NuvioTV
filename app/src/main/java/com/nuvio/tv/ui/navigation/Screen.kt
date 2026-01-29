package com.nuvio.tv.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Detail : Screen("detail/{itemId}/{itemType}") {
        fun createRoute(itemId: String, itemType: String) = "detail/$itemId/$itemType"
    }
    data object Search : Screen("search")
    data object Settings : Screen("settings")
    data object AddonManager : Screen("addon_manager")
}

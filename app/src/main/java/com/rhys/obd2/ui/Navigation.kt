package com.rhys.obd2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rhys.obd2.data.ConnectionState
import com.rhys.obd2.ui.screens.ConnectScreen
import com.rhys.obd2.ui.screens.DashboardScreen
import com.rhys.obd2.ui.screens.DtcScreen
import com.rhys.obd2.ui.screens.LiveDataScreen
import com.rhys.obd2.ui.screens.LogsScreen
import com.rhys.obd2.ui.screens.MonitorTestScreen
import com.rhys.obd2.ui.screens.MoreScreen
import com.rhys.obd2.ui.screens.ReadinessScreen
import com.rhys.obd2.ui.screens.SettingsScreen
import com.rhys.obd2.ui.screens.TerminalScreen
import com.rhys.obd2.ui.screens.VehicleInfoScreen

object Routes {
    const val CONNECT = "connect"
    const val DASHBOARD = "dashboard"
    const val LIVE = "live"
    const val CODES = "codes"
    const val HEALTH = "health"
    const val MORE = "more"
    const val VEHICLE = "vehicle"
    const val TESTS = "tests"
    const val LOGS = "logs"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"
}

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/** Routes that display continuously updating values and therefore want the poll loop. */
private val LIVE_ROUTES = setOf(Routes.DASHBOARD, Routes.LIVE)

private val BOTTOM_BAR = listOf(
    Destination(Routes.DASHBOARD, "Dash", Icons.Filled.Dashboard),
    Destination(Routes.LIVE, "Live", Icons.Filled.BarChart),
    Destination(Routes.CODES, "Codes", Icons.Filled.Warning),
    Destination(Routes.HEALTH, "Health", Icons.Filled.VerifiedUser),
    Destination(Routes.MORE, "More", Icons.Filled.MoreHoriz),
)

@Composable
fun OpenObdApp(
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    viewModel: ObdViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val connection by viewModel.connectionState.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // The car is the whole point of the app, so losing the link takes you back to the
    // screen that can restore it rather than leaving dead gauges on screen.
    LaunchedEffect(connection) {
        if (connection is ConnectionState.Connected) {
            if (navController.currentDestination?.route == Routes.CONNECT) {
                navController.navigate(Routes.DASHBOARD) {
                    popUpTo(Routes.CONNECT) { inclusive = true }
                }
            }
        }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in BOTTOM_BAR.map { it.route }

    // Only two screens show live values. Everywhere else, polling would compete with
    // whatever that screen is actually trying to read, so it is stopped centrally here
    // rather than in each screen's own disposal — which would otherwise race with the
    // incoming screen starting it again.
    LaunchedEffect(currentRoute) {
        if (currentRoute != null && currentRoute !in LIVE_ROUTES) viewModel.stopPolling()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    BOTTOM_BAR.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navigateTop(navController, destination.route) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Routes.CONNECT,
            ) {
                composable(Routes.CONNECT) {
                    ConnectScreen(
                        viewModel = viewModel,
                        permissionsGranted = permissionsGranted,
                        onRequestPermissions = onRequestPermissions,
                        onConnected = { navigateTop(navController, Routes.DASHBOARD) },
                    )
                }
                composable(Routes.DASHBOARD) {
                    DashboardScreen(
                        viewModel = viewModel,
                        onOpenConnect = { navController.navigate(Routes.CONNECT) },
                        onOpenCodes = { navigateTop(navController, Routes.CODES) },
                    )
                }
                composable(Routes.LIVE) { LiveDataScreen(viewModel) }
                composable(Routes.CODES) { DtcScreen(viewModel) }
                composable(Routes.HEALTH) {
                    ReadinessScreen(
                        viewModel = viewModel,
                        onOpenTests = { navController.navigate(Routes.TESTS) },
                    )
                }
                composable(Routes.MORE) {
                    MoreScreen(
                        viewModel = viewModel,
                        onNavigate = { route -> navController.navigate(route) },
                    )
                }
                composable(Routes.VEHICLE) { VehicleInfoScreen(viewModel, onBack = { navController.popBackStack() }) }
                composable(Routes.TESTS) { MonitorTestScreen(viewModel, onBack = { navController.popBackStack() }) }
                composable(Routes.LOGS) { LogsScreen(viewModel, onBack = { navController.popBackStack() }) }
                composable(Routes.TERMINAL) { TerminalScreen(viewModel, onBack = { navController.popBackStack() }) }
                composable(Routes.SETTINGS) { SettingsScreen(viewModel, onBack = { navController.popBackStack() }) }
            }
        }
    }
}

/** Bottom-bar navigation: single instance per tab, state preserved when switching back. */
private fun navigateTop(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

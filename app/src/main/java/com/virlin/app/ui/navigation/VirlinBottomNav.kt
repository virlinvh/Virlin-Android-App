package com.virlin.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/** The four permanent root destinations. Detail routes are nested and never appear here. */
enum class RootDestination(val route: String, val label: String, val selectedIcon: ImageVector, val icon: ImageVector) {
    NOW("now", "Now", Icons.Filled.Home, Icons.Outlined.Home),
    STREAMS("streams", "Streams", Icons.Filled.Layers, Icons.Outlined.Layers),
    PULSE("pulse", "Pulse", Icons.Filled.Timeline, Icons.Outlined.Timeline),
    INBOX("inbox", "Inbox", Icons.Filled.Inbox, Icons.Outlined.Inbox);

    companion object { val routes = entries.map { it.route } }
}

const val BottomNavTag = "bottom_nav"
const val InboxBadgeTag = "bottom_nav_inbox_badge"
fun bottomNavItemTag(d: RootDestination) = "bottom_nav_${d.route}"

private val NavBackground = Color(0xFFFCFBF9)
private val NavDivider = Color(0x14162016)
private val Active = Color(0xFF047857)
private val ActiveIndicator = Color(0xFFDDF3E7)
private val Inactive = Color(0xFF7A8391)

/**
 * Root bottom navigation: a standard, bottom-attached Material 3 NavigationBar. It owns its own
 * navigation-bar inset (so the surface reaches the physical bottom edge with no gap), draws no
 * floating card, and shows the live Inbox count as a compact badge.
 */
@Composable
fun VirlinBottomNav(currentRoute: String, inboxCount: Int, navController: NavController, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().testTag(BottomNavTag)) {
        HorizontalDivider(thickness = 1.dp, color = NavDivider)
        NavigationBar(
            containerColor = NavBackground,
            tonalElevation = 0.dp,
            windowInsets = NavigationBarDefaults.windowInsets
        ) {
            RootDestination.entries.forEach { d ->
                val selected = currentRoute == d.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { navController.navigateRoot(d.route) },
                    icon = {
                        if (d == RootDestination.INBOX && inboxCount > 0) BadgedBox(badge = {
                            Badge(containerColor = Active, contentColor = Color.White, modifier = Modifier.testTag(InboxBadgeTag)) { Text(if (inboxCount > 99) "99+" else "$inboxCount", fontSize = 10.sp) }
                        }) { Icon(if (selected) d.selectedIcon else d.icon, contentDescription = null) }
                        else Icon(if (selected) d.selectedIcon else d.icon, contentDescription = null)
                    },
                    label = { Text(d.label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Active, selectedTextColor = Active, indicatorColor = ActiveIndicator,
                        unselectedIconColor = Inactive, unselectedTextColor = Inactive
                    ),
                    modifier = Modifier.testTag(bottomNavItemTag(d)).semantics {
                        contentDescription = if (d == RootDestination.INBOX && inboxCount > 0) "${d.label}, $inboxCount in inbox" else d.label
                    }
                )
            }
        }
    }
}

/** Standard root-tab navigation: one instance per destination, state saved/restored, no duplicate stack entries. */
fun NavController.navigateRoot(route: String) {
    if (currentBackStackEntry?.destination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

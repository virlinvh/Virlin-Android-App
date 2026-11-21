package com.virlin.app.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Apps
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
    // The route and the destination keep their name; only what the tab is called changed.
    STREAMS("streams", "Projects", Icons.Filled.Layers, Icons.Outlined.Layers),
    PULSE("pulse", "Momentum", Icons.Filled.Timeline, Icons.Outlined.Timeline),
    // The fourth tab is now the Apps gallery; the capture Inbox is the first app inside it.
    // The constant keeps its name so the live capture-count badge below is unchanged.
    INBOX("apps", "Apps", Icons.Filled.Apps, Icons.Outlined.Apps);

    companion object { val routes = entries.map { it.route } }
}

const val BottomNavTag = "bottom_nav"
const val InboxBadgeTag = "bottom_nav_inbox_badge"
fun bottomNavItemTag(d: RootDestination) = "bottom_nav_${d.route}"

private val NavInk = Color(0xFF1E1442)
private val BadgeGreen = Color(0xFF10B981)
private val SelectedPill = Brush.verticalGradient(listOf(Color(0xFFFF8E8E), Color(0xFFFF5C77), Color(0xFFF2416A)))

/** Each destination keeps its own colour when it is not the selected tab. */
private fun RootDestination.accent(): Color = when (this) {
    RootDestination.NOW -> Color(0xFFFF5C6C)
    RootDestination.STREAMS -> Color(0xFF7C3AED)
    RootDestination.PULSE -> Color(0xFF14A38F)
    RootDestination.INBOX -> Color(0xFF7C3AED)
}

/**
 * Root bottom navigation: a floating rounded bar on a soft colour backdrop. It owns its own
 * navigation-bar inset (so the backdrop reaches the physical bottom edge with no gap), marks
 * the selected tab with a coral pill, and shows the live Inbox count as a compact badge.
 */
@Composable
fun VirlinBottomNav(currentRoute: String, inboxCount: Int, navController: NavController, modifier: Modifier = Modifier) {
    ApprovedNowDock(currentRoute, inboxCount, { navController.navigateRoot(it) }, modifier)
}

/** Retained reference for the prior dock; existing page content and navigation remain unchanged. */
@Composable
private fun PreviousVirlinBottomNav(currentRoute: String, inboxCount: Int, navController: NavController, modifier: Modifier = Modifier) {
    val barShape = RoundedCornerShape(32.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(BottomNavTag)
            .drawBehind {
                drawRect(Color(0xFFFCFBFF))
                val r = size.height * 1.4f
                drawCircle(Brush.radialGradient(listOf(Color(0x66FF8CC0), Color(0x00FF8CC0)),
                    center = Offset(0f, size.height), radius = r), radius = r, center = Offset(0f, size.height))
                drawCircle(Brush.radialGradient(listOf(Color(0x5CB9A5FF), Color(0x00B9A5FF)),
                    center = Offset(size.width, size.height), radius = r), radius = r, center = Offset(size.width, size.height))
            }
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(10.dp, barShape, ambientColor = Color(0x407C3AED), spotColor = Color(0x407C3AED))
                .clip(barShape)
                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFF5F0FF))))
                .border(1.dp, Color.White, barShape)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RootDestination.entries.forEach { d ->
                val selected = currentRoute == d.route
                NavItem(
                    destination = d,
                    selected = selected,
                    inboxCount = inboxCount,
                    onClick = { navController.navigateRoot(d.route) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavItem(destination: RootDestination, selected: Boolean, inboxCount: Int, onClick: () -> Unit, modifier: Modifier) {
    val pillAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "navPill")
    val pillShape = RoundedCornerShape(26.dp)
    val tint = if (selected) Color.White else destination.accent()
    Box(
        modifier
            .fillMaxHeight()
            .testTag(bottomNavItemTag(destination))
            .semantics {
                contentDescription = if (destination == RootDestination.INBOX && inboxCount > 0)
                    "${destination.label}, $inboxCount in inbox" else destination.label
            }
            .clip(pillShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (pillAlpha > 0f) Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = pillAlpha }
                .background(SelectedPill, pillShape)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val icon = if (selected) destination.selectedIcon else destination.icon
            if (destination == RootDestination.INBOX && inboxCount > 0) BadgedBox(badge = {
                Badge(containerColor = BadgeGreen, contentColor = Color.White, modifier = Modifier.testTag(InboxBadgeTag)) {
                    Text(if (inboxCount > 99) "99+" else "$inboxCount", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) }
            else Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Text(
                destination.label,
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                fontFamily = com.virlin.app.ui.screens.VirlinDmSans,
                color = if (selected) Color.White else NavInk,
                maxLines = 1
            )
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

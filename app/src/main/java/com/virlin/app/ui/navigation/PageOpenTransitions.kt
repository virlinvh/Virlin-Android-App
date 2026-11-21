package com.virlin.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/**
 * Item lists that open their entries: a task's Page and a project/workstream map Page.
 * Anything opened from one of them slides in as a physical sheet instead of the default
 * cross-fade, and Back slides it away again. Every other navigation keeps the default.
 */
internal val PAGE_ROUTES = setOf(
    com.virlin.app.ui.page.TaskPageRoute,
    "map_container_page/{projectId}/{scope}",
)

internal fun isPageRoute(route: String?): Boolean = route in PAGE_ROUTES

private const val PageOpenMillis = 340
private val PageOpenEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// Both screens travel the full width on the same curve, so they never overlap: no screen
// needs to be opaque and nothing is drawn translucent while the editor builds its first frame.
private fun <T> pageOpenSpec() = tween<T>(PageOpenMillis, easing = PageOpenEasing)

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pageOpenEnter(): EnterTransition? =
    if (isPageRoute(initialState.destination.route)) slideInHorizontally(pageOpenSpec()) { it } else null

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pageOpenExit(): ExitTransition? =
    if (isPageRoute(initialState.destination.route)) slideOutHorizontally(pageOpenSpec()) { -it } else null

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pageOpenPopEnter(): EnterTransition? =
    if (isPageRoute(targetState.destination.route)) slideInHorizontally(pageOpenSpec()) { -it } else null

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pageOpenPopExit(): ExitTransition? =
    if (isPageRoute(targetState.destination.route)) slideOutHorizontally(pageOpenSpec()) { it } else null

package com.virlin.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry

/**
 * Switching between the four bottom-bar tabs is a short fade-through instead of the 700 ms
 * library cross-fade, so two full screens are never drawn on top of each other for long.
 * Every other navigation keeps its existing transition.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isRootTabSwitch(): Boolean {
    val from = initialState.destination.route
    val to = targetState.destination.route
    return from != to && from in RootDestination.routes && to in RootDestination.routes
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.rootTabEnter(): EnterTransition? =
    if (isRootTabSwitch()) fadeIn(tween(200, delayMillis = 60, easing = LinearOutSlowInEasing)) else null

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.rootTabExit(): ExitTransition? =
    if (isRootTabSwitch()) fadeOut(tween(110, easing = FastOutLinearInEasing)) else null

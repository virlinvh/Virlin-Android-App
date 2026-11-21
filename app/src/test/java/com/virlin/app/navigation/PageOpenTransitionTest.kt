package com.virlin.app.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.virlin.app.ui.navigation.PAGE_ROUTES
import com.virlin.app.ui.navigation.isPageRoute
import com.virlin.app.ui.navigation.pageOpenEnter
import com.virlin.app.ui.navigation.pageOpenExit
import com.virlin.app.ui.navigation.pageOpenPopEnter
import com.virlin.app.ui.navigation.pageOpenPopExit
import com.virlin.app.ui.page.TaskPageRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Items opened from a Page slide in and out; every other navigation keeps the default fade. */
@RunWith(RobolectricTestRunner::class)
class PageOpenTransitionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController

    @Test fun onlyTheTwoPageRoutesCount() {
        assertEquals(setOf(TaskPageRoute, "map_container_page/{projectId}/{scope}"), PAGE_ROUTES)
        assertTrue(isPageRoute("task_page/{taskId}"))
        listOf("project_map/{id}", "notes/{ownerKey}?title={title}", "now", null).forEach {
            assertFalse("$it", isPageRoute(it))
        }
    }

    private fun host() = compose.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = "map",
            enterTransition = { pageOpenEnter() ?: fadeIn(tween(700)) },
            exitTransition = { pageOpenExit() ?: fadeOut(tween(700)) },
            popEnterTransition = { pageOpenPopEnter() ?: fadeIn(tween(700)) },
            popExitTransition = { pageOpenPopExit() ?: fadeOut(tween(700)) }) {
            composable("map") { Box(Modifier.fillMaxSize().testTag("map")) }
            composable(TaskPageRoute) { Box(Modifier.fillMaxSize().testTag("page")) }
            composable("note") { Box(Modifier.fillMaxSize().testTag("note")) }
        }
    }

    private fun x(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().x

    private fun midway(action: () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle(action)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(170)
    }

    private fun settle() {
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun openingFromAPageSlidesTheItemInAndBackSlidesItOut() {
        host()
        compose.runOnIdle { nav.navigate("task_page/t1") }
        compose.waitForIdle()

        midway { nav.navigate("note") }
        assertTrue("item enters from the right", x("note") > 0f)
        assertTrue("page leaves to the left", x("page") < 0f)
        settle()
        assertEquals(0f, x("note"))

        midway { nav.popBackStack() }
        assertTrue("item leaves to the right", x("note") > 0f)
        assertTrue("page returns from the left", x("page") < 0f)
        settle()
        assertEquals(0f, x("page"))
    }

    @Test fun otherNavigationKeepsTheDefaultFade() {
        host()
        midway { nav.navigate("task_page/t1") }
        assertEquals(0f, x("page"))
        assertEquals(0f, x("map"))
        settle()
    }
}

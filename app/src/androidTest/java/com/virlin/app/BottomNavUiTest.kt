package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.ui.agent.capture.CaptureArchiveTag
import com.virlin.app.ui.agent.capture.CaptureCloseTag
import com.virlin.app.ui.agent.capture.CaptureDetailTag
import com.virlin.app.ui.agent.capture.CaptureRestoreTag
import com.virlin.app.ui.agent.capture.captureFilterTag
import com.virlin.app.ui.agent.capture.captureRowTag
import com.virlin.app.ui.agent.capture.CaptureFilter
import com.virlin.app.ui.components.VirlinOrbTestTag
import com.virlin.app.ui.hierarchy.WorkStreamDetailTag
import com.virlin.app.ui.navigation.BottomNavTag
import com.virlin.app.ui.navigation.InboxBadgeTag
import com.virlin.app.ui.navigation.RootDestination
import com.virlin.app.ui.navigation.bottomNavItemTag
import com.virlin.app.ui.agent.AgentComposerTestTag
import com.virlin.app.ui.agent.CaptureSaveInboxTestTag
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.screens.AgentShellTestTag
import com.virlin.app.ui.screens.agentModeTag
import com.virlin.app.ui.screens.FocusContextTag
import com.virlin.app.ui.screens.InboxScreenTag
import com.virlin.app.ui.screens.StreamsListTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Root bottom navigation (Now · Streams · Pulse · Inbox): items, selection, single-instance
 * tabs, Inbox = the real capture Inbox, live badge, back behaviour and Orb clearance.
 */
class BottomNavUiTest {

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) { composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8) }
        composeRule.mainClock.advanceTimeByFrame()
    }
    private fun tag(t: String) = composeRule.onNodeWithTag(t, useUnmergedTree = true)
    private fun touch(t: String, after: Long = 700) { runCatching { tag(t).performScrollTo() }.onSuccess { pump(400) }; tag(t).performTouchInput { click() }; pump(after) }
    private fun tap(d: RootDestination) { tag(bottomNavItemTag(d)).performClick(); pump(600) }
    private fun repo() = VirlinGraph.repository
    private fun badge(n: Int) = composeRule.onNode(hasTestTag(InboxBadgeTag) and hasAnyDescendant(hasText("$n")), useUnmergedTree = true).assertIsDisplayed()
    private fun inboxCount() = repo().captures.value.count { it.status == CaptureStatus.INBOX }
    private fun archiveAll() = runBlocking { repo().captures.value.filter { it.status == CaptureStatus.INBOX }.forEach { VirlinGraph.actions.archiveCapture(it.id) } }

    @Test fun four_root_items_now_selected_and_switching_keeps_single_instances() {
        tag(BottomNavTag).assertIsDisplayed()
        RootDestination.entries.forEach { tag(bottomNavItemTag(it)).assertIsDisplayed() }
        tag(bottomNavItemTag(RootDestination.NOW)).assertIsSelected()
        tag(FocusContextTag).assertIsDisplayed()
        tap(RootDestination.STREAMS); tag(StreamsListTag).assertIsDisplayed(); tag(bottomNavItemTag(RootDestination.STREAMS)).assertIsSelected()
        tap(RootDestination.PULSE); tag(bottomNavItemTag(RootDestination.PULSE)).assertIsSelected()
        tap(RootDestination.INBOX); tag(InboxScreenTag).assertIsDisplayed(); tag(bottomNavItemTag(RootDestination.INBOX)).assertIsSelected()
        tap(RootDestination.NOW); tag(FocusContextTag).assertIsDisplayed(); tag(bottomNavItemTag(RootDestination.NOW)).assertIsSelected()
        // repeated taps never stack: one Back from a root tab leaves the app's root graph rather than unwinding tab copies
        repeat(3) { tap(RootDestination.STREAMS) }; repeat(2) { tap(RootDestination.INBOX) }
        tap(RootDestination.NOW)
        // WorkStream detail → Back → Streams (nested route pops to its tab)
        tap(RootDestination.STREAMS)
        val ws = repo().streams.value.first()
        tag("stream_row_${ws.id}").performScrollTo(); pump(300); tag("stream_row_${ws.id}").performTouchInput { click(centerLeft) }; pump(700)
        tag(WorkStreamDetailTag).assertIsDisplayed()
        Espresso.pressBack(); pump(700)
        tag(StreamsListTag).assertIsDisplayed(); tag(bottomNavItemTag(RootDestination.STREAMS)).assertIsSelected()
        // the Orb floats above the bar; both stay displayed and the Inbox item remains tappable
        tag(VirlinOrbTestTag).assertIsDisplayed(); tag(bottomNavItemTag(RootDestination.INBOX)).assertIsDisplayed()
        tap(RootDestination.INBOX); tag(InboxScreenTag).assertIsDisplayed()
    }

    @Test fun inbox_badge_is_live_and_inbox_tab_is_the_real_capture_inbox() {
        archiveAll(); pump(400)
        tag(InboxBadgeTag).assertDoesNotExist()
        // save through the Orb's CAPTURE mode → badge appears with the real count
        composeRule.onNodeWithTag(VirlinOrbTestTag).performTouchInput { click() }; pump(800)
        tag(AgentShellTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CAPTURE)).performClick(); pump(400)
        composeRule.onNodeWithTag(AgentComposerTestTag).performTextInput("Badge check one"); pump(300)
        composeRule.onNodeWithTag(CaptureSaveInboxTestTag).performClick(); pump(1200)
        Espresso.closeSoftKeyboard(); pump(400)                      // Back must close the Agent, not just the IME
        Espresso.pressBack(); pump(900)
        if (runCatching { tag(AgentShellTestTag).assertIsDisplayed() }.isSuccess) { Espresso.pressBack(); pump(900) }
        tag(AgentShellTestTag).assertDoesNotExist()
        val one = inboxCount(); check(one == 1) { "inbox=$one" }
        tag(InboxBadgeTag).assertIsDisplayed()
        badge(1)
        // a second capture straight through the domain (same path the Agent uses) → 2
        runBlocking { VirlinGraph.actions.createCapture(CreateCapture(CaptureType.NOTE, "Badge check two", context = CaptureContext.None)) }; pump(400)
        badge(2)
        // Inbox tab shows the same capture rows; archive from the detail → badge decrements; restore → increments
        tap(RootDestination.INBOX); tag(InboxScreenTag).assertIsDisplayed()
        val item = repo().captures.value.first { it.content == "Badge check two" }
        touch(captureRowTag(item.id))
        tag(CaptureDetailTag).assertIsDisplayed()
        touch(CaptureArchiveTag, 900)
        check(inboxCount() == 1) { "after archive ${inboxCount()}" }
        badge(1)
        touch(captureFilterTag(CaptureFilter.ARCHIVED))
        touch(captureRowTag(item.id))
        touch(CaptureRestoreTag, 900)
        check(inboxCount() == 2) { "after restore ${inboxCount()}" }
        badge(2)
        // capture detail → Back (‹ Back) → Inbox list, still on the Inbox tab
        touch(captureFilterTag(CaptureFilter.INBOX))
        touch(captureRowTag(item.id)); tag(CaptureDetailTag).assertIsDisplayed()
        touch(CaptureCloseTag)
        tag(InboxScreenTag).assertIsDisplayed(); tag(bottomNavItemTag(RootDestination.INBOX)).assertIsSelected()
        archiveAll(); pump(400); tag(InboxBadgeTag).assertDoesNotExist()
    }
}

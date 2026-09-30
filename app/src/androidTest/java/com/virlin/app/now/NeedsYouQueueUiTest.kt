package com.virlin.app.now

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.ui.screens.AttentionKind
import com.virlin.app.ui.screens.NeedsYouCard
import com.virlin.app.ui.screens.NeedsYouPriority
import com.virlin.app.ui.screens.needsYouRankTag
import com.virlin.app.ui.theme.VirlinTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The Phase 03 acceptance scenario, rendered: a 12-item queue A…L driven by the REAL domain
 * (`InMemoryWorkStreamRepository` + `DefaultVirlinActions.reorderNeedsYou`). Moving L to #1 must
 * re-colour every affected card automatically and must not touch any timer.
 */
class NeedsYouQueueUiTest {

    @get:Rule val composeRule = createComposeRule()
    private val t0: Instant = Instant.parse("2026-09-22T09:00:00Z")
    private val nowState = mutableStateOf(t0)
    private val names = ('A'..'L').map { it.toString() }

    private fun seed() = names.mapIndexed { i, n ->
        WorkStream(id = n, title = "Agent $n", state = WorkStreamState.CHECK, checkAt = t0.minusSeconds((12 - i) * 600L),
            createdAt = t0.minusSeconds(7200), updatedAt = t0.minusSeconds(7200))
    }
    private val repo = InMemoryWorkStreamRepository(seed = seed())
    private val clock = object : VirlinClock { override fun now() = t0 }
    private val ids = object : IdProvider { private var n = 0; override fun newId(prefix: String) = "$prefix-${++n}" }
    private val actions = DefaultVirlinActions(repo, clock, ids)

    private fun display(id: String) = MockData.streams.value.first().copy(
        id = id, subtitle = "Queue item $id", title = "Agent $id", projectId = "p1", state = StreamState.NEEDS_YOU
    )

    private fun show() {
        composeRule.setContent {
            val streams by repo.streams.collectAsState()
            val queue = NeedsYouOrder.queue(streams)
            VirlinTheme {
                Box(Modifier.width(411.dp).background(Color(0xFFF8F7F4)).testTag("queue_root")) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        queue.forEach { e ->
                            androidx.compose.runtime.key(e.stream.id) {
                                NeedsYouCard(
                                    stream = display(e.stream.id), index = e.rank - 1, kind = AttentionKind.CHECK_DUE,
                                    waitingSince = NeedsYouOrder.waitingSince(e.stream), now = nowState,
                                    project = Project(id = "p1", title = "Virlin Development", createdAt = t0, updatedAt = t0),
                                    position = e.rank, total = queue.size
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
    private fun capture(name: String) {
        val image = composeRule.onNodeWithTag("queue_root").captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        File(dir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun rankShown(id: String, rank: Int) =
        composeRule.onNode(hasTestTag(needsYouRankTag(id)) and hasAnyDescendant(hasText("#$rank")), useUnmergedTree = true).assertExists()
    private fun timerOf(id: String) = composeRule.onNodeWithTag("needs_you_timer_$id", useUnmergedTree = true)
        .fetchSemanticsNode().config.toString().substringAfter("Text : ").substringBefore("]")

    @Test fun moveLastToFirst_recoloursEveryCard_andNoTimerResets() {
        show()
        assertEquals(names, NeedsYouOrder.queue(repo.streams.value).map { it.stream.id })
        names.forEachIndexed { i, n -> rankShown(n, i + 1) }
        val timers = names.associateWith { timerOf(it) }
        capture("needs_you_queue_before.png")

        runBlocking { actions.reorderNeedsYou("L", 1) }
        composeRule.waitForIdle()

        val after = NeedsYouOrder.queue(repo.streams.value)
        assertEquals(listOf("L") + names.dropLast(1), after.map { it.stream.id })
        assertEquals((1..12).toList(), after.map { it.rank })
        rankShown("L", 1); rankShown("A", 2); rankShown("I", 10); rankShown("J", 11); rankShown("K", 12)
        // Visual identity follows the queue with no manual colour work anywhere.
        assertEquals(NeedsYouPriority.accents[0], NeedsYouPriority.visualsFor(1).accent)
        assertTrue("J crossed 10 → 11", NeedsYouPriority.visualsFor(11).neutral)
        assertEquals(NeedsYouPriority.accents[9], NeedsYouPriority.visualsFor(10).accent)
        // Timers are untouched by the move.
        names.forEach { assertEquals("timer $it", timers[it], timerOf(it)) }
        capture("needs_you_queue_after.png")
    }
}

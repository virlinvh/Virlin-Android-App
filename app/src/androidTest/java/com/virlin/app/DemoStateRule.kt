package com.virlin.app

import androidx.test.core.app.ApplicationProvider
import com.virlin.app.data.projecticon.ProjectIconStore
import com.virlin.app.domain.DemoSeed
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.model.WorkStreamState
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import java.time.Instant

/**
 * TEST INFRASTRUCTURE ONLY — restores the demo fixture before a UI test runs.
 *
 * Every instrumented UI test shares ONE process, one `virlin.db` and one [VirlinGraph]; the demo
 * seed is applied only when the database is empty (`DemoSeed.applyIfEmpty`). A test that drives
 * real actions therefore leaves real persisted state behind (attention ranks, a stream left READY,
 * an open FocusSession, a chosen project icon), and the next test — which legitimately expects the
 * seeded state — sees it. This rule makes each such test start from the same deterministic state
 * regardless of execution order.
 *
 * It rewrites the seeded rows through the repository's own transaction boundary, so the
 * repository's StateFlows (and the display projection built on them) are refreshed exactly as they
 * are in production; no production code is involved and no behaviour changes. Rows a test created
 * itself are left alone — they carry ids no seeded assertion refers to.
 *
 * Use it OUTSIDE the activity rule so the restore happens before `MainActivity` is launched:
 *
 * ```
 * @get:Rule val rules: RuleChain = RuleChain.outerRule(DemoStateRule()).around(composeRule)
 * ```
 */
class DemoStateRule : ExternalResource() {

    override fun before() = restore()

    /** Also usable directly (`DemoStateRule.restoreDemoState()`) from a test's own `@Before`. */
    companion object {
        fun restoreDemoState() = DemoStateRule().restore()
    }

    internal fun restore() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        VirlinGraph.init(context)
        runBlocking {
            VirlinGraph.ensureReady()
            val repo = VirlinGraph.repository
            // The fixture is rebuilt from the same source the first install uses. `createdAt` /
            // `updatedAt` come from `now`, so waiting time is deterministic per test, exactly as on
            // a fresh install.
            val fixture = DemoSeed.build(Instant.now())
            repo.transaction {
                fixture.projects.forEach { saveProject(it) }
                fixture.streams.forEach { seeded ->
                    // A FocusSession left open by an earlier test would keep ticking on a stream
                    // that is no longer focused; close it as the domain does on any exit.
                    getOpenFocusSession(seeded.id)?.let { open -> saveFocusSession(open.copy(endedAt = Instant.now())) }
                    saveStream(seeded)
                }
                fixture.tasks.forEach { saveTask(it) }
            }
            check(repo.streams.value.count { it.state == WorkStreamState.FOCUS } <= 1) { "single-Focus invariant broken by restore" }
            // Custom project icons are files, not rows: drop them so icon assertions see the
            // built-in / automatic identity a fresh install shows.
            fixture.projects.forEach { ProjectIconStore.delete(context, it.id) }
        }
    }
}

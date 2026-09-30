package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.getOrNull
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.TagTargetType
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Tags label; they never own. Steps are checkboxes; they are not tasks. These tests hold both
 * lines, and the project boundary that keeps one project's vocabulary out of another's work.
 */
class TagActionsTest {

    private val t0: Instant = Instant.parse("2026-09-27T09:00:00Z")
    private lateinit var clock: FakeClock
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var a: DefaultVirlinActions

    @Before fun setUp() {
        clock = FakeClock(t0)
        repo = InMemoryWorkStreamRepository(
            seed = listOf(
                WorkStream(id = "ws", title = "Android App", state = WorkStreamState.READY,
                    projectId = "p1", executionPreference = ExecutionPreference.HUMAN,
                    createdAt = t0, updatedAt = t0)
            ),
            seedProjects = listOf(
                Project("p1", "Phase 08", createdAt = t0, updatedAt = t0),
                Project("p2", "Other", createdAt = t0, updatedAt = t0)
            )
        )
        a = DefaultVirlinActions(repo, clock, SequentialIdProvider())
    }

    private suspend fun task(id: String, project: String = "p1") =
        (a.createTask(CreateTask(title = id, projectId = project, workStreamId = if (project == "p1") "ws" else null, id = id))
            as ActionResult.Success).value

    private suspend fun capture(id: String, project: String = "p1") =
        (a.createCapture(
            CreateCapture(CaptureType.NOTE, "body", id, context = CaptureContext(projectId = project), id = id)
        ) as ActionResult.Success).value

    // ------------------------------------------------------------------ tags

    @Test fun aTagIsUniqueWithinItsProject_ignoringCase() = runTest {
        val first = a.createTag("p1", "Release").getOrNull()!!
        val again = a.createTag("p1", "  release ").getOrNull()!!
        assertEquals("the same tag, not a second one", first.id, again.id)
        // A different project may hold its own tag with that name.
        val elsewhere = a.createTag("p2", "Release").getOrNull()!!
        assertTrue(elsewhere.id != first.id)
    }

    @Test fun renameKeepsTheIdAndEveryLink() = runTest {
        val tag = a.createTag("p1", "Release").getOrNull()!!
        val t = task("t1")
        a.setTags("p1", setOf(tag.id), emptySet(), setOf(t.id))

        val renamed = a.renameTag(tag.id, "Shipping").getOrNull()!!
        assertEquals(tag.id, renamed.id)
        assertEquals("Shipping", renamed.name)
        assertEquals(listOf(tag.id), repo.tagLinks.value.filter { it.targetId == t.id }.map { it.tagId })
    }

    @Test fun aDuplicateNameIsRejected() = runTest {
        a.createTag("p1", "Release")
        val other = a.createTag("p1", "Android").getOrNull()!!
        val result = a.renameTag(other.id, "release")
        assertEquals(DomainError.DuplicateTagName("release"), (result as ActionResult.Rejected).reason)
    }

    @Test fun mergeReassignsEveryReference_andRemovesTheOldTag() = runTest {
        val from = a.createTag("p1", "Android").getOrNull()!!
        val into = a.createTag("p1", "Release").getOrNull()!!
        val t = task("t1")
        val c = capture("c1")
        a.setTags("p1", setOf(from.id), setOf(c.id), setOf(t.id))

        a.mergeTags(from.id, into.id)
        assertEquals(null, repo.getTag(from.id))
        val links = repo.tagLinks.value
        assertEquals(setOf(into.id), links.map { it.tagId }.toSet())
        assertEquals(setOf(t.id, c.id), links.map { it.targetId }.toSet())
    }

    @Test fun deletingATagRemovesTheLabelButNotTheContent() = runTest {
        val tag = a.createTag("p1", "Release").getOrNull()!!
        val c = capture("c1")
        a.setTags("p1", setOf(tag.id), setOf(c.id), emptySet())

        a.deleteTag(tag.id)
        assertTrue(repo.tagLinks.value.isEmpty())
        assertNotNull("the capture itself survives", repo.getCapture(c.id))
    }

    @Test fun setTagsAppliesAcrossTasksAndCaptures_andReplacesWhatWasThere() = runTest {
        val release = a.createTag("p1", "Release").getOrNull()!!
        val android = a.createTag("p1", "Android").getOrNull()!!
        val t = task("t1")
        val c = capture("c1")

        a.setTags("p1", setOf(release.id, android.id), setOf(c.id), setOf(t.id))
        assertEquals(4, repo.tagLinks.value.size)

        // A narrower set removes the tag that was dropped.
        a.setTags("p1", setOf(release.id), setOf(c.id), setOf(t.id))
        assertEquals(setOf(release.id), repo.tagLinks.value.map { it.tagId }.toSet())
        assertEquals(2, repo.tagLinks.value.size)
    }

    @Test fun anotherProjectsTagOrContentIsRejected() = runTest {
        val mine = a.createTag("p1", "Release").getOrNull()!!
        val theirs = a.createTag("p2", "Release").getOrNull()!!
        val t = task("t1")

        assertEquals(
            DomainError.OwnershipMismatch,
            (a.setTags("p1", setOf(theirs.id), emptySet(), setOf(t.id)) as ActionResult.Rejected).reason
        )
        val foreignTask = task("t2", project = "p2")
        assertEquals(
            DomainError.OwnershipMismatch,
            (a.setTags("p1", setOf(mine.id), emptySet(), setOf(foreignTask.id)) as ActionResult.Rejected).reason
        )
        // Nothing was written by either rejected call.
        assertTrue(repo.tagLinks.value.isEmpty())
    }

    // ------------------------------------------------------------------ steps

    @Test fun stepsAreOrdered_andTogglingOneNeverCompletesTheTask() = runTest {
        val t = task("t1")
        listOf("Freeze", "Test", "Tag", "Upload").forEach { a.addStep(t.id, it) }
        val steps = repo.getTaskSteps(t.id)
        assertEquals(listOf("Freeze", "Test", "Tag", "Upload"), steps.map { it.text })
        assertEquals(listOf(0, 1, 2, 3), steps.map { it.order })

        steps.forEach { a.setStepDone(it.id, true) }
        assertTrue(repo.getTaskSteps(t.id).all { it.done })
        // Every box ticked, and the task is still open: completion is the Action Layer's call.
        assertTrue(!repo.getTask(t.id)!!.status.isTerminal)
    }

    @Test fun aStepCanBeEditedToggledBackAndReordered() = runTest {
        val t = task("t1")
        listOf("one", "two", "three").forEach { a.addStep(t.id, it) }
        val steps = repo.getTaskSteps(t.id)

        a.setStepDone(steps[0].id, true)
        a.setStepDone(steps[0].id, false)
        assertEquals(false, repo.getTaskSteps(t.id).first { it.id == steps[0].id }.done)

        a.editStep(steps[1].id, "two (edited)")
        a.moveStep(steps[2].id, 0)
        assertEquals(
            listOf("three", "one", "two (edited)"),
            repo.getTaskSteps(t.id).map { it.text }
        )
        // Renumbered, so the order is never ambiguous.
        assertEquals(listOf(0, 1, 2), repo.getTaskSteps(t.id).map { it.order })

        a.deleteStep(steps[0].id)
        assertEquals(listOf("three", "two (edited)"), repo.getTaskSteps(t.id).map { it.text })
    }

    @Test fun stepsDoNotAffectProjectProgress() = runTest {
        val t = task("t1")
        val before = com.virlin.app.domain.progress.ProgressCalculator
            .ofProject(repo.tasks.value, repo.streams.value, "p1")
        repeat(3) { a.addStep(t.id, "step $it") }
        a.setStepDone(repo.getTaskSteps(t.id).first().id, true)
        val after = com.virlin.app.domain.progress.ProgressCalculator
            .ofProject(repo.tasks.value, repo.streams.value, "p1")
        assertEquals(before, after)
    }

    @Test fun tagsAndStepsLandOnTheRightTargets() = runTest {
        val tag = a.createTag("p1", "Release").getOrNull()!!
        val t = task("t1")
        a.setTags("p1", setOf(tag.id), emptySet(), setOf(t.id))
        val link = repo.tagLinks.value.single()
        assertEquals(TagTargetType.TASK, link.targetType)
        assertEquals(t.id, link.targetId)
    }
}

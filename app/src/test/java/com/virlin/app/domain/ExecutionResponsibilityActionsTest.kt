package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateProject
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.CreateWorkStream
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.ExecutionModeResolver
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** Phase C — Hand Off domain gate + mid-PROCESSING preference rejection. */
class ExecutionResponsibilityActionsTest {

    private val t0 = Instant.parse("2026-09-14T12:00:00Z")
    private lateinit var actions: DefaultVirlinActions
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var clock: FakeClock

    @Before fun setUp() {
        clock = FakeClock(t0)
        repo = InMemoryWorkStreamRepository()
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
    }

    @Test fun handOff_rejectedWhenEffectiveHuman() = runTest {
        val ws = (actions.createWorkStream(CreateWorkStream("Human WS", executionPreference = ExecutionPreference.HUMAN)) as ActionResult.Success).value
        actions.focusStream(ws.id)
        val r = actions.handOffStream(ws.id)
        assertTrue(r is ActionResult.Rejected)
        assertEquals(DomainError.NotExternalExecution, (r as ActionResult.Rejected).reason)
        assertEquals(WorkStreamState.FOCUS, repo.getStream(ws.id)!!.state)
    }

    @Test fun handOff_allowedWhenEffectiveExternal() = runTest {
        val ws = (actions.createWorkStream(CreateWorkStream("Ext WS", executionPreference = ExecutionPreference.EXTERNAL)) as ActionResult.Success).value
        actions.focusStream(ws.id)
        val r = actions.handOffStream(ws.id)
        assertTrue(r is ActionResult.Success)
        assertEquals(WorkStreamState.PROCESSING, (r as ActionResult.Success).value.state)
    }

    @Test fun handOff_usesDeepestActiveTaskEffectiveMode() = runTest {
        val p = (actions.createProject(CreateProject("P", defaultExecutionMode = EffectiveExecutionMode.EXTERNAL)) as ActionResult.Success).value
        val ws = (actions.createWorkStream(CreateWorkStream("WS", projectId = p.id, executionPreference = ExecutionPreference.EXTERNAL)) as ActionResult.Success).value
        val task = (actions.createTask(CreateTask("Human task", workStreamId = ws.id, executionPreference = ExecutionPreference.HUMAN)) as ActionResult.Success).value
        actions.setActiveTask(ws.id, task.id)
        actions.focusStream(ws.id)
        val r = actions.handOffStream(ws.id)
        assertTrue(r is ActionResult.Rejected)
        assertEquals(DomainError.NotExternalExecution, (r as ActionResult.Rejected).reason)
    }

    @Test fun leave_neverStartsProcessing() = runTest {
        val ws = (actions.createWorkStream(CreateWorkStream("Ext", executionPreference = ExecutionPreference.EXTERNAL)) as ActionResult.Success).value
        actions.focusStream(ws.id)
        val r = actions.leaveFocus(ws.id)
        assertTrue(r is ActionResult.Success)
        assertEquals(WorkStreamState.READY, (r as ActionResult.Success).value.state)
    }

    @Test fun processing_preferenceChangeToHuman_rejected() = runTest {
        val ws = (actions.createWorkStream(CreateWorkStream("Ext", executionPreference = ExecutionPreference.EXTERNAL)) as ActionResult.Success).value
        actions.focusStream(ws.id)
        actions.handOffStream(ws.id)
        val r = actions.setWorkStreamExecutionPreference(ws.id, ExecutionPreference.HUMAN)
        assertTrue(r is ActionResult.Rejected)
        assertEquals(DomainError.CannotChangeExecutionWhileProcessing, (r as ActionResult.Rejected).reason)
        assertEquals(ExecutionPreference.EXTERNAL, repo.getStream(ws.id)!!.executionPreference)
    }

    @Test fun focus_preferenceSwitch_allowed() = runTest {
        val ws = (actions.createWorkStream(CreateWorkStream("H", executionPreference = ExecutionPreference.HUMAN)) as ActionResult.Success).value
        actions.focusStream(ws.id)
        val r = actions.setWorkStreamExecutionPreference(ws.id, ExecutionPreference.EXTERNAL)
        assertTrue(r is ActionResult.Success)
        assertEquals(ExecutionPreference.EXTERNAL, (r as ActionResult.Success).value.executionPreference)
        assertEquals(WorkStreamState.FOCUS, r.value.state)
    }

    @Test fun projectless_inherit_rejected() = runTest {
        val r = actions.createWorkStream(CreateWorkStream("X", executionPreference = ExecutionPreference.INHERIT))
        assertTrue(r is ActionResult.Rejected)
        assertEquals(DomainError.InheritRequiresProject, (r as ActionResult.Rejected).reason)
    }

    @Test fun parentChange_doesNotRewriteExplicitChild() = runTest {
        val p = (actions.createProject(CreateProject("P", defaultExecutionMode = EffectiveExecutionMode.HUMAN)) as ActionResult.Success).value
        val ws = (actions.createWorkStream(CreateWorkStream("WS", projectId = p.id, executionPreference = ExecutionPreference.INHERIT)) as ActionResult.Success).value
        val task = (actions.createTask(CreateTask("T", workStreamId = ws.id, executionPreference = ExecutionPreference.EXTERNAL)) as ActionResult.Success).value
        actions.setProjectExecutionDefault(p.id, EffectiveExecutionMode.EXTERNAL)
        assertEquals(ExecutionPreference.EXTERNAL, repo.getTask(task.id)!!.executionPreference)
        assertEquals(
            EffectiveExecutionMode.EXTERNAL,
            ExecutionModeResolver.resolveTask(repo.getTask(task.id)!!, repo.projects.value, repo.streams.value, repo.tasks.value)
        )
        actions.setProjectExecutionDefault(p.id, EffectiveExecutionMode.HUMAN)
        assertEquals(ExecutionPreference.EXTERNAL, repo.getTask(task.id)!!.executionPreference)
        assertEquals(
            EffectiveExecutionMode.EXTERNAL,
            ExecutionModeResolver.resolveTask(repo.getTask(task.id)!!, repo.projects.value, repo.streams.value, repo.tasks.value)
        )
    }

    @Test fun reset_restoresInheritance() = runTest {
        val p = (actions.createProject(CreateProject("P", defaultExecutionMode = EffectiveExecutionMode.EXTERNAL)) as ActionResult.Success).value
        val ws = (actions.createWorkStream(CreateWorkStream("WS", projectId = p.id, executionPreference = ExecutionPreference.HUMAN)) as ActionResult.Success).value
        actions.resetWorkStreamExecutionPreference(ws.id)
        assertEquals(ExecutionPreference.INHERIT, repo.getStream(ws.id)!!.executionPreference)
        assertEquals(
            EffectiveExecutionMode.EXTERNAL,
            ExecutionModeResolver.resolveWorkStream(repo.getStream(ws.id)!!, repo.projects.value)
        )
    }

    @Test fun projectDefaultChange_rejectedWhenInheritedProcessingWouldBecomeHuman() = runTest {
        val p = (actions.createProject(CreateProject("P", defaultExecutionMode = EffectiveExecutionMode.EXTERNAL)) as ActionResult.Success).value
        val ws = (actions.createWorkStream(CreateWorkStream("WS", projectId = p.id, executionPreference = ExecutionPreference.INHERIT)) as ActionResult.Success).value
        actions.focusStream(ws.id)
        actions.handOffStream(ws.id)
        assertEquals(WorkStreamState.PROCESSING, repo.getStream(ws.id)!!.state)
        val r = actions.setProjectExecutionDefault(p.id, EffectiveExecutionMode.HUMAN)
        assertTrue(r is ActionResult.Rejected)
        assertEquals(DomainError.CannotChangeExecutionWhileProcessing, (r as ActionResult.Rejected).reason)
        assertEquals(EffectiveExecutionMode.EXTERNAL, repo.getProject(p.id)!!.defaultExecutionMode)
    }

    @Test fun projectDefaultChange_allowedWhenProcessingStreamHasExplicitExternal() = runTest {
        val p = (actions.createProject(CreateProject("P", defaultExecutionMode = EffectiveExecutionMode.EXTERNAL)) as ActionResult.Success).value
        val ws = (actions.createWorkStream(CreateWorkStream("WS", projectId = p.id, executionPreference = ExecutionPreference.EXTERNAL)) as ActionResult.Success).value
        actions.focusStream(ws.id)
        actions.handOffStream(ws.id)
        val r = actions.setProjectExecutionDefault(p.id, EffectiveExecutionMode.HUMAN)
        assertTrue(r is ActionResult.Success)
        assertEquals(EffectiveExecutionMode.HUMAN, (r as ActionResult.Success).value.defaultExecutionMode)
        assertEquals(WorkStreamState.PROCESSING, repo.getStream(ws.id)!!.state)
    }

    @Test fun handOff_allowedWhenActiveTaskIsExternalOverrideOnHumanWorkStream() = runTest {
        val ws = (actions.createWorkStream(CreateWorkStream("H", executionPreference = ExecutionPreference.HUMAN)) as ActionResult.Success).value
        val task = (actions.createTask(CreateTask("Ext task", workStreamId = ws.id, executionPreference = ExecutionPreference.EXTERNAL)) as ActionResult.Success).value
        actions.setActiveTask(ws.id, task.id)
        actions.focusStream(ws.id)
        val r = actions.handOffStream(ws.id)
        assertTrue(r is ActionResult.Success)
        assertEquals(WorkStreamState.PROCESSING, (r as ActionResult.Success).value.state)
    }
}

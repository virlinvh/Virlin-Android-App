package com.virlin.app.domain

import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.ExecutionModeResolver
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProjectStatus
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Phase A — pure [ExecutionModeResolver] inheritance. No repository / Room.
 */
class ExecutionModeResolverTest {

    private val t0 = Instant.parse("2026-09-14T12:00:00Z")

    private fun project(
        id: String = "p1",
        default: EffectiveExecutionMode = EffectiveExecutionMode.HUMAN
    ) = Project(
        id = id, title = id, status = ProjectStatus.ACTIVE,
        defaultExecutionMode = default, createdAt = t0, updatedAt = t0
    )

    private fun stream(
        id: String = "ws1",
        projectId: String? = "p1",
        pref: ExecutionPreference = ExecutionPreference.INHERIT,
        activeTaskId: String? = null
    ) = WorkStream(
        id = id, title = id, projectId = projectId, executionPreference = pref,
        state = WorkStreamState.READY, activeTaskId = activeTaskId, createdAt = t0, updatedAt = t0
    )

    private fun task(
        id: String,
        pref: ExecutionPreference = ExecutionPreference.INHERIT,
        parent: String? = null,
        workStreamId: String? = "ws1",
        projectId: String? = "p1"
    ) = Task(
        id = id, title = id, projectId = projectId, workStreamId = workStreamId,
        parentTaskId = parent, status = TaskStatus.TODO, executionPreference = pref,
        createdAt = t0, updatedAt = t0
    )

    @Test fun projectHuman_inheritedWorkStreamIsHuman() {
        val p = project(default = EffectiveExecutionMode.HUMAN)
        assertEquals(
            EffectiveExecutionMode.HUMAN,
            ExecutionModeResolver.resolveWorkStream(stream(pref = ExecutionPreference.INHERIT), p.defaultExecutionMode)
        )
    }

    @Test fun projectExternal_inheritedWorkStreamIsExternal() {
        val p = project(default = EffectiveExecutionMode.EXTERNAL)
        assertEquals(
            EffectiveExecutionMode.EXTERNAL,
            ExecutionModeResolver.resolveWorkStream(stream(pref = ExecutionPreference.INHERIT), p.defaultExecutionMode)
        )
    }

    @Test fun workStreamOverrideBeatsProject() {
        val p = project(default = EffectiveExecutionMode.HUMAN)
        assertEquals(
            EffectiveExecutionMode.EXTERNAL,
            ExecutionModeResolver.resolveWorkStream(
                stream(pref = ExecutionPreference.EXTERNAL), p.defaultExecutionMode
            )
        )
    }

    @Test fun taskOverrideBeatsWorkStream() {
        val ws = stream(pref = ExecutionPreference.EXTERNAL)
        val t = task("t1", pref = ExecutionPreference.HUMAN)
        assertEquals(
            EffectiveExecutionMode.HUMAN,
            ExecutionModeResolver.resolveTask(t, mapOf(t.id to t), ws, EffectiveExecutionMode.EXTERNAL)
        )
    }

    @Test fun nearestParentTaskOverrideWins() {
        val ws = stream(pref = ExecutionPreference.HUMAN)
        val a = task("a", pref = ExecutionPreference.EXTERNAL)
        val b = task("b", pref = ExecutionPreference.INHERIT, parent = "a")
        val c = task("c", pref = ExecutionPreference.INHERIT, parent = "b")
        val byId = listOf(a, b, c).associateBy { it.id }
        assertEquals(
            EffectiveExecutionMode.EXTERNAL,
            ExecutionModeResolver.resolveTask(c, byId, ws, EffectiveExecutionMode.HUMAN)
        )
    }

    @Test fun deepRecursiveInheritance() {
        val ws = stream(pref = ExecutionPreference.INHERIT)
        val a = task("a", pref = ExecutionPreference.INHERIT)
        val b = task("b", pref = ExecutionPreference.INHERIT, parent = "a")
        val c = task("c", pref = ExecutionPreference.INHERIT, parent = "b")
        val byId = listOf(a, b, c).associateBy { it.id }
        assertEquals(
            EffectiveExecutionMode.EXTERNAL,
            ExecutionModeResolver.resolveTask(c, byId, ws, EffectiveExecutionMode.EXTERNAL)
        )
    }

    @Test fun projectlessWorkStream_explicitHuman() {
        assertEquals(
            EffectiveExecutionMode.HUMAN,
            ExecutionModeResolver.resolveWorkStream(
                stream(projectId = null, pref = ExecutionPreference.HUMAN), null
            )
        )
    }

    @Test fun projectlessWorkStream_inheritFallsBackToHuman() {
        assertEquals(
            EffectiveExecutionMode.HUMAN,
            ExecutionModeResolver.resolveWorkStream(
                stream(projectId = null, pref = ExecutionPreference.INHERIT), null
            )
        )
    }

    @Test fun tasklessWorkStream_usesWorkStreamResolution() {
        val ws = stream(pref = ExecutionPreference.EXTERNAL, activeTaskId = null)
        assertEquals(
            EffectiveExecutionMode.EXTERNAL,
            ExecutionModeResolver.resolveCurrent(ws, emptyMap(), EffectiveExecutionMode.HUMAN)
        )
    }

    @Test fun deepestActiveTaskDeterminesCurrent() {
        val a = task("a", pref = ExecutionPreference.EXTERNAL)
        val b = task("b", pref = ExecutionPreference.HUMAN, parent = "a")
        val ws = stream(pref = ExecutionPreference.EXTERNAL, activeTaskId = "b")
        val byId = listOf(a, b).associateBy { it.id }
        assertEquals(
            EffectiveExecutionMode.HUMAN,
            ExecutionModeResolver.resolveCurrent(ws, byId, EffectiveExecutionMode.EXTERNAL)
        )
    }

    @Test fun fallbackHuman() {
        assertEquals(
            EffectiveExecutionMode.HUMAN,
            ExecutionModeResolver.resolveWorkStream(
                stream(projectId = null, pref = ExecutionPreference.INHERIT), null
            )
        )
    }

    @Test fun explicitDescendantSurvivesParentChange() {
        val a = task("a", pref = ExecutionPreference.EXTERNAL)
        val byId = mapOf(a.id to a)
        val ws = stream(pref = ExecutionPreference.INHERIT)
        // Parent project default HUMAN → still EXTERNAL (explicit)
        assertEquals(
            EffectiveExecutionMode.EXTERNAL,
            ExecutionModeResolver.resolveTask(a, byId, ws, EffectiveExecutionMode.HUMAN)
        )
        // Parent project default EXTERNAL → still EXTERNAL (explicit, not rewritten)
        assertEquals(
            EffectiveExecutionMode.EXTERNAL,
            ExecutionModeResolver.resolveTask(a, byId, ws, EffectiveExecutionMode.EXTERNAL)
        )
    }

    @Test fun resetRestoresInheritance() {
        val ws = stream(pref = ExecutionPreference.INHERIT)
        val inherited = task("t1", pref = ExecutionPreference.INHERIT)
        val byId = mapOf(inherited.id to inherited)
        assertEquals(
            EffectiveExecutionMode.EXTERNAL,
            ExecutionModeResolver.resolveTask(inherited, byId, ws, EffectiveExecutionMode.EXTERNAL)
        )
        // After "reset", preference stays INHERIT; changing WorkStream preference re-resolves
        val wsHuman = ws.copy(executionPreference = ExecutionPreference.HUMAN)
        assertEquals(
            EffectiveExecutionMode.HUMAN,
            ExecutionModeResolver.resolveTask(inherited, byId, wsHuman, EffectiveExecutionMode.EXTERNAL)
        )
    }

    @Test fun listConvenience_resolveCurrent() {
        val p = project(default = EffectiveExecutionMode.EXTERNAL)
        val t = task("t1", pref = ExecutionPreference.HUMAN)
        val ws = stream(pref = ExecutionPreference.INHERIT, activeTaskId = "t1")
        assertEquals(
            EffectiveExecutionMode.HUMAN,
            ExecutionModeResolver.resolveCurrent(ws, listOf(p), listOf(t))
        )
    }
}

package com.virlin.app.domain.model

/**
 * Execution responsibility — independent of hierarchy and attention lifecycle.
 *
 * [ExecutionPreference] is what is stored (explicit or inherit).
 * [EffectiveExecutionMode] is the resolved HUMAN/EXTERNAL used for Hand Off eligibility
 * and Now/Control button sets. MIXED is never stored — it is a derived container summary only.
 *
 * Actor/tool identity (Me, Claude, Cursor, …) is a future separate concept; do not encode
 * it into these enums.
 */
enum class ExecutionPreference {
    /** Resolve from nearest parent Task → WorkStream → Project default → HUMAN. */
    INHERIT,
    HUMAN,
    EXTERNAL;

    fun asEffectiveOrNull(): EffectiveExecutionMode? = when (this) {
        INHERIT -> null
        HUMAN -> EffectiveExecutionMode.HUMAN
        EXTERNAL -> EffectiveExecutionMode.EXTERNAL
    }
}

/** Resolved who executes when the human is not looking. Never INHERIT / MIXED. */
enum class EffectiveExecutionMode {
    HUMAN,
    EXTERNAL;

    /** Explicit preference (never INHERIT) — used when Create/command chooses Human/External. */
    fun toPreference(): ExecutionPreference = when (this) {
        HUMAN -> ExecutionPreference.HUMAN
        EXTERNAL -> ExecutionPreference.EXTERNAL
    }
}

/**
 * Canonical inheritance resolver. Pure: callers supply the Project default and Task map.
 * UI must not reimplement this chain.
 *
 * Order: Task explicit → nearest parent Task explicit → WorkStream explicit →
 * Project.defaultExecutionMode → HUMAN.
 */
object ExecutionModeResolver {

    fun resolveWorkStream(
        workStream: WorkStream,
        projectDefault: EffectiveExecutionMode?
    ): EffectiveExecutionMode =
        workStream.executionPreference.asEffectiveOrNull()
            ?: projectDefault
            ?: EffectiveExecutionMode.HUMAN

    /**
     * Walks [task] then parents via [tasksById]. Missing parent links fall through to the
     * WorkStream / Project chain (defensive; cycles are rejected at write time).
     */
    fun resolveTask(
        task: Task,
        tasksById: Map<String, Task>,
        workStream: WorkStream?,
        projectDefault: EffectiveExecutionMode?
    ): EffectiveExecutionMode {
        var cur: Task? = task
        val seen = HashSet<String>()
        while (cur != null) {
            if (!seen.add(cur.id)) break
            cur.executionPreference.asEffectiveOrNull()?.let { return it }
            val parentId = cur.parentTaskId ?: break
            cur = tasksById[parentId]
        }
        return when {
            workStream != null -> resolveWorkStream(workStream, projectDefault)
            projectDefault != null -> projectDefault
            else -> EffectiveExecutionMode.HUMAN
        }
    }

    /**
     * Effective mode for the current attention cycle on [workStream]: deepest active Task
     * when [WorkStream.activeTaskId] resolves, otherwise the WorkStream itself.
     */
    fun resolveCurrent(
        workStream: WorkStream,
        tasksById: Map<String, Task>,
        projectDefault: EffectiveExecutionMode?
    ): EffectiveExecutionMode {
        val activeId = workStream.activeTaskId ?: return resolveWorkStream(workStream, projectDefault)
        val active = tasksById[activeId] ?: return resolveWorkStream(workStream, projectDefault)
        return resolveTask(active, tasksById, workStream, projectDefault)
    }

    fun projectDefault(project: Project?): EffectiveExecutionMode? =
        project?.defaultExecutionMode

    /** Convenience when Projects and Tasks are already loaded as lists. */
    fun resolveCurrent(
        workStream: WorkStream,
        projects: Collection<Project>,
        tasks: Collection<Task>
    ): EffectiveExecutionMode {
        val projectDefault = workStream.projectId
            ?.let { id -> projects.firstOrNull { it.id == id } }
            ?.defaultExecutionMode
        return resolveCurrent(workStream, tasks.associateBy { it.id }, projectDefault)
    }

    fun resolveWorkStream(
        workStream: WorkStream,
        projects: Collection<Project>
    ): EffectiveExecutionMode {
        val projectDefault = workStream.projectId
            ?.let { id -> projects.firstOrNull { it.id == id } }
            ?.defaultExecutionMode
        return resolveWorkStream(workStream, projectDefault)
    }

    fun resolveTask(
        task: Task,
        projects: Collection<Project>,
        streams: Collection<WorkStream>,
        tasks: Collection<Task>
    ): EffectiveExecutionMode {
        val tasksById = tasks.associateBy { it.id }
        val workStream = task.workStreamId?.let { id -> streams.firstOrNull { it.id == id } }
        val projectDefault = (workStream?.projectId ?: task.projectId)
            ?.let { id -> projects.firstOrNull { it.id == id } }
            ?.defaultExecutionMode
        return resolveTask(task, tasksById, workStream, projectDefault)
    }
}

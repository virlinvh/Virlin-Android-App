package com.virlin.app.domain.external

import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.domain.model.ExternalActor
import com.virlin.app.domain.model.ExternalStage
import com.virlin.app.domain.model.ExternalStages
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.effectiveAttentionState
import java.time.Instant

/**
 * WORKING FOR YOU (Phase 10) — the projection of "something else is doing this for me".
 *
 * It is a PROJECTION, not a store: an item is in Working For You exactly while its WorkStream is
 * PROCESSING and its check time has not arrived. When `now >= checkAt` the same WorkStream —
 * same id, same task, same actor, same stages — presents as attention (`CHECK`) and belongs to
 * Needs You. Nothing is copied or duplicated to move between the two sections.
 *
 * The countdown is always `checkAt − now` (`AttentionTiming`), so backgrounding and process death
 * cannot drift it, and nothing is written per second.
 */
data class ExternalWorkItem(
    val stream: WorkStream,
    val actor: ExternalActor?,
    /** What the external actor was asked to do (`WorkStream.waitingFor`). */
    val instruction: String?,
    /** Project / WorkStream / deepest active Task — stable ids resolved for display. */
    val projectTitle: String?,
    val workItemTitle: String?,
    val stage: ExternalStage?,
    val stagePosition: Int?,
    val stageCount: Int,
    val checkAt: Instant?
) {
    val id: String get() = stream.id
    val actorName: String get() = actor?.displayName ?: stream.tool ?: "External process"

    /** "Stage 2 · Implement fix", or null when the run has no stages. */
    fun stageLabel(): String? = stage?.let { s -> stagePosition?.let { "Stage $it · ${s.title}" } ?: s.title }

    fun countdown(now: Instant): String = AttentionTiming.format(checkAt, now)

    /** "Claude Code. Implement listening state. Working. Due in 4 minutes 32 seconds." */
    fun describe(now: Instant): String = buildList {
        add(actorName)
        (workItemTitle ?: instruction ?: stream.title).let { add(it) }
        stageLabel()?.let { add(it) }
        add(if (checkAt == null) "Working. No check time." else "Working. " + AttentionTiming.describe(checkAt, now))
    }.joinToString(". ")
}

object ExternalWork {

    /**
     * The Working For You list for [now]: externally executing streams whose check time has NOT
     * arrived, soonest check first. Ties (and items with no check time) are ordered deterministically
     * by id; items with no check time come last — they are running with nothing planned.
     *
     * Deliberately NOT ordered by the Needs You priority ranking: Working For You is future-oriented
     * (when will I need to look), Needs You is attention-oriented (what do I handle first).
     */
    fun workingForYou(
        streams: Collection<WorkStream>,
        now: Instant,
        stages: Collection<ExternalStage> = emptyList(),
        projects: Collection<Project> = emptyList(),
        tasks: Collection<Task> = emptyList()
    ): List<ExternalWorkItem> = streams
        .filter { it.state == WorkStreamState.PROCESSING && effectiveAttentionState(it, now) == WorkStreamState.PROCESSING }
        .sortedWith(compareBy({ it.checkAt == null }, { it.checkAt ?: Instant.MAX }, { it.id }))
        .map { item(it, stages, projects, tasks) }

    fun item(
        stream: WorkStream,
        stages: Collection<ExternalStage> = emptyList(),
        projects: Collection<Project> = emptyList(),
        tasks: Collection<Task> = emptyList()
    ): ExternalWorkItem {
        val stage = ExternalStages.current(stages, stream.id)
        val activeTask = stream.activeTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
        return ExternalWorkItem(
            stream = stream,
            actor = ExternalActor.resolve(stream.externalActorId, stream.tool),
            instruction = stream.waitingFor,
            projectTitle = stream.projectId?.let { id -> projects.firstOrNull { it.id == id }?.title },
            workItemTitle = activeTask?.title,
            stage = stage,
            stagePosition = ExternalStages.position(stages, stream.id, stage),
            stageCount = ExternalStages.count(stages, stream.id),
            checkAt = stream.checkAt
        )
    }

    /** Breadcrumb for the detail surface: Project › WorkStream › Task (leaf last). */
    fun context(
        stream: WorkStream,
        projects: Collection<Project>,
        tasks: Collection<Task>
    ): List<String> = buildList {
        stream.projectId?.let { id -> projects.firstOrNull { it.id == id }?.let { add(it.title) } }
        add(stream.title)
        stream.activeTaskId?.let { id ->
            TaskHierarchy.ancestry(tasks, id)?.reversed()?.forEach { add(it.title) }
        }
    }
}

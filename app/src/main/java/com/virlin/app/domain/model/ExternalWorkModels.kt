package com.virlin.app.domain.model

import java.time.Duration
import java.time.Instant

/**
 * # External work (Phase 10)
 *
 * "Working For You" is not a second entity: it is the [WorkStream] itself while an external
 * actor is doing the work (`state == PROCESSING`, `checkAt` = when to look again,
 * `activeTaskId` = the exact hierarchy work item, `waitingFor` = the instruction given).
 * Phase 10 adds only the two things the WorkStream could not already express:
 *
 *  - WHO/WHAT is doing it — [ExternalActor], stored as a stable id on the stream.
 *  - the planned steps of the external process — [ExternalStage]s, tracking metadata that
 *    describes the external run. Stages are NOT hierarchy Tasks and never become them.
 */

/**
 * The actor performing external work. [id] is the stable identity; [displayName] is what the
 * card shows. The catalogue is a convenience, not a closed set: any id with a display name is
 * valid, so tools Virlin has never heard of still work (and no actor-management screen exists).
 */
data class ExternalActor(val id: String, val displayName: String) {
    companion object {
        val CLAUDE_CODE = ExternalActor("claude_code", "Claude Code")
        val ANTIGRAVITY = ExternalActor("antigravity", "AntiGravity")
        val CODEX = ExternalActor("codex", "Codex")
        val GEMINI = ExternalActor("gemini", "Gemini")
        val CURSOR = ExternalActor("cursor", "Cursor")
        val ANDROID_STUDIO = ExternalActor("android_studio", "Android Studio")
        val BUILD = ExternalActor("build", "Build")
        val RENDER = ExternalActor("render", "Render")
        val PERSON = ExternalActor("person", "Someone else")
        val OTHER = ExternalActor("other", "External process")

        /** Offered in the quick create path. Not a restriction on what may be stored. */
        val catalogue: List<ExternalActor> = listOf(
            CLAUDE_CODE, ANTIGRAVITY, CODEX, GEMINI, CURSOR, ANDROID_STUDIO, BUILD, RENDER, PERSON, OTHER
        )

        /**
         * Resolve a stored id for display. An unknown id keeps its identity and falls back to
         * [fallbackName] (historically `WorkStream.tool`) so nothing ever renders as blank.
         */
        fun resolve(id: String?, fallbackName: String? = null): ExternalActor? {
            if (id.isNullOrBlank()) return fallbackName?.takeIf { it.isNotBlank() }?.let { ExternalActor(it.lowercase(), it) }
            return catalogue.firstOrNull { it.id == id }
                ?: ExternalActor(id, fallbackName?.takeIf { it.isNotBlank() } ?: id.replace('_', ' ').replaceFirstChar { c -> c.uppercase() })
        }
    }
}

enum class ExternalStageStatus {
    /** Planned; the external actor has not been asked to do it yet. */
    PENDING,
    /** The actor is doing this stage now — this is what the Working For You card names. */
    IN_PROGRESS,
    /** The user confirmed this stage finished. Virlin never detects this by itself. */
    DONE;

    val isTerminal: Boolean get() = this == DONE
}

/**
 * One planned step of an external run. [order] is explicit and is the ONLY ordering truth —
 * never title, insertion time or row order. [expectedMinutes] is what the check time is
 * derived from when the stage starts (`checkAt = startedAt + expected`); no countdown is
 * ever persisted.
 */
data class ExternalStage(
    val id: String,
    val workStreamId: String,
    val title: String,
    val order: Int,
    val expectedMinutes: Long? = null,
    val status: ExternalStageStatus = ExternalStageStatus.PENDING,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null
) {
    val expected: Duration? get() = expectedMinutes?.let { Duration.ofMinutes(it) }
}

/** Pure stage arithmetic. The UI and the actions both read stages through here. */
object ExternalStages {

    fun of(stages: Collection<ExternalStage>, streamId: String): List<ExternalStage> =
        stages.filter { it.workStreamId == streamId }.sortedWith(compareBy({ it.order }, { it.id }))

    /** The stage being executed, or the first one still to do when none is running. */
    fun current(stages: Collection<ExternalStage>, streamId: String): ExternalStage? {
        val ordered = of(stages, streamId)
        return ordered.firstOrNull { it.status == ExternalStageStatus.IN_PROGRESS }
            ?: ordered.firstOrNull { it.status == ExternalStageStatus.PENDING }
    }

    /** The next PENDING stage after [after] in explicit order; null when [after] is the last one. */
    fun next(stages: Collection<ExternalStage>, streamId: String, after: ExternalStage?): ExternalStage? {
        val ordered = of(stages, streamId)
        val index = after?.let { a -> ordered.indexOfFirst { it.id == a.id } } ?: -1
        return ordered.drop(index + 1).firstOrNull { it.status != ExternalStageStatus.DONE }
    }

    fun isFinal(stages: Collection<ExternalStage>, streamId: String, stage: ExternalStage?): Boolean =
        stage != null && next(stages, streamId, stage) == null

    /** 1-based position of [stage] within the stream's stages, or null. */
    fun position(stages: Collection<ExternalStage>, streamId: String, stage: ExternalStage?): Int? {
        if (stage == null) return null
        val i = of(stages, streamId).indexOfFirst { it.id == stage.id }
        return if (i < 0) null else i + 1
    }

    fun count(stages: Collection<ExternalStage>, streamId: String): Int = of(stages, streamId).size
}

package com.virlin.app.domain.action

import com.virlin.app.domain.model.ExternalActor
import java.time.Instant

/**
 * Requests for the Phase 10 external-work actions. They are values, so the Now UI, a hierarchy
 * screen and (later) the Agent all express the same intent without duplicating any rule.
 */

/** One planned step of an external run. Order is the position in the list. */
data class NewExternalStage(val title: String, val expectedMinutes: Long? = null)

/**
 * "Something else is doing this for me now."
 *
 * The check time is resolved in this order: explicit [checkAt] → [checkInMinutes] from now →
 * the first stage's expected duration → none (running with nothing planned). A stage list is
 * optional: a single-shot delegation has no stages at all.
 */
data class StartExternalWork(
    val workStreamId: String,
    val actor: ExternalActor? = null,
    /** What the actor was asked to do. Stored as the stream's `waitingFor`. */
    val instruction: String? = null,
    /** The exact hierarchy work item the external work concerns (stable id, never a title). */
    val workItemId: String? = null,
    val checkInMinutes: Long? = null,
    val checkAt: Instant? = null,
    val stages: List<NewExternalStage> = emptyList()
)

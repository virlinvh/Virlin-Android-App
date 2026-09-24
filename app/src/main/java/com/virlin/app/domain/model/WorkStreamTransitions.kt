package com.virlin.app.domain.model

import com.virlin.app.domain.model.WorkStreamState.*

/**
 * The single table of valid state transitions. Every state change in Virlin passes through
 * [canTransition]; nothing may assign a state directly.
 *
 * Notes:
 * - PROCESSING never goes to SNOOZED: a processing stream defers attention via `checkAt`,
 *   which is a different concept from a human choosing not to look.
 * - FOCUS → SNOOZED is "Leave with a return time" (human return). FOCUS → PROCESSING is
 *   "Hand off" (an external process keeps working). They are different intents.
 * - READY -> PROCESSING is Phase 10 DELEGATE: work the human is NOT doing is handed to an
 *   external actor. FOCUS -> PROCESSING remains Hand Off (the human leaves Focus). Both end in
 *   the same place; only PROCESSING ever means "something else is working".
 * - DONE is terminal until an explicit reopen action exists.
 */
object WorkStreamTransitions {

    private val allowed: Map<WorkStreamState, Set<WorkStreamState>> = mapOf(
        FOCUS      to setOf(PROCESSING, READY, SNOOZED, PAUSED, BLOCKED, DONE),
        PROCESSING to setOf(CHECK, READY, BLOCKED, DONE),
        CHECK      to setOf(PROCESSING, FOCUS, SNOOZED, READY, BLOCKED),
        READY      to setOf(FOCUS, PROCESSING, SNOOZED, BLOCKED, PAUSED, DONE),
        SNOOZED    to setOf(CHECK, READY, FOCUS),
        BLOCKED    to setOf(READY, FOCUS, DONE),
        PAUSED     to setOf(READY, FOCUS, DONE),
        DONE       to emptySet()
    )

    fun canTransition(from: WorkStreamState, to: WorkStreamState): Boolean =
        from != to && allowed[from]?.contains(to) == true

    fun targetsFrom(from: WorkStreamState): Set<WorkStreamState> = allowed[from].orEmpty()
}

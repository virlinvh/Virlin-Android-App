package com.virlin.app.domain.action

import com.virlin.app.domain.model.WorkStreamState

/**
 * Structured outcome of every domain action. Expected outcomes are values, not exceptions,
 * so the Now UI, the Agent and future interpreters can map them to receipts and copy.
 */
sealed interface ActionResult<out T> {
    data class Success<T>(val value: T) : ActionResult<T>
    /** The request was understood but is not valid in the current domain state. */
    data class Rejected(val reason: DomainError) : ActionResult<Nothing>
    data class NotFound(val streamId: String) : ActionResult<Nothing>
    /** Persistence or unexpected failure. */
    data class Failure(val cause: Throwable) : ActionResult<Nothing>

    val isSuccess: Boolean get() = this is Success
}

inline fun <T> ActionResult<T>.getOrNull(): T? = (this as? ActionResult.Success)?.value

/**
 * Typed, human-mappable reasons. No user-facing copy lives here — UI and Agent map
 * reason → wording.
 */
sealed interface DomainError {
    data class InvalidTransition(val from: WorkStreamState, val to: WorkStreamState) : DomainError
    data object StreamAlreadyDone : DomainError
    data object AlreadyFocused : DomainError
    data object NotInFocus : DomainError
    data object NoActiveFocus : DomainError
    data object InvalidSnoozeTime : DomainError
    /** The action needs an external-check context (a due PROCESSING check), not a human return. */
    data object NotAnExternalCheck : DomainError
    /** The action needs a timed return (a snoozed stream), not a processing check. */
    data object NotAReturn : DomainError
    data object NotBlocked : DomainError
    data object EmptyNote : DomainError
    /** Needs You reorder asked for a stream that is not in CHECK. */
    data object NotInNeedsYou : DomainError

    // ---- Structure
    data object EmptyTitle : DomainError
    data class ProjectNotFound(val projectId: String) : DomainError
    data class TaskNotFound(val taskId: String) : DomainError
    data object ProjectAlreadyDone : DomainError
    data object TaskAlreadyClosed : DomainError
    data object SelfParent : DomainError
    data object CyclicParent : DomainError
    /** Child would point at a different Project / WorkStream than its parent. */
    data object OwnershipMismatch : DomainError
    /** Task does not belong to that WorkStream. */
    data object TaskNotInWorkStream : DomainError
    data object InvalidEffort : DomainError

    // ---- External work (Phase 10)
    /** START NEXT STAGE was asked for but the run has no stage left to start. */
    data object NoNextStage : DomainError
    /** The action needs an external run (a stream with an actor / PROCESSING context). */
    data object NotExternalWork : DomainError

    // ---- Execution responsibility
    /** Hand Off / external-processing verbs require effective EXTERNAL. */
    data object NotExternalExecution : DomainError
    /** Preference change would make a PROCESSING stream effective HUMAN. */
    data object CannotChangeExecutionWhileProcessing : DomainError
    /** Projectless WorkStreams may not use INHERIT. */
    data object InheritRequiresProject : DomainError

    // ---- Capture (Pass 10)
    data object EmptyCapture : DomainError
    data class NoteNotFound(val captureItemId: String) : DomainError
    data object NotATextNote : DomainError
    data object NotAPrompt : DomainError
    data object NotAnAttachment : DomainError
    data object NotAVoiceNote : DomainError
    data object InvalidLink : DomainError
    data class CaptureNotFound(val captureId: String) : DomainError
    data object CaptureAlreadyOrganized : DomainError
    data object CaptureNotArchived : DomainError
}

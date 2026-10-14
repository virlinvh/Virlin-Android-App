package com.virlin.app.ui.screens

import com.virlin.app.domain.model.executionStream

import android.util.Log
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.ui.screens.NowViewModel.Chooser
import com.virlin.app.ui.screens.NowViewModel.TimedIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * The finalized attention intents (Leave / Hand off / Check outcomes / Defer / Complete) and
 * the single lightweight chooser — ONE implementation shared by every surface that offers
 * them (Now, Stream Detail, the Agent's CONTROL mode). Owns no domain rules: every intent is
 * a `VirlinActions` call whose structured result is logged and summarized as [feedback].
 */
interface AttentionIntents {
    val chooser: StateFlow<Chooser?>
    fun openLeave(streamId: String)
    fun openHandOff(streamId: String)
    fun openCheck(streamId: String)
    fun openStillRunning(streamId: String)
    fun openResultReady(streamId: String)
    fun openDefer(streamId: String)
    fun openCustom(streamId: String, intent: TimedIntent)
    fun dismissChooser()
    fun leave(streamId: String, minutes: Long?)
    fun handOff(streamId: String, minutes: Long?)
    fun stillRunning(streamId: String, minutes: Long)
    fun resultReadyNow(streamId: String)
    fun resultReadyLater(streamId: String, minutes: Long)
    fun deferReturn(streamId: String, minutes: Long)
    fun block(streamId: String)
    fun customMinutes(streamId: String, intent: TimedIntent, minutes: Long): Boolean
}

class AttentionIntentController(
    private val actions: VirlinActions,
    private val clock: VirlinClock,
    private val repository: WorkStreamRepository,
    private val scope: CoroutineScope,
    private val tag: String = "NowActions"
) : AttentionIntents {

    private val _chooser = MutableStateFlow<Chooser?>(null)
    override val chooser: StateFlow<Chooser?> = _chooser.asStateFlow()

    /** Short, human feedback for the last intent ("Left Psychology for 10m"); null when none. */
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()
    fun clearFeedback() { _feedback.value = null }
    fun reportFeedback(message: String) { _feedback.value = message }

    /** Stream id awaiting the user's explicit "complete the whole WorkStream" confirmation. */
    private val _pendingWorkStreamCompletion = MutableStateFlow<String?>(null)
    val pendingWorkStreamCompletion: StateFlow<String?> = _pendingWorkStreamCompletion.asStateFlow()

    override fun openLeave(streamId: String) { _chooser.value = Chooser.Leave(streamId) }
    override fun openHandOff(streamId: String) { _chooser.value = Chooser.HandOff(streamId) }
    override fun openCheck(streamId: String) { _chooser.value = Chooser.CheckOutcome(streamId) }
    override fun openStillRunning(streamId: String) { _chooser.value = Chooser.StillRunning(streamId) }
    override fun openResultReady(streamId: String) { _chooser.value = Chooser.ResultReady(streamId) }
    override fun openDefer(streamId: String) { _chooser.value = Chooser.Defer(streamId) }
    override fun openCustom(streamId: String, intent: TimedIntent) { _chooser.value = Chooser.Custom(streamId, intent) }
    override fun dismissChooser() { _chooser.value = null }

    private fun at(minutes: Long) = clock.now().plus(Duration.ofMinutes(minutes))
    private suspend fun activity(streamId: String) = repository.getTask(streamId)?.executionStream()
        ?: repository.getStream(streamId)
    private suspend fun title(streamId: String) = activity(streamId)?.title ?: "WorkStream"

    override fun leave(streamId: String, minutes: Long?) { dismissChooser(); run(streamId, { t -> if (minutes == null) "Left $t" else "Left $t · back in ${minutes}m" }) { actions.leaveFocus(streamId, minutes?.let(::at)) } }
    override fun handOff(streamId: String, minutes: Long?) { dismissChooser(); run(streamId, { t -> if (minutes == null) "Handed off $t" else "Handed off $t · check in ${minutes}m" }) { actions.handOffStream(streamId, checkAt = minutes?.let(::at)) } }
    override fun stillRunning(streamId: String, minutes: Long) { dismissChooser(); run(streamId, { t -> "$t still running · check in ${minutes}m" }) { actions.stillRunning(streamId, at(minutes)) } }
    override fun resultReadyNow(streamId: String) { dismissChooser(); run(streamId, { t -> "Focused $t" }) { actions.resultReadyNow(streamId) } }
    override fun resultReadyLater(streamId: String, minutes: Long) { dismissChooser(); run(streamId, { t -> "$t result ready · remind in ${minutes}m" }) { actions.resultReadyLater(streamId, at(minutes)) } }
    override fun deferReturn(streamId: String, minutes: Long) { dismissChooser(); run(streamId, { t -> "$t · back in ${minutes}m" }) { actions.deferReturn(streamId, at(minutes)) } }
    override fun block(streamId: String) { dismissChooser(); run(streamId, { t -> "$t blocked" }) { actions.blockStream(streamId) } }
    /** "Not now": keep the item, stop asking — the existing READY transition, nothing new. */
    fun markReady(streamId: String) { dismissChooser(); run(streamId, { t -> "$t is ready when you are" }) { actions.markReady(streamId) } }
    fun focus(streamId: String) = run(streamId, { t -> "Resumed $t" }) {
        if (activity(streamId)?.externalTrackingPaused == true) actions.resumeExternalTracking(streamId)
        else actions.focusStream(streamId)
    }
    fun pauseExternal(streamId: String, minutes: Long?) = run(streamId, { t -> "$t · external tracking paused" }) {
        actions.pauseExternalTracking(streamId, minutes?.let { at(it) })
    }
    fun resumeExternal(streamId: String) = run(streamId, { t -> "$t · external tracking resumed" }) {
        actions.resumeExternalTracking(streamId)
    }
    fun checkDue(streamId: String) = run(streamId, { null }) { actions.checkDue(streamId) }
    fun continueProcessing(streamId: String, minutes: Long) = run(streamId, { t -> "$t · check in ${minutes}m" }) { actions.continueProcessing(streamId, at(minutes)) }
    fun completeStream(streamId: String) = run(streamId, { t -> "$t completed" }) { actions.completeStream(streamId) }
    fun pauseWorkstream(streamId: String) = run(streamId, { t -> "$t paused" }) { actions.pauseStream(streamId) }
    fun confirmExternalResult(streamId: String) = run(streamId, { t -> "$t result ready · review next" }) { actions.markExternalResultReady(streamId) }
    fun unblockWorkstream(streamId: String) = run(streamId, { t -> "$t unblocked · ready" }) { actions.unblockStream(streamId) }
    fun resumeLater(streamId: String, minutes: Long) = run(streamId, { t -> "$t · return in ${minutes}m" }) { actions.snoozeStream(streamId, at(minutes)) }
    fun editBlockReason(streamId: String, reason: String) = run(streamId, { "Block reason saved" }) {
        actions.updateContext(streamId, com.virlin.app.domain.action.ContextUpdate(
            blockerReason = com.virlin.app.domain.action.Field.Set(reason.trim())))
    }
    fun startExternalRun(streamId: String, minutes: Long?, taskId: String? = null) = run(streamId, { t -> "$t running externally" }) {
        val stream = activity(streamId) ?: return@run ActionResult.NotFound(streamId)
        val projectDefault = stream.projectId?.let { repository.getProject(it)?.defaultExecutionMode }
        val task = (taskId ?: stream.activeTaskId)?.let { repository.getTask(it) }
        val owner = task?.workStreamId?.let { repository.getStream(it) } ?: stream
        val effective = if (task != null) com.virlin.app.domain.model.ExecutionModeResolver.resolveTask(
            task, repository.tasks.value.associateBy { it.id }, owner, projectDefault)
        else com.virlin.app.domain.model.ExecutionModeResolver.resolveWorkStream(stream, projectDefault)
        if (effective != com.virlin.app.domain.model.EffectiveExecutionMode.EXTERNAL) {
            ActionResult.Rejected(com.virlin.app.domain.action.DomainError.NotExternalExecution)
        } else actions.startExternalWork(com.virlin.app.domain.action.StartExternalWork(
            workStreamId = streamId, workItemId = taskId ?: stream.activeTaskId, checkInMinutes = minutes))
    }
    /** Needs You queue position (Phase 1 `reorderNeedsYou`): ordering only — never timers, urgency or `updatedAt`. */
    fun reorderNeedsYou(streamId: String, position: Int) = run(streamId, { t -> "$t → #$position" }) { actions.reorderNeedsYou(streamId, position) }
    /** Priority editor SAVE: the same Phase 03 move, plus the typed preference for later occurrences. */
    fun setNeedsYouPriority(streamId: String, position: Int, scope: com.virlin.app.domain.attention.PriorityScope) =
        run(streamId, { t -> "$t → #$position" }) { actions.setNeedsYouPriority(streamId, position, scope) }

    override fun customMinutes(streamId: String, intent: TimedIntent, minutes: Long): Boolean {
        if (minutes <= 0) return false
        when (intent) {
            TimedIntent.LEAVE -> leave(streamId, minutes)
            TimedIntent.HAND_OFF -> handOff(streamId, minutes)
            TimedIntent.STILL_RUNNING -> stillRunning(streamId, minutes)
            TimedIntent.RESULT_READY_LATER -> resultReadyLater(streamId, minutes)
            TimedIntent.DEFER_RETURN -> deferReturn(streamId, minutes)
        }
        return true
    }

    /**
     * COMPLETE: with an active Task it completes ONLY that task (domain clears `activeTaskId`,
     * nothing auto-selected). Without one it asks for explicit whole-WorkStream confirmation.
     */
    fun complete(streamId: String) {
        scope.launch {
            // Now's queue also contains task execution projections, whose id is the task id.
            // Complete that exact task, not its owner or the currently focused task elsewhere.
            if (repository.getTask(streamId) != null) {
                completeTaskNow(streamId)
                return@launch
            }
            val stream = repository.getStream(streamId)
            val active = stream?.activeTaskId
            if (active != null) {
                val taskTitle = repository.getTask(active)?.title ?: "Task"
                // Completion is AWAITED here: asking for the next candidate before the write lands
                // would offer the item that was just completed, and FOCUS NEXT would be rejected.
                val completed = actions.completeTask(active)
                report(title(streamId), completed) { "$taskTitle completed" }
                if (completed is ActionResult.Success) {
                    // Phase 09: offer what is next — FOCUS NEXT requires intent, it never auto-starts.
                    val next = (actions.nextTaskCandidate(streamId) as? ActionResult.Success)?.value
                    _completedFocus.value = CompletedFocus(streamId, taskTitle, next?.id, next?.title)
                }
            } else _pendingWorkStreamCompletion.value = streamId
        }
    }

    /** What was just completed while focusing, and the next candidate (if any). Transient UI state. */
    data class CompletedFocus(val streamId: String, val completedTitle: String, val nextTaskId: String?, val nextTitle: String?)

    private val _completedFocus = MutableStateFlow<CompletedFocus?>(null)
    val completedFocus: StateFlow<CompletedFocus?> = _completedFocus.asStateFlow()

    /** "DONE FOR NOW": dismiss the continuation; nothing is focused and nothing is scheduled. */
    fun dismissCompletedFocus() { _completedFocus.value = null }

    /** "FOCUS NEXT": explicitly start the offered next leaf. */
    fun focusNextAfterCompletion() {
        val done = _completedFocus.value ?: return
        _completedFocus.value = null
        val next = done.nextTaskId ?: return
        run(done.streamId, { "Focused ${done.nextTitle}" }) { actions.startFocus(next) }
    }
    fun confirmCompleteWorkStream() {
        val id = _pendingWorkStreamCompletion.value ?: return
        _pendingWorkStreamCompletion.value = null
        completeStream(id)
    }
    fun dismissWorkStreamCompletion() { _pendingWorkStreamCompletion.value = null }

    // ---- structure intents (same facade; the Agent's task controls use these)
    fun setActiveTask(streamId: String, taskId: String) = scope.launch {
        val t = repository.getTask(taskId)?.title ?: "Task"
        report(t, actions.setActiveTask(streamId, taskId)) { "Current task · $t" }
    }
    fun completeTask(taskId: String) = scope.launch { completeTaskNow(taskId) }
    suspend fun completeTaskNow(taskId: String) {
        val t = repository.getTask(taskId)?.title ?: "Task"
        report(t, actions.completeTask(taskId)) { "$t completed" }
    }
    fun cancelTask(taskId: String) = scope.launch {
        val t = repository.getTask(taskId)?.title ?: "Task"
        report(t, actions.cancelTask(taskId)) { "$t cancelled" }
    }

    private fun run(streamId: String, success: (String) -> String?, block: suspend () -> ActionResult<*>) {
        scope.launch { report(title(streamId), block(), success) }
    }

    /** Maps the structured result to short human feedback. Never fabricates success, never leaks exceptions. */
    private fun report(subject: String, r: ActionResult<*>, success: (String) -> String?) {
        when (r) {
            is ActionResult.Success -> { Log.d(tag, "ok"); _feedback.value = success(subject) }
            is ActionResult.Rejected -> { Log.d(tag, "rejected ${r.reason}"); _feedback.value =
                if (r.reason == com.virlin.app.domain.action.DomainError.AnotherTaskIsExecuting)
                    "Another task is active in this workstream. Its activity has not been changed."
                else "Couldn't do that — $subject has changed" }
            is ActionResult.NotFound -> { Log.w(tag, "not found ${r.streamId}"); _feedback.value = "$subject no longer exists" }
            is ActionResult.Failure -> { Log.e(tag, "failure", r.cause); _feedback.value = "Something went wrong" }
        }
    }
}

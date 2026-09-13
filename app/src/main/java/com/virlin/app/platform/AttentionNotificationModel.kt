package com.virlin.app.platform

import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.schedule.ScheduleKind

/**
 * Typed notification actions. The ONLY strings are the enum names, used once in the intent
 * extras; everything else works on the enum.
 *  - RESUME / FOCUS_NOW → the accepted focus action (single-Focus displacement applies)
 *  - DEFER_5 → `deferReturn(now + 5m)` (reason unchanged)
 *  - CHECK_AGAIN_5 → `stillRunning(now + 5m)` (external check shortcut, no FocusSession)
 *  - CHECK → navigation only: open Virlin on the "What happened?" flow. Never a mutation.
 */
enum class NotificationAction(val label: String, val mutates: Boolean) {
    RESUME("RESUME", true),
    DEFER_5("+5 MIN", true),
    CHECK("CHECK", false),
    FOCUS_NOW("FOCUS NOW", true),
    CHECK_AGAIN_5("+5 MIN", true)
}

/** What a notification body tap opens. Navigation only — never a mutation. */
enum class NotificationTarget { WORKSTREAM_DETAIL, CHECK_FLOW }

/**
 * Pure, testable description of one attention notification. Built from persisted domain data
 * only (WorkStream + active Task titles); short, lock-screen safe, no ids, no notes/context.
 */
data class AttentionNotificationModel(
    val streamId: String,
    val kind: ScheduleKind,
    /** Headline: WorkStream title. */
    val title: String,
    /** The attention reason, worded like Now's Needs You. */
    val reason: String,
    /** Exact active task title when one exists — never a placeholder. */
    val taskTitle: String?,
    val actions: List<NotificationAction>,
    val bodyTarget: NotificationTarget
) {
    /** Collapsed text: "<reason>" or "<reason> · <task>". */
    val text: String get() = taskTitle?.let { "$reason · $it" } ?: reason

    companion object {
        fun build(stream: WorkStream, activeTask: Task?, kind: ScheduleKind): AttentionNotificationModel {
            val task = activeTask?.takeIf { it.id == stream.activeTaskId }?.title?.takeIf { it.isNotBlank() }
            return when (kind) {
                ScheduleKind.HUMAN_RETURN -> AttentionNotificationModel(
                    stream.id, kind, stream.title, "Ready to continue", task,
                    listOf(NotificationAction.RESUME, NotificationAction.DEFER_5), NotificationTarget.WORKSTREAM_DETAIL
                )
                ScheduleKind.EXTERNAL_RESULT_READY -> AttentionNotificationModel(
                    stream.id, kind, stream.title, "Result ready", task,
                    listOf(NotificationAction.FOCUS_NOW, NotificationAction.DEFER_5), NotificationTarget.WORKSTREAM_DETAIL
                )
                ScheduleKind.EXTERNAL_CHECK -> AttentionNotificationModel(
                    stream.id, kind, "Check ${stream.title}", "Check due", task,
                    listOf(NotificationAction.CHECK, NotificationAction.CHECK_AGAIN_5), NotificationTarget.CHECK_FLOW
                )
            }
        }
    }
}

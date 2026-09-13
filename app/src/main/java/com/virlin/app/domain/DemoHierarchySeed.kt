package com.virlin.app.domain

import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import java.time.Duration
import java.time.Instant

/**
 * Deterministic in-memory demo hierarchy for visual validation of Pass 2 — just enough to
 * prove the UI. Attaches to existing seeded streams: "s4" (Claude · Virlin, project p1) becomes
 * the Agent Development tree; "s1" (Psychology, projectless) gets the Unit 23 tree.
 * Replaced by real persistence later.
 */
object DemoHierarchySeed {

    fun tasks(streams: List<WorkStream>, now: Instant): List<Task> {
        val out = ArrayList<Task>()
        fun t(id: String, title: String, ws: String?, project: String?, parent: String?, status: TaskStatus, order: Int, est: Long? = null) {
            out += Task(id, title, projectId = project, workStreamId = ws, parentTaskId = parent, status = status, order = order,
                estimatedEffort = est?.let(Duration::ofMinutes), createdAt = now, updatedAt = now,
                completedAt = if (status == TaskStatus.DONE) now else null)
        }
        val agent = streams.firstOrNull { it.id == "s4" }
        if (agent != null) {
            val ws = agent.id; val pr = agent.projectId
            t("t_control", "Control Mode", ws, pr, null, TaskStatus.DONE, 0)
            t("t_create", "Create Mode", ws, pr, null, TaskStatus.TODO, 1)
            t("t_task", "Task Creation", ws, pr, "t_create", TaskStatus.DONE, 0, 10)
            t("t_wsc", "WorkStream Creation", ws, pr, "t_create", TaskStatus.DONE, 1, 10)
            t("t_rem", "Reminder Creation", ws, pr, "t_create", TaskStatus.TODO, 2)
            t("t_presets", "Presets", ws, pr, "t_rem", TaskStatus.DONE, 0, 5)
            t("t_custom", "Custom Time", ws, pr, "t_rem", TaskStatus.DONE, 1, 20)
            t("t_nl", "Natural Language", ws, pr, "t_rem", TaskStatus.IN_PROGRESS, 2, 45)
            t("t_capture", "Capture Mode", ws, pr, null, TaskStatus.TODO, 2)
            t("t_prompt", "Prompt", ws, pr, "t_capture", TaskStatus.DONE, 0)
            t("t_voice", "Voice", ws, pr, "t_capture", TaskStatus.TODO, 1)
            t("t_file", "File", ws, pr, "t_capture", TaskStatus.TODO, 2)
            t("t_image", "Image", ws, pr, "t_capture", TaskStatus.TODO, 3)
            t("t_pixel", "Pixel 8 Validation", ws, pr, null, TaskStatus.TODO, 3)
            if (pr != null) {
                t("t_apk", "Send APK to tester", null, pr, null, TaskStatus.DONE, 0)
                t("t_notes", "Prepare release notes", null, pr, null, TaskStatus.TODO, 1)
            }
        }
        if (streams.any { it.id == "s8" }) {
            // Projectless demo stream (see [projectless]) with a short flat list.
            t("n_skim", "Skim Unit 22 summary", "s8", null, null, TaskStatus.DONE, 0, 10)
            t("n_gaps", "List knowledge gaps", "s8", null, null, TaskStatus.TODO, 1, 15)
            t("n_cards", "Write revision cards", "s8", null, null, TaskStatus.TODO, 2)
        }
        if (streams.any { it.id == "s1" }) {
            t("p_read", "Read Chapter", "s1", null, null, TaskStatus.DONE, 0)
            t("p_notes", "Make Notes", "s1", null, null, TaskStatus.DONE, 1)
            t("p_answer", "Answer Questions", "s1", null, null, TaskStatus.TODO, 2)
            t("p_q1", "Questions 1–10", "s1", null, "p_answer", TaskStatus.DONE, 0)
            t("p_q2", "Questions 11–20", "s1", null, "p_answer", TaskStatus.TODO, 1)
            t("p_q11", "Question 11", "s1", null, "p_q2", TaskStatus.DONE, 0)
            t("p_q12", "Question 12", "s1", null, "p_q2", TaskStatus.DONE, 1)
            t("p_q17", "Question 17", "s1", null, "p_q2", TaskStatus.IN_PROGRESS, 2)
        }
        return out
    }

    /** Streams seeded WITHOUT a project, to prove the projectless path on device. */
    val projectless = setOf("s8")

    /** Active tasks for the demo streams, applied at seed time. */
    val activeTasks = mapOf("s4" to "t_nl", "s1" to "p_q17")
}

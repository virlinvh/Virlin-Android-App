package com.virlin.app.domain

import com.virlin.app.data.db.*
import com.virlin.app.data.project.ProjectDeletionCleanup
import com.virlin.app.domain.action.*
import com.virlin.app.domain.schedule.AttentionScheduler
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectDeletionRoomTest {
    @Test fun everyOwnedTableAndManagedFileIsRemovedWhileOtherDataSurvives() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, VirlinDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RoomWorkStreamRepository.create(db)
            val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
            val other = (actions.createProject(CreateProject("Other")) as ActionResult.Success).value
            actions.createTask(CreateTask("Keep", projectId = other.id))
            val sql = db.openHelper.writableDatabase
            val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata'").use { c ->
                buildList { while(c.moveToNext()) add(c.getString(0)) }
            }
            fun count(table: String) = sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            val before = tables.associateWith { count(it) }
            val project = (actions.createProject(CreateProject("Delete")) as ActionResult.Success).value
            val stream = (actions.createWorkStream(CreateWorkStream("Stream", project.id)) as ActionResult.Success).value
            val task = (actions.createTask(CreateTask("Task", workStreamId = stream.id)) as ActionResult.Success).value
            val child = (actions.addSubtask(task.id, "Child") as ActionResult.Success).value
            actions.createTask(CreateTask("Standalone", projectId = project.id))
            val capture = (actions.createCapture(CreateCapture(com.virlin.app.domain.model.CaptureType.NOTE, "Owned", context = CaptureContext(projectId = project.id))) as ActionResult.Success).value
            // Seed all dependent tables in this isolated DB, including archived/history surfaces.
            fun row(table: String, fields: Map<String, Any>) {
                val values = android.content.ContentValues()
                sql.query("PRAGMA table_info($table)").use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1); val type = c.getString(2)
                        val value = fields[name] ?: if (type.contains("INT")) 0L else "$table-$name"
                        if (value is Number) values.put(name, value.toLong()) else values.put(name, value.toString())
                    }
                }
                sql.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, values)
            }
            listOf("cycles", "focus_sessions", "context_snapshots", "events", "external_stages").forEach { row(it, mapOf("workStreamId" to stream.id, "taskId" to child.id)) }
            listOf("note_documents", "prompt_documents", "attachment_documents", "voice_documents").forEach { row(it, mapOf("captureItemId" to capture.id, "id" to "$it-owned")) }
            row("priority_preferences", mapOf("streamId" to child.id))
            row("task_steps", mapOf("taskId" to child.id))
            row("task_page_blocks", mapOf("taskId" to child.id, "contentId" to capture.id))
            row("project_tags", mapOf("id" to "owned-tag", "projectId" to project.id))
            row("tag_links", mapOf("tagId" to "owned-tag", "targetId" to child.id))
            row("virlin_notes", mapOf("ownerKey" to "task-${child.id}"))
            row("virlin_notes", mapOf("ownerKey" to "project-${project.id}"))
            row("map_node_previews", mapOf("ownerKey" to child.id))
            val keptFile = File(context.filesDir, "attachments/other/original").apply { parentFile!!.mkdirs(); writeText("keep") }
            val ownedFiles = listOf("attachments/attachment_documents-owned/original", "voices/${capture.id}/clip.m4a",
                "project-icons/${project.id}/original", "map_previews/${child.id}/image.png").map { path ->
                File(context.filesDir, path).apply { parentFile!!.mkdirs(); writeText("delete") }
            }
            context.getSharedPreferences("project_appearance", 0).edit().putString(project.id, "owned").putString(other.id, "keep").commit()
            context.getSharedPreferences("hierarchy_timer_display", 0).edit().putInt("project:${project.id}", 7).putInt(child.id, 5).putInt("project:${other.id}", 6).commit()
            assertTrue(actions.deleteProject(project.id) is ActionResult.Success)
            assertEquals(1, repo.pendingProjectDeletions().size)
            // Rehydrate: deleted data stays deleted; the durable receipt survives repository recreation.
            val reopened = RoomWorkStreamRepository.create(db)
            assertEquals(listOf(other.id), reopened.projects.value.map { it.id })
            assertEquals(1, reopened.pendingProjectDeletions().size)
            ProjectDeletionCleanup.flush(context, reopened, AttentionScheduler.NoOp)
            tables.forEach { assertEquals("Leftovers in $it", before[it], count(it)) }
            assertTrue(ownedFiles.none { it.exists() }); assertTrue(keptFile.exists())
            assertFalse(context.getSharedPreferences("project_appearance", 0).contains(project.id))
            assertEquals("keep", context.getSharedPreferences("project_appearance", 0).getString(other.id, null))
            val timers = context.getSharedPreferences("hierarchy_timer_display", 0)
            assertFalse(timers.contains("project:${project.id}")); assertFalse(timers.contains(child.id))
            assertEquals(6, timers.getInt("project:${other.id}", 0))
            assertTrue(reopened.pendingProjectDeletions().isEmpty())
        } finally { db.close() }
    }

    @Test fun managedCleanupRefusesTraversal() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(runCatching { ProjectDeletionCleanup.deleteManagedTree(context, "attachments", "../outside") }.isFailure)
        assertTrue(runCatching { ProjectDeletionCleanup.deleteManagedTree(context, "attachments", "") }.isFailure)
        assertTrue(runCatching { ProjectDeletionCleanup.deleteManagedTree(context, "other", "id") }.isFailure)
    }

    @Test fun databaseRollbackRestoresTreeAndDoesNotScheduleFileDeletion() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), VirlinDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RoomWorkStreamRepository.create(db)
            val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
            val project = (actions.createProject(CreateProject("Keep")) as ActionResult.Success).value
            val stream = (actions.createWorkStream(CreateWorkStream("Keep stream", project.id)) as ActionResult.Success).value
            assertTrue(runCatching { repo.transaction { deleteProjectTree(project.id); error("Simulated failure") } }.isFailure)
            assertNotNull(repo.getProject(project.id))
            assertNotNull(repo.getStream(stream.id))
            assertTrue(repo.pendingProjectDeletions().isEmpty())
        } finally { db.close() }
    }

    @Test fun failedCleanupKeepsDurableReceiptForRetry() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, VirlinDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RoomWorkStreamRepository.create(db)
            val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
            val project = (actions.createProject(CreateProject("Retry")) as ActionResult.Success).value
            actions.createWorkStream(CreateWorkStream("Stream", project.id))
            actions.deleteProject(project.id)
            val failing = object : AttentionScheduler by AttentionScheduler.NoOp {
                override fun cancel(streamId: String) { error("Simulated platform failure") }
            }
            assertTrue(runCatching { ProjectDeletionCleanup.flush(context, repo, failing) }.isFailure)
            assertEquals(1, repo.pendingProjectDeletions().size)
            ProjectDeletionCleanup.flush(context, repo, AttentionScheduler.NoOp)
            assertTrue(repo.pendingProjectDeletions().isEmpty())
        } finally { db.close() }
    }
}

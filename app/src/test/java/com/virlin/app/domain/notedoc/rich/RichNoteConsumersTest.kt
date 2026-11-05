package com.virlin.app.domain.notedoc.rich

import com.virlin.app.data.project.ProjectArchive
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskPageBlock
import com.virlin.app.domain.model.TaskPageTypeKeys
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.VirlinNoteDoc
import com.virlin.app.domain.project.ProjectCopyMode
import com.virlin.app.domain.project.ProjectDuplicator
import com.virlin.app.domain.project.projectSnapshot
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.hierarchy.taskPageBody
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Consumers of stored notes keep the rich tree: duplication, archive export, Master Document. */
@RunWith(RobolectricTestRunner::class)
class RichNoteConsumersTest {
    private val at = Instant.parse("2026-11-05T00:00:00Z")
    private val tree = """{"type":"doc","content":[
        {"type":"heading","attrs":{"id":"h","level":2},"content":[{"type":"text","text":"Plan"}]},
        {"type":"taskList","attrs":{"id":"tl"},"content":[
          {"type":"taskItem","attrs":{"id":"t1","checked":true},"content":[{"type":"paragraph","attrs":{"id":"t1p"},"content":[{"type":"text","text":"Done thing"}]}]}]},
        {"type":"paragraph","attrs":{"id":"m"},"content":[{"type":"text","text":"Area "},{"type":"mathInline","attrs":{"latex":"\\pi r^2"}}]},
        {"type":"table","attrs":{"id":"tb"},"content":[{"type":"tableRow","content":[
          {"type":"tableHeader","content":[{"type":"paragraph","attrs":{"id":"c1"},"content":[{"type":"text","text":"A","marks":[{"type":"bold"}]}]}]}]}]}
      ]}""".let(RichNoteAdapter::canonical)

    private suspend fun fixture(): InMemoryWorkStreamRepository {
        val repo = InMemoryWorkStreamRepository()
        repo.transaction {
            saveProject(Project("p", "Original", createdAt = at, updatedAt = at))
            saveTask(Task("t", "Task", projectId = "p", createdAt = at, updatedAt = at))
            saveTask(Task("d", "Damaged", projectId = "p", createdAt = at, updatedAt = at))
            saveNoteDoc(VirlinNoteDoc("task-t", "Rich", RichNoteAdapter.project(tree), at, at, 3, richDocument = tree))
            saveNoteDoc(VirlinNoteDoc("task-d", "Broken", emptyList(), at, at, 2, loadError = "bad", damagedRaw = "{\"v\":2,"))
            savePageBlock(TaskPageBlock("pn", "t", TaskPageTypeKeys.NOTE, "task-t", 0, at, at))
            savePageBlock(TaskPageBlock("pd", "d", TaskPageTypeKeys.NOTE, "task-d", 0, at, at))
        }
        return repo
    }

    @Test fun duplicateResetsTreeChecklistAndIdsButKeepsContent() = runTest {
        val repo = fixture()
        val result = ProjectDuplicator(repo).duplicate(repo.projectSnapshot("p"), "Copy", ProjectCopyMode.FULL,
            at.plusSeconds(1), { _, _, _ -> error("No media") })
        val copy = repo.projectSnapshot(result.id).richNotes.first { it.title == "Rich" }
        val copied = copy.richDocument!!
        assertFalse(copied.contains("\"checked\":true"))
        assertTrue(copied.contains("\"checked\":false"))
        assertFalse("ids must be fresh", listOf("\"h\"", "\"tl\"", "\"t1\"", "\"m\"", "\"tb\"").any { copied.contains(":$it") })
        assertTrue(copied.contains("\\\\pi r^2") && copied.contains("Done thing") && copied.contains("\"bold\""))
        assertEquals(RichNoteAdapter.project(copied), copy.blocks)
        assertTrue(copy.blocks.none { it.checked })
        val damaged = repo.projectSnapshot(result.id).richNotes.first { it.title == "Broken" }
        assertEquals("{\"v\":2,", damaged.damagedRaw)
        assertEquals(tree, repo.getNoteDoc("task-t")!!.richDocument)
    }

    @Test fun archiveExportsTheFullV2DocumentAndDamagedRawVerbatim() = runTest {
        val repo = fixture()
        val manifest = ProjectArchive.manifest(repo.projectSnapshot("p"))
        val notes = manifest.getJSONArray("richNotes")
        val rich = (0 until notes.length()).map(notes::getJSONObject).first { it.getString("ownerKey") == "task-t" }
        val payload = rich.getJSONObject("payload")
        assertEquals(2, payload.getInt("v"))
        assertEquals(tree, RichNoteAdapter.canonical(payload.getJSONObject("doc").toString()))
        assertTrue(payload.getJSONArray("blocks").length() > 0)
        val broken = (0 until notes.length()).map(notes::getJSONObject).first { it.getString("ownerKey") == "task-d" }
        assertTrue(broken.isNull("payload"))
        assertEquals("{\"v\":2,", broken.getString("damagedRaw"))
    }

    @Test fun masterDocumentReadsTheProjectionAndFlagsDamagedNotes() = runTest {
        val repo = fixture()
        val body = taskPageBody(TaskPageTypeKeys.NOTE, "t", emptyList(), repo.getNoteDoc("task-t"))
        assertTrue(body.contains("## Plan"))
        assertTrue(body.contains("☑ Done thing"))
        assertTrue(body.contains("\\(\\pi r^2\\)"))
        val broken = taskPageBody(TaskPageTypeKeys.NOTE, "d", emptyList(), repo.getNoteDoc("task-d"))
        assertTrue(broken.contains("could not be read"))
        assertTrue(RichNoteAdapter.project(tree).any { it.type == NoteDocBlockType.TABLE })
        JSONObject(RichNoteFormat.encode(RichNoteAdapter.project(tree), tree))
    }
}

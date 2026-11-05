package com.virlin.app.platform.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.data.voice.VoiceFileStore
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.model.VoiceClip
import kotlinx.coroutines.runBlocking
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class DesktopWorkspaceProtocolTest {
    private val protocol = DesktopWorkspaceProtocol()
    @Test fun uploadedNoteImageIsBoundToProjectAndReadableOnlyAfterSave() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = InMemoryWorkStreamRepository()
        val clock = object : VirlinClock { override fun now() = Instant.parse("2026-10-16T00:00:00Z") }
        val session = DesktopWorkspaceProtocol(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider()), context)
        fun send(json: JSONObject): JSONObject {
            val output = ByteArrayOutputStream()
            assertTrue(session.next(ByteArrayInputStream((json.toString() + "\n").toByteArray()), output))
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        val projectId = send(JSONObject().put("v", 1).put("type", "create_project")
            .put("key", "00000000-0000-0000-0000-0000000000a1").put("title", "Pictures")).getString("id")
        val uploadId = java.util.UUID.randomUUID().toString()
        val editedId = java.util.UUID.randomUUID().toString()
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val request = JSONObject().put("v", 1).put("type", "upload_note_image")
            .put("projectId", projectId).put("uploadId", uploadId).put("totalBytes", bytes.size)
            .put("offset", 0).put("sha256", hash)
            .put("data", java.util.Base64.getEncoder().encodeToString(bytes))
        try {
            assertEquals("note_image_uploaded", send(request).getString("type"))
            val image = JSONObject().put("type", "IMAGE").put("text", "")
                .put("imagePath", uploadId).put("runs", org.json.JSONArray())
            val note = send(JSONObject().put("v", 1).put("type", "create_knowledge")
                .put("key", "00000000-0000-0000-0000-0000000000a2").put("projectId", projectId)
                .put("kind", "note").put("noteBlocks", org.json.JSONArray().put(image)))
            assertEquals("knowledge_created", note.getString("type"))
            val detail = send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
                .put("projectId", projectId).put("captureId", note.getString("id")))
            assertEquals(uploadId, detail.getJSONArray("noteBlocks").getJSONObject(0).getString("imagePath"))
            val read = send(JSONObject().put("v", 1).put("type", "read_note_image_chunk")
                .put("projectId", projectId).put("captureId", note.getString("id"))
                .put("imagePath", uploadId).put("offset", 0))
            assertEquals("note_image_chunk", read.getString("type"))
            assertTrue(bytes.contentEquals(java.util.Base64.getDecoder().decode(read.getString("data"))))
            assertEquals("note_image_uploaded", send(JSONObject(request.toString())
                .put("uploadId", editedId)).getString("type"))
            val editedBlock = JSONObject(detail.getJSONArray("noteBlocks").getJSONObject(0).toString())
                .put("imagePath", editedId)
            val saved = send(JSONObject().put("v", 1).put("type", "save_knowledge_note")
                .put("key", "00000000-0000-0000-0000-0000000000a3")
                .put("projectId", projectId).put("captureId", note.getString("id"))
                .put("expectedRevision", detail.getInt("noteRevision"))
                .put("blocks", org.json.JSONArray().put(editedBlock)))
            assertEquals("knowledge_note_saved", saved.getString("type"))
            val updated = send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
                .put("projectId", projectId).put("captureId", note.getString("id")))
            assertEquals(editedId, updated.getJSONArray("noteBlocks").getJSONObject(0).getString("imagePath"))
        } finally {
            session.close()
            com.virlin.app.data.noteimage.NoteImageStore.resolve(context, uploadId)?.delete()
            com.virlin.app.data.noteimage.NoteImageStore.resolve(context, editedId)?.delete()
        }
    }
    @Test fun createsRichNoteAtomicallyAndPreservesFormatting() {
        val repo = InMemoryWorkStreamRepository()
        val clock = object : VirlinClock { override fun now() = Instant.parse("2026-10-16T00:00:00Z") }
        val session = DesktopWorkspaceProtocol(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider()))
        fun send(json: JSONObject): JSONObject {
            val output = ByteArrayOutputStream()
            assertTrue(session.next(ByteArrayInputStream((json.toString() + "\n").toByteArray()), output))
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        val projectId = send(JSONObject().put("v", 1).put("type", "create_project")
            .put("key", "00000000-0000-0000-0000-000000000091").put("title", "Rich notes")).getString("id")
        val runs = org.json.JSONArray().put(JSONObject().put("start", 0).put("end", 4)
            .put("marks", org.json.JSONArray(listOf("BOLD"))).put("fontFamily", "Serif")
            .put("fontSizeSp", 24).put("linkUrl", "https://example.com"))
        val blocks = org.json.JSONArray().put(JSONObject().put("type", "HEADING_1")
            .put("text", "Test").put("align", "CENTER").put("runs", runs))
        val request = JSONObject().put("v", 1).put("type", "create_knowledge")
            .put("key", "00000000-0000-0000-0000-000000000092").put("projectId", projectId)
            .put("kind", "note").put("title", "Formatted").put("noteBlocks", blocks)
        val created = send(request)
        assertEquals("knowledge_created", created.getString("type"))
        assertEquals(created.getString("id"), send(request).getString("id"))
        val detail = send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
            .put("projectId", projectId).put("captureId", created.getString("id")))
        val block = detail.getJSONArray("noteBlocks").getJSONObject(0)
        assertEquals("CENTER", block.getString("align"))
        val run = block.getJSONArray("runs").getJSONObject(0)
        assertEquals("BOLD", run.getJSONArray("marks").getString(0))
        assertEquals(24, run.getInt("fontSizeSp"))
        assertEquals("Serif", run.getString("fontFamily"))
        assertEquals("https://example.com", run.getString("linkUrl"))
    }
    @Test fun voicePlaybackReadsOnlyOwnedBoundedClipChunks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = InMemoryWorkStreamRepository()
        val clock = object : VirlinClock { override fun now() = Instant.parse("2026-10-16T00:00:00Z") }
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        val session = DesktopWorkspaceProtocol(repo, actions, context)
        fun send(json: JSONObject): JSONObject {
            val output = ByteArrayOutputStream()
            assertTrue(session.next(ByteArrayInputStream((json.toString() + "\n").toByteArray()), output))
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        val projectId = send(JSONObject().put("v", 1).put("type", "create_project")
            .put("key", "00000000-0000-0000-0000-000000000081").put("title", "Audio")).getString("id")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val file = VoiceFileStore.clipFile(context, "voicecap", "clipone")
        try {
            file.writeBytes(bytes)
            val clip = VoiceClip("clipone", "Recording", VoiceFileStore.relativePath("voicecap", "clipone"),
                1000, bytes.size.toLong(), sortOrder = 0, createdAt = clock.now())
            assertTrue(runBlocking { actions.createVoice("Voice", listOf(clip), CaptureContext(projectId = projectId),
                captureId = "voicecap") } is ActionResult.Success)
            val detail = send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
                .put("projectId", projectId).put("captureId", "voicecap"))
            assertEquals("clipone", detail.getJSONArray("voiceClips").getJSONObject(0).getString("id"))
            val chunk = send(JSONObject().put("v", 1).put("type", "read_voice_chunk")
                .put("projectId", projectId).put("captureId", "voicecap")
                .put("clipId", "clipone").put("offset", 0))
            assertEquals("voice_chunk", chunk.getString("type"))
            assertEquals("AQIDBAU=", chunk.getString("data"))
            assertTrue(chunk.getBoolean("done"))
            assertEquals("NOT_FOUND", send(JSONObject().put("v", 1).put("type", "read_voice_chunk")
                .put("projectId", "other").put("captureId", "voicecap")
                .put("clipId", "clipone").put("offset", 0)).getString("code"))
        } finally { file.delete() }
    }
    @Test fun createsProjectKnowledgeAndReplaysReceiptWithoutDuplicates() {
        val repo = InMemoryWorkStreamRepository()
        val clock = object : VirlinClock { override fun now() = Instant.parse("2026-10-16T00:00:00Z") }
        val session = DesktopWorkspaceProtocol(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider()))
        fun send(json: JSONObject): JSONObject {
            val output = ByteArrayOutputStream()
            assertTrue(session.next(ByteArrayInputStream((json.toString() + "\n").toByteArray()), output))
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        val projectId = send(JSONObject().put("v", 1).put("type", "create_project")
            .put("key", "00000000-0000-0000-0000-000000000061").put("title", "Project")).getString("id")
        val capabilities = send(JSONObject().put("v", 1).put("type", "get_capabilities"))
        assertEquals("capabilities", capabilities.getString("type"))
        assertTrue(capabilities.getBoolean("knowledgeDetail"))
        assertEquals(false, capabilities.getBoolean("knowledgeAudioTransfer"))
        val request = JSONObject().put("v", 1).put("type", "create_knowledge")
            .put("key", "00000000-0000-0000-0000-000000000062").put("projectId", projectId)
            .put("kind", "checklist").put("title", "Checklist").put("content", "First\nSecond")
        val created = send(request)
        assertEquals("knowledge_created", created.getString("type"))
        assertEquals(created.getString("id"), send(request).getString("id"))
        val items = send(JSONObject().put("v", 1).put("type", "get_knowledge")
            .put("projectId", projectId)).getJSONArray("items")
        assertEquals(1, items.length())
        assertEquals("checklist", items.getJSONObject(0).getString("kind"))
        assertEquals(2, items.getJSONObject(0).getInt("totalCount"))
        for ((index, kind) in listOf("note", "steps", "prompt", "link").withIndex()) {
            val item = JSONObject().put("v", 1).put("type", "create_knowledge")
                .put("key", "00000000-0000-0000-0000-00000000006${index + 4}")
                .put("projectId", projectId).put("kind", kind)
                .put("title", kind).put("content", "First\nSecond")
            if (kind == "link") item.put("url", "https://example.com/resource")
            if (kind == "prompt") item.put("description", "Context for the prompt")
                .put("tags", org.json.JSONArray(listOf("desktop", "audio")))
                .put("mode", "CODE").put("language", "kotlin")
                .put("responseText", "Expected response")
            assertEquals("knowledge_created", send(item).getString("type"))
        }
        val allItems = send(JSONObject().put("v", 1).put("type", "get_knowledge")
            .put("projectId", projectId)).getJSONArray("items")
        assertEquals(5, allItems.length())
        val noteId = (0 until allItems.length()).map { allItems.getJSONObject(it) }
            .first { it.getString("kind") == "note" }.getString("id")
        val detail = send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
            .put("projectId", projectId).put("captureId", noteId))
        assertEquals("knowledge_item", detail.getString("type"))
        assertEquals("First\nSecond", detail.getString("content"))
        assertTrue(detail.getJSONArray("noteBlocks").length() > 0)
        assertEquals("PARAGRAPH", detail.getJSONArray("noteBlocks").getJSONObject(0).getString("type"))
        val editBlocks = detail.getJSONArray("noteBlocks")
        editBlocks.getJSONObject(0).put("text", "Updated from Windows")
            .put("runs", org.json.JSONArray().put(JSONObject().put("start", 0).put("end", 7)
                .put("marks", org.json.JSONArray().put("BOLD"))))
        val edit = JSONObject().put("v", 1).put("type", "save_knowledge_note")
            .put("key", "00000000-0000-0000-0000-000000000071")
            .put("projectId", projectId).put("captureId", noteId)
            .put("expectedRevision", detail.getInt("noteRevision"))
            .put("title", "Updated note").put("blocks", editBlocks)
        assertEquals("knowledge_note_saved", send(edit).getString("type"))
        assertEquals("knowledge_note_saved", send(edit).getString("type"))
        val updated = send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
            .put("projectId", projectId).put("captureId", noteId))
        assertEquals("Updated from Windows", updated.getString("content"))
        assertEquals("BOLD", updated.getJSONArray("noteBlocks").getJSONObject(0)
            .getJSONArray("runs").getJSONObject(0).getJSONArray("marks").getString(0))
        assertEquals("STALE_OR_REJECTED", send(JSONObject(edit.toString())
            .put("key", "00000000-0000-0000-0000-000000000072")).getString("code"))
        val added = JSONObject().put("id", "desktop-new-block").put("type", "HEADING_2")
            .put("text", "New heading").put("runs", org.json.JSONArray())
        val reorderedBlocks = org.json.JSONArray().put(added)
            .put(updated.getJSONArray("noteBlocks").getJSONObject(0))
        val addBlockRequest = JSONObject().put("v", 1).put("type", "save_knowledge_note")
            .put("key", "00000000-0000-0000-0000-000000000090")
            .put("projectId", projectId).put("captureId", noteId)
            .put("expectedRevision", updated.getInt("noteRevision"))
            .put("title", "Updated note").put("blocks", reorderedBlocks)
        assertEquals("knowledge_note_saved", send(addBlockRequest).getString("type"))
        val expanded = send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
            .put("projectId", projectId).put("captureId", noteId))
        assertEquals("desktop-new-block", expanded.getJSONArray("noteBlocks").getJSONObject(0).getString("id"))
        assertEquals(2, expanded.getJSONArray("noteBlocks").length())
        assertEquals("INVALID_BLOCKS", send(JSONObject(addBlockRequest.toString())
            .put("key", "00000000-0000-0000-0000-000000000091")
            .put("expectedRevision", expanded.getInt("noteRevision"))
            .put("blocks", org.json.JSONArray().put(added))).getString("code"))
        val otherProjectId = send(JSONObject().put("v", 1).put("type", "create_project")
            .put("key", "00000000-0000-0000-0000-000000000073").put("title", "Other")).getString("id")
        assertEquals("NOT_FOUND", send(JSONObject(edit.toString())
            .put("key", "00000000-0000-0000-0000-000000000074")
            .put("projectId", otherProjectId)).getString("code"))
        val promptId = (0 until allItems.length()).map { allItems.getJSONObject(it) }
            .first { it.getString("kind") == "prompt" }.getString("id")
        val promptDetail = send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
            .put("projectId", projectId).put("captureId", promptId))
        assertEquals("Context for the prompt", promptDetail.getString("description"))
        assertEquals("CODE", promptDetail.getString("mode"))
        assertEquals("kotlin", promptDetail.getString("language"))
        assertEquals("Expected response", promptDetail.getString("responseText"))
        assertEquals("desktop", promptDetail.getJSONArray("tags").getString(0))
        assertTrue(promptDetail.getBoolean("promptCanEdit"))
        val promptEdit = JSONObject().put("v", 1).put("type", "save_knowledge_prompt")
            .put("key", "00000000-0000-0000-0000-000000000075")
            .put("projectId", projectId).put("captureId", promptId)
            .put("expectedToken", promptDetail.getString("promptEditToken"))
            .put("title", "Updated prompt").put("description", "New context")
            .put("tags", org.json.JSONArray().put("desktop"))
            .put("content", "New prompt text").put("responseText", "New response")
            .put("mode", "CODE").put("language", "kotlin")
        assertEquals("knowledge_prompt_saved", send(promptEdit).getString("type"))
        assertEquals("knowledge_prompt_saved", send(promptEdit).getString("type"))
        val promptAfter = send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
            .put("projectId", projectId).put("captureId", promptId))
        assertEquals("New prompt text", promptAfter.getString("content"))
        assertEquals("New response", promptAfter.getString("responseText"))
        assertEquals("STALE_OR_REJECTED", send(JSONObject(promptEdit.toString())
            .put("key", "00000000-0000-0000-0000-000000000076")).getString("code"))
        val linkId = (0 until allItems.length()).map { allItems.getJSONObject(it) }
            .first { it.getString("kind") == "link" }.getString("id")
        val linkDetail = send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
            .put("projectId", projectId).put("captureId", linkId))
        val linkEdit = JSONObject().put("v", 1).put("type", "save_knowledge_link")
            .put("key", "00000000-0000-0000-0000-000000000088")
            .put("projectId", projectId).put("captureId", linkId)
            .put("expectedToken", linkDetail.getString("linkEditToken"))
            .put("title", "Updated link").put("url", "https://example.com/updated")
            .put("content", "A useful link")
        assertEquals("knowledge_link_saved", send(linkEdit).getString("type"))
        assertEquals("knowledge_link_saved", send(linkEdit).getString("type"))
        val linkAfter = send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
            .put("projectId", projectId).put("captureId", linkId))
        assertEquals("A useful link", linkAfter.getString("content"))
        assertEquals("https://example.com/updated", linkAfter.getString("sourceUrl"))
        assertEquals("STALE_OR_REJECTED", send(JSONObject(linkEdit.toString())
            .put("key", "00000000-0000-0000-0000-000000000089")).getString("code"))
        assertEquals("NOT_FOUND", send(JSONObject().put("v", 1).put("type", "get_knowledge_item")
            .put("projectId", projectId).put("captureId", "missing")).getString("code"))
        assertEquals(setOf("note", "steps", "prompt", "link", "checklist"),
            (0 until allItems.length()).map { allItems.getJSONObject(it).getString("kind") }.toSet())
        assertEquals("INVALID_KIND", send(JSONObject().put("v", 1).put("type", "create_knowledge")
            .put("key", "00000000-0000-0000-0000-000000000063").put("projectId", projectId)
            .put("kind", "file").put("content", "path")).getString("code"))
        val task = send(JSONObject().put("v", 1).put("type", "create_task")
            .put("key", "00000000-0000-0000-0000-000000000077").put("ownerType", "project")
            .put("ownerId", projectId).put("title", "Task notes"))
        val taskNote = send(JSONObject().put("v", 1).put("type", "create_knowledge")
            .put("key", "00000000-0000-0000-0000-000000000078").put("projectId", projectId)
            .put("taskId", task.getString("id")).put("kind", "note").put("content", "Linked note"))
        assertEquals("knowledge_created", taskNote.getString("type"))
        val linked = send(JSONObject().put("v", 1).put("type", "get_knowledge")
            .put("projectId", projectId)).getJSONArray("items")
        assertEquals(task.getString("id"), (0 until linked.length()).map { linked.getJSONObject(it) }
            .first { it.getString("id") == taskNote.getString("id") }.getString("taskId"))
        assertEquals("INVALID_TASK", send(JSONObject().put("v", 1).put("type", "create_knowledge")
            .put("key", "00000000-0000-0000-0000-000000000079").put("projectId", projectId)
            .put("taskId", "missing").put("kind", "note").put("content", "Rejected"))
            .getString("code"))
    }
    @Test fun readsOneLineWithoutConsumingNext() {
        val input = ByteArrayInputStream("first\nsecond\n".toByteArray())
        assertEquals("first", protocol.readLine(input, 32))
        assertEquals("second", protocol.readLine(input, 32))
        assertNull(protocol.readLine(input, 32))
    }

    @Test fun rejectsOversizeAndIncompleteMessages() {
        assertThrows(IOException::class.java) {
            protocol.readLine(ByteArrayInputStream("12345\n".toByteArray()), 4)
        }
        assertThrows(IOException::class.java) {
            protocol.readLine(ByteArrayInputStream("partial".toByteArray()), 32)
        }
    }

    @Test fun createsNestedItemsThroughActionsAndReplaysDuplicateRequest() {
        val repo = InMemoryWorkStreamRepository()
        val clock = object : VirlinClock { override fun now() = Instant.parse("2026-10-16T00:00:00Z") }
        val session = DesktopWorkspaceProtocol(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider()))
        fun send(json: JSONObject): JSONObject {
            val output = ByteArrayOutputStream()
            assertTrue(session.next(ByteArrayInputStream((json.toString() + "\n").toByteArray()), output))
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        fun create(type: String, key: String, ownerType: String? = null, ownerId: String? = null): JSONObject =
            send(JSONObject().put("v", 1).put("type", type).put("key", key).put("title", type)
                .put("ownerType", ownerType).put("ownerId", ownerId))
        val project = create("create_project", "00000000-0000-0000-0000-000000000001")
        assertEquals("created", project.getString("type"))
        assertEquals(project.getString("id"), create("create_project", "00000000-0000-0000-0000-000000000001").getString("id"))
        val emptyProject = send(JSONObject().put("v", 1).put("type", "get_tree"))
            .getJSONArray("projects").getJSONObject(0)
        assertTrue(emptyProject.isNull("progressPercent"))
        assertTrue(emptyProject.getString("iconId").isNotBlank())
        val stream = create("create_workstream", "00000000-0000-0000-0000-000000000002", "project", project.getString("id"))
        val task = create("create_task", "00000000-0000-0000-0000-000000000003", "workstream", stream.getString("id"))
        val child = create("create_task", "00000000-0000-0000-0000-000000000004", "task", task.getString("id"))
        assertEquals("created", child.getString("type"))
        val tree = send(JSONObject().put("v", 1).put("type", "get_tree"))
        assertEquals(1, tree.getJSONArray("projects").length())
        assertEquals(1, tree.getJSONArray("workstreams").length())
        assertEquals(2, tree.getJSONArray("tasks").length())
        assertEquals(0, tree.getJSONArray("projects").getJSONObject(0).getInt("progressPercent"))
        assertEquals("READY", tree.getJSONArray("workstreams").getJSONObject(0).getString("effectiveState"))
    }

    @Test fun reordersWorkstreamSiblingsOnlyWithCurrentSnapshot() {
        val repo = InMemoryWorkStreamRepository()
        val clock = object : VirlinClock { override fun now() = Instant.parse("2026-10-16T00:00:00Z") }
        val session = DesktopWorkspaceProtocol(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider()))
        fun send(json: JSONObject): JSONObject {
            val output = ByteArrayOutputStream()
            assertTrue(session.next(ByteArrayInputStream((json.toString() + "\n").toByteArray()), output))
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        val project = send(JSONObject().put("v", 1).put("type", "create_project")
            .put("key", "00000000-0000-0000-0000-000000000081").put("title", "Project"))
        val stream = send(JSONObject().put("v", 1).put("type", "create_workstream")
            .put("key", "00000000-0000-0000-0000-000000000082").put("ownerType", "project")
            .put("ownerId", project.getString("id")).put("title", "Stream"))
        val ids = (0..2).map { index ->
            send(JSONObject().put("v", 1).put("type", "create_task")
                .put("key", "00000000-0000-0000-0000-00000000008${index + 3}")
                .put("ownerType", "workstream").put("ownerId", stream.getString("id"))
                .put("title", "Task $index")).getString("id")
        }
        val request = JSONObject().put("v", 1).put("type", "reorder_task")
            .put("key", "00000000-0000-0000-0000-000000000086")
            .put("taskId", ids[2]).put("afterId", ids[0])
            .put("expectedSiblingIds", org.json.JSONArray(ids))
        assertEquals("task_reordered", send(request).getString("type"))
        assertEquals("task_reordered", send(request).getString("type"))
        val ordered = repo.tasks.value.sortedBy { it.order }.map { it.id }
        assertEquals(listOf(ids[0], ids[2], ids[1]), ordered)
        assertEquals("STALE_TASK_ORDER", send(JSONObject(request.toString())
            .put("key", "00000000-0000-0000-0000-000000000087")).getString("code"))
    }

    @Test fun taskActionsUseCanonicalPhoneRecordsAndReplayReceipts() {
        val repo = InMemoryWorkStreamRepository()
        val clock = object : VirlinClock { override fun now() = Instant.parse("2026-10-16T00:00:00Z") }
        val session = DesktopWorkspaceProtocol(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider()))
        fun send(json: JSONObject): JSONObject {
            val output = ByteArrayOutputStream()
            assertTrue(session.next(ByteArrayInputStream((json.toString() + "\n").toByteArray()), output))
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        val project = send(JSONObject().put("v", 1).put("type", "create_project")
            .put("key", "00000000-0000-0000-0000-000000000041").put("title", "P"))
        val task = send(JSONObject().put("v", 1).put("type", "create_task")
            .put("key", "00000000-0000-0000-0000-000000000042").put("ownerType", "project")
            .put("ownerId", project.getString("id")).put("title", "Original"))
        val id = task.getString("id")
        val rename = JSONObject().put("v", 1).put("type", "task_action").put("action", "rename")
            .put("taskId", id).put("title", "Renamed").put("key", "00000000-0000-0000-0000-000000000043")
        assertEquals("task_updated", send(rename).getString("type"))
        assertEquals("Renamed", repo.tasks.value.first { it.id == id }.title)
        assertEquals("task_updated", send(rename).getString("type"))
        assertEquals(1, repo.tasks.value.size)
        val duplicate = send(JSONObject().put("v", 1).put("type", "task_action").put("action", "duplicate")
            .put("taskId", id).put("key", "00000000-0000-0000-0000-000000000044"))
        assertEquals("task_updated", duplicate.getString("type"))
        assertEquals(2, repo.tasks.value.size)
        assertEquals("task_updated", send(JSONObject().put("v", 1).put("type", "task_action")
            .put("action", "complete").put("taskId", id)
            .put("key", "00000000-0000-0000-0000-000000000045")).getString("type"))
        assertEquals("DONE", repo.tasks.value.first { it.id == id }.status.name)
        val tree = send(JSONObject().put("v", 1).put("type", "get_tree"))
        assertEquals("DONE", tree.getJSONArray("tasks").getJSONObject(0).getString("status"))
        assertTrue(tree.getJSONArray("tasks").getJSONObject(0).has("executionPreference"))
    }

    @Test fun focusRequiresMatchingPreviewBeforePhoneChangesState() {
        val repo = InMemoryWorkStreamRepository()
        val clock = object : VirlinClock { override fun now() = Instant.parse("2026-10-16T00:00:00Z") }
        val session = DesktopWorkspaceProtocol(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider()))
        fun send(json: JSONObject): JSONObject {
            val output = ByteArrayOutputStream()
            assertTrue(session.next(ByteArrayInputStream((json.toString() + "\n").toByteArray()), output))
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        val project = send(JSONObject().put("v", 1).put("type", "create_project")
            .put("key", "00000000-0000-0000-0000-000000000051").put("title", "P"))
        val stream = send(JSONObject().put("v", 1).put("type", "create_workstream")
            .put("key", "00000000-0000-0000-0000-000000000052").put("ownerType", "project")
            .put("ownerId", project.getString("id")).put("title", "W"))
        val task = send(JSONObject().put("v", 1).put("type", "create_task")
            .put("key", "00000000-0000-0000-0000-000000000053").put("ownerType", "workstream")
            .put("ownerId", stream.getString("id")).put("title", "T"))
        val preview = send(JSONObject().put("v", 1).put("type", "focus_preview").put("taskId", task.getString("id")))
        assertEquals("focus_target", preview.getString("type"))
        val stale = send(JSONObject().put("v", 1).put("type", "focus_start")
            .put("key", "00000000-0000-0000-0000-000000000054").put("taskId", task.getString("id"))
            .put("expectedWorkItemId", "wrong"))
        assertEquals("STALE_FOCUS_PREVIEW", stale.getString("code"))
        val started = send(JSONObject().put("v", 1).put("type", "focus_start")
            .put("key", "00000000-0000-0000-0000-000000000055").put("taskId", task.getString("id"))
            .put("expectedWorkItemId", preview.getString("workItemId"))
            .put("expectedDisplacedStreamId", preview.opt("displacedStreamId")))
        assertEquals("focused", started.getString("type"))
        assertEquals("focused", send(JSONObject().put("v", 1).put("type", "focus_start")
            .put("key", "00000000-0000-0000-0000-000000000055").put("taskId", task.getString("id"))
            .put("expectedWorkItemId", preview.getString("workItemId"))).getString("type"))
    }

    @Test fun knowledgeReadIsProjectScopedAndRejectsUnknownProjects() {
        val repo = InMemoryWorkStreamRepository()
        val clock = object : VirlinClock { override fun now() = Instant.parse("2026-10-16T00:00:00Z") }
        val session = DesktopWorkspaceProtocol(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider()))
        fun send(json: JSONObject): JSONObject {
            val output = ByteArrayOutputStream()
            assertTrue(session.next(ByteArrayInputStream((json.toString() + "\n").toByteArray()), output))
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        assertEquals("INVALID_PROJECT", send(JSONObject().put("v", 1).put("type", "get_knowledge")
            .put("projectId", "../outside")).getString("code"))
        assertEquals("NOT_FOUND", send(JSONObject().put("v", 1).put("type", "get_knowledge")
            .put("projectId", "missing")).getString("code"))
        val project = send(JSONObject().put("v", 1).put("type", "create_project")
            .put("key", "00000000-0000-0000-0000-000000000031").put("title", "Library"))
        val result = send(JSONObject().put("v", 1).put("type", "get_knowledge")
            .put("projectId", project.getString("id")))
        assertEquals("knowledge", result.getString("type"))
        assertEquals(0, result.getJSONArray("items").length())
    }

    @Test fun bulkImportRequiresPreviewAndUsesAtomicDomainAction() {
        val repo = InMemoryWorkStreamRepository()
        val clock = object : VirlinClock { override fun now() = Instant.parse("2026-10-16T00:00:00Z") }
        val session = DesktopWorkspaceProtocol(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider()))
        fun send(json: JSONObject): JSONObject {
            val output = ByteArrayOutputStream()
            session.next(ByteArrayInputStream((json.toString() + "\n").toByteArray()), output)
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        val key = "00000000-0000-0000-0000-000000000011"
        assertEquals("PREVIEW_REQUIRED", send(JSONObject().put("v", 1).put("type", "apply_bulk")
            .put("key", key)).getString("code"))
        val source = """{"version":2,"items":[{"name":"P","children":[{"name":"W","children":[{"name":"T"}]}]}]}"""
        val preview = send(JSONObject().put("v", 1).put("type", "preview_bulk").put("key", key)
            .put("target", "projects").put("ownerType", JSONObject.NULL)
            .put("ownerId", JSONObject.NULL).put("source", source))
        assertEquals("bulk_preview", preview.getString("type"))
        assertEquals(3, preview.getInt("count"))
        val result = send(JSONObject().put("v", 1).put("type", "apply_bulk").put("key", key))
        assertEquals("bulk_created", result.getString("type"))
        assertEquals(3, result.getInt("count"))
        assertEquals(3, send(JSONObject().put("v", 1).put("type", "apply_bulk").put("key", key)).getInt("count"))
        assertEquals(1, repo.projects.value.size)
        assertEquals(1, repo.streams.value.size)
        assertEquals(1, repo.tasks.value.size)
    }

    @Test fun mapAppearanceAndPlacementArePhoneConfirmedAndProjectScoped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = InMemoryWorkStreamRepository()
        val clock = object : VirlinClock { override fun now() = Instant.parse("2026-10-16T00:00:00Z") }
        val session = DesktopWorkspaceProtocol(repo, DefaultVirlinActions(repo, clock, SequentialIdProvider()), context)
        fun send(json: JSONObject): JSONObject {
            val output = ByteArrayOutputStream()
            session.next(ByteArrayInputStream((json.toString() + "\n").toByteArray()), output)
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        fun request(type: String, key: String) = JSONObject().put("v", 1).put("type", type).put("key", key)
        val projectId = send(request("create_project", "00000000-0000-0000-0000-0000000000b1")
            .put("title", "Map test")).getString("id")
        val streamId = send(request("create_workstream", "00000000-0000-0000-0000-0000000000b2")
            .put("ownerId", projectId).put("title", "Stream")).getString("id")
        val taskId = send(request("create_task", "00000000-0000-0000-0000-0000000000b3")
            .put("ownerType", "workstream").put("ownerId", streamId).put("title", "Task")).getString("id")
        val initial = send(JSONObject().put("v", 1).put("type", "get_map_appearance")
            .put("projectId", projectId))
        assertEquals("map_appearance", initial.getString("type"))
        val color = send(request("set_map_appearance", "00000000-0000-0000-0000-0000000000b4")
            .put("projectId", projectId).put("nodeId", taskId).put("operation", "color")
            .put("color", 0xFF52A887L).put("width", JSONObject.NULL))
        assertEquals(0xFF52A887L, color.getJSONObject("nodeColors").getLong(taskId))
        val width = send(request("set_map_appearance", "00000000-0000-0000-0000-0000000000b5")
            .put("projectId", projectId).put("nodeId", taskId).put("operation", "width")
            .put("color", JSONObject.NULL).put("width", 280))
        assertEquals(280.0, width.getJSONObject("nodeWidths").getDouble(taskId), 0.01)
        val staleColor = send(request("set_map_appearance", "00000000-0000-0000-0000-0000000000b8")
            .put("projectId", projectId).put("nodeId", taskId).put("operation", "color")
            .put("color", 0xFF95BC7CL).put("expectedColor", JSONObject.NULL)
            .put("hasExpected", true))
        assertEquals("STALE_MAP_APPEARANCE", staleColor.getString("code"))
        val staleWidth = send(request("set_map_appearance", "00000000-0000-0000-0000-0000000000bb")
            .put("projectId", projectId).put("nodeId", taskId).put("operation", "width")
            .put("width", 320).put("expectedWidth", JSONObject.NULL)
            .put("hasExpected", true))
        assertEquals("STALE_MAP_APPEARANCE", staleWidth.getString("code"))
        assertEquals(280.0, send(JSONObject().put("v", 1).put("type", "get_map_appearance")
            .put("projectId", projectId)).getJSONObject("nodeWidths").getDouble(taskId), 0.01)
        val deniedDelete = send(request("map_delete", "00000000-0000-0000-0000-0000000000b9")
            .put("projectId", projectId).put("nodeId", taskId).put("kind", "task")
            .put("confirmTitle", "wrong name"))
        assertEquals("CONFIRMATION_MISMATCH", deniedDelete.getString("code"))
        assertTrue(repo.tasks.value.any { it.id == taskId })
        val renamed = send(request("map_rename", "00000000-0000-0000-0000-0000000000ba")
            .put("projectId", projectId).put("nodeId", streamId).put("kind", "workstream")
            .put("title", "Renamed stream"))
        assertEquals("map_renamed", renamed.getString("type"))
        assertEquals("Renamed stream", repo.streams.value.first { it.id == streamId }.title)
        val move = send(request("map_place", "00000000-0000-0000-0000-0000000000b6")
            .put("projectId", projectId).put("sourceId", taskId).put("targetParentId", projectId)
            .put("insertBeforeId", JSONObject.NULL).put("operation", "MOVE")
            .put("expectedRevision", initial.getString("hierarchyRevision")))
        assertEquals(move.toString(), "map_placed", move.getString("type"))
        assertEquals(null, repo.tasks.value.first { it.id == taskId }.workStreamId)
        val stale = send(request("map_place", "00000000-0000-0000-0000-0000000000b7")
            .put("projectId", projectId).put("sourceId", taskId).put("targetParentId", streamId)
            .put("operation", "MOVE").put("expectedRevision", initial.getString("hierarchyRevision")))
        assertEquals("error", stale.getString("type"))
    }
}

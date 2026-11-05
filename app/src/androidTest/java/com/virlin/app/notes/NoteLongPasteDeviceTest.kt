package com.virlin.app.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.id.UuidIdProvider
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteMath
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.time.SystemVirlinClock
import com.virlin.app.ui.notes.NoteBlockEditor
import com.virlin.app.ui.notes.NotesViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the Android editor/clipboard path with a response larger than the old 500-block cap. */
@RunWith(AndroidJUnit4::class)
class NoteLongPasteDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longStructuredClipboardTextCreatesBlocksOnDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val pasted = buildString {
            append("## Plan\n\nUse the **existing** design.\n\n")
            repeat(300) { append("Paragraph $it.\n\n") }
            repeat(320) { append("- Step $it\n") }
            append("\n```json\n{\"ready\":true}\n```")
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("note", pasted))
        val vm = NotesViewModel("device-long-paste", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), SystemVirlinClock,
                UuidIdProvider), UuidIdProvider)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm) }
        }
        compose.waitUntil(10_000) { !vm.state.value.loading }
        val id = vm.state.value.blocks.single().id
        compose.onNodeWithTag("notes_block_$id").performTextInput(pasted)
        compose.runOnIdle {
            assertTrue("blocks=${vm.state.value.blocks.size} firstType=${vm.state.value.blocks.firstOrNull()?.type} firstLength=${vm.state.value.blocks.firstOrNull()?.text?.length} dialog=${vm.state.value.dialog}",
                vm.state.value.blocks.size > 500)
            assertEquals(NoteDocBlockType.HEADING_2, vm.state.value.blocks.first().type)
            assertTrue(vm.state.value.blocks.any { it.type == NoteDocBlockType.CODE })
            assertTrue(vm.state.value.blocks.none { "**" in it.text })
        }
    }

    @Test fun mixedClipboardContentPreservesMarksPromptCodeAndMathOnDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val pasted = "## Overview\n\nThis is **important**.\n\n" +
            "- First point\n\n```json\n{\"ok\":true}\n```\n\n" +
            "```prompt\nCheck the result.\n```\n\n" +
            "\$\$x^2+y^2=z^2\$\$"
        clipboard.setPrimaryClip(ClipData.newPlainText("note", pasted))
        val vm = NotesViewModel("device-mixed-paste", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), SystemVirlinClock,
                UuidIdProvider), UuidIdProvider)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm) }
        }
        compose.waitUntil(10_000) { !vm.state.value.loading }
        val id = vm.state.value.blocks.single().id
        compose.onNodeWithTag("notes_block_$id").performTextInput(pasted)
        compose.runOnIdle {
            val blocks = vm.state.value.blocks
            assertEquals(listOf(NoteDocBlockType.HEADING_2, NoteDocBlockType.PARAGRAPH,
                NoteDocBlockType.BULLET, NoteDocBlockType.CODE, NoteDocBlockType.PROMPT,
                NoteDocBlockType.CODE), blocks.map { it.type })
            assertTrue(blocks[1].runs.any { NoteDocMark.BOLD in it.marks })
            assertTrue(NoteMath.isEquation(blocks.last()))
        }
    }

    @Test fun styledHtmlClipboardWithMarkdownSourceUsesRealFormattingOnDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val pasted = "### Checklist\n\nUse the **existing** project.\n\n" +
            "```json\n{\"ok\":true}\n```"
        val html = "<p><span style='color:#202124'>### Checklist</span></p>" +
            "<p><span style='color:#202124'>Use the **existing** project.</span></p>" +
            "<p><span style='color:#202124'>```json</span></p>" +
            "<p>{\"ok\":true}</p><p>```</p>"
        clipboard.setPrimaryClip(ClipData.newHtmlText("note", pasted, html))
        val vm = NotesViewModel("device-html-paste", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), SystemVirlinClock,
                UuidIdProvider), UuidIdProvider)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm) }
        }
        compose.waitUntil(10_000) { !vm.state.value.loading }
        val id = vm.state.value.blocks.single().id
        compose.onNodeWithTag("notes_block_$id").performTextInput(pasted)
        compose.runOnIdle {
            val blocks = vm.state.value.blocks
            assertEquals(listOf(NoteDocBlockType.HEADING_3, NoteDocBlockType.PARAGRAPH,
                NoteDocBlockType.CODE), blocks.map { it.type })
            assertTrue(blocks[1].runs.any { NoteDocMark.BOLD in it.marks })
            assertTrue(blocks.none { "**" in it.text || "```" in it.text })
        }
    }

    @Test fun androidPasteKeyUsesStructuredImport() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val pasted = "## Device paste\n\nA **bold** answer.\n\n- One item"
        clipboard.setPrimaryClip(ClipData.newPlainText("note", pasted))
        val vm = NotesViewModel("device-key-paste", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), SystemVirlinClock,
                UuidIdProvider), UuidIdProvider)
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { if (!state.loading) NoteBlockEditor(state, vm) }
        }
        compose.waitUntil(10_000) { !vm.state.value.loading }
        val id = vm.state.value.blocks.single().id
        compose.onNodeWithTag("notes_block_$id").performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_PASTE)
        compose.waitForIdle()
        compose.runOnIdle {
            val blocks = vm.state.value.blocks
            assertEquals(listOf(NoteDocBlockType.HEADING_2, NoteDocBlockType.PARAGRAPH,
                NoteDocBlockType.BULLET), blocks.map { it.type })
            assertTrue(blocks[1].runs.any { NoteDocMark.BOLD in it.marks })
        }
    }
}

package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.PromptDocument
import com.virlin.app.domain.model.PromptContentMode
import com.virlin.app.domain.note.NoteClipboardImporter
import com.virlin.app.domain.prompt.PromptDocumentCodec
import com.virlin.app.domain.prompt.PromptContentParser
import com.virlin.app.domain.prompt.ResponseSegment
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.prompt.PromptEditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PromptDocumentTest {

    private val clock = FakeClock(Instant.parse("2026-09-14T12:00:00Z"))
    private val ids = SequentialIdProvider()
    private val dispatcher = UnconfinedTestDispatcher()
    private var seq = 0
    private val newId = { "b${++seq}" }

    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun tags_roundTrip() {
        val tags = listOf("Coding", "Review")
        assertEquals(tags, PromptDocumentCodec.decodeTags(PromptDocumentCodec.encodeTags(tags)))
        assertEquals(emptyList<String>(), PromptDocumentCodec.decodeTags("[]"))
    }

    @Test fun pasteMarkdown_preservesStructureInPrompt() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val created = actions.createPrompt(
            title = "Code Review",
            description = "Reusable",
            tags = listOf("Coding"),
            blocks = NoteClipboardImporter.import(
                null,
                "# Role\n\nYou are senior.\n\n- correctness\n\n```kotlin\nx\n```",
                newId
            )
        )
        assertTrue(created is ActionResult.Success)
        val doc = (created as ActionResult.Success).value
        assertEquals(NoteBlockType.HEADING_1, doc.blocks[0].type)
        assertTrue(doc.blocks.any { it.type == NoteBlockType.BULLETED_LIST })
        assertTrue(doc.blocks.any { it.type == NoteBlockType.CODE })
        assertEquals(listOf("Coding"), doc.tags)
        val loaded = actions.getPromptByCaptureId(doc.captureItemId)
        assertNotNull(loaded)
        assertEquals("Code Review", loaded!!.title)
    }

    @Test fun hydrateLegacyPlainPrompt() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val cap = actions.createCapture(
            com.virlin.app.domain.action.CreateCapture(
                type = com.virlin.app.domain.model.CaptureType.PROMPT,
                content = "plain prompt body",
                title = "Legacy"
            )
        )
        assertTrue(cap is ActionResult.Success)
        val id = (cap as ActionResult.Success).value.id
        val hydrated = actions.getOrHydratePrompt(id)
        assertTrue(hydrated is ActionResult.Success)
        val doc = (hydrated as ActionResult.Success).value
        assertEquals("Legacy", doc.title)
        assertEquals("plain prompt body", doc.blocks.single().plainText)
    }

    @Test fun viewModel_pasteReplacesEmptyAndCopyExcludesPlaceholder() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = PromptEditorViewModel(null, actions, repo, ids, clock)
        val id = vm.state.value.blocks.first().id
        vm.pasteStructured(id, null, "## Task\n\nDo the thing.")
        assertEquals(NoteBlockType.HEADING_2, vm.state.value.blocks[0].type)
        val plain = vm.copyPromptPlainText()
        assertFalse(plain.contains("Paste or type"))
        assertTrue(plain.contains("Task"))
    }

    @Test fun emptyDraft_notMeaningful() {
        val doc = PromptDocument(
            id = "p", captureItemId = "c", title = null,
            blocks = listOf(NoteBlock("1", NoteBlockType.TEXT)),
            createdAt = clock.now(), updatedAt = clock.now()
        )
        assertFalse(doc.hasMeaningfulContent())
    }

    @Test fun versionedPayload_roundTripsExactSourceModeLanguageAndResponse() {
        val source = "  Keep leading spaces\r\n\tand exact line endings  "
        val response = "Explanation\n\n```kotlin\nval answer = 42\n```\n\nDone."
        val original = PromptDocument(
            id = "p", captureItemId = "c", title = "Exact",
            blocks = listOf(NoteBlock("1", NoteBlockType.TEXT, plainText = source)),
            sourceText = source, mode = PromptContentMode.CODE, language = "Kotlin",
            responseText = response, createdAt = clock.now(), updatedAt = clock.now()
        )
        val payload = PromptDocumentCodec.encodeDocument(original)
        val decoded = PromptDocumentCodec.decodeInto(
            PromptDocumentCodec.Meta("p", "c", "Exact", null, "[]", clock.now(), clock.now()),
            payload
        )
        assertEquals(source, decoded.sourceText)
        assertEquals(PromptContentMode.CODE, decoded.mode)
        assertEquals("Kotlin", decoded.language)
        assertEquals(response, decoded.responseText)
        assertEquals(source, PromptDocumentCodec.plainText(decoded))
    }

    @Test fun responseParser_separatesProseAndEveryFencedCodeBlock_withoutChangingSource() {
        val raw = "Before\n```kotlin\nval x = 1\n```\nMiddle\n```sql\nSELECT 1;\n```\nAfter"
        val segments = PromptContentParser.responseSegments(raw)
        assertEquals(5, segments.size)
        assertEquals("Before", (segments[0] as ResponseSegment.Prose).text)
        assertEquals("Kotlin", (segments[1] as ResponseSegment.Code).language)
        assertEquals("val x = 1", (segments[1] as ResponseSegment.Code).text)
        assertEquals("SQL", (segments[3] as ResponseSegment.Code).language)
    }

    @Test fun languageDetection_isDeterministicAndConservative() {
        assertEquals("Kotlin", PromptContentParser.detectLanguage("fun main() { val x = 1 }"))
        assertEquals("Python", PromptContentParser.detectLanguage("def main():\n    print('ok')"))
        assertEquals("SQL", PromptContentParser.detectLanguage("SELECT * FROM tasks"))
        assertEquals("Plain text", PromptContentParser.detectLanguage("Please summarize this chapter."))
    }

    @Test fun viewModel_modeResponseAndExactCopy_survivePersistence() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = PromptEditorViewModel(null, actions, repo, ids, clock)
        val source = "  fun main() {\n    println(\"Hi\")\n  }  "
        val response = "Use this:\n```kotlin\nprintln(\"Hi\")\n```"
        vm.onSourceTextChange(source)
        vm.setMode(PromptContentMode.CODE)
        vm.setLanguage("Kotlin")
        vm.onResponseTextChange(response)
        vm.saveToInbox()
        val captureId = vm.state.value.captureId!!
        val loaded = actions.getPromptByCaptureId(captureId)!!
        assertEquals(source, loaded.sourceText)
        assertEquals(source, vm.copyPromptPlainText())
        assertEquals(PromptContentMode.CODE, loaded.mode)
        assertEquals("Kotlin", loaded.language)
        assertEquals(response, loaded.responseText)
    }
}

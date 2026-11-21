package com.virlin.app.domain

import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteMath
import com.virlin.app.domain.notedoc.NotePasteParser
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.notes.NotesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotePasteParserTest {
    @Test fun markdownHeadingsBoldCodeAndEquationBecomeRealBlocks() {
        val pasted = NotePasteParser.parse(
            "# IFET CodeTrack — Complete Development Roadmap\n\n" +
                "A **bold** sentence.\n\n```kotlin\nval answer = 42\n```\n\n" +
                "$$\nx^2 + y^2 = z^2\n$$")
        assertEquals(listOf(NoteDocBlockType.HEADING_1, NoteDocBlockType.PARAGRAPH,
            NoteDocBlockType.CODE, NoteDocBlockType.CODE), pasted.map { it.type })
        assertEquals("IFET CodeTrack — Complete Development Roadmap", pasted[0].text)
        assertEquals("A bold sentence.", pasted[1].text)
        assertTrue(pasted[1].runs.any { NoteDocMark.BOLD in it.marks &&
            pasted[1].text.substring(it.start, it.end) == "bold" })
        assertEquals("val answer = 42", pasted[2].text)
        assertTrue(pasted[3].text.contains("x^2 + y^2"))
    }

    @Test fun styledHtmlWrappingLiteralMarkdownUsesMarkdownStructure() {
        val plain = "### What Claude has completed\n\n" +
            "| Component | Result |\n| --- | --- |\n| API | Ready |\n\n" +
            "```json\n{\"status\":\"error\"}\n```\n\n" +
            "```message\nCheck the response.\n```\n\n" +
            "\$\$x^2+y^2=z^2\$\$"
        val html = plain.lines().joinToString("") { line ->
            "<p><span style='font-family:Arial;color:#223344'>${line.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")}</span></p>"
        }
        val blocks = NotePasteParser.parse(plain, html)
        assertEquals(listOf(NoteDocBlockType.HEADING_3, NoteDocBlockType.TABLE,
            NoteDocBlockType.CODE, NoteDocBlockType.PROMPT, NoteDocBlockType.CODE),
            blocks.map { it.type })
        assertEquals("What Claude has completed", blocks[0].text)
        assertEquals("Ready", blocks[1].table!!.cell(1, 1))
        assertEquals("{\"status\":\"error\"}", blocks[2].text)
        assertFalse(NoteMath.isEquation(blocks[2]))
        assertTrue(NoteMath.isEquation(blocks[4]))
    }

    @Test fun styledClipboardParagraphsDoNotKeepLiteralBoldMarkers() {
        val plain = "Yes. Make Claude treat this as a **layout-only refactor**.\n\n" +
            "The implementation is the **functional source of truth**."
        val html = "<p><span style='color:#202124'>Yes. Make Claude treat this as a " +
            "**layout-only refactor**.</span></p><p><span style='color:#202124'>" +
            "The implementation is the **functional source of truth**.</span></p>"
        val blocks = NotePasteParser.parse(plain, html)
        assertEquals(2, blocks.size)
        assertFalse(blocks.any { "**" in it.text })
        assertTrue(blocks.all { block -> block.runs.any { NoteDocMark.BOLD in it.marks } })
    }

    @Test fun genuinelySemanticHtmlStillWinsOverMarkdownClipboardText() {
        val plain = "### Heading\n\n```json\n{\"x\":1}\n```"
        val html = "<h3>Heading</h3><pre><code>{\"x\":1}</code></pre>"
        val blocks = NotePasteParser.parse(plain, html)
        assertEquals(listOf(NoteDocBlockType.HEADING_3, NoteDocBlockType.CODE),
            blocks.map { it.type })
        assertEquals("Heading", blocks[0].text)
    }

    @Test fun importedImageIsNotDiscardedByMarkdownFallback() {
        val plain = "### Heading\n\nDiagram"
        val html = "<p>### Heading</p><img src='content://example/image/2' alt='Diagram'>"
        val blocks = NotePasteParser.parse(plain, html) { source ->
            if (source == "content://example/image/2") "note_images/diagram.png" else null
        }
        assertTrue(blocks.any { it.type == NoteDocBlockType.IMAGE &&
            it.imagePath == "note_images/diagram.png" })
    }

    @Test fun htmlPreservesSemanticBlocksAndInlineMarks() {
        val html = "<h1>Roadmap</h1><p>A <strong>bold</strong> and <em>careful</em> plan.</p>" +
            "<p><span style='font-weight:700;color:#336699;font-size:20px'>Styled</span></p>" +
            "<pre><code>val answer = 42\nprintln(answer)</code></pre>" +
            "<ol><li>First</li><li>Second</li></ol>"
        val pasted = NotePasteParser.parse("Roadmap\nA bold and careful plan.", html)
        assertEquals(listOf(NoteDocBlockType.HEADING_1, NoteDocBlockType.PARAGRAPH,
            NoteDocBlockType.PARAGRAPH, NoteDocBlockType.CODE,
            NoteDocBlockType.NUMBERED, NoteDocBlockType.NUMBERED),
            pasted.map { it.type })
        assertTrue(pasted[1].runs.any { NoteDocMark.BOLD in it.marks })
        assertTrue(pasted[1].runs.any { NoteDocMark.ITALIC in it.marks })
        assertEquals(20, pasted[2].runs.single().fontSizeSp)
        assertEquals(0xFF336699L, pasted[2].runs.single().color)
        assertTrue(pasted[3].text.contains("println(answer)"))
    }

    @Test fun markdownTableBecomesEditableTable() {
        val pasted = NotePasteParser.parse(
            "| Component | Status |\n| --- | --- |\n| API | Ready |\n| UI | Next |")
        assertEquals(NoteDocBlockType.TABLE, pasted.single().type)
        assertEquals(3, pasted.single().table!!.rows)
        assertEquals("Ready", pasted.single().table!!.cell(1, 1))
    }

    @Test fun pastedLatexRemainsEditableSourceAndIsRecognizedAsMath() {
        val pasted = NotePasteParser.parse(
            "1. Equations\n\n\\frac{a}{b} = \\frac{c}{d}\n\n" +
                "$$\nx = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}\n$$")
        assertEquals(listOf(NoteDocBlockType.NUMBERED, NoteDocBlockType.CODE,
            NoteDocBlockType.CODE), pasted.map { it.type })
        assertEquals("\\frac{a}{b} = \\frac{c}{d}", pasted[1].text)
        assertTrue(NoteMath.isEquation(pasted[1].text))
        assertTrue(NoteMath.renderSource(pasted[2].text).startsWith("x = \\frac"))
    }

    @Test fun environmentCodeAndMessageStayNonMathAndKeepTheirStructure() {
        val html = "<h2>3\\. Give Claude this execution instruction</h2>" +
            "<pre data-language='environment'><code>LEETCODE_PROVIDER_URL=https://example.test\n" +
            "LEETCODE_PROVIDER_FORMAT=leetcode-stats\nUSE_FIXTURE_PROVIDER=false</code></pre>" +
            "<pre class='language-message'><code>Use the existing codebase.\n\nDo not rebuild it.</code></pre>"
        val parsed = NotePasteParser.parse("", html)
        assertEquals(listOf(NoteDocBlockType.HEADING_2, NoteDocBlockType.CODE,
            NoteDocBlockType.PROMPT), parsed.map { it.type })
        assertEquals("3. Give Claude this execution instruction", parsed[0].text)
        assertEquals(false, parsed[1].mathMode)
        assertFalse(NoteMath.isEquation(parsed[1]))
        assertTrue(parsed[1].text.contains("LEETCODE_PROVIDER_URL=https://example.test"))
        assertEquals("Use the existing codebase.\n\nDo not rebuild it.", parsed[2].text)
    }

    @Test fun codeWithEquationSyntaxStillRemainsCodeButMathAnnotationRenders() {
        val parsed = NotePasteParser.parse("```python\nE=mc^2\n```\n\n\$\$x^2+y^2=z^2\$\$")
        assertEquals(listOf(false, true), parsed.map { it.mathMode })
        assertFalse(NoteMath.isEquation(parsed[0]))
        assertTrue(NoteMath.isEquation(parsed[1]))
        assertFalse(NoteMath.isEquation("LEETCODE_PROVIDER_URL=https://example.test"))
    }

    @Test fun escapedNumberedHeadingDoesNotDisplayBackslash() {
        val parsed = NotePasteParser.parse("3\\. **Give Claude this execution instruction**")
        assertFalse(parsed.single().text.contains('\\'))
    }

    @Test fun renderedMathMlPasteUsesOriginalTexAnnotation() {
        val html = "<p><span class='katex'><math><semantics><mrow><mi>x</mi></mrow>" +
            "<annotation encoding='application/x-tex'>x^2 + y^2 = z^2</annotation>" +
            "</semantics></math></span></p>"
        val pasted = NotePasteParser.parse("x² + y² = z²", html)
        assertEquals(NoteDocBlockType.CODE, pasted.single().type)
        assertEquals("x^2 + y^2 = z^2", pasted.single().text)
        assertTrue(NoteMath.isEquation(pasted.single().text))
    }

    @Test fun multipleMathMlEquationsInOneHtmlLineAreNotDropped() {
        val html = "<p><span class='katex'><math><annotation encoding='application/x-tex'>" +
            "x^2+y^2=z^2</annotation></math></span>" +
            "<span class='katex'><math><annotation encoding='application/x-tex'>" +
            "\\frac{a}{b}=c</annotation></math></span></p>"
        val pasted = NotePasteParser.parse("x²+y²=z² a/b=c", html)
        assertEquals("x^2+y^2=z^2\n\\frac{a}{b}=c", pasted.single().text)
        assertEquals("x^2+y^2=z^2 \\; \\frac{a}{b}=c",
            NoteMath.renderSource(pasted.single().text))
    }

    @Test fun longCopiedMathLineRemainsOneScrollableDisplayExpression() {
        val pieces = (1..40).map { "x_$it^2 + y_$it^2 = z_$it^2" }
        val source = pieces.joinToString("\n")
        val rendered = NoteMath.renderSource(source)
        assertEquals(pieces.joinToString(" \\; "), rendered)
        assertTrue("A long equation must not be converted to aligned rows", "\\begin{aligned}" !in rendered)
    }

    @Test fun explicitAlignedLatexKeepsItsRows() {
        val source = "\\begin{aligned}a &= b \\\\ c &= d\\end{aligned}"
        assertEquals(source, NoteMath.renderSource(source))
    }

    @Test fun chatGptMetricCardsBecomeReadableGrid() {
        val pasted = NotePasteParser.parse("Total\n191\nEasy\n94\nMedium\n70\nHard\n27")
        assertEquals(NoteDocBlockType.TABLE, pasted.single().type)
        assertEquals(4, pasted.single().table!!.cols)
        assertEquals("Medium", pasted.single().table!!.cell(0, 2))
        assertEquals("70", pasted.single().table!!.cell(1, 2))
    }

    @Test fun htmlMetricCardsBecomeReadableGrid() {
        val html = "<div><div>Total</div><strong>191</strong></div>" +
            "<div><div>Easy</div><strong>94</strong></div>" +
            "<div><div>Medium</div><strong>70</strong></div>" +
            "<div><div>Hard</div><strong>27</strong></div>"
        val pasted = NotePasteParser.parse("Total 191 Easy 94 Medium 70 Hard 27", html)
        assertEquals(NoteDocBlockType.TABLE, pasted.single().type)
        assertEquals("27", pasted.single().table!!.cell(1, 3))
    }

    @Test fun htmlHighlightPromptAndImageAreImportedAsData() {
        val html = "<p><mark>important</mark> <em>detail</em></p>" +
            "<pre class='prompt'><button>Copy</button><code>Check the result</code></pre>" +
            "<img src='content://example/image/1' alt='Diagram'>"
        val pasted = NotePasteParser.parse("important detail", html) { source ->
            if (source == "content://example/image/1") "note_images/test.png" else null
        }
        assertEquals(listOf(NoteDocBlockType.PARAGRAPH, NoteDocBlockType.PROMPT,
            NoteDocBlockType.IMAGE), pasted.map { it.type })
        assertTrue(pasted[0].runs.any { it.highlight == 0xFFFFEB8AL })
        assertTrue(pasted[0].runs.any { NoteDocMark.ITALIC in it.marks })
        assertEquals("Check the result", pasted[1].text)
        assertEquals("note_images/test.png", pasted[2].imagePath)
    }

    @Test fun unstyledHtmlWrapperDoesNotHideMarkdownHeading() {
        val pasted = NotePasteParser.parse("# Roadmap", "<p># Roadmap</p>")
        assertEquals(NoteDocBlockType.HEADING_1, pasted.single().type)
        assertEquals("Roadmap", pasted.single().text)
    }

    @Test fun identifiersWithUnderscoresStayLiteral() {
        val pasted = NotePasteParser.parse("Use student_id_key for the lookup.")
        assertEquals("Use student_id_key for the lookup.", pasted.single().text)
        assertTrue(pasted.single().runs.isEmpty())
    }

    @Test fun structuredPasteIsOneUndoableEdit() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val actions = DefaultVirlinActions(InMemoryWorkStreamRepository(),
                FakeClock(Instant.EPOCH), ids)
            val vm = NotesViewModel("task-paste-test", "Note", actions, ids)
            val original = vm.state.value.blocks.single()
            val clipboard = "# Heading\n\n**Bold** text\n\n```\ncode()\n```"
            assertTrue(vm.pasteStructured(original.id, clipboard, clipboard))
            assertEquals(listOf(NoteDocBlockType.HEADING_1, NoteDocBlockType.PARAGRAPH,
                NoteDocBlockType.CODE), vm.state.value.blocks.map { it.type })
            assertTrue(vm.state.value.blocks[1].runs.any { NoteDocMark.BOLD in it.marks })
            vm.undo()
            assertEquals(listOf(original), vm.state.value.blocks)
            assertFalse(vm.pasteStructured(original.id, "typed", "different clipboard"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun replacingSelectionPreservesTextOnBothSides() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-paste-selection", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(),
                    FakeClock(Instant.EPOCH), ids), ids)
            val id = vm.state.value.blocks.single().id
            vm.onTextChanged(id, "Before selected After", TextRange(15))
            assertTrue(vm.pasteStructured(id, "Before # Heading After", "# Heading"))
            assertEquals(listOf("Before ", "Heading", " After"),
                vm.state.value.blocks.map { it.text })
            assertEquals(NoteDocBlockType.HEADING_1, vm.state.value.blocks[1].type)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun pastedCodeAndPromptCanBeEditedDeletedAndUndone() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-code-delete", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(),
                    FakeClock(Instant.EPOCH), ids), ids)
            val original = vm.state.value.blocks.single()
            val pasted = "```env\nLEETCODE_PROVIDER_URL=https://example.test\n```\n\n" +
                "```message\nUse the existing project.\n```"
            assertTrue(vm.pasteStructured(original.id, pasted, pasted))
            val code = vm.state.value.blocks.first { it.type == NoteDocBlockType.CODE }
            val prompt = vm.state.value.blocks.first { it.type == NoteDocBlockType.PROMPT }
            assertFalse(NoteMath.isEquation(code))
            vm.onTextChanged(code.id, "LEETCODE_PROVIDER_URL=", TextRange(22))
            assertEquals("LEETCODE_PROVIDER_URL=", vm.state.value.blocks.first { it.id == code.id }.text)
            vm.deleteBlock(prompt.id)
            assertFalse(vm.state.value.blocks.any { it.id == prompt.id })
            vm.undo()
            assertTrue(vm.state.value.blocks.any { it.id == prompt.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun structuredPasteUsesInsertedTextWhenClipboardTextDiffers() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-paste-clipboard-mismatch", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(),
                    FakeClock(Instant.EPOCH), ids), ids)
            val id = vm.state.value.blocks.single().id
            val inserted = "### Summary\n\n```json\n{\"status\":\"error\"}\n```"
            assertTrue(vm.pasteStructured(id, inserted,
                plainText = "different clipboard representation",
                htmlText = "<p>stale HTML</p>"))
            assertEquals(listOf(NoteDocBlockType.HEADING_3, NoteDocBlockType.CODE),
                vm.state.value.blocks.map { it.type })
            assertEquals("{\"status\":\"error\"}", vm.state.value.blocks[1].text)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun semanticHtmlIsImportedWhenClipboardPlainTextDiffers() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-html-clipboard-mismatch", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(),
                    FakeClock(Instant.EPOCH), ids), ids)
            val id = vm.state.value.blocks.single().id
            assertTrue(vm.pasteStructured(id, "Important answer", "different clipboard text",
                "<p><strong>Important</strong> answer</p>"))
            assertTrue(vm.state.value.blocks.single().runs.any { NoteDocMark.BOLD in it.marks })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun markdownBoldImportsDespiteClipboardRepresentationMismatch() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-markdown-clipboard-mismatch", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(),
                    FakeClock(Instant.EPOCH), ids), ids)
            val id = vm.state.value.blocks.single().id
            assertTrue(vm.pasteStructured(id, "**Important** answer", "Important answer"))
            assertEquals("Important answer", vm.state.value.blocks.single().text)
            assertTrue(vm.state.value.blocks.single().runs.any { NoteDocMark.BOLD in it.marks })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun largeMarkdownTableKeepsAllRowsAndColumns() {
        val header = (1..25).joinToString("|") { "column$it" }
        val divider = (1..25).joinToString("|") { "---" }
        val rows = (1..150).joinToString("\n") { row ->
            (1..25).joinToString("|") { column -> "r${row}c${column}" }
        }
        val table = NotePasteParser.parse("$header\n$divider\n$rows").single().table!!
        assertEquals(151, table.rows)
        assertEquals(25, table.cols)
    }

    @Test fun richEditorTransactionSurvivesSaveAndReopen() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val repository = InMemoryWorkStreamRepository()
            val actions = DefaultVirlinActions(repository, FakeClock(Instant.EPOCH), ids)
            val first = NotesViewModel("rich-editor-save", "Note", actions, ids)
            val imported = NotePasteParser.parse("# Heading\n\n**Bold** answer\n\n```prompt\nExplain\n```")
                .map { it.copy(id = ids.newId("nb")) }
            first.replaceFromRichEditor(imported)
            first.flush()
            testScheduler.advanceUntilIdle()
            val reopened = NotesViewModel("rich-editor-save", "Note", actions, ids)
            assertEquals(imported, reopened.state.value.blocks)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun oversizedClipboardHtmlFallsBackToStructuredPlainText() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-large-rich-paste", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(),
                    FakeClock(Instant.EPOCH), ids), ids,
                pasteDispatcher = UnconfinedTestDispatcher(testScheduler))
            val id = vm.state.value.blocks.single().id
            val pasted = buildString {
                append("## TASK: Complete implementation\n\n")
                append("**Project:** Example\n\n")
                append("- First step\n- Second step\n\n")
                append("```json\n{\"status\":\"error\"}\n```\n\n")
                append("\$\$x^2+y^2=z^2\$\$\n\n")
                repeat(35) { append("A longer implementation paragraph with context and detail.\n\n") }
            }
            val oversizedHtml = "<div>" + "x".repeat(300_000) + "</div>"
            assertTrue(vm.pasteStructured(id, pasted, pasted, oversizedHtml))
            val blocks = vm.state.value.blocks
            assertEquals(NoteDocBlockType.HEADING_2, blocks.first().type)
            assertTrue(blocks.any { it.type == NoteDocBlockType.CODE })
            assertTrue(blocks.any { NoteMath.isEquation(it) })
            assertTrue(blocks.any { it.runs.any { run -> NoteDocMark.BOLD in run.marks } })
            assertFalse(blocks.any { it.text.startsWith("## ") })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun longAiResponseWithHundredsOfListRowsIsNotRejected() = runTest {
        val plain = buildString {
            append("# Implementation plan\n\nA **formatted** introduction.\n\n")
            repeat(350) { append("Paragraph $it explains the next step in detail.\n\n") }
            repeat(380) { append("- Checklist item $it\n") }
            append("\n```json\n{\"ready\":true}\n```\n\n")
            append("```prompt\nVerify every step.\n```\n\n")
            append("\$\$x^2+y^2=z^2\$\$")
        }
        val parsed = NotePasteParser.parse(plain)
        assertTrue(parsed.size > 500)
        assertEquals(NoteDocBlockType.HEADING_1, parsed.first().type)
        assertTrue(parsed.any { it.type == NoteDocBlockType.BULLET })
        assertTrue(parsed.any { it.type == NoteDocBlockType.CODE && !NoteMath.isEquation(it) })
        assertTrue(parsed.any { it.type == NoteDocBlockType.PROMPT })
        assertTrue(parsed.any(NoteMath::isEquation))

        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-long-paste", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(),
                    FakeClock(Instant.EPOCH), ids), ids,
                pasteDispatcher = UnconfinedTestDispatcher(testScheduler))
            val id = vm.state.value.blocks.single().id
            assertTrue(vm.pasteStructured(id, plain, plain))
            assertEquals(parsed.map { it.type }, vm.state.value.blocks.map { it.type })
            assertFalse(vm.state.value.blocks.any { it.text.contains("**") })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun pictureInsideInlineMarkupIsKeptAfterItsParagraph() {
        val html = "<h3>5. The conversation flow</h3>" +
            "<p><span>See below <span><img src='content://chat/flow' alt='Flow diagram'></span></span></p>" +
            "<p>My recommendation</p>"
        val pasted = NotePasteParser.parse("5. The conversation flow\nSee below\nMy recommendation", html) {
            if (it == "content://chat/flow") "flow-image" else null
        }
        assertEquals(listOf(NoteDocBlockType.HEADING_3, NoteDocBlockType.PARAGRAPH,
            NoteDocBlockType.IMAGE, NoteDocBlockType.PARAGRAPH), pasted.map { it.type })
        assertEquals("flow-image", pasted[2].imagePath)
    }

    @Test fun unreadableWebPictureKeepsItsDescriptionAndLink() {
        val pasted = NotePasteParser.parse("Flow", "<p>Flow</p><img src='https://example.com/flow.png' alt='Flow chart'>")
        assertEquals("Flow chart", pasted[1].text)
        assertEquals("https://example.com/flow.png", pasted[1].runs.single().linkUrl)
    }

    @Test fun drawnDiagramKeepsItsWordsOnePerLineAndIconsAddNothing() {
        val html = "<p>Steps <svg viewBox='0 0 10 10'><path d='M0 0'/></svg></p>" +
            "<svg><rect/><text>Introduce yourself</text><text>Ask permission</text></svg>"
        val pasted = NotePasteParser.parse("Steps", html)
        assertEquals(listOf("Steps", "Introduce yourself\nAsk permission"), pasted.map { it.text })
    }

    @Test fun markdownImageLineBecomesAnImageOrItsLinkedDescription() {
        val plain = "# Flow\n\n![Conversation flow](content://chat/flow)\n\n![Chart](https://example.com/c.png)"
        val pasted = NotePasteParser.parse(plain, null) { if (it.startsWith("content://")) "stored" else null }
        assertEquals(listOf(NoteDocBlockType.HEADING_1, NoteDocBlockType.IMAGE, NoteDocBlockType.PARAGRAPH),
            pasted.map { it.type })
        assertEquals("stored", pasted[1].imagePath)
        assertEquals("Chart", pasted[2].text)
        assertEquals("https://example.com/c.png", pasted[2].runs.single().linkUrl)
    }

    @Test fun codeTheHtmlLeftOutIsRestoredFromTheMarkdownInPlace() {
        val plain = "## 5. Flow\n\n```mermaid\nflowchart TD\nA[Introduce yourself] --> B[Ask permission]\n```\n\n" +
            "## My recommendation\n\nOpen with **industry engagement**."
        val html = "<h2>5. Flow</h2><div class='diagram'></div><h2>My recommendation</h2>" +
            "<p>Open with <strong>industry engagement</strong>.</p>"
        val pasted = NotePasteParser.parse(plain, html)
        assertEquals(listOf(NoteDocBlockType.HEADING_2, NoteDocBlockType.CODE,
            NoteDocBlockType.HEADING_2, NoteDocBlockType.PARAGRAPH), pasted.map { it.type })
        assertTrue(pasted[1].text.startsWith("flowchart TD"))
        assertTrue(pasted[3].runs.any { NoteDocMark.BOLD in it.marks })
    }

    @Test fun codeTheHtmlAlreadyHasIsNotDuplicated() {
        val plain = "## Setup\n\n```bash\nnpm install virlin\n```\n\n| A | B |\n| --- | --- |\n| 1 | 2 |"
        val html = "<h2>Setup</h2><pre><code>npm install virlin</code></pre>" +
            "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>"
        val pasted = NotePasteParser.parse(plain, html)
        assertEquals(listOf(NoteDocBlockType.HEADING_2, NoteDocBlockType.CODE, NoteDocBlockType.TABLE),
            pasted.map { it.type })
    }

    @Test fun pasteDetailsSayWhenTheClipboardHeldOnlyText() {
        val report = com.virlin.app.domain.notedoc.NotePasteReport.describe(
            com.virlin.app.domain.notedoc.NotePasteReport.ClipMeta(1, listOf("text/plain"), 0),
            "## Flow\n\nIntroduce yourself", null, false, NotePasteParser.parse("## Flow\n\nIntroduce yourself"))
        assertTrue(report, report.contains("No picture or diagram was on the clipboard"))
        assertTrue(report, report.contains("text/plain"))
        val withPicture = com.virlin.app.domain.notedoc.NotePasteReport.describe(null, "Flow",
            "<p>Flow</p><img src='data:image/png;base64,AAAA'>", true, emptyList())
        assertTrue(withPicture, withPicture.contains("1 image(s) (1 embedded)"))
    }

    @Test fun viewModelRecordsPasteDetails() {
        val ids = SequentialIdProvider()
        val vm = NotesViewModel("task-paste-report", "Note",
            DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids)
        val id = vm.state.value.blocks.single().id
        val plain = "## Flow\n\nIntroduce yourself and ask permission."
        assertTrue(vm.pasteStructured(id, plain, plain,
            clipSummary = com.virlin.app.domain.notedoc.NotePasteReport.ClipMeta(1, listOf("text/plain"), 0)))
        assertTrue(vm.lastPasteReport.orEmpty().contains("No picture or diagram"))
    }
}

package com.virlin.app.domain

import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.notedoc.GoogleDocsPaste
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NotePasteParser
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.notes.NotesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Pasting a selection copied in Google Docs keeps its headings, styles, lists, pictures and tables. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoogleDocsPasteTest {
    private fun s(text: String, size: Int = 11, weight: Int = 400, italic: Boolean = false,
        color: String = "#000000", decoration: String = "none") =
        "<span style=\"font-size:${size}pt;font-family:Arial,sans-serif;color:$color;background-color:transparent;" +
            "font-weight:$weight;font-style:${if (italic) "italic" else "normal"};font-variant:normal;" +
            "text-decoration:$decoration;vertical-align:baseline;white-space:pre;white-space:pre-wrap;\">$text</span>"

    private val picture = "https://lh7-rt.googleusercontent.com/docsz/AD_4nXabc123?key=xyz"

    private val docsHtml = "<meta charset=\"utf-8\"><b style=\"font-weight:normal;\" " +
        "id=\"docs-internal-guid-6f1c2a3b-7fff-4d2e-9a10-1234567890ab\">" +
        "<h1 dir=\"ltr\" style=\"line-height:1.38;margin-top:20pt;margin-bottom:6pt;\">${s("Builder's English for Virlin", 20)}</h1>" +
        "<p dir=\"ltr\" style=\"line-height:1.38;margin-top:0pt;margin-bottom:0pt;\">${s("Oct 30, 2026 · @Virlin V H")}</p>" +
        "<p dir=\"ltr\">${s("What you lack is not ability; it is ")}${s("names for the things you are already doing", weight = 700)}" +
        "${s(". You fall back on five safe words: ")}${s("safely, proceed", italic = true)}</p>" +
        "<br /><h2 dir=\"ltr\">${s("Start here", 16)}</h2>" +
        "<ul style=\"margin-top:0;margin-bottom:0;padding-inline-start:48px;\">" +
        "<li dir=\"ltr\" style=\"list-style-type:disc;font-size:11pt;\" aria-level=\"1\"><p dir=\"ltr\" role=\"presentation\">${s("Claude")}</p></li>" +
        "<li dir=\"ltr\" style=\"list-style-type:circle;font-size:11pt;\" aria-level=\"2\"><p dir=\"ltr\" role=\"presentation\">${s("Cursor")}</p></li></ul>" +
        "<ol><li dir=\"ltr\" style=\"list-style-type:decimal;\" aria-level=\"1\"><p role=\"presentation\">${s("Read the plan")}</p></li></ol>" +
        "<p dir=\"ltr\" style=\"text-align:center;\"><span style=\"font-size:11pt;\"><span style=\"border:none;display:inline-block;\">" +
        "<img src=\"$picture\" width=\"602\" height=\"339\" /></span></span></p>" +
        "<p dir=\"ltr\" style=\"text-align:center;\">${s("Workflow at a glance", 26)}</p>" +
        "<p dir=\"ltr\"><a href=\"https://www.google.com/url?q=https://example.com/page&amp;sa=D&amp;source=editors\" style=\"text-decoration:none;\">" +
        s("the reference page", color = "#1155cc", decoration = "underline;-webkit-text-decoration-skip:none") + "</a></p>" +
        "<div dir=\"ltr\" align=\"left\"><table style=\"border:none;border-collapse:collapse;\"><colgroup><col width=\"301\" /><col width=\"301\" /></colgroup><tbody>" +
        "<tr><td><p dir=\"ltr\">${s("Word")}</p></td><td><p dir=\"ltr\">${s("Meaning")}</p></td></tr>" +
        "<tr><td><p dir=\"ltr\">${s("Refactor")}</p></td><td><p dir=\"ltr\">${s("Restructure code")}</p></td></tr>" +
        "</tbody></table></div></b><br class=\"Apple-interchange-newline\">"

    private val docsPlain = "Builder's English for Virlin\nOct 30, 2026 · @Virlin V H\n" +
        "What you lack is not ability; it is names for the things you are already doing. You fall back on five safe words: safely, proceed\n\n" +
        "Start here\nClaude\nCursor\nRead the plan\n\nWorkflow at a glance\nthe reference page\nWord\tMeaning\nRefactor\tRestructure code"

    private fun NoteDocBlock.runFor(part: String) = text.indexOf(part).let { at ->
        runs.firstOrNull { at >= it.start && at < it.end }
    }

    @Test fun headingsStylesListsPicturesLinksAndTablesArePreserved() {
        val asked = mutableListOf<String>()
        val blocks = NotePasteParser.parse(docsPlain, docsHtml) { source -> asked += source; "stored-picture" }

        assertEquals(listOf(
            NoteDocBlockType.HEADING_1, NoteDocBlockType.PARAGRAPH, NoteDocBlockType.PARAGRAPH,
            NoteDocBlockType.HEADING_2, NoteDocBlockType.BULLET, NoteDocBlockType.BULLET,
            NoteDocBlockType.NUMBERED, NoteDocBlockType.IMAGE, NoteDocBlockType.HEADING_1,
            NoteDocBlockType.PARAGRAPH, NoteDocBlockType.TABLE,
        ), blocks.map { it.type })
        assertEquals("Builder's English for Virlin", blocks[0].text)
        assertTrue("headings take the note's heading size", blocks[0].runs.none { it.fontSizeSp != null })

        val body = blocks[2]
        assertTrue(NoteDocMark.BOLD in body.runFor("names for the things")!!.marks)
        assertNull("plain words stay plain", body.runFor("What you lack"))
        assertTrue(NoteDocMark.ITALIC in body.runFor("safely")!!.marks)
        assertTrue("black text and Docs' 11 pt body are the note's normal look",
            blocks.flatMap { it.runs }.none { it.color == 0xFF000000L || it.fontSizeSp == 15 || it.fontFamily != null })

        assertEquals(0, blocks[4].indent)
        assertEquals(1, blocks[5].indent)
        assertEquals("stored-picture", blocks[7].imagePath)
        assertEquals(listOf(picture), asked)
        assertEquals("Workflow at a glance", blocks[8].text)
        assertEquals(NoteDocAlign.CENTER, blocks[8].align)

        val link = blocks[9].runFor("reference")!!
        assertEquals("https://example.com/page", link.linkUrl)
        assertTrue(NoteDocMark.UNDERLINE in link.marks)
        assertEquals(0xFF1155CCL, link.color)

        val table = blocks[10].table!!
        assertEquals(listOf("Word", "Meaning", "Refactor", "Restructure code"), table.cells)
    }

    @Test fun aPictureThatCannotBeDownloadedStaysAsALink() {
        val blocks = NotePasteParser.parse(docsPlain, docsHtml) { null }
        val placeholder = blocks.first { it.text.startsWith("Picture from Google Docs") }
        assertEquals(picture, placeholder.runs.single().linkUrl)
    }

    @Test fun onlyGoogleDocsHtmlTakesTheDocsReading() {
        assertTrue(GoogleDocsPaste.isGoogleDocsHtml(docsHtml))
        assertFalse(GoogleDocsPaste.isGoogleDocsHtml("<p><strong>Important</strong> answer</p>"))
        val other = NotePasteParser.parse("Important answer", "<p><b style=\"font-weight:normal\">Important</b> answer</p>")
        assertTrue("other sources keep the general reading", other.single().runs.any { NoteDocMark.BOLD in it.marks })
    }

    @Test fun onlyGooglePictureServersAreDownloaded() {
        assertTrue(GoogleDocsPaste.isDocsImageUrl(picture))
        assertTrue(GoogleDocsPaste.isDocsImageUrl("https://lh3.googleusercontent.com/abc"))
        assertFalse(GoogleDocsPaste.isDocsImageUrl("http://lh3.googleusercontent.com/abc"))
        assertFalse(GoogleDocsPaste.isDocsImageUrl("https://googleusercontent.com.evil.example/x"))
        assertFalse(GoogleDocsPaste.isDocsImageUrl("https://evil.example/googleusercontent.com/x"))
        assertTrue(GoogleDocsPaste.hasRemoteImages(docsHtml))
    }

    @Test fun docsTextIsRecognisedDespiteSpacingDifferences() {
        assertTrue(GoogleDocsPaste.textMatches(docsHtml, docsPlain))
        assertTrue(GoogleDocsPaste.textMatches(docsHtml, docsPlain.replace("\n", "\n\n")))
        assertFalse(GoogleDocsPaste.textMatches(docsHtml, "typed words"))
    }

    @Test fun pastingIntoANoteKeepsTheStructureEvenWhenTheKeyboardTextDiffers() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-docs-paste", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids,
                pasteDispatcher = UnconfinedTestDispatcher(testScheduler))
            val original = vm.state.value.blocks.single()
            val withoutPicture = docsHtml.replace(Regex("<img[^>]*>"), "")
            val inserted = docsPlain.replace("\t", " ")
            assertTrue(vm.pasteStructured(original.id, inserted, plainText = "a different representation",
                htmlText = withoutPicture))
            val blocks = vm.state.value.blocks
            assertEquals(NoteDocBlockType.HEADING_1, blocks.first().type)
            assertTrue(blocks.any { it.type == NoteDocBlockType.BULLET && it.indent == 1 })
            assertTrue(blocks.any { it.type == NoteDocBlockType.TABLE })
            vm.undo()
            assertEquals(listOf(original), vm.state.value.blocks)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun picturesAreFetchedOffTheMainThreadPath() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-docs-pictures", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids,
                pasteDispatcher = UnconfinedTestDispatcher(testScheduler))
            val original = vm.state.value.blocks.single()
            assertTrue(vm.pasteStructured(original.id, docsPlain, docsPlain, docsHtml,
                imageResolver = { "stored-picture" }))
            assertEquals("stored-picture",
                vm.state.value.blocks.single { it.type == NoteDocBlockType.IMAGE }.imagePath)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

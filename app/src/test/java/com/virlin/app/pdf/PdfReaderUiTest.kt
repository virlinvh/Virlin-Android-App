package com.virlin.app.pdf

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.virlin.app.ui.pdf.PdfReader
import com.virlin.app.ui.pdf.PdfAnnotation
import com.virlin.app.ui.pdf.PdfAnnotationTool
import com.virlin.app.ui.pdf.PdfWorkspaceState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfReaderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pageNumbersStayInTheFloatingNavigatorNotBetweenPages() {
        compose.setContent {
            PdfReader(
                PdfWorkspaceState(absolutePath = "/missing-test.pdf", pageCount = 3,
                    displayName = "Test.pdf"),
                pagesOpen = false,
                onPageChanged = {},
                setPagesOpen = {},
            )
        }
        compose.onNodeWithText("1/3").assertExists()
        compose.onNodeWithText("1 / 3").assertDoesNotExist()
    }

    @Test fun annotationModeUsesTheSameReaderAndIconTools() {
        val added = mutableListOf<PdfAnnotation>()
        compose.setContent {
            PdfReader(
                PdfWorkspaceState(absolutePath = "/missing-test.pdf", pageCount = 2,
                    displayName = "Test.pdf"),
                pagesOpen = false,
                onPageChanged = {},
                setPagesOpen = {},
                annotationEnabled = true,
                onAddAnnotation = { added += it },
            )
        }
        compose.onNodeWithTag("pdf_document_scroll").assertExists()
        compose.onNodeWithContentDescription("Highlight").performClick()
        compose.onNodeWithTag("pdf_reader_page_0").performTouchInput { swipeRight() }
        compose.runOnIdle {
            assertTrue("Highlight tool should create a page annotation", added.any {
                it.page == 0 && it.tool == PdfAnnotationTool.HIGHLIGHT && it.points.isNotEmpty()
            })
        }
    }

    @Test fun zoomExpandsTheDocumentAndAllowsHorizontalTravel() {
        compose.setContent {
            PdfReader(
                PdfWorkspaceState(absolutePath = "/missing-test.pdf", pageCount = 3,
                    displayName = "Test.pdf"),
                pagesOpen = false,
                onPageChanged = {},
                setPagesOpen = {},
            )
        }
        compose.onNodeWithTag("pdf_reader_page_0").assertExists()
        val initialWidth = compose.onNodeWithTag("pdf_reader_page_0")
            .fetchSemanticsNode().boundsInRoot.width
        repeat(4) { compose.onNodeWithContentDescription("Zoom in").performClick() }
        compose.onNodeWithText("200%").assertExists()
        val enlargedWidth = compose.onNodeWithTag("pdf_reader_page_0")
            .fetchSemanticsNode().boundsInRoot.width
        assertTrue("Zoom should resize the actual document page", enlargedWidth > initialWidth)
        val initialX = compose.onNodeWithTag("pdf_document_pan")
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        compose.onNodeWithTag("pdf_document_pan").performTouchInput { swipeLeft() }
        val pannedX = compose.onNodeWithTag("pdf_document_pan")
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("Zoomed document should pan horizontally ($initialX -> $pannedX)", pannedX > initialX)
        val initialY = compose.onNodeWithTag("pdf_document_scroll")
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("pdf_document_scroll").performTouchInput { swipeUp() }
        val scrolledY = compose.onNodeWithTag("pdf_document_scroll")
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Zoomed document should keep scrolling vertically", scrolledY > initialY)
    }
}

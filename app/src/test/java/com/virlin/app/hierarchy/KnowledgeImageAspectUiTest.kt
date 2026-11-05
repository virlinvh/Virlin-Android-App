package com.virlin.app.hierarchy

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.ui.hierarchy.KnowledgeActions
import com.virlin.app.ui.hierarchy.KnowledgeGalleryCard
import com.virlin.app.ui.hierarchy.KnowledgeItem
import com.virlin.app.ui.hierarchy.KnowledgeType
import com.virlin.app.ui.hierarchy.VirlinProjectKnowledgeLibrary
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KnowledgeImageAspectUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun portraitAndLandscapeCardsPreserveTheirOwnImageRatios() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = AttachmentFileStore.attachmentsRoot(context)
        val files = listOf(200 to 400, 400 to 200).mapIndexed { index, (width, height) ->
            File.createTempFile("knowledge-aspect-$index-", ".png", root).also { file ->
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
        }
        try {
            val actions = KnowledgeActions({}, {}, { _, _, _ -> }, {}, {}, {}, {}, {},
                { _, _ -> }, { _, _, _ -> }, onMoveMany = { _, _, _ -> })
            compose.setContent {
                Column(Modifier.width(200.dp)) {
                    files.forEachIndexed { index, file ->
                        KnowledgeGalleryCard(
                            KnowledgeItem("image-$index", "project", KnowledgeType.IMAGE,
                                file.name, updatedAtMillis = 0, mediaPath = file.name),
                            ZoneId.of("UTC"), emptyMap(), { _, modifier -> Box(modifier) },
                            actions, false, false, {}, {}, {}, {}, {})
                    }
                }
            }
            compose.waitUntil(5000) {
                val portrait = compose.onNodeWithTag("knowledge_image_image-0", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                portrait.height > portrait.width * 1.8f
            }
            val portrait = compose.onNodeWithTag("knowledge_image_image-0", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val landscape = compose.onNodeWithTag("knowledge_image_image-1", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertEquals(0.5f, portrait.width / portrait.height, .02f)
            assertEquals(2f, landscape.width / landscape.height, .03f)
        } finally {
            files.forEach(File::delete)
        }
    }

    @Test fun nextCardFillsTheShorterColumnBesideATallImage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = AttachmentFileStore.attachmentsRoot(context)
        val file = File.createTempFile("knowledge-masonry-", ".png", root)
        val bitmap = Bitmap.createBitmap(200, 600, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val actions = KnowledgeActions({}, {}, { _, _, _ -> }, {}, {}, {}, {}, {},
                { _, _ -> }, { _, _, _ -> }, onMoveMany = { _, _, _ -> })
            compose.setContent {
                VirlinProjectKnowledgeLibrary("project", listOf(
                    KnowledgeItem("pdf", "project", KnowledgeType.DOCUMENT, "Document.pdf",
                        updatedAtMillis = 3, kindLabel = "PDF"),
                    KnowledgeItem("tall", "project", KnowledgeType.IMAGE, "Portrait",
                        updatedAtMillis = 2, mediaPath = file.name),
                    KnowledgeItem("note", "project", KnowledgeType.DOCUMENT, "Next note",
                        updatedAtMillis = 1),
                ), workstreams = emptyList(), tasks = emptyList(), actions = actions,
                    imagePreview = { _, modifier -> Box(modifier) })
            }
            compose.waitUntil(5000) {
                val image = compose.onNodeWithTag("knowledge_image_tall", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                image.height > image.width * 2.5f
            }
            val pdf = compose.onNodeWithTag("knowledge_card_pdf").fetchSemanticsNode().boundsInRoot
            val image = compose.onNodeWithTag("knowledge_card_tall").fetchSemanticsNode().boundsInRoot
            val note = compose.onNodeWithTag("knowledge_card_note").fetchSemanticsNode().boundsInRoot
            assertEquals(pdf.left, note.left, 1f)
            assertTrue("Next item should start below the short card", note.top >= pdf.bottom)
            assertTrue("Next item should fill the gap alongside the tall image", note.top < image.bottom)
        } finally {
            file.delete()
        }
    }
}

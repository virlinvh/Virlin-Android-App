package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeMediaWorkspaceTest {
    @Test fun reusedMediaWorkspacesAreProjectScopedAndPdfDraftHasProjectContext() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val now = Instant.EPOCH
            val repo = InMemoryWorkStreamRepository(seedProjects = listOf("p", "q").map { Project(it, it, createdAt = now, updatedAt = now) })
            val ids = SequentialIdProvider()
            val actions = DefaultVirlinActions(repo, FakeClock(now), ids)
            for (project in listOf("p", "q")) {
                actions.createAttachment("$project.png", "image/png", 32, "managed/$project.png", AttachmentKind.IMAGE, CaptureContext(projectId = project))
                actions.createAttachment("$project.zip", "application/zip", 32, "managed/$project.zip", AttachmentKind.ARCHIVE, CaptureContext(projectId = project))
            }
            val context = RuntimeEnvironment.getApplication()
            val images = com.virlin.app.ui.image.ImageWorkspaceViewModel(null, null, context, actions, repo, ids, projectId = "p")
            val files = com.virlin.app.ui.attachment.AttachmentWorkspaceViewModel("", context, actions, repo, ids, projectId = "p")
            val pdf = com.virlin.app.ui.pdf.PdfWorkspaceViewModel(null, null, context, actions, repo, ids, projectId = "p")
            advanceUntilIdle()
            assertEquals(listOf("p.png"), images.state.value.items.map { it.document.displayName })
            assertEquals(setOf("p.png", "p.zip"), files.state.value.rows.map { it.document.displayName }.toSet())
            assertEquals("p", pdf.state.value.context.projectId)
            assertNull(pdf.state.value.context.taskId)
            assertTrue(repo.tasks.value.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
}

package com.virlin.app.domain

import android.content.Intent
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CaptureUpdate
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.capture.LinkUrl
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.link.LinkEditorViewModel
import com.virlin.app.ui.link.LinkIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LinkCaptureTest {

    private val clock = FakeClock(Instant.parse("2026-09-14T12:00:00Z"))
    private val ids = SequentialIdProvider()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun validHttps() {
        val r = LinkUrl.parse("https://developer.android.com/")
        assertTrue(r is LinkUrl.Result.Valid)
        assertEquals("https://developer.android.com/", (r as LinkUrl.Result.Valid).canonical)
    }

    @Test fun validHttp() {
        assertTrue(LinkUrl.parse("http://example.com/path") is LinkUrl.Result.Valid)
    }

    @Test fun invalidSchemesRejected() {
        listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://media/1",
            "intent://scan/#Intent",
            "data:text/html,hi"
        ).forEach {
            assertEquals(LinkUrl.Result.Invalid, LinkUrl.parse(it))
        }
    }

    @Test fun emptyAndProseRejected() {
        assertEquals(LinkUrl.Result.Invalid, LinkUrl.parse(""))
        assertEquals(LinkUrl.Result.Invalid, LinkUrl.parse("   "))
        assertEquals(LinkUrl.Result.Invalid, LinkUrl.parse("not a url"))
        assertEquals(LinkUrl.Result.Invalid, LinkUrl.parse("github.com/x y"))
    }

    @Test fun domainShapedNormalizesToHttps() {
        val r = LinkUrl.parse("example.com")
        assertTrue(r is LinkUrl.Result.Valid)
        assertEquals("https://example.com", (r as LinkUrl.Result.Valid).canonical)
        assertEquals("https://example.com/docs", (LinkUrl.parse("example.com/docs") as LinkUrl.Result.Valid).canonical)
    }

    @Test fun hostDisplayAndCustomTitle() {
        assertEquals("developer.android.com", LinkUrl.displayUrl("https://www.developer.android.com/"))
        assertEquals(
            "Compose Documentation",
            LinkUrl.displayLabel("Compose Documentation", "https://developer.android.com/jetpack/compose")
        )
        assertEquals(
            "developer.android.com",
            LinkUrl.displayLabel(null, "https://developer.android.com/jetpack/compose")
        )
    }

    @Test fun createCapture_normalizesAndPersistsNoteTitle() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val r = actions.createCapture(
            CreateCapture(
                type = CaptureType.LINK,
                sourceUrl = "example.com/a",
                content = "Useful reference",
                title = "MBA Research Source"
            )
        )
        assertTrue(r is ActionResult.Success)
        val item = (r as ActionResult.Success).value
        assertEquals("https://example.com/a", item.sourceUrl)
        assertEquals("Useful reference", item.content)
        assertEquals("MBA Research Source", item.title)
    }

    @Test fun createCapture_rejectsInvalid() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        assertEquals(
            ActionResult.Rejected(DomainError.InvalidLink),
            actions.createCapture(CreateCapture(CaptureType.LINK, sourceUrl = "javascript:x"))
        )
    }

    @Test fun viewModel_pasteTransformsToCard() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = LinkEditorViewModel(null, actions, repo, ids, clock)
        vm.onUrlInputChange("https://developer.android.com/")
        assertFalse(vm.state.value.editingUrl)
        assertEquals("https://developer.android.com/", vm.state.value.canonicalUrl)
        assertTrue(vm.state.value.showCard)
        vm.onTitleChange("Android docs")
        assertEquals("Android docs", vm.state.value.displayTitle)
    }

    @Test fun viewModel_inboxCommitAndNoDuplicate() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = LinkEditorViewModel(null, actions, repo, ids, clock)
        vm.onUrlInputChange("https://example.com/")
        vm.onTitleChange("Ex")
        val first = vm.prepareExit()
        assertTrue(first.navigate)
        assertTrue(first.showInboxFeedback)
        assertTrue(vm.state.value.committedToInbox)
        val id = vm.state.value.captureId!!
        assertEquals(1, repo.captures.value.count { it.type == CaptureType.LINK })

        vm.onNoteChange("updated note")
        val second = vm.prepareExit()
        assertTrue(second.navigate)
        assertFalse(second.showInboxFeedback)
        assertEquals(1, repo.captures.value.count { it.id == id })
        assertEquals("updated note", repo.getCapture(id)!!.content)
    }

    @Test fun viewModel_emptyDraftDiscard() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = LinkEditorViewModel(null, actions, repo, ids, clock)
        val exit = vm.prepareExit()
        assertTrue(exit.navigate)
        assertFalse(exit.showInboxFeedback)
        assertEquals(0, repo.captures.value.size)
    }

    @Test fun legacyLinkHydrationAndEditSameItem() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val created = (actions.createCapture(
            CreateCapture(
                type = CaptureType.LINK,
                sourceUrl = "https://github.com/virlin/app",
                content = "the repo",
                title = "GitHub",
                context = CaptureContext.None
            )
        ) as ActionResult.Success).value

        val vm = LinkEditorViewModel(created.id, actions, repo, ids, clock)
        advanceUntilIdle()
        assertEquals("https://github.com/virlin/app", vm.state.value.canonicalUrl)
        assertEquals("GitHub", vm.state.value.title)
        assertEquals("the repo", vm.state.value.note)
        assertTrue(vm.state.value.committedToInbox)

        vm.onUrlInputChange("https://github.com/virlin/app/pull/1")
        vm.prepareExit()
        val updated = repo.getCapture(created.id)!!
        assertEquals("https://github.com/virlin/app/pull/1", updated.sourceUrl)
        assertEquals(1, repo.captures.value.size)
    }

    @Test fun intents_actionViewNoPackage() {
        val view = LinkIntents.viewIntent("https://developer.android.com/")!!
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("https://developer.android.com/", view.data?.toString())
        assertNull(view.`package`)

        val share = LinkIntents.shareIntent("https://developer.android.com/")!!
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("https://developer.android.com/", share.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(share.`package`)

        assertNull(LinkIntents.viewIntent("javascript:alert(1)"))
    }

    @Test fun contextPersistenceOnCreate() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val project = (actions.createProject(
            com.virlin.app.domain.action.CreateProject(title = "P1")
        ) as ActionResult.Success).value
        val r = actions.createCapture(
            CreateCapture(
                type = CaptureType.LINK,
                sourceUrl = "https://example.com",
                context = CaptureContext(projectId = project.id)
            )
        )
        assertTrue(r is ActionResult.Success)
        assertEquals(project.id, (r as ActionResult.Success).value.projectId)
    }
}

package com.virlin.app.domain

import android.content.Intent
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CaptureUpdate
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.capture.LinkUrl
import com.virlin.app.domain.capture.LinkDocument
import com.virlin.app.domain.capture.LinkDocumentCodec
import com.virlin.app.domain.capture.LinkPresentation
import com.virlin.app.domain.capture.LinkProvider
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.link.LinkEditorViewModel
import com.virlin.app.ui.link.LinkIntents
import com.virlin.app.ui.link.youtubePlayerHtml
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
        vm.save(); advanceUntilIdle()
        assertTrue(vm.state.value.committedToInbox)
        val id = vm.state.value.captureId!!
        assertEquals(1, repo.captures.value.count { it.type == CaptureType.LINK })

        vm.edit(); vm.onNoteChange("updated note"); vm.save(); advanceUntilIdle()
        assertEquals(1, repo.captures.value.count { it.id == id })
        assertEquals("updated note", LinkDocumentCodec.decode(repo.getCapture(id)!!.content).note)
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

        vm.edit(); vm.onUrlInputChange("https://github.com/virlin/app/pull/1"); vm.save(); advanceUntilIdle()
        val updated = repo.getCapture(created.id)!!
        assertEquals("https://github.com/virlin/app/pull/1", updated.sourceUrl)
        assertEquals(1, repo.captures.value.size)
    }

    @Test fun linkDocument_roundTripsAndLegacyNoteStillLoads() {
        val doc = LinkDocument("reference", showPreview = false, playbackEnabled = true, startSeconds = 135, endSeconds = 510)
        assertEquals(doc, LinkDocumentCodec.decode(LinkDocumentCodec.encode(doc)))
        assertEquals(LinkDocument(note = "legacy note"), LinkDocumentCodec.decode("legacy note"))
    }

    @Test fun youtubePreviewAndStartTimeAreDeterministic() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val preview = LinkPresentation.preview(url)
        assertEquals(LinkProvider.YOUTUBE, preview.provider)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", preview.thumbnailUrl)
        val playable = LinkPresentation.playableUrl(url, LinkDocument(playbackEnabled = true, startSeconds = 135, endSeconds = 510))
        assertTrue(playable.contains("t=135s"))
        assertFalse(playable.contains("510")) // external YouTube end behavior is intentionally not promised
    }

    @Test fun youtubeIds_coverWatchShortAndEmbed_withoutAcceptingLookalikeHosts() {
        assertEquals("dQw4w9WgXcQ", LinkPresentation.youtubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", LinkPresentation.youtubeVideoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", LinkPresentation.youtubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertNull(LinkPresentation.youtubeVideoId("https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ"))
    }

    @Test fun embeddedPlayer_cuesExactSavedSegment_andDoesNotAutoplay() {
        val html = youtubePlayerHtml("dQw4w9WgXcQ", 135, 510)
        assertTrue(html.contains("cueVideoById"))
        assertTrue(html.contains("startSeconds:135"))
        assertTrue(html.contains("endSeconds:510"))
        assertFalse(html.contains("playVideo()"))
        assertFalse(html.contains("addJavascriptInterface"))
    }

    @Test fun embeddedPlayer_omitsInvalidEndBoundary() {
        val html = youtubePlayerHtml("dQw4w9WgXcQ", 510, 135)
        assertTrue(html.contains("startSeconds:510"))
        assertFalse(html.contains("endSeconds:"))
    }

    @Test fun timeParsingRejectsMalformedAndEndBeforeStart() {
        assertEquals(135, LinkPresentation.parseTime("02:15"))
        assertNull(LinkPresentation.parseTime("2:75"))
        val state = com.virlin.app.ui.link.LinkUiState(
            canonicalUrl = "https://youtu.be/dQw4w9WgXcQ", playbackEnabled = true,
            startInput = "08:30", endInput = "02:15"
        )
        assertFalse(state.isTimeValid)
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

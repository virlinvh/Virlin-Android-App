package com.virlin.app.platform

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteDeepLinksTest {

    @Test
    fun roundTripsTaskProjectGlobalAndCaptureOwners() {
        listOf(
            "global",
            "task-t-11111111-2222-3333-4444-555555555555",
            "project-p-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            "capture-c-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            "capture-legacy-c-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        ).forEach { owner ->
            val uri = NoteDeepLinks.uriFor(owner)
            assertNotNull("uri for $owner", uri)
            assertEquals(owner, NoteDeepLinks.ownerKeyFromUri(uri))
        }
    }

    @Test
    fun roundTripsKnowledgeDraftOwnersWithSlashes() {
        val project = com.virlin.app.domain.notedoc.CaptureNoteBridge.draftOwner("proj-1", "cap-9")
        val stream = com.virlin.app.domain.notedoc.CaptureNoteBridge.draftWorkstreamOwner(
            "proj-1", "ws-2", "cap-9",
        )
        assertTrue(project.contains('/'))
        assertTrue(stream.contains('/'))
        assertEquals(project, NoteDeepLinks.ownerKeyFromUri(NoteDeepLinks.uriFor(project)))
        assertEquals(stream, NoteDeepLinks.ownerKeyFromUri(NoteDeepLinks.uriFor(stream)))
    }

    @Test
    fun rejectsMalformedOrHostileOwnerKeys() {
        assertNull(NoteDeepLinks.uriFor(""))
        assertNull(NoteDeepLinks.uriFor(" "))
        assertNull(NoteDeepLinks.uriFor("../etc/passwd"))
        assertNull(NoteDeepLinks.uriFor("task-"))
        assertNull(NoteDeepLinks.uriFor("task-with spaces"))
        assertNull(NoteDeepLinks.uriFor("javascript:alert(1)"))
        assertNull(NoteDeepLinks.uriFor("task-\u0000x"))
        assertNull(NoteDeepLinks.uriFor("x".repeat(600)))
        assertFalse(NoteDeepLinks.isValidOwnerKey("notes/../admin"))
    }

    @Test
    fun rejectsForeignSchemesAndHosts() {
        assertNull(NoteDeepLinks.ownerKeyFromUri(Uri.parse("https://example.com/note/abc")))
        assertNull(NoteDeepLinks.ownerKeyFromUri(Uri.parse("virlin://attention/stream-1")))
        assertNull(NoteDeepLinks.ownerKeyFromUri(Uri.parse("virlin://note/")))
        assertNull(NoteDeepLinks.ownerKeyFromUri(Uri.parse("virlin://note/!!!not-base64!!!")))
    }

    @Test
    fun shareIntentCarriesLinkWithoutMutatingDomain() {
        val owner = "task-t-11111111-2222-3333-4444-555555555555"
        val intent = NoteDeepLinks.shareIntent(owner, "Natural Language")
        assertNotNull(intent)
        val text = intent!!.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty()
        assertTrue(text.contains("virlin://note/"))
        assertTrue(text.contains("Natural Language"))
        assertEquals(owner, intent.getStringExtra(NoteDeepLinks.EXTRA_OWNER_KEY))
        assertEquals("text/plain", intent.type)
    }
}

package com.virlin.app.ui.notes

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The Google Docs app shares its formatted copy as a content: URI; its text/html stream is read. */
@RunWith(RobolectricTestRunner::class)
class GoogleDocsClipboardProviderTest {
    class HtmlProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/html"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = File.createTempFile("clip", ".html").apply { writeText(served) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

        companion object { var served = "" }
    }

    private val authority = "com.google.android.apps.docs.editors.kix.editors.clipboard"
    private val uri = Uri.parse("content://$authority/text/html")
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun clip(): Pair<ClipData, ClipData.Item> {
        Robolectric.setupContentProvider(HtmlProvider::class.java, authority)
        val item = ClipData.Item(uri)
        val clip = ClipData(ClipDescription("docs", arrayOf("text/plain", "text/html")), item)
        return clip to item
    }

    @Test fun docsHtmlIsReadFromTheClipboardProvider() {
        val html = "<meta charset=\"utf-8\"><b style=\"font-weight:normal;\" id=\"docs-internal-guid-1\">" +
            "<h1><span>Builder's English</span></h1><p><span>Body</span></p></b>"
        HtmlProvider.served = html
        val (clip, item) = clip()
        assertEquals(html, googleDocsProviderHtml(context, clip, item))
    }

    @Test fun otherProviderHtmlIsIgnored() {
        HtmlProvider.served = "<p>From another app</p>"
        val (clip, item) = clip()
        assertNull(googleDocsProviderHtml(context, clip, item))
    }

    @Test fun clipsWithoutAContentUriAreIgnored() {
        val item = ClipData.Item("plain text")
        val clip = ClipData(ClipDescription("text", arrayOf("text/plain")), item)
        assertNull(googleDocsProviderHtml(context, clip, item))
    }
}

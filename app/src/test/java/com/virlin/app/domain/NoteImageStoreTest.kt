package com.virlin.app.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.data.noteimage.NoteImageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NoteImageStoreTest {
    @Test fun pickedImageIsCopiedIntoPrivateStorageAndCanBeReopened() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "note-image-store-test.png")
        Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        try {
            val name = NoteImageStore.import(context, Uri.fromFile(source))
            val stored = NoteImageStore.resolve(context, name)
            assertNotNull(stored)
            assertEquals(4, BitmapFactory.decodeFile(stored!!.absolutePath).width)
            assertEquals(3, BitmapFactory.decodeFile(stored.absolutePath).height)
            assertNull(NoteImageStore.resolve(context, "../outside"))
            stored.delete()
        } finally {
            source.delete()
        }
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }
}

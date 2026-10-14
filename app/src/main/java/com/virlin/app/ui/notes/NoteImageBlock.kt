package com.virlin.app.ui.notes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.data.noteimage.NoteImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared in-editor and preview rendering of the saved image block. */
@Composable
fun NoteImageBlock(path: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val file = path?.let { NoteImageStore.resolve(context, it) }
            if (file?.isFile != true) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val options = BitmapFactory.Options().apply {
                inSampleSize = generateSequence(1) { it * 2 }
                    .first { bounds.outWidth / it <= 1400 && bounds.outHeight / it <= 1400 }
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "Image in note",
            contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
                .aspectRatio(image.width.toFloat() / image.height.toFloat())
                .testTag("notes_image_block"),
        )
    } else {
        Text("Image unavailable", color = NotesMuted, fontSize = 13.sp,
            modifier = modifier.fillMaxWidth().padding(24.dp).testTag("notes_image_block"))
    }
}

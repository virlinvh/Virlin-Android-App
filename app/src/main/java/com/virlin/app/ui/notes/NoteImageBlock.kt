package com.virlin.app.ui.notes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

/**
 * Shared in-editor and preview rendering of the saved image block. In the editor [onClick]
 * selects the image for its tools, and [selected] outlines it.
 */
@Composable
fun NoteImageBlock(path: String?, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(6.dp)
    val interaction = if (onClick == null) Modifier else Modifier
        .clip(shape)
        .border(if (selected) 2.5.dp else 0.dp, if (selected) NotesAccent else Color.Transparent, shape)
        .clickable(onClickLabel = "Edit image", role = Role.Image, onClick = onClick)
        .semantics { this.selected = selected }
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
            contentDescription = if (selected) "Image in note, selected" else "Image in note",
            contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
                .aspectRatio(image.width.toFloat() / image.height.toFloat())
                .then(interaction)
                .testTag("notes_image_block"),
        )
    } else {
        Text("Image unavailable", color = NotesMuted, fontSize = 13.sp,
            modifier = modifier.fillMaxWidth().then(interaction).padding(24.dp).testTag("notes_image_block"))
    }
}

package com.virlin.app.ui.map.preview

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.data.mappreview.MapPreviewImageStore
import com.virlin.app.domain.mappreview.FormatMark
import com.virlin.app.domain.mappreview.FormattedText
import com.virlin.app.domain.mappreview.MapPreviewImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MapPreviewExpandedTag = "map_preview_expanded"
fun mapPreviewDotTag(index: Int) = "map_preview_dot_$index"

private val Emerald = Color(0xFF0E7A54)
private val Ink = Color(0xFF192229)
private val Muted = Color(0xFF637283)

/** Turns the stored runs into the spans Compose draws. Only bold and italic exist. */
fun annotatedDescription(description: FormattedText): AnnotatedString = buildAnnotatedString {
    append(description.text)
    description.normalized().runs.forEach { run ->
        val start = run.start.coerceIn(0, description.text.length)
        val end = run.end.coerceIn(start, description.text.length)
        if (end <= start) return@forEach
        addStyle(
            SpanStyle(
                fontWeight = if (FormatMark.BOLD in run.marks) FontWeight.Bold else null,
                fontStyle = if (FormatMark.ITALIC in run.marks) FontStyle.Italic else null,
            ),
            start, end,
        )
    }
}

/**
 * Loads one managed preview image, bounded.
 *
 * The decoded Bitmap is handed to Compose through `asImageBitmap()`, which wraps it **without
 * copying**. It must therefore never be recycled here: a RenderNode display list can replay after
 * this composable leaves, and recycling is exactly what crashed the Image workspace with
 * "Canvas: trying to use a recycled bitmap". With `minSdk 26` the bytes are garbage collected, so
 * no explicit release is needed or wanted.
 *
 * A file that is missing or unreadable renders an honest placeholder rather than an empty box, so a
 * preview never silently pretends an image is still there.
 */
@Composable
fun MapPreviewImageView(image: MapPreviewImage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(image.relativePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image.relativePath) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val file = MapPreviewImageStore.resolve(context, image.relativePath)
                if (!file.isFile || file.length() <= 0L) return@runCatching null
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(loaded, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Color(0xFFEFF2F0)), Alignment.Center) {
            Icon(
                Icons.Outlined.BrokenImage,
                contentDescription = "This image is no longer available",
                tint = Muted.copy(alpha = .7f),
            )
        }
    }
}

/**
 * The expanded preview shown inside a selected node.
 *
 * This is the read-only face of a preview: formatted description, swipeable carousel, pagination
 * dots, and no editing affordance of any kind. It is a Compose overlay rather than something drawn
 * into the map's Canvas because only the selected node can be expanded, so one overlay exists at
 * most — the map keeps costing no composables per node.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MapNodeExpandedPreview(
    description: FormattedText,
    images: List<MapPreviewImage>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag(MapPreviewExpandedTag)) {
        if (!description.isBlank) {
            Text(
                annotatedDescription(description),
                fontSize = 13.sp,
                color = Ink.copy(alpha = .86f),
                lineHeight = 18.sp,
            )
        }
        if (images.isNotEmpty()) {
            if (!description.isBlank) Spacer(Modifier.height(8.dp))
            val pager = rememberPagerState(pageCount = { images.size })
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .clip(RoundedCornerShape(12.dp)),
            ) { page ->
                MapPreviewImageView(
                    images[page],
                    Modifier
                        .fillMaxSize()
                        // Each page announces its position for a screen reader.
                        .semantics {
                            contentDescription = "Image ${page + 1} of ${images.size}"
                        },
                )
            }
            if (images.size > 1) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    images.indices.forEach { index ->
                        val selected = index == pager.currentPage
                        Box(
                            Modifier
                                .padding(horizontal = 2.5f.dp)
                                .size(if (selected) 6.dp else 5.dp)
                                .clip(CircleShape)
                                .background(if (selected) Emerald else Muted.copy(alpha = .35f))
                                .testTag(mapPreviewDotTag(index)),
                        )
                    }
                }
            }
        }
    }
}

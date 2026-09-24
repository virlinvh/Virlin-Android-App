package com.virlin.app.ui.components

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.data.projecticon.ProjectIconStore
import com.virlin.app.domain.model.ProjectIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val ProjectIconTestTag = "project_icon"
const val ProjectIconImageTestTag = "project_icon_image"
const val ProjectIconFallbackTestTag = "project_icon_fallback"
fun projectIconBuiltInTag(id: String) = "project_icon_builtin_$id"

/**
 * Deterministic fallback avatar colours for a project: a soft tinted container with dark
 * initials, both derived from the project id's stable hue. Pure — testable without Compose UI.
 */
object ProjectIconFallback {
    fun container(projectId: String): Color = Color.hsl(ProjectIdentity.hue(projectId).toFloat(), 0.42f, 0.90f)
    fun foreground(projectId: String): Color = Color.hsl(ProjectIdentity.hue(projectId).toFloat(), 0.40f, 0.30f)
}

/**
 * Small process-wide cache of decoded, downsampled icons keyed by `path|mtime|targetPx`, so the
 * same project icon shown on several rows decodes once — and a recomposition (timer tick,
 * urgency animation) never touches the decoder at all.
 */
private object ProjectIconCache {
    private val cache = object : LruCache<String, ImageBitmap>(24) {}
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, bmp: ImageBitmap) { cache.put(key, bmp) }
}

/**
 * ONE reusable project identity presentation: the custom image (centre-cropped into a circle)
 * when [iconPath] resolves to a readable image, otherwise the deterministic fallback avatar
 * (tinted container + initials from [name]). Never shows a broken-image state; a missing or
 * corrupt file simply renders the fallback.
 *
 * Semantics: the whole icon is one node described as "<name> project icon" — pass
 * [decorative] = true where the surrounding row already speaks the project name, so a screen
 * reader does not announce it twice.
 */
/** Convenience: render a [com.virlin.app.domain.model.Project]'s identity with the standard priority. */
@Composable
fun ProjectIcon(
    project: com.virlin.app.domain.model.Project,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    decorative: Boolean = false
) = ProjectIcon(projectId = project.id, name = project.title, iconPath = project.iconPath, iconId = project.iconId, size = size, modifier = modifier, decorative = decorative)

@Composable
fun ProjectIcon(
    projectId: String,
    name: String,
    iconPath: String?,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    decorative: Boolean = false,
    /** Chosen built-in icon id; null = automatic (`ProjectIconCatalog.autoIconId`). */
    iconId: String? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val targetPx = with(density) { size.roundToPx() }
    val custom = ProjectIdentity.hasCustomIcon(iconPath)

    // Decode off the main thread, once per (path, mtime, size); the result is remembered by key.
    val image by produceState<ImageBitmap?>(initialValue = null, iconPath, targetPx) {
        if (!custom) { value = null; return@produceState }
        val path = iconPath!!
        val file = ProjectIconStore.resolve(context, path)
        val key = "$path|${file.lastModified()}|$targetPx"
        ProjectIconCache.get(key)?.let { value = it; return@produceState }
        value = withContext(Dispatchers.IO) {
            ProjectIconStore.decodeForDisplay(context, path, targetPx)?.asImageBitmap()?.also { ProjectIconCache.put(key, it) }
        }
    }

    val semanticsModifier = if (decorative) Modifier else Modifier.semantics { contentDescription = "$name project icon" }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .testTag(ProjectIconTestTag)
            .then(semanticsModifier),
        contentAlignment = Alignment.Center
    ) {
        val bmp = image
        // Priority: custom image → chosen built-in → automatic built-in → initials (last safety net).
        val builtIn = BuiltInProjectIcons.lookOf(
            if (com.virlin.app.domain.model.ProjectIconCatalog.isKnown(iconId)) iconId
            else com.virlin.app.domain.model.ProjectIconCatalog.autoIconId(name, projectId)
        )
        if (custom && bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,        // never stretched; transparent PNG/WebP keeps alpha
                modifier = Modifier.size(size).testTag(ProjectIconImageTestTag)
            )
        } else if (builtIn != null) {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(builtIn.surface, CircleShape)
                    .testTag(projectIconBuiltInTag(builtIn.id)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    builtIn.icon, contentDescription = null, tint = builtIn.symbol,
                    modifier = Modifier.size(size * 0.54f)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(ProjectIconFallback.container(projectId), CircleShape)
                    .testTag(ProjectIconFallbackTestTag),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ProjectIdentity.initials(name),
                    color = ProjectIconFallback.foreground(projectId),
                    fontSize = (size.value * 0.36f).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.2.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/** Test seam: whether a bitmap for this path is already decoded and cached at this size. */
internal fun projectIconCached(path: String, mtime: Long, targetPx: Int): Boolean =
    ProjectIconCache.get("$path|$mtime|$targetPx") != null

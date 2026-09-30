package com.virlin.app.ui.apps

import com.virlin.app.R
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Content for the existing Virlin Apps tab. Put this INSIDE the current app Scaffold.
 * The host continues to own the persistent bottom navigation, Orb and routing.
 * All four icons are bitmap drawables so their approved 3D artwork is preserved.
 */
@Composable
fun VirlinAppsScreen(
    onOpenInbox: () -> Unit,
    onOpenMindMaps: () -> Unit,
    onOpenPages: () -> Unit,
    modifier: Modifier = Modifier,
    flashcardsAvailable: Boolean = false,
    onOpenFlashcards: () -> Unit = {},
    animateIcons: Boolean = true,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val apps = listOf(
        AppEntry("inbox", "Inbox", R.drawable.virlin_app_inbox, onOpenInbox),
        AppEntry("maps", "Mind Maps", R.drawable.virlin_app_mind_maps, onOpenMindMaps),
        AppEntry("cards", "Flashcards", R.drawable.virlin_app_flashcards, onOpenFlashcards),
        AppEntry("pages", "Pages", R.drawable.virlin_app_pages, onOpenPages),
    )
    val visible = apps.filter { it.title.contains(query.trim(), ignoreCase = true) }

    Column(modifier.fillMaxSize().testTag(AppsScreenTag).background(VirlinCream)) {
        AppsHeader(
            searching = searching,
            query = query,
            onQueryChange = { query = it },
            onToggleSearch = {
                searching = !searching
                if (!searching) query = ""
            },
        )
        Text(
            text = "Your apps",
            color = MutedInk,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 18.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (visible.isEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Text(
                        "No apps found",
                        color = MutedInk,
                        fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            items(visible, key = { it.id }) { app ->
                val available = app.id != "cards" || flashcardsAvailable
                AppTile(app, available, animateIcons)
            }
        }
    }
}

private data class AppEntry(
    val id: String,
    val title: String,
    @DrawableRes val icon: Int,
    val onOpen: () -> Unit,
)

private val VirlinCream = Color(0xFFF9F8F5)
private val VirlinInk = Color(0xFF192019)
private val MutedInk = Color(0xFF565D59)

@Composable
private fun AppsHeader(
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 18.dp, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Apps", color = VirlinInk, fontWeight = FontWeight.Bold, fontSize = 36.sp,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleSearch) {
            Icon(
                imageVector = if (searching) Icons.Default.Close else Icons.Default.Search,
                contentDescription = if (searching) "Close app search" else "Search apps",
                tint = VirlinInk,
                modifier = Modifier.size(27.dp),
            )
        }
    }
    if (searching) {
        // Opening search should let you type straight away rather than ask for a second tap.
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = VirlinInk, fontSize = 16.sp,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)
                .focusRequester(focus)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFF0F1EE))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Search your apps", color = MutedInk, fontSize = 16.sp)
                    inner()
                }
            },
        )
    } else {
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun AppTile(app: AppEntry, available: Boolean, animate: Boolean) {
    val label = if (available) app.title else "${app.title}, coming soon"
    val tileModifier = if (available) {
        Modifier.clickable(role = Role.Button, onClick = app.onOpen)
    } else {
        Modifier.semantics { disabled() }
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(appTileTag(app.id)).then(tileModifier)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(app.icon),
            contentDescription = null, // The whole tile supplies the accessible label.
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(122.dp).then(iconMovement(app.id, animate)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = VirlinInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (!available) {
            Spacer(Modifier.height(2.dp))
            Text("Coming soon", fontSize = 14.sp, color = MutedInk)
        }
    }
}

/** Tiny movements of the bitmap as a whole; no repainting of approved artwork. */
@Composable
private fun iconMovement(id: String, enabled: Boolean): Modifier {
    if (!enabled) return Modifier
    val transition = rememberInfiniteTransition(label = "${id} icon motion")
    val movement by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (id) {
                    "inbox" -> 2700
                    "maps" -> 3200
                    "cards" -> 3000
                    else -> 3600
                },
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${id} movement",
    )
    val density = LocalDensity.current
    return Modifier.graphicsLayer {
        when (id) {
            "inbox" -> translationY = with(density) { (-2.0f * movement).dp.toPx() }
            "maps" -> {
                scaleX = 1f + 0.014f * movement
                scaleY = 1f + 0.014f * movement
            }
            "cards" -> rotationZ = -0.45f + 0.9f * movement
            "pages" -> translationY = with(density) { (-1.2f * movement).dp.toPx() }
        }
    }
}

const val AppsScreenTag = "apps_screen"
fun appTileTag(id: String) = "app_tile_$id"

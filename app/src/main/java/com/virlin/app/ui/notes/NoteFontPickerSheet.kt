package com.virlin.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

/** A tab of the picker; null category = the curated Featured view. */
private data class FontTab(val label: String, val category: NoteFontCategory?)

private val fontTabs = listOf(
    FontTab("Featured", null),
    FontTab("Handwriting", NoteFontCategory.HANDWRITING),
    FontTab("Creative", NoteFontCategory.DISPLAY),
    FontTab("Serif", NoteFontCategory.SERIF),
    FontTab("Sans serif", NoteFontCategory.SANS),
    FontTab("Monospace", NoteFontCategory.MONO),
)

private sealed interface FontListItem {
    data class Header(val title: String) : FontListItem
    data class Entry(val font: NoteFont, val section: String = "") : FontListItem
}

/**
 * Font chooser for the note editor. Every row shows the family's own name drawn in that family,
 * so the choice is visible before it is applied. Picking calls [onPick] with the stored name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteFontPickerSheet(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(0) }
    val items = remember(query, tab) {
        if (query.isNotBlank()) NoteFonts.search(query).map { FontListItem.Entry(it) }
        else when (val category = fontTabs[tab].category) {
            null -> NoteFonts.featured.flatMap { (title, fonts) ->
                listOf(FontListItem.Header(title)) + fonts.map { FontListItem.Entry(it, title) }
            }
            else -> NoteFonts.inCategory(category).map { FontListItem.Entry(it) }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(query, tab) { listState.scrollToItem(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.86f).testTag("notes_dialog")) {
            Row(Modifier.padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Font", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Current  ", color = Muted, fontSize = 12.sp)
                        Text(current, color = Green, fontSize = 15.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontFamily = NoteFonts.familyFor(current))
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp).testTag("notes_font_close")) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Ink)
                }
            }
            SearchField(query, { query = it }, Modifier.padding(horizontal = 18.dp).padding(top = 10.dp))
            if (query.isBlank()) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fontTabs.forEachIndexed { i, t ->
                    val count = t.category?.let { NoteFonts.inCategory(it).size }
                    TabChip(if (count == null) t.label else "${t.label} $count", i == tab) { tab = i }
                }
            } else Text(
                if (items.isEmpty()) "No fonts match \u201c${query.trim()}\u201d" else if (items.size == 1) "1 matching font" else "${items.size} matching fonts",
                color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).testTag("notes_font_list"),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 10.dp, bottom = 24.dp),
            ) {
                items(items, key = { item ->
                    when (item) {
                        is FontListItem.Header -> "h:${item.title}"
                        is FontListItem.Entry -> "f:${item.section}:${item.font.category}:${item.font.name}"
                    }
                }) { item ->
                    when (item) {
                        is FontListItem.Header -> Text(item.title.uppercase(), color = Muted, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 4.dp))
                        is FontListItem.Entry -> FontRow(item.font, item.font.name == current) { onPick(item.font.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF6F8F7)).border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search ${NoteFonts.totalCount} fonts", color = Muted, fontSize = 14.sp)
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 14.sp), cursorBrush = SolidColor(Ink),
                modifier = Modifier.fillMaxWidth().testTag("notes_font_search")
                    .semantics { contentDescription = "Search fonts" },
            )
        }
        if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = Muted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        Modifier.clip(shape).background(if (selected) Mint else Color.White)
            .border(1.dp, if (selected) Green.copy(alpha = .38f) else Border, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("notes_font_tab_$label")
            .padding(horizontal = 12.dp, vertical = 7.dp),
        color = if (selected) Green else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
    )
}

@Composable
private fun FontRow(font: NoteFont, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Mint else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${font.name}, ${font.caption}"; this.selected = selected }
            .testTag("notes_font_${font.name}")
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            font.name, Modifier.weight(1f),
            color = if (selected) Green else Ink, fontSize = 20.sp, lineHeight = 26.sp,
            fontFamily = NoteFonts.familyFor(font.name), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
        else Text(font.caption, color = Muted, fontSize = 11.sp, maxLines = 1)
    }
}

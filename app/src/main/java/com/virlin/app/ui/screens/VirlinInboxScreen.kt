package com.virlin.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val InboxCaptureTextTag = "inbox_capture_text"
const val InboxCaptureVoiceTag = "inbox_capture_voice"
const val InboxSelectTag = "inbox_select"
fun inboxTabTag(tab: InboxTab) = "inbox_tab_${tab.name.lowercase()}"

enum class InboxTab { INBOX, ORGANIZED, ARCHIVED }

/**
 * One row, projected from a persisted capture. [id] is the capture's own id; nothing here is a
 * fixture and nothing is stored by this screen.
 */
data class InboxUiItem(
    val id: String,
    val title: String,
    val kind: String,
    val timeLabel: String,
    val tab: InboxTab,
    val icon: ImageVector = Icons.Default.Description,
    /** The project / workstream an organized capture is attached to, when it has one. */
    val contextLabel: String? = null,
)

private val Blue = Color(0xFF32689A)
private val BluePale = Color(0xFFEAF2FB)
private val BlueLine = Color(0xFFC9DBF0)
private val Ink = Color(0xFF17212A)
private val Muted = Color(0xFF6D7A87)
private val Line = Color(0xFFE0E5EA)

/**
 * Content only: the app shell keeps the bottom navigation and the Orb in their own green, and
 * this composable draws neither. The tab lives with the caller's view model so the counts and
 * rows stay the domain's, not a copy.
 */
@Composable
fun VirlinInboxScreen(
    items: List<InboxUiItem>,
    inboxCount: Int,
    tab: InboxTab,
    onTabChange: (InboxTab) -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOrganize: (String) -> Unit,
    onMenu: (String) -> Unit,
    onCaptureText: () -> Unit,
    onCaptureVoice: (() -> Unit)?,
    onBulkOrganize: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    // A plain set: the selection is per-visit and does not need to outlive the screen.
    val selected = remember { mutableStateListOf<String>() }

    val visible = remember(items, query) {
        items.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }
    // A selection can only ever contain items that are still on this tab.
    // A selection can only ever contain items that are still on this tab: after an organize
    // succeeds the rows change, and anything that left must drop out of the selection.
    LaunchedEffect(items) {
        val live = items.mapTo(HashSet()) { it.id }
        selected.retainAll { it in live }
    }

    Column(modifier.fillMaxSize().background(Color.White).testTag(InboxScreenTag)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Apps", tint = Ink)
            }
            Text(
                "Inbox", Modifier.weight(1f), fontSize = 22.sp,
                fontWeight = FontWeight.Bold, color = Ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { search = !search; if (!search) query = "" }) {
                Icon(Icons.Default.Search, if (search) "Close search" else "Search inbox", tint = Ink)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().background(Color.White),
            // The Orb floats above the footer bottom-right; the last card clears it.
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Spacer(Modifier.height(22.dp))
                    Text(
                        "Capture now. Place it later.", fontSize = 25.sp, lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold, color = Ink
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Captured, not yet organized · save more from the Orb",
                        fontSize = 13.sp, color = Muted
                    )
                    Spacer(Modifier.height(23.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        color = Color(0xFFF8FBFF), border = BorderStroke(1.dp, BlueLine),
                        shape = RoundedCornerShape(29.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = onCaptureText,
                                modifier = Modifier.size(44.dp).testTag(InboxCaptureTextTag),
                                color = BluePale, shape = CircleShape
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Add, "Capture a note", tint = Blue)
                                }
                            }
                            Text(
                                "Capture a thought…",
                                Modifier.weight(1f)
                                    .clickable(role = Role.Button, onClick = onCaptureText)
                                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                                fontSize = 15.sp, color = Muted, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // The microphone appears only when voice capture really exists.
                            if (onCaptureVoice != null) {
                                VerticalDivider(Modifier.height(26.dp), color = BlueLine)
                                IconButton(
                                    onClick = onCaptureVoice,
                                    modifier = Modifier.testTag(InboxCaptureVoiceTag)
                                ) { Icon(Icons.Default.Mic, "Capture voice", tint = Blue) }
                            }
                        }
                    }
                    if (search) {
                        Spacer(Modifier.height(12.dp))
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        OutlinedTextField(
                            query, { query = it },
                            Modifier.fillMaxWidth().focusRequester(focus).testTag("inbox_search"),
                            label = { Text("Search items") }, singleLine = true
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.Bottom) {
                    InboxTab.entries.forEach { option ->
                        val active = option == tab
                        val label = when (option) {
                            InboxTab.INBOX -> "INBOX · $inboxCount"
                            InboxTab.ORGANIZED -> "ORGANIZED"
                            InboxTab.ARCHIVED -> "ARCHIVED"
                        }
                        Column(
                            Modifier.weight(1f).fillMaxHeight()
                                .testTag(inboxTabTag(option))
                                .semantics {
                                    contentDescription = label
                                    this.selected = active
                                }
                                .clickable(role = Role.Tab) {
                                    onTabChange(option)
                                    selecting = false
                                    selected.clear()
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (active) Blue else Muted, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(12.dp))
                            Box(
                                Modifier.fillMaxWidth().height(3.dp)
                                    .background(if (active) Blue else Color.Transparent)
                            )
                        }
                    }
                }
                HorizontalDivider(color = Line)
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (tab) {
                            InboxTab.INBOX -> "To organize"
                            InboxTab.ORGANIZED -> "Organized"
                            InboxTab.ARCHIVED -> "Archived"
                        },
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${visible.size} ${if (visible.size == 1) "item" else "items"}",
                        Modifier.weight(1f), fontSize = 14.sp, color = Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (tab == InboxTab.INBOX && visible.isNotEmpty()) TextButton(
                        onClick = { selecting = !selecting; selected.clear() },
                        modifier = Modifier.testTag(InboxSelectTag)
                    ) { Text(if (selecting) "Cancel" else "Select", color = Blue) }
                }
                if (selecting && selected.isNotEmpty()) {
                    Button(
                        onClick = { onBulkOrganize(selected.toSet()) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                            .testTag("inbox_bulk_organize"),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) { Text("Organize ${selected.size} selected") }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (visible.isEmpty()) {
                item {
                    Text(
                        when {
                            query.isNotBlank() -> "No matching items."
                            tab == InboxTab.INBOX -> "Inbox is empty. Anything you save lands here."
                            tab == InboxTab.ORGANIZED -> "Nothing organized yet."
                            else -> "Nothing archived."
                        },
                        Modifier.padding(horizontal = 24.dp, vertical = 30.dp), color = Muted
                    )
                }
            }
            items(visible, key = { it.id }) { row ->
                InboxItemCard(
                    row = row, selected = row.id in selected, selecting = selecting,
                    onClick = {
                        if (selecting) {
                            if (row.id in selected) selected.remove(row.id) else selected.add(row.id)
                        } else onOpen(row.id)
                    },
                    onOrganize = { onOrganize(row.id) },
                    onMenu = { onMenu(row.id) }
                )
            }
        }
    }
}

@Composable
private fun InboxItemCard(
    row: InboxUiItem,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onOrganize: () -> Unit,
    onMenu: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 5.dp)
            .testTag(com.virlin.app.ui.agent.capture.captureRowTag(row.id))
            .semantics {
                contentDescription = listOfNotNull(
                    row.title, row.kind, row.timeLabel, row.contextLabel,
                    if (selecting) (if (selected) "selected" else "not selected") else null
                ).joinToString(", ")
            },
        color = Color.White,
        border = BorderStroke(1.dp, if (selected) Blue else Line),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .padding(start = 12.dp, end = 5.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(BluePale),
                contentAlignment = Alignment.Center
            ) {
                Icon(row.icon, null, tint = Color(0xFF62758D), modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text("${row.kind} · ${row.timeLabel}", color = Muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Where an organized capture actually went; shown only when it has one.
                row.contextLabel?.let {
                    Text(it, color = Blue, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            if (selecting) {
                // The whole card is the target; the box only reflects the state, so a tap on
                // it is not swallowed by its own click handling.
                Checkbox(
                    checked = selected, onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = Blue)
                )
            } else {
                if (row.tab == InboxTab.INBOX) TextButton(
                    onClick = onOrganize,
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) { Text("Organize", color = Blue, fontSize = 12.sp) }
                IconButton(onClick = onMenu, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.MoreVert, "More options for ${row.title}",
                        tint = Muted, modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** The capture types the app really has, mapped to their tile icon. */
internal fun captureTypeIcon(type: com.virlin.app.domain.model.CaptureType): ImageVector =
    when (type) {
        com.virlin.app.domain.model.CaptureType.NOTE -> Icons.Default.Description
        com.virlin.app.domain.model.CaptureType.PROMPT -> Icons.Default.Terminal
        com.virlin.app.domain.model.CaptureType.LINK -> Icons.Default.Link
        com.virlin.app.domain.model.CaptureType.FILE -> Icons.Default.AttachFile
        com.virlin.app.domain.model.CaptureType.VOICE -> Icons.Default.Mic
    }

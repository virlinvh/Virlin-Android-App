package com.virlin.app.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val MapSelectedTaskBarTag = "map_selected_task_bar"
fun mapTaskActionTag(label: String) = "map_task_action_${label.lowercase().replace(' ', '_')}"

/**
 * The actions for the task currently selected on the map.
 *
 * It floats over the canvas rather than sitting in a column with it: as a sibling its height
 * would change when a task was selected, which moves the canvas out from under a finger that is
 * already dragging. The host hides it while a drag or a modal is in progress.
 *
 * Order is fixed - Open Task | Move | Page | Add - so the destructive-ish actions never swap
 * places under a thumb that has learned where they are.
 */
@Composable
internal fun MapSelectedTaskBar(
    task: MapTopic,
    onOpenTask: (MapTopic) -> Unit,
    onMove: (MapTopic) -> Unit,
    onPage: (MapTopic) -> Unit,
    onAdd: (MapTopic) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only a task has these four actions; a workstream or the project root has its own bar.
    require(task.kind == MapKind.TASK) { "The selected-task bar is only for tasks" }
    Surface(
        modifier = modifier.fillMaxWidth().testTag(MapSelectedTaskBarTag),
        shape = RoundedCornerShape(22.dp),
        color = Color.White,
        border = BorderStroke(1.dp, MapHairline),
        shadowElevation = 5.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Open Task is widest and leftmost: it is the one action that leaves the map.
            MapTaskAction(Icons.Outlined.OpenInNew, "Open Task", task.title, Modifier.weight(1.22f)) {
                onOpenTask(task)
            }
            Box(Modifier.width(1.dp).height(38.dp)) {
                Surface(color = MapHairline, modifier = Modifier.width(1.dp).height(38.dp)) {}
            }
            MapTaskAction(Icons.Outlined.OpenWith, "Move", task.title, Modifier.weight(1f)) { onMove(task) }
            MapTaskAction(Icons.Outlined.Description, "Page", task.title, Modifier.weight(1f)) { onPage(task) }
            MapTaskAction(Icons.Outlined.Add, "Add", task.title, Modifier.weight(1f)) { onAdd(task) }
        }
    }
}

@Composable
private fun MapTaskAction(
    icon: ImageVector,
    label: String,
    taskTitle: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .testTag(mapTaskActionTag(label))
            // A screen reader hears which task this acts on, not just "Move".
            .semantics { contentDescription = "$label, $taskTitle" }
            .clickable(role = Role.Button, onClick = onClick)
            // 48dp is Android's minimum touch target; the icon+label is shorter than that.
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MapInk)
        Spacer(Modifier.height(5.dp))
        Text(
            label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, color = MapInk, maxLines = 1,
        )
    }
}

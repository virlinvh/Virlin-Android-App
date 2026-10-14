package com.virlin.app.ui.pulse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.ui.hierarchy.taskDetail
import java.time.LocalDate
import java.time.ZoneId

const val PulseProjectRoute = "pulse_project/{id}"
fun pulseProject(id: String) = "pulse_project/$id"

/** The Pulse tab. The app shell keeps the footer and the Orb; this screen draws neither. */
@Composable
fun PulseScreen(navController: NavController, vm: PulseViewModel = viewModel()) {
    val input by vm.input.collectAsState()
    val loading by vm.loading.collectAsState()
    var explaining by rememberSaveable { mutableStateOf(false) }

    if (loading && input.intervals.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        VirlinPulseScreen(
            input = input,
            onProject = { id, period, date ->
                navController.currentBackStackEntry?.savedStateHandle?.set("pulse_period", period.name)
                navController.currentBackStackEntry?.savedStateHandle?.set("pulse_date", date.toString())
                navController.navigate(pulseProject(id))
            },
            onInfoSaved = { explaining = true }
        )
    }

    if (explaining) SavedTimeInfoSheet { explaining = false }
}

/**
 * What "estimated time saved" is, in plain terms — and what it is not. It is stated here rather
 * than implied by the number, because a proxy presented without its meaning reads as a promise.
 */
@Composable
private fun SavedTimeInfoSheet(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About Momentum") },
        text = {
            Text(
                "Focus and external time come from recorded intervals. Active days count days " +
                    "with focus or a completed task; they are a record, not a streak target. " +
                    "Tasks, workstreams and captures come from saved activity in the selected period.\n\n" +
                    "Parallel work is an estimate: focus on one task while an external process " +
                    "ran on another. It excludes paused timers and same-task overlap, is already " +
                    "included in both totals, and is not added on top of them."
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}

/** One project's Pulse: its workstreams, its tasks' minutes, and when the focus happened. */
@Composable
fun PulseProjectScreen(
    projectId: String?,
    navController: NavController,
    vm: PulseViewModel = viewModel(),
    zone: ZoneId = ZoneId.systemDefault()
) {
    val input by vm.input.collectAsState()
    if (projectId == null) return
    // The drilldown shows the same period the report was built for.
    val source = navController.previousBackStackEntry?.savedStateHandle
    val period = runCatching { PulsePeriod.valueOf(source?.get<String>("pulse_period") ?: "WEEK") }
        .getOrDefault(PulsePeriod.WEEK)
    val date = runCatching { LocalDate.parse(source?.get<String>("pulse_date") ?: LocalDate.now(zone).toString()) }
        .getOrDefault(LocalDate.now(zone))
    val report = remember(input, zone, period, date) {
        calculatePulse(input, period, date, zone)
    }
    VirlinPulseProjectScreen(
        input = input,
        report = report,
        projectId = projectId,
        onBack = { navController.popBackStack() },
        // A task opens where it already lives, not in a second task page.
        onTask = { navController.navigate(taskDetail(it)) },
        zone = zone
    )
}

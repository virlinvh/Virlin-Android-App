package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.theme.Charcoal
import com.virlin.app.ui.theme.CharcoalLight
import com.virlin.app.ui.theme.CharcoalMuted
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * NOW — greeting + today's achievement summary (presentation only).
 *
 * The four numbers below are **temporary display values**: they live in exactly one place,
 * [NowDaySummaryValues], and nothing here reads the domain, the repository or the clock for them.
 * Wiring them later means passing a real instance from the ViewModel — the bar itself
 * ([VirlinProgressBar]) only ever receives formatted strings.
 *
 * The date pill is the only live thing on this surface, and it is device date formatting, not a
 * calculation.
 */
data class NowDaySummaryValues(
    /** "42m" — time the user did not have to spend. */
    val timeSaved: String = "42m",
    val tasksAdvanced: String = "8",
    val tasksAdvancedUnit: String = "tasks",
    val projectsMoved: String = "3",
    val projectsMovedUnit: String = "projects",
    /** "22h 18m" — real human attention invested. */
    val timeFocused: String = "22h 18m",
    val greetingName: String = "Maya"
)

private val SummaryGreen = Color(0xFF16A34A)
private val SummaryGreenBg = Color(0xFFDCFCE7)
private val SummaryPurple = Color(0xFF7C3AED)
private val SummaryPurpleBg = Color(0xFFEDE4FE)
private val SummaryBlue = Color(0xFF2196F3)
private val SummaryBlueBg = Color(0xFFDCEBFB)
private val SummaryOrange = Color(0xFFF59E0B)
private val SummaryOrangeBg = Color(0xFFFDEBD2)
private val DatePillBg = Color(0xFFDDF3DE)
private val DatePillInk = Color(0xFF14532D)
// The card itself: barely-there green-tinted off-white on the pearl page, a thin green-grey
// hairline and a soft shadow — enough to read as ONE floating summary, never as a coloured panel.

/** "Tue, 23 Sep 2025" — the device's own date, formatted for display. */
private val DateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

@Composable
fun NowDaySummary(
    values: NowDaySummaryValues = NowDaySummaryValues(),
    today: LocalDate = LocalDate.now(),
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().testTag(NowDaySummaryTag)) {
        // ── greeting · date pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Hi, ${values.greetingName} 👋",
                    fontSize = 22.sp, fontWeight = FontWeight.Black, color = Charcoal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Small steps. Big progress.",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Row(
                modifier = Modifier
                    .background(DatePillBg, RoundedCornerShape(50))
                    .testTag(NowDatePillTag)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.CalendarToday, contentDescription = null, tint = DatePillInk, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    today.format(DateFormat),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DatePillInk,
                    maxLines = 1, softWrap = false
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── THE SUMMARY BAR — the supplied `VirlinProgressBar`, fed by the same values.
        VirlinProgressBar(
            timeSaved = values.timeSaved,
            tasksAdvanced = values.tasksAdvanced,
            projectsMoved = values.projectsMoved,
            timeFocused = values.timeFocused,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .testTag(NowSummaryBarTag)
                .semantics {
                    contentDescription = "${values.timeSaved} saved, " +
                        "${values.tasksAdvanced} ${values.tasksAdvancedUnit} advanced, " +
                        "${values.projectsMoved} ${values.projectsMovedUnit} moved, " +
                        "${values.timeFocused} focused"
                }
        )
    }
}

const val NowDaySummaryTag = "now_day_summary"
const val NowDatePillTag = "now_date_pill"
const val NowSummaryBarTag = "now_summary_bar"

package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import com.virlin.app.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** All metrics are supplied by the existing projection, never invented by this visual layer. */
data class NowDaySummaryValues(
    val timeSaved: String = "—", val tasksAdvanced: String = "—", val tasksAdvancedUnit: String = "tasks",
    val projectsMoved: String = "—", val projectsMovedUnit: String = "projects", val timeFocused: String = "—",
    val greetingName: String = "", val savedUnit: String = "saved", val timeExternal: String = "—"
)

@Composable
fun NowDaySummary(
    values: NowDaySummaryValues = NowDaySummaryValues(), today: LocalDate = rememberCurrentLocalDate(),
    modifier: Modifier = Modifier, timersRunning: Boolean = false, pausingTimers: Boolean = false,
    onPauseAll: (() -> Unit)? = null
) {
    val resources = LocalContext.current.resources
    // Decode a single stable frame: painterResource cannot load animated WebP drawables.
    val greetingWave = remember(resources) {
        android.graphics.BitmapFactory.decodeResource(resources, R.drawable.now_greeting_wave).asImageBitmap()
    }
    Column(modifier.fillMaxWidth().testTag(NowDaySummaryTag)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 340.dp
            val date = today.format(DateTimeFormatter.ofPattern(if (compact) "EEE, d MMM" else "EEE, d MMM yyyy", Locale.getDefault()))
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(5.dp).fillMaxHeight().background(Brush.verticalGradient(listOf(Color(0xFFFF788E), Color(0xFFF73772))), RoundedCornerShape(50)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (values.greetingName.isBlank()) "Hi" else "Hi, ${values.greetingName}",
                                modifier = Modifier.weight(1f, fill = false), fontFamily = NowApprovedFont,
                                fontSize = if (compact) 28.sp else 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black,
                                color = NowApprovedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(6.dp))
                            Image(greetingWave, null, Modifier.size(if (compact) 30.dp else 34.dp))
                        }
                        Text("Small steps. Big progress.", fontFamily = NowApprovedFont, fontSize = if (compact) 12.sp else 13.sp,
                            fontWeight = FontWeight.Bold, color = Color(0xFF71418E), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                val shape = RoundedCornerShape(50)
                // Darker teal lip under the pill gives the raised edge of the reference.
                Row(Modifier.shadow(3.dp, shape, spotColor = Color(0xFF21DCCB), ambientColor = Color(0xFF21DCCB))
                    .background(Color(0xFF2FE0C8), shape).padding(bottom = 3.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFFE6FFF6), Color(0xFFC0FAED))), shape)
                    .border(1.dp, Color(0xFFA6F0E1), shape).testTag(NowDatePillTag)
                    .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 9.dp else 11.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CalendarToday, null, tint = NowApprovedInk, modifier = Modifier.size(if (compact) 15.dp else 17.dp))
                    Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
                    Text(date, fontFamily = NowApprovedFont, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold,
                        color = NowApprovedInk, maxLines = 1, softWrap = false)
                }
                if (onPauseAll != null) MasterTimerButton(timersRunning, pausingTimers, onPauseAll,
                    runningGradient = listOf(Color(0xFFFFC46A), Color(0xFFF2922A)), runningFrame = Color(0xFFC07A2C),
                    glyphScale = if (compact) 1.4f else 1.65f)
            }
        }
        Spacer(Modifier.height(10.dp))
        VirlinProgressBar(values.timeSaved, values.tasksAdvanced, values.projectsMoved, values.timeFocused,
            savedUnit = values.savedUnit, timeExternal = values.timeExternal,
            modifier = Modifier.fillMaxWidth().testTag(NowSummaryBarTag).semantics {
                contentDescription = "${values.timeSaved} ${values.savedUnit}, ${values.timeFocused} focused, ${values.timeExternal} combined external task time"
            })
    }
}

const val NowDaySummaryTag = "now_day_summary"
const val NowDatePillTag = "now_date_pill"
const val NowSummaryBarTag = "now_summary_bar"

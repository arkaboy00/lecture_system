package com.arka.lecturecapture.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.arka.lecturecapture.core.LectureState
import com.arka.lecturecapture.core.StopReason
import com.arka.lecturecapture.core.Stamps
import com.arka.lecturecapture.core.TimelineItem
import java.time.format.DateTimeFormatter

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/**
 * One line under the header ("Аудио: 2 сегм., 47 мин · паузы: 1"); tap to expand into the
 * list of segments and the breaks between them. Photos keep the rest of the screen.
 */
@Composable
fun SegmentStrip(state: LectureState) {
    if (state.segments.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val items = state.timeline().filter { it !is TimelineItem.Shot }
    val gaps = items.filterIsInstance<TimelineItem.Gap>()
    val total = state.segments.sumOf { it.lengthMs() }

    Column(
        Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 4.dp).testTag("segments_toggle"),
    ) {
        Text(
            buildString {
                append("Аудио: ${state.segments.size} сегм., ${minutes(total)}")
                if (gaps.isNotEmpty()) append(" · паузы: ${gaps.size} (${minutes(gaps.sumOf { it.durationMs })})")
                append(if (expanded) "  ▲" else "  ▼")
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!expanded) return@Column
        var segmentIndex = 0
        var gapIndex = 0
        items.forEach { item ->
            when (item) {
                is TimelineItem.Segment -> {
                    val seg = item.segment
                    val n = ++segmentIndex
                    Row(Modifier.padding(top = 4.dp).testTag("segment_${n - 1}")) {
                        Text(
                            buildString {
                                append("$n · ${clock(seg.start.ts)}–${seg.stop?.let { clock(it.ts) } ?: "идёт"}")
                                if (seg.stop != null) append(" · ${minutes(seg.lengthMs())}")
                                if (seg.silences.isNotEmpty()) append(" · заглушён ${seg.silences.size}×")
                                when (seg.stopReason) {
                                    StopReason.RECOVERED -> append(" · обрыв (сбой)")
                                    StopReason.ERROR -> append(" · ошибка")
                                    else -> {}
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is TimelineItem.Gap -> Text(
                    "      пауза ${minutes(item.durationMs)}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("gap_${gapIndex++}"),
                )
                is TimelineItem.Shot -> {}
            }
        }
        Spacer(Modifier.padding(top = 4.dp))
    }
    HorizontalDivider()
}

private fun clock(ts: String) = Stamps.zoned(ts).format(CLOCK)

private fun minutes(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s >= 3600 -> "%d ч %02d мин".format(s / 3600, s % 3600 / 60)
        s >= 60 -> "${s / 60} мин"
        else -> "$s с"
    }
}

package com.android.benchmark.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.android.benchmark.results.FrameStats
import com.android.benchmark.results.JankBreakdown
import java.util.Locale

internal data class Metric(val label: String, val value: String)

internal fun summaryPercent(value: Double): String =
    String.format(Locale.getDefault(), if (value == 100.0) "%.0f%%" else "%.2f%%", value)

@Composable
internal fun Metrics(
    vararg metrics: Metric,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BenchTokens.small)) {
        for ((label, value) in metrics) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
                Text(label, color = labelColor, style = MaterialTheme.typography.labelMedium)
                Numeric(value, color = color)
            }
        }
    }
}

@Composable
internal fun Disclosure(
    title: String,
    expanded: Boolean,
    toggle: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = BenchTokens.panelShape,
    summary: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = shape, color = colors.surfaceContainerLow) {
        Column {
            Column(
                Modifier.fillMaxWidth()
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .clickable(role = Role.Button, onClick = toggle)
                    .heightIn(min = BenchTokens.touch)
                    .padding(horizontal = BenchTokens.inset, vertical = BenchTokens.gap),
                verticalArrangement = Arrangement.spacedBy(BenchTokens.small),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BenchTokens.small)) {
                    Text(title, Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                    BenchGlyph(if (expanded) Glyph.Up else Glyph.Down, colors.primary)
                }
                summary?.invoke(this)
            }
            if (expanded) {
                HorizontalDivider(color = colors.outlineVariant)
                Column(
                    Modifier.background(colors.surfaceContainerLowest).fillMaxWidth().padding(BenchTokens.inset),
                    verticalArrangement = Arrangement.spacedBy(BenchTokens.gap),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun FrameStatistics(stats: FrameStats) {
    Supporting("${stats.janky} of ${stats.frames} frames missed their deadline")
    Metrics(Metric("Jank frames", ResultLabels.percent(stats.jankPercent())),
        Metric("Bad frames", summaryPercent(stats.slowPercent())))
    Text("Actual frame duration", style = MaterialTheme.typography.titleSmall)
    Metrics(Metric("Average", ResultLabels.ms(stats.mean)), Metric("P50", ResultLabels.ms(stats.p50)))
    Metrics(Metric("P90", ResultLabels.ms(stats.p90)), Metric("P95", ResultLabels.ms(stats.p95)))
    Metrics(Metric("P99", ResultLabels.ms(stats.p99)), Metric("Worst", ResultLabels.ms(stats.max)))
    Text("Deadline overrun", style = MaterialTheme.typography.titleSmall)
    Metrics(Metric("P95", ResultLabels.signedMs(stats.p95Overrun)),
        Metric("P99", ResultLabels.signedMs(stats.p99Overrun)))
    Supporting("Negative values mean time remained in the frame budget.")
}

@Composable
internal fun CompositorDetails(timing: JankBreakdown, total: Int) {
    if (timing.classified() == 0) return
    Text("Compositor classification", style = MaterialTheme.typography.titleSmall)
    Supporting("${timing.classified()} of $total frames classified. Classifications can overlap.")
    Metrics(Metric("App", timing.app().toString()), Metric("Compositor", timing.composer().toString()),
        Metric("Other system", timing.other().toString()))
}

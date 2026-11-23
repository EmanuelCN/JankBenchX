package com.android.benchmark.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.benchmark.results.FrameStats.Frame
import com.android.benchmark.results.RunReport.Workload

private const val P95_AXIS_MAX_MS = 1000.0 / 30

@Composable
internal fun WorkloadChart(workloads: List<Workload>) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        Numeric("0 ms", Modifier.weight(1f), MaterialTheme.typography.labelMedium)
        Text("16.7 ms", Modifier.weight(1f), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
        Text("33.3 ms", Modifier.weight(1f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
    }
    for (workload in workloads) {
        val p95 = workload.stats()?.p95 ?: continue
        val ratio = (p95 / P95_AXIS_MAX_MS).coerceIn(0.0, 1.0).toFloat()
        Column(verticalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BenchTokens.small),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(workload.test().title), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                Numeric(ResultLabels.ms(p95), style = MaterialTheme.typography.labelLarge)
            }
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val radius = CornerRadius(BenchTokens.actionCorner.toPx())
                drawRoundRect(colors.surfaceContainerHighest, cornerRadius = radius)
                if (ratio > 0f) {
                    drawRoundRect(colors.primary, size = Size(size.width * ratio, size.height), cornerRadius = radius)
                }
                drawLine(colors.surface, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height),
                    strokeWidth = 1.dp.toPx())
            }
        }
    }
    Supporting("Values above 33.3 ms fill the bar and keep their measured label.")
}

@Composable
internal fun PassTimeline(frames: List<Frame>) {
    val colors = MaterialTheme.colorScheme
    val ceiling = remember(frames) {
        frames.maxOf { maxOf(it.durationNs(), it.budgetNs()) }.coerceAtLeast(1L)
    }
    Column(verticalArrangement = Arrangement.spacedBy(BenchTokens.small)) {
        Text("Frame duration and deadline", style = MaterialTheme.typography.titleSmall)
        Supporting("Vertical scale 0–${ResultLabels.ms(ceiling / 1_000_000.0)}")
        Box(
            Modifier.fillMaxWidth().height(112.dp).drawWithCache {
                val normal = Path()
                val missed = Path()
                val deadline = Path()
                val columns = minOf(frames.size, size.width.toInt())
                val bottom = size.height - 1.dp.toPx()
                val height = bottom - BenchTokens.fine.toPx()
                if (columns > 0 && height > 0) {
                    val columnWidth = size.width / columns
                    for (i in 0 until columns) {
                        val from = i * frames.size / columns
                        val end = (i + 1) * frames.size / columns
                        var peak = 0L
                        var budget = 0L
                        var jank = false
                        for (j in from until end) {
                            val frame = frames[j]
                            peak = maxOf(peak, frame.durationNs())
                            budget += frame.budgetNs()
                            jank = jank || frame.missedDeadline()
                        }
                        val x = i * columnWidth
                        val bars = if (jank) missed else normal
                        bars.addRect(Rect(x, bottom - height * peak / ceiling,
                            x + maxOf(1f, columnWidth * .85f), bottom))
                        val budgetY = bottom - height * budget / (end - from) / ceiling
                        if (i == 0) deadline.moveTo(x, budgetY)
                        deadline.lineTo(x + columnWidth, budgetY)
                    }
                }
                onDrawBehind {
                    drawRect(colors.surfaceContainerLow)
                    for (tick in 0..2) {
                        val y = bottom - height * tick / 2
                        drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y))
                    }
                    drawPath(normal, colors.primary)
                    drawPath(missed, colors.error)
                    drawPath(deadline, colors.onSurface, style = Stroke(1.5.dp.toPx()))
                }
            },
        )
        Supporting("First to last captured frame")
        Legend("Duration within deadline", colors.primary)
        Legend("Missed deadline", colors.error)
        Legend("Deadline used for each frame", colors.onSurface, line = true)
    }
}

@Composable
private fun Legend(label: String, color: Color, line: Boolean = false) {
    Row(Modifier.padding(start = BenchTokens.fine), horizontalArrangement = Arrangement.spacedBy(BenchTokens.small),
        verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp, 10.dp)) {
            if (line) drawLine(color, Offset(0f, center.y), Offset(size.width, center.y),
                strokeWidth = 1.5.dp.toPx())
            else drawRect(color)
        }
        Supporting(label)
    }
}

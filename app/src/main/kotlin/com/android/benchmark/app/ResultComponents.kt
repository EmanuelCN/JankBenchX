package com.android.benchmark.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.benchmark.results.FrameStats
import com.android.benchmark.results.JankBreakdown
import java.util.Locale
import kotlin.math.abs

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
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val (expandSpec, collapseSpec) = remember(motion, density) {
        val mediumHeight = with(density) { 320.dp.roundToPx() }
        val tallHeight = with(density) { 700.dp.roundToPx() }
        DisclosureSizeSpec(motion.defaultSpatialSpec(), mediumHeight, tallHeight, 450, 650) to
            DisclosureSizeSpec(motion.fastSpatialSpec(), mediumHeight, tallHeight, 400, 550)
    }
    val chevronRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        motion.fastSpatialSpec(),
        label = "Disclosure chevron",
    )
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
                    BenchGlyph(Glyph.Down, Modifier.graphicsLayer { rotationZ = chevronRotation }, color = colors.primary)
                }
                summary?.invoke(this)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandSpec, expandFrom = Alignment.Top),
                exit = shrinkVertically(collapseSpec, shrinkTowards = Alignment.Top),
                modifier = Modifier.semantics { if (!expanded) hideFromAccessibility() },
            ) {
                Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLowest)) {
                    HorizontalDivider(color = colors.outlineVariant)
                    Column(
                        Modifier.fillMaxWidth().padding(BenchTokens.inset),
                        verticalArrangement = Arrangement.spacedBy(BenchTokens.gap),
                        content = content,
                    )
                }
            }
        }
    }
}

private class DisclosureSizeSpec(
    private val compact: FiniteAnimationSpec<IntSize>,
    private val mediumHeight: Int,
    private val tallHeight: Int,
    private val mediumMillis: Int,
    private val tallMillis: Int,
) : FiniteAnimationSpec<IntSize> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<IntSize, V>,
    ): VectorizedFiniteAnimationSpec<V> {
        val shortSpec = compact.vectorize(converter)
        val mediumSpec = tween<IntSize>(mediumMillis, easing = FastOutSlowInEasing).vectorize(converter)
        val tallSpec = tween<IntSize>(tallMillis, easing = FastOutSlowInEasing).vectorize(converter)

        fun spec(initial: V, target: V): VectorizedFiniteAnimationSpec<V> {
            val travel = abs(converter.convertFromVector(initial).height - converter.convertFromVector(target).height)
            return when {
                travel >= tallHeight -> tallSpec
                travel >= mediumHeight -> mediumSpec
                else -> shortSpec
            }
        }

        return object : VectorizedFiniteAnimationSpec<V> {
            override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
                spec(initialValue, targetValue).getDurationNanos(initialValue, targetValue, initialVelocity)

            override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
                spec(initialValue, targetValue).getValueFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)

            override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
                spec(initialValue, targetValue).getVelocityFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
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

package com.android.benchmark.app

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.benchmark.results.FrameStats
import com.android.benchmark.results.RunReport
import com.android.benchmark.results.RunReport.Workload
import com.android.benchmark.results.RunStore
import com.android.benchmark.results.RunStore.Status
import com.android.benchmark.results.RunStore.Step
import com.android.benchmark.results.RunStore.StepStatus

class ResultsActivity : Screen() {
    private var report by mutableStateOf<RunReport?>(null)
    private var loadError by mutableStateOf<String?>(null)
    private var deleteError by mutableStateOf(false)
    private var deleting by mutableStateOf(false)
    private var runId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runId = intent.getLongExtra(RUN_ID, 0)
        setContent {
            BenchmarkTheme {
                AppSurface {
                    val snackbar = remember { SnackbarHostState() }
                    var confirm by rememberSaveable { mutableStateOf(false) }
                    val loaded = report
                    Box(Modifier.fillMaxSize()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
                            ScreenBar("Results", back = ::finish) {
                                FilledTonalButton(onClick = { confirm = true },
                                    enabled = loaded != null && loaded.run.status() != Status.RUNNING && !deleting) {
                                    Text("Delete")
                                }
                            }
                            val body = Modifier.widthIn(max = BenchTokens.contentWidth).fillMaxWidth()
                            when {
                                loaded != null -> ResultContent(loaded, body.weight(1f))
                                loadError != null -> Box(body.padding(BenchTokens.inset)) {
                                    ScreenMessage(loadError.orEmpty())
                                }
                                else -> Box(body.padding(BenchTokens.inset)) {
                                    ScreenMessage("Loading result", loading = true)
                                }
                            }
                        }
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                    }
                    LaunchedEffect(deleteError) {
                        if (deleteError) {
                            snackbar.showSnackbar("Could not delete this result.")
                            deleteError = false
                        }
                    }
                    if (confirm) {
                        DeleteResultDialog(onClose = { confirm = false }, onConfirm = {
                            confirm = false
                            deleteResult()
                        })
                    }
                }
            }
        }

        val store = RunStore.get(this)
        RunStore.DISK.execute {
            try {
                val loaded = store.details(runId)?.let(::RunReport)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        report = loaded
                        if (loaded == null) loadError = "This benchmark result is unavailable."
                    }
                }
            } catch (error: RuntimeException) {
                Log.e("JankBenchX", "Could not load result", error)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) loadError = "This benchmark result could not be loaded."
                }
            }
        }
    }

    private fun deleteResult() {
        deleting = true
        deleteError = false
        val store = RunStore.get(this)
        RunStore.DISK.execute {
            try {
                store.delete(runId)
                runOnUiThread(::finish)
            } catch (error: RuntimeException) {
                Log.e("JankBenchX", "Could not delete benchmark result", error)
                runOnUiThread {
                    if (!isDestroyed) {
                        deleting = false
                        deleteError = true
                    }
                }
            }
        }
    }

    companion object {
        const val RUN_ID = "result-run-id"
    }
}

@Composable
private fun DeleteResultDialog(onClose: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            DialogRefreshRate()
            Text("Delete this result?")
        },
        text = { Text("This run and its frame measurements will be removed from History.") },
        dismissButton = { FilledTonalButton(onClick = onClose) { Text("Keep result") } },
        confirmButton = { FilledTonalButton(onClick = onConfirm) { Text("Delete") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    )
}

@Composable
private fun ResultContent(report: RunReport, modifier: Modifier) {
    val workloads = remember(report) { report.workloads() }
    LazyColumn(modifier, contentPadding = PaddingValues(BenchTokens.inset),
        verticalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
        item(key = "summary") { RunSummary(report) }
        report.stats?.let { stats ->
            item(key = "essentials") {
                Column(Modifier.padding(top = BenchTokens.gap, bottom = BenchTokens.small),
                    verticalArrangement = Arrangement.spacedBy(BenchTokens.small)) {
                    SectionHeading("Frame performance")
                    Panel {
                        Metrics(
                            Metric("Jank frames", ResultLabels.percent(stats.jankPercent())),
                            Metric("Bad frames", summaryPercent(stats.slowPercent())),
                            Metric("P95 frame time", ResultLabels.ms(stats.p95)),
                        )
                        Supporting("Across completed passes. Jank missed the deadline. Bad frames used at least 75% of the frame budget.")
                    }
                }
            }
            item(key = "statistics") {
                var expanded by rememberSaveable { mutableStateOf(false) }
                Disclosure("Frame statistics", expanded, { expanded = !expanded },
                    shape = groupShape(0, 2)) {
                    FrameStatistics(stats)
                    CompositorDetails(report.compositor, stats.frames)
                    if (report.compositor.classified() == 0 && report.run.api() >= 36) {
                        Supporting("Compositor classification unavailable for this run.")
                    }
                }
            }
        }
        if (report.stats != null) {
            item(key = "chart") {
                var expanded by rememberSaveable { mutableStateOf(false) }
                Disclosure("95th-percentile frame time", expanded, { expanded = !expanded },
                    shape = groupShape(1, 2),
                    summary = { Supporting("Compare workloads on a fixed 0–33.3 ms scale") },
                ) { WorkloadChart(workloads) }
            }
        }
        item(key = "workloads") {
            SectionHeading("Workload details", Modifier.padding(top = BenchTokens.section, bottom = BenchTokens.small))
        }
        itemsIndexed(workloads, key = { _, workload -> workload.test().name }) { index, workload ->
            WorkloadResult(workload, groupShape(index, workloads.size))
        }
        item(key = "method") {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Disclosure("How to read the score", expanded, { expanded = !expanded }) {
                Supporting("The score rises with missed deadlines, the cost of slow frames, and their variability. A few long stalls can raise it even when most frames are fast.")
                Supporting("Compare the same workloads and repetition counts at similar observed refresh rates. Version 3 uses a different scale from JankBenchX 2.2 and older.")
                Supporting("Open a pass for its score inputs and the frame-by-frame duration and deadline chart.")
            }
        }
    }
}

@Composable
private fun RunSummary(report: RunReport) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = BenchTokens.panelShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= BenchTokens.twoColumnWidth) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    RunScore(report, Modifier.weight(1f))
                    RunSummaryDetails(report, Modifier.weight(1.15f).fillMaxHeight())
                }
            } else {
                Column {
                    RunScore(report, Modifier.fillMaxWidth())
                    RunSummaryDetails(report, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun RunScore(report: RunReport, modifier: Modifier) {
    val run = report.run
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(BenchTokens.inset), verticalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
        Row(horizontalArrangement = Arrangement.spacedBy(BenchTokens.small), verticalAlignment = Alignment.CenterVertically) {
            Text("JankBenchX score", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            StatusLabel(run.status(), colors.onPrimaryContainer)
        }
        Numeric(run.score()?.let(ResultLabels::score) ?: "Unavailable",
            style = if (run.score() == null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayLarge)
        Text("Lower is better · Scoring version ${run.scoringVersion()}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RunSummaryDetails(report: RunReport, modifier: Modifier) {
    val run = report.run
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val unfinished = report.passes - report.complete - report.failed
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Surface(modifier, color = colors.surfaceContainerLow, contentColor = colors.onSurface) {
        Column {
            Column(Modifier.padding(BenchTokens.inset), verticalArrangement = Arrangement.spacedBy(BenchTokens.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(BenchTokens.small), verticalAlignment = Alignment.CenterVertically) {
                    Text(ResultLabels.date(run), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text("Run #${run.id()}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                HorizontalDivider(color = colors.outlineVariant)
                Metrics(
                    Metric("Passes complete", "${report.complete} / ${report.passes}"),
                    Metric("Display refresh", ResultLabels.refresh(report.lowHz, report.highHz)),
                )
                if (report.failed > 0 || unfinished > 0) {
                    Supporting("${report.failed} failed · $unfinished unfinished")
                }
                run.reason()?.let { Text(it, color = if (run.status() == Status.FAILED) colors.error else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Disclosure("Run details", showDetails, { showDetails = !showDetails }, shape = RectangleShape) {
                Supporting(ResultLabels.device(run))
                Supporting(ResultLabels.kernel(context, run))
                Supporting("${report.selectedTests().size} selected workloads · ${run.repetitions()} repetitions per workload")
                if (report.failed == 0 && unfinished == 0) Supporting("0 failed · 0 unfinished")
            }
        }
    }
}

@Composable
private fun WorkloadResult(workload: Workload, shape: androidx.compose.foundation.shape.RoundedCornerShape) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val stats = workload.stats()
    Disclosure(
        title = stringResource(workload.test().title),
        expanded = expanded,
        toggle = { expanded = !expanded },
        shape = shape,
        summary = {
            if (workload.score() == null) {
                val failed = workload.failed() > 0
                Text(if (failed) "Failed" else "Incomplete",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Metrics(
                Metric("Score", workload.score()?.let(ResultLabels::score) ?: "—"),
                Metric("Jank frames", stats?.let { summaryPercent(it.jankPercent()) } ?: "—"),
                Metric("Bad frames", stats?.let { summaryPercent(it.slowPercent()) } ?: "—"),
            )
        },
    ) {
        Supporting("${workload.complete()} of ${workload.steps().size} passes complete · ${workload.failed()} failed")
        if (stats != null) {
            FrameStatistics(stats)
            CompositorDetails(workload.compositor(), stats.frames)
        }
        workload.medianFirstDrawMs()?.let { Metrics(Metric("Median first detail draw", ResultLabels.ms(it))) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Pass diagnostics", style = MaterialTheme.typography.titleSmall)
        for (step in workload.steps()) PassResult(step)
    }
}

@Composable
private fun PassResult(step: Step) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val measurement = step.measurement()
    val status = when (step.status()) {
        StepStatus.COMPLETE -> "Score " + ResultLabels.score(step.score())
        StepStatus.FAILED -> "Failed"
        StepStatus.PENDING -> "Incomplete"
    }
    Disclosure("Pass ${step.repetition()}", expanded, { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        summary = {
            Text(status, style = MaterialTheme.typography.labelMedium,
                color = if (step.status() == StepStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) {
        measurement.error()?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }
        if (measurement.dropped() != 0 || measurement.invalid() != 0) {
            Supporting("${measurement.dropped()} dropped reports · ${measurement.invalid()} invalid reports")
        }
        val frames = measurement.frames()
        if (frames.isNotEmpty()) {
            val stats = remember(measurement) { FrameStats.measure(frames) }
            Metrics(Metric("Display refresh", ResultLabels.refresh(measurement.lowHz(), measurement.highHz())))
            FrameStatistics(stats)
            if (step.status() == StepStatus.COMPLETE) {
                Text("Score inputs", style = MaterialTheme.typography.titleSmall)
                Metrics(Metric("Slow-frame mean", ResultLabels.ms(stats.normalizedSlowMean)),
                    Metric("Slow-frame spread", ResultLabels.ms(stats.normalizedSlowDeviation)))
                Supporting("Slow-frame inputs use a 60 Hz equivalent budget. The score combines them with the jank percentage.")
            }
            CompositorDetails(measurement.compositor(), frames.size)
            RunReport.medianFirstDrawMs(measurement.firstDrawsNs())?.let {
                Metrics(Metric("Median first detail draw", ResultLabels.ms(it)))
            }
            if (measurement.fallback() > 0) {
                Supporting("${measurement.fallback()} frames used the display refresh period for the deadline.")
            }
            PassTimeline(frames)
        } else if (measurement.error() == null) {
            Supporting("No frame measurements for this pass.")
        }
    }
}

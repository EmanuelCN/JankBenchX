package com.android.benchmark.app

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.android.benchmark.results.RunReport
import com.android.benchmark.results.RunReport.Workload
import com.android.benchmark.results.RunStore
import com.android.benchmark.results.RunStore.Run

class CompareRunsActivity : Screen() {
    private var history by mutableStateOf<List<Run>?>(null)
    private var comparison by mutableStateOf<Pair<RunReport, RunReport>?>(null)
    private var failure by mutableStateOf<String?>(null)
    private var leftId by mutableLongStateOf(0L)
    private var rightId by mutableLongStateOf(0L)
    private var request = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        leftId = savedInstanceState?.getLong(LEFT_ID) ?: 0
        rightId = savedInstanceState?.getLong(RIGHT_ID) ?: 0
        setContent {
            BenchmarkTheme {
                AppSurface {
                    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
                    val runs = history
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        ScreenBar("Compare runs", back = ::finish)
                        val body = Modifier.weight(1f).widthIn(max = BenchTokens.contentWidth).fillMaxWidth()
                        if (runs != null && runs.size >= 2) {
                            ComparisonContent(
                                runs.first { it.id() == leftId },
                                runs.first { it.id() == rightId },
                                comparison, failure, body, onChoose = { choosing = it },
                            )
                        } else {
                            Box(body.padding(BenchTokens.inset)) {
                                when {
                                    failure != null -> ScreenMessage(failure.orEmpty())
                                    runs == null -> ScreenMessage("Loading History", loading = true)
                                    else -> ScreenMessage("Two runs needed", "Save at least two runs to compare them.")
                                }
                            }
                        }
                    }
                    val slot = choosing
                    if (slot != null && runs != null && runs.size >= 2) {
                        val left = slot == RUN_A
                        val current = if (left) leftId else rightId
                        val other = if (left) rightId else leftId
                        ChooseRunDialog(slot, runs.filter { it.id() != other }, current, onClose = { choosing = null }) { run ->
                            choosing = null
                            if (left) leftId = run.id() else rightId = run.id()
                            loadPair()
                        }
                    }
                }
            }
        }
        loadHistory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(LEFT_ID, leftId)
        outState.putLong(RIGHT_ID, rightId)
        super.onSaveInstanceState(outState)
    }

    private fun loadHistory() {
        val store = RunStore.get(this)
        RunStore.DISK.execute {
            try {
                val runs = store.history()
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        if (runs.size >= 2) {
                            if (leftId == rightId || runs.none { it.id() == leftId } || runs.none { it.id() == rightId }) {
                                leftId = runs[0].id()
                                rightId = runs[1].id()
                            }
                            loadPair()
                        }
                        history = runs
                    }
                }
            } catch (error: RuntimeException) {
                Log.e("JankBenchX", "Could not load history", error)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) failure = "History could not be loaded."
                }
            }
        }
    }

    private fun loadPair() {
        comparison = null
        failure = null
        val current = ++request
        val a = leftId
        val b = rightId
        val store = RunStore.get(this)
        RunStore.DISK.execute {
            try {
                val first = store.details(a)?.let(::RunReport)
                val second = store.details(b)?.let(::RunReport)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && current == request) {
                        if (first != null && second != null) comparison = first to second
                        else failure = "One of the selected runs is unavailable."
                    }
                }
            } catch (error: RuntimeException) {
                Log.e("JankBenchX", "Could not compare runs", error)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && current == request) {
                        failure = "These benchmark runs could not be compared."
                    }
                }
            }
        }
    }
}

private const val LEFT_ID = "left-run"
private const val RIGHT_ID = "right-run"
private const val RUN_A = "Run A"
private const val RUN_B = "Run B"

@Composable
private fun ComparisonContent(
    a: Run,
    b: Run,
    reports: Pair<RunReport, RunReport>?,
    failure: String?,
    modifier: Modifier,
    onChoose: (String) -> Unit,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(BenchTokens.inset),
        verticalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= BenchTokens.twoColumnWidth) {
                    Row(horizontalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
                        RunSelector(RUN_A, a, BenchTokens.panelShape, Modifier.weight(1f)) { onChoose(RUN_A) }
                        RunSelector(RUN_B, b, BenchTokens.panelShape, Modifier.weight(1f)) { onChoose(RUN_B) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
                        RunSelector(RUN_A, a, groupShape(0, 2)) { onChoose(RUN_A) }
                        RunSelector(RUN_B, b, groupShape(1, 2)) { onChoose(RUN_B) }
                    }
                }
            }
        }
        if (reports == null) {
            item {
                Box(Modifier.padding(top = BenchTokens.gap)) {
                    if (failure == null) ScreenMessage("Loading comparison", loading = true)
                    else ScreenMessage(failure)
                }
            }
        } else {
            val (first, second) = reports
            item {
                Column(Modifier.padding(top = BenchTokens.gap), verticalArrangement = Arrangement.spacedBy(BenchTokens.small)) {
                    SectionHeading("Run overview")
                    Panel {
                        ComparisonMetric("Score", a.score()?.let(ResultLabels::score), b.score()?.let(ResultLabels::score))
                        ComparisonMetric("Display refresh", ResultLabels.refresh(first.lowHz, first.highHz),
                            ResultLabels.refresh(second.lowHz, second.highHz))
                        Supporting("Lower scores are better.")
                    }
                }
            }
            item {
                var expanded by rememberSaveable(a.id(), b.id()) { mutableStateOf(false) }
                Disclosure("Run details", expanded, { expanded = !expanded }) {
                    RunMetadata(RUN_A, first)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RunMetadata(RUN_B, second)
                }
            }
            item {
                Column(Modifier.padding(top = BenchTokens.section, bottom = BenchTokens.small),
                    verticalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
                    SectionHeading("Workload comparison")
                    Supporting("Frame metrics use completed passes.")
                }
            }
            val workloads = (first.selectedTests() + second.selectedTests()).toList()
            itemsIndexed(workloads, key = { _, test -> test.name }) { index, test ->
                val left = first.workload(test)
                val right = second.workload(test)
                var expanded by rememberSaveable(a.id(), b.id()) { mutableStateOf(false) }
                Disclosure(stringResource(test.title), expanded, { expanded = !expanded },
                    shape = groupShape(index, workloads.size),
                    summary = {
                        val aValue = left?.stats()?.let { ResultLabels.percent(it.jankPercent()) }
                        val bValue = right?.stats()?.let { ResultLabels.percent(it.jankPercent()) }
                        Text("Jank frames", style = MaterialTheme.typography.labelMedium)
                        Metrics(Metric(RUN_A, aValue ?: unavailable(left)), Metric(RUN_B, bValue ?: unavailable(right)))
                    },
                ) { WorkloadComparison(left, right) }
            }
        }
    }
}

@Composable
private fun RunSelector(label: String, run: Run, shape: RoundedCornerShape, modifier: Modifier = Modifier,
                        onChange: () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.padding(horizontal = BenchTokens.inset, vertical = BenchTokens.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BenchTokens.small),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
                Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(ResultLabels.date(run), style = MaterialTheme.typography.titleMedium)
                Supporting("Run #${run.id()} · ${ResultLabels.status(run.status())}")
            }
            TextButton(
                onClick = onChange,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text("Change $label") }
        }
    }
}

@Composable
private fun RunMetadata(label: String, report: RunReport) {
    val context = LocalContext.current
    Text(label, style = MaterialTheme.typography.titleSmall)
    Supporting(ResultLabels.device(report.run))
    Supporting(ResultLabels.kernel(context, report.run))
    Supporting("${report.selectedTests().size} workloads · ${report.run.repetitions()} repetitions · Scoring version ${report.run.scoringVersion()}")
}

@Composable
private fun ChooseRunDialog(label: String, runs: List<Run>, current: Long, onClose: () -> Unit, onPick: (Run) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            DialogRefreshRate()
            Text("Choose $label")
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 368.dp),
                verticalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
                itemsIndexed(runs, key = { _, run -> run.id() }) { index, run ->
                    RunEntry(remember(run) { SavedRun(run) }, shape = groupShape(index, runs.size), selected = current == run.id(),
                        onClick = { onPick(run) })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onClose,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text("Cancel") }
        },
    )
}

@Composable
private fun WorkloadComparison(a: Workload?, b: Workload?) {
    val left = a?.stats()
    val right = b?.stats()
    if (left == null && right == null) {
        Supporting("Frame metrics become available when a pass completes.")
        return
    }
    ComparisonMetric("P95 frame time", left?.let { ResultLabels.ms(it.p95) }, right?.let { ResultLabels.ms(it.p95) })
    ComparisonMetric("P99 frame time", left?.let { ResultLabels.ms(it.p99) }, right?.let { ResultLabels.ms(it.p99) })
    ComparisonMetric("P95 deadline overrun", left?.let { ResultLabels.signedMs(it.p95Overrun) },
        right?.let { ResultLabels.signedMs(it.p95Overrun) })
    ComparisonMetric("P99 deadline overrun", left?.let { ResultLabels.signedMs(it.p99Overrun) },
        right?.let { ResultLabels.signedMs(it.p99Overrun) })
    val drawA = a?.medianFirstDrawMs()
    val drawB = b?.medianFirstDrawMs()
    if (drawA != null || drawB != null) {
        ComparisonMetric("Median first detail draw", drawA?.let(ResultLabels::ms), drawB?.let(ResultLabels::ms))
    }
    ComparisonMetric("Passes complete", a?.let { "${it.complete()} / ${it.steps().size}" },
        b?.let { "${it.complete()} / ${it.steps().size}" })
}

@Composable
private fun ComparisonMetric(label: String, a: String?, b: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(BenchTokens.small)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Metrics(Metric(RUN_A, a ?: "—"), Metric(RUN_B, b ?: "—"))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun unavailable(workload: Workload?): String =
    if (workload == null) "Not selected" else "No completed passes"

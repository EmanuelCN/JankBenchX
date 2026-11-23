/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the
 * License.
 *
 */

package com.android.benchmark.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.android.benchmark.R
import com.android.benchmark.results.RunStore
import com.android.benchmark.results.RunStore.Run
import com.android.benchmark.ui.BenchmarkTest

class HomeActivity : Screen() {
    private val preferences by lazy { getSharedPreferences("benchmark_settings", MODE_PRIVATE) }
    private var showingHistory by mutableStateOf(false)
    private var repetitions by mutableIntStateOf(5)
    private var selected by mutableStateOf<Set<String>>(emptySet())
    private var launching by mutableStateOf(false)
    private var history by mutableStateOf<List<SavedRun>?>(null)
    private var historyError by mutableStateOf(false)
    private var historyRequest = 0
    private lateinit var workloads: List<BenchmarkTest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showingHistory = savedInstanceState?.getBoolean("show_history") == true
        repetitions = preferences.getInt("repetitions", 5)
        workloads = BenchmarkTest.workloads().filter { it.isSupported(this) }
        val saved = preferences.getStringSet(WORKLOAD_SELECTION, null)
        selected = workloads.filter { test ->
            saved == null || test.name in saved ||
                (test == BenchmarkTest.PHOTO_GALLERY && BenchmarkTest.UI_TV_BROWSE.name in saved)
        }.mapTo(mutableSetOf()) { it.name }

        setContent {
            BenchmarkTheme {
                AppSurface {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        HomeTabs(showingHistory) { historyTab ->
                            if (showingHistory != historyTab) {
                                showingHistory = historyTab
                                if (historyTab) loadHistory()
                            }
                        }
                        val motion = MaterialTheme.motionScheme
                        AnimatedContent(
                            targetState = showingHistory,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            transitionSpec = { fadeIn(motion.fastEffectsSpec()) togetherWith ExitTransition.None },
                            label = "Home tab",
                        ) { historyTab ->
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                val content = Modifier.weight(1f).widthIn(max = BenchTokens.contentWidth).fillMaxWidth()
                                if (historyTab) {
                                    HistoryContent(history, historyError, content, onCompare = {
                                        startActivity(Intent(this@HomeActivity, CompareRunsActivity::class.java))
                                    }, onOpen = { run ->
                                        startActivity(Intent(this@HomeActivity, ResultsActivity::class.java)
                                            .putExtra(ResultsActivity.RUN_ID, run.id()))
                                    })
                                } else {
                                    SetupContent(workloads, selected, repetitions, content, ::saveRepetitions,
                                        ::setWorkloadSelected, ::toggleAllWorkloads)
                                    RunAction(selected.size, !launching && selected.isNotEmpty(), ::startBenchmark)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        launching = false
        if (showingHistory) loadHistory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("show_history", showingHistory)
        super.onSaveInstanceState(outState)
    }

    private fun saveRepetitions(count: Int) {
        repetitions = count
        preferences.edit { putInt("repetitions", count) }
    }

    private fun setWorkloadSelected(name: String, checked: Boolean) {
        saveSelection(if (checked) selected + name else selected - name)
    }

    private fun toggleAllWorkloads() {
        saveSelection(if (selected.size == workloads.size) emptySet() else workloads.mapTo(mutableSetOf()) { it.name })
    }

    private fun saveSelection(selection: Set<String>) {
        selected = selection
        preferences.edit { putStringSet(WORKLOAD_SELECTION, HashSet(selection)) }
    }

    private fun startBenchmark() {
        if (launching || selected.isEmpty()) return
        val tests = workloads.filter { it.name in selected }.map { it.name }.toTypedArray()
        launching = true
        startActivity(Intent(this, RunLocalBenchmarksActivity::class.java)
            .putExtra(RunLocalBenchmarksActivity.TESTS, tests)
            .putExtra(RunLocalBenchmarksActivity.REPETITIONS, repetitions))
    }

    private fun loadHistory() {
        historyError = false
        val request = ++historyRequest
        val store = RunStore.get(this)
        RunStore.DISK.execute {
            try {
                val runs = store.history().map { SavedRun(it) }
                runOnUiThread {
                    if (!isDestroyed && showingHistory && request == historyRequest) history = runs
                }
            } catch (error: RuntimeException) {
                Log.e("JankBenchX", "Could not load history", error)
                runOnUiThread {
                    if (!isDestroyed && showingHistory && request == historyRequest) {
                        history = null
                        historyError = true
                    }
                }
            }
        }
    }

    private companion object {
        const val WORKLOAD_SELECTION = "selected_workloads"
    }
}

@Composable
private fun HomeTabs(showingHistory: Boolean, onSwitch: (Boolean) -> Unit) {
    Row(
        Modifier.widthIn(max = BenchTokens.contentWidth).fillMaxWidth()
            .padding(horizontal = BenchTokens.inset, vertical = BenchTokens.small)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(BenchTokens.small),
    ) {
        HomeTab("Benchmark", !showingHistory, Modifier.weight(1f)) { onSwitch(false) }
        HomeTab("History", showingHistory, Modifier.weight(1f)) { onSwitch(true) }
    }
}

@Composable
private fun HomeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val highlight by animateFloatAsState(
        if (selected) 1f else 0f,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "Tab highlight",
    )
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = BenchTokens.touch).semantics { role = Role.Tab },
        shape = MaterialTheme.shapes.medium,
        color = lerp(colors.surfaceContainerLow, colors.primaryContainer, highlight),
        contentColor = lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, highlight),
    ) {
        Box(Modifier.padding(horizontal = BenchTokens.small, vertical = BenchTokens.gap),
            contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SetupContent(
    workloads: List<BenchmarkTest>,
    selection: Set<String>,
    repetitions: Int,
    modifier: Modifier,
    onRepetitions: (Int) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onToggleAll: () -> Unit,
) {
    BoxWithConstraints(modifier) {
        if (maxWidth >= BenchTokens.twoColumnWidth) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(.9f).verticalScroll(rememberScrollState()).padding(BenchTokens.inset)) {
                    SetupSettings(repetitions, onRepetitions)
                }
                WorkloadCatalog(workloads, selection, Modifier.weight(1.1f), onToggle, onToggleAll)
            }
        } else {
            WorkloadCatalog(workloads, selection, Modifier.fillMaxSize(), onToggle, onToggleAll) {
                SetupSettings(repetitions, onRepetitions)
            }
        }
    }
}

@Composable
private fun SetupSettings(repetitions: Int, onRepetitions: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(BenchTokens.gap)) {
        Text("Configure JankBenchX", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Panel {
            Text(stringResource(R.string.repetitions_per_workload), style = MaterialTheme.typography.titleMedium)
            // Keep all five options reachable at narrow split-screen widths and large font scales.
            BoxWithConstraints {
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).width(maxOf(maxWidth, 252.dp)),
                ) {
                    for (count in 1..5) {
                        RepetitionSegment(count, repetitions == count) {
                            if (repetitions != count) onRepetitions(count)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleChoiceSegmentedButtonRowScope.RepetitionSegment(count: Int, selected: Boolean, onClick: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val emphasis by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "Repetition selection",
    )
    val fill = lerp(palette.surfaceContainerLow, palette.primaryContainer, emphasis)
    val ink = lerp(palette.onSurface, palette.onPrimaryContainer, emphasis)
    val outline = lerp(palette.outlineVariant, palette.primary, emphasis)
    val description = pluralStringResource(R.plurals.repetition_count, count, count)
    SegmentedButton(
        selected = selected,
        onClick = onClick,
        shape = SegmentedButtonDefaults.itemShape(count - 1, 5, MaterialTheme.shapes.medium),
        modifier = Modifier.heightIn(min = 52.dp).semantics { contentDescription = description },
        colors = SegmentedButtonDefaults.colors(
            activeContainerColor = fill,
            inactiveContainerColor = fill,
            activeContentColor = ink,
            inactiveContentColor = ink,
            activeBorderColor = outline,
            inactiveBorderColor = outline,
        ),
        contentPadding = PaddingValues(),
        icon = {},
    ) {
        Numeric(count.toString(), Modifier.graphicsLayer {
            val scale = 1f + emphasis * .08f
            scaleX = scale
            scaleY = scale
        })
    }
}

@Composable
private fun WorkloadCatalog(
    workloads: List<BenchmarkTest>,
    selection: Set<String>,
    modifier: Modifier,
    onToggle: (String, Boolean) -> Unit,
    onToggleAll: () -> Unit,
    settings: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(BenchTokens.inset),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (settings != null) item { settings() }
        item {
            val allSelected = selection.size == workloads.size
            Row(
                Modifier.fillMaxWidth().padding(top = if (settings != null) BenchTokens.gap else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeading(stringResource(R.string.workloads), Modifier.weight(1f))
                FilledTonalButton(
                    onClick = onToggleAll,
                    shapes = actionButtonShapes(),
                    enabled = workloads.isNotEmpty(),
                ) { Text(if (allSelected) "Clear all" else stringResource(R.string.all_workloads)) }
            }
        }
        itemsIndexed(workloads, key = { _, test -> test.name }) { index, test ->
            WorkloadRow(test, test.name in selection, groupShape(index, workloads.size), onToggle)
        }
    }
}

@Composable
private fun WorkloadRow(test: BenchmarkTest, checked: Boolean, shape: RoundedCornerShape,
                        onToggle: (String, Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val background by animateColorAsState(
        if (checked) colors.surfaceContainer else colors.surfaceContainerLow,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "Workload selection",
    )
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape)
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = interaction,
                onValueChange = { onToggle(test.name, it) },
            )
            .heightIn(min = BenchTokens.touch),
        shape = shape,
        color = background,
    ) {
        Row(
            Modifier.padding(horizontal = BenchTokens.inset, vertical = BenchTokens.gap),
            horizontalArrangement = Arrangement.spacedBy(BenchTokens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(test.title), style = MaterialTheme.typography.titleMedium)
                Supporting(test.description)
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                interactionSource = interaction,
                thumbContent = if (checked) {
                    {
                        BenchGlyph(Glyph.Check, Modifier.size(SwitchDefaults.IconSize),
                            color = LocalContentColor.current)
                    }
                } else null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = colors.primary,
                    checkedThumbColor = colors.onPrimary,
                    checkedIconColor = colors.primary,
                ),
            )
        }
    }
}

@Composable
private fun RunAction(selected: Int, enabled: Boolean, onRun: () -> Unit) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceDim) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.widthIn(max = BenchTokens.contentWidth).fillMaxWidth()
                    .padding(horizontal = BenchTokens.inset, vertical = BenchTokens.small),
                horizontalArrangement = Arrangement.spacedBy(BenchTokens.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    Surface(
                        shape = BenchTokens.actionShape,
                        color = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer,
                    ) {
                        AnimatedContent(
                            targetState = selected,
                            transitionSpec = {
                                fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                            },
                            label = "Selected workload count",
                        ) { count ->
                            Text(
                                if (count == 1) "1 workload" else "$count workloads",
                                Modifier.padding(horizontal = BenchTokens.inset, vertical = BenchTokens.gap),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                FilledTonalIconButton(
                    onClick = onRun,
                    shapes = actionIconShapes(),
                    modifier = Modifier.size(width = 76.dp, height = BenchTokens.touch)
                        .semantics { contentDescription = "Run Benchmark" },
                    enabled = enabled,
                ) {
                    BenchGlyph(Glyph.Play, color = LocalContentColor.current)
                }
            }
        }
    }
}

@Composable
private fun HistoryContent(
    runs: List<SavedRun>?,
    error: Boolean,
    modifier: Modifier,
    onCompare: () -> Unit,
    onOpen: (Run) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    LazyColumn(modifier, contentPadding = PaddingValues(BenchTokens.inset),
        verticalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = BenchTokens.small),
                horizontalArrangement = Arrangement.spacedBy(BenchTokens.small), verticalAlignment = Alignment.CenterVertically) {
                SectionHeading("Saved runs", Modifier.weight(1f))
                if (runs != null && runs.size >= 2) {
                    FilledTonalButton(onClick = onCompare, shapes = actionButtonShapes()) { Text("Compare runs") }
                }
            }
        }
        when {
            error -> item { ScreenMessage("History could not be loaded") }
            runs == null -> item { ScreenMessage("Loading runs", loading = true) }
            runs.isEmpty() -> item { ScreenMessage("No runs yet", "Run a benchmark to see your results here.") }
            else -> itemsIndexed(runs, key = { _, saved -> saved.run.id() }) { index, saved ->
                RunEntry(
                    saved,
                    onClick = { onOpen(saved.run) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = motion.fastEffectsSpec(),
                        placementSpec = motion.defaultSpatialSpec(),
                    ),
                    shape = groupShape(index, runs.size),
                )
            }
        }
    }
}

internal fun groupShape(index: Int, size: Int): RoundedCornerShape {
    val top = if (index == 0) BenchTokens.groupCorner else BenchTokens.seamCorner
    val bottom = if (index == size - 1) BenchTokens.groupCorner else BenchTokens.seamCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

internal data class SavedRun(
    val run: Run,
    val date: String = ResultLabels.date(run),
    val score: String? = run.score()?.let(ResultLabels::score),
)

@Composable
internal fun RunEntry(
    saved: SavedRun,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = BenchTokens.panelShape,
    selected: Boolean? = null,
) {
    val run = saved.run
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val background = if (selected == true) colors.secondaryContainer else colors.surfaceContainerLow
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().semantics {
            if (selected != null) this.selected = selected
        },
        color = background,
        contentColor = if (selected == true) colors.onSecondaryContainer else colors.onSurface,
        shape = shape,
    ) {
        Column(Modifier.padding(BenchTokens.inset), verticalArrangement = Arrangement.spacedBy(BenchTokens.fine)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BenchTokens.small)) {
                Text("Run #${run.id()}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                StatusLabel(run.status())
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BenchTokens.small),
            ) {
                Text(saved.date, Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.titleMedium)
                Column(Modifier.widthIn(max = 116.dp).alignBy(FirstBaseline), horizontalAlignment = Alignment.End) {
                    Supporting("Score")
                    Numeric(saved.score ?: "—",
                        modifier = if (saved.score == null) Modifier.semantics { contentDescription = "Score unavailable" }
                        else Modifier)
                }
            }
            Supporting(ResultLabels.device(run))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BenchTokens.small),
                verticalAlignment = Alignment.CenterVertically) {
                Supporting(ResultLabels.kernel(context, run), Modifier.weight(1f))
                if (selected == true) Text("Selected", style = MaterialTheme.typography.labelMedium)
                if (selected == null) RunEntryArrow(interaction)
            }
        }
    }
}

@Composable
private fun RunEntryArrow(interaction: InteractionSource) {
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        if (pressed) BenchTokens.actionPressedCorner else BenchTokens.actionCorner,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "Open result button",
    )
    Surface(
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            BenchGlyph(Glyph.Forward, color = LocalContentColor.current)
        }
    }
}

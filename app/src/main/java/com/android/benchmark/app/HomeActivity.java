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

package com.android.benchmark.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;

import com.android.benchmark.R;
import com.android.benchmark.results.RunStore;
import com.android.benchmark.results.RunStore.Run;
import com.android.benchmark.results.RunStore.Status;
import com.android.benchmark.ui.BenchmarkTest;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HomeActivity extends Screen {
    private static final String HISTORY = "show_history";
    private static final String WORKLOAD_SELECTION = "selected_workloads";
    private SharedPreferences preferences;
    private LinearLayout content;
    private Button benchmarkTab;
    private Button historyTab;
    private Button start;
    private boolean showingHistory;
    private final List<BenchmarkTest> availableWorkloads = new ArrayList<>();
    private Set<String> selectedWorkloads;
    private int historyRequest;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("benchmark_settings", MODE_PRIVATE);
        showingHistory = state != null && state.getBoolean(HISTORY);
        LinearLayout root = createScreen();
        for (BenchmarkTest test : BenchmarkTest.workloads()) {
            if (test.isSupported(this)) {
                availableWorkloads.add(test);
            }
        }
        selectedWorkloads = loadWorkloadSelection();
        LinearLayout header = column();
        header.setBackgroundColor(surface);
        header.setPadding(dp(20), dp(14), dp(20), dp(12));
        TextView appName = text("JankBenchX", 24, ink, true);
        header.addView(appName);
        space(header, 10);
        LinearLayout nav = row();
        benchmarkTab = action("Benchmark", false);
        historyTab = action("History", false);
        LinearLayout.LayoutParams benchmarkParams = new LinearLayout.LayoutParams(0, -2, 1);
        benchmarkParams.setMarginEnd(dp(8));
        nav.addView(benchmarkTab, benchmarkParams);
        nav.addView(historyTab, new LinearLayout.LayoutParams(0, -2, 1));
        benchmarkTab.setOnClickListener(view -> switchTab(false));
        historyTab.setOnClickListener(view -> switchTab(true));
        header.addView(nav);
        root.addView(header);
        content = scroller(root);
        updateTabs();
        if (!showingHistory) {
            showBenchmark();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (showingHistory) {
            showHistory();
        } else if (start != null) {
            start.setEnabled(!selectedWorkloads.isEmpty());
        }
    }

    private void switchTab(boolean history) {
        if (showingHistory == history) {
            return;
        }
        showingHistory = history;
        updateTabs();
        scrollToTop(content);
        if (history) {
            showHistory();
        } else {
            showBenchmark();
        }
    }

    private void updateTabs() {
        benchmarkTab.setSelected(!showingHistory);
        historyTab.setSelected(showingHistory);
        benchmarkTab.setBackground(ripple(showingHistory ? background : soft, 14));
        historyTab.setBackground(ripple(showingHistory ? soft : background, 14));
        benchmarkTab.setTextColor(showingHistory ? muted : onSoft);
        historyTab.setTextColor(showingHistory ? onSoft : muted);
    }

    private void showBenchmark() {
        content.removeAllViews();
        LinearLayout intro = card(content);
        intro.addView(text("UI performance", 30, ink, true));
        space(intro, 5);
        intro.addView(text("Measure how smoothly this device scrolls, lays out text, and renders graphics.",
                15, muted, false));
        space(intro, 16);
        start = action("Run Benchmark", true);
        start.setId(android.R.id.button1);
        intro.addView(start);

        showRepetitionPicker(card(content));

        MaterialSwitch all = addWorkloadsHeader();
        Map<BenchmarkTest, MaterialSwitch> available = new LinkedHashMap<>();
        for (BenchmarkTest test : availableWorkloads) {
            MaterialSwitch option = workloadSwitch(getString(test.title), test.description);
            option.setChecked(selectedWorkloads.contains(test.name()));
            available.put(test, option);
            option.setOnClickListener(view -> {
                if (option.isChecked()) {
                    selectedWorkloads.add(test.name());
                } else {
                    selectedWorkloads.remove(test.name());
                }
                all.setChecked(selectedWorkloads.size() == available.size());
                saveWorkloadSelection();
            });
            addWorkloadSwitch(option);
        }
        all.setChecked(selectedWorkloads.size() == available.size());
        all.setOnClickListener(view -> {
            boolean select = all.isChecked();
            selectedWorkloads.clear();
            for (Map.Entry<BenchmarkTest, MaterialSwitch> entry : available.entrySet()) {
                if (select) {
                    selectedWorkloads.add(entry.getKey().name());
                }
                entry.getValue().setChecked(select);
            }
            saveWorkloadSelection();
        });

        start.setEnabled(!selectedWorkloads.isEmpty());
        start.setOnClickListener(view -> {
            ArrayList<String> selectedTests = new ArrayList<>();
            for (BenchmarkTest test : availableWorkloads) {
                if (selectedWorkloads.contains(test.name())) {
                    selectedTests.add(test.name());
                }
            }
            if (!selectedTests.isEmpty()) {
                start.setEnabled(false);
                startActivity(new Intent(this, RunLocalBenchmarksActivity.class)
                        .putExtra(RunLocalBenchmarksActivity.TESTS,
                                selectedTests.toArray(new String[0]))
                        .putExtra(RunLocalBenchmarksActivity.REPETITIONS,
                                preferences.getInt("repetitions", 5)));
            }
        });
    }

    private Set<String> loadWorkloadSelection() {
        Set<String> saved = preferences.getStringSet(WORKLOAD_SELECTION, null);
        Set<String> selected = new HashSet<>();
        for (BenchmarkTest test : availableWorkloads) {
            if (saved == null || saved.contains(test.name())
                    || (test == BenchmarkTest.PHOTO_GALLERY && saved.contains(BenchmarkTest.UI_TV_BROWSE.name()))) {
                selected.add(test.name());
            }
        }
        return selected;
    }

    private void saveWorkloadSelection() {
        preferences.edit().putStringSet(WORKLOAD_SELECTION, new HashSet<>(selectedWorkloads)).apply();
        start.setEnabled(!selectedWorkloads.isEmpty());
    }

    private MaterialSwitch addWorkloadsHeader() {
        space(content, 12);
        LinearLayout header = row();
        TextView heading = text(getString(R.string.workloads), 20, ink, true);
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialSwitch all = workloadSwitch(getString(R.string.all_workloads), 48);
        header.addView(all);
        content.addView(header);
        space(content, 10);
        return all;
    }

    private void addWorkloadSwitch(MaterialSwitch option) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(6);
        content.addView(option, params);
    }

    private MaterialSwitch workloadSwitch(String title, String description) {
        SpannableString label = new SpannableString(title + "\n" + description);
        label.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int summary = title.length() + 1;
        label.setSpan(new ForegroundColorSpan(muted), summary, label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new RelativeSizeSpan(13f / 16f), summary, label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return workloadSwitch(label, 70);
    }

    private MaterialSwitch workloadSwitch(CharSequence label, int minimumHeight) {
        MaterialSwitch option = new MaterialSwitch(materialTheme);
        option.setText(label);
        option.setTextColor(ink);
        option.setTextSize(16);
        option.setLineSpacing(dp(3), 1);
        option.setSingleLine(false);
        option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        option.setMinimumHeight(dp(minimumHeight));
        option.setSwitchPadding(dp(14));
        option.setThumbIconDrawable(AppCompatResources.getDrawable(
                materialTheme, R.drawable.ic_workload_checked));
        option.setThumbIconTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{primary, Color.TRANSPARENT}));
        option.setBackground(ripple(surface, 18));
        option.setPaddingRelative(dp(16), dp(12), dp(16), dp(12));
        return option;
    }

    private void showRepetitionPicker(LinearLayout settings) {
        settings.addView(text(getString(R.string.repetitions_per_workload), 16, ink, true));
        space(settings, 10);
        MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(materialTheme);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);
        int[][] states = { {android.R.attr.state_checked}, {} };
        int selected = preferences.getInt("repetitions", 5);
        for (int count = 1; count <= 5; count++) {
            MaterialButton choice = new MaterialButton(group.getContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            choice.setId(View.generateViewId());
            choice.setTag(count);
            choice.setText(String.format(java.util.Locale.getDefault(), "%d", count));
            choice.setContentDescription(getResources().getQuantityString(
                    R.plurals.repetition_count, count, count));
            choice.setMinimumWidth(0);
            choice.setMinWidth(0);
            choice.setMinHeight(dp(52));
            choice.setInsetTop(0);
            choice.setInsetBottom(0);
            choice.setPadding(0, 0, 0, 0);
            choice.setTextColor(new ColorStateList(states, new int[]{onSoft, ink}));
            choice.setBackgroundTintList(new ColorStateList(states, new int[]{soft, surface}));
            choice.setStrokeColor(new ColorStateList(states, new int[]{primary, outline}));
            group.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
            if (count == selected) {
                group.check(choice.getId());
            }
        }
        group.addOnButtonCheckedListener((buttons, id, checked) -> {
            if (checked) {
                int count = (Integer) buttons.findViewById(id).getTag();
                preferences.edit().putInt("repetitions", count).apply();
            }
        });
        // Keep five 48 dp targets usable even in a very narrow split-screen window.
        group.setMinimumWidth(dp(252));
        HorizontalScrollView picker = new HorizontalScrollView(this);
        picker.setFillViewport(true);
        picker.setHorizontalScrollBarEnabled(false);
        picker.addView(group);
        settings.addView(picker);
    }

    private void showHistory() {
        content.removeAllViews();
        section(content, "Benchmark history");
        LinearLayout list = column();
        content.addView(list);
        list.addView(text("Loading runs…", 15, muted, false));
        int request = ++historyRequest;
        RunStore store = RunStore.get(this);
        RunStore.DISK.execute(() -> {
            try {
                List<Run> runs = store.history();
                runOnUiThread(() -> {
                    if (!isDestroyed() && showingHistory && request == historyRequest) {
                        populateHistory(list, runs);
                    }
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && showingHistory && request == historyRequest) {
                        list.removeAllViews();
                        list.addView(text("History could not be loaded.", 16, danger, true));
                    }
                });
            }
        });
    }

    private void populateHistory(LinearLayout list, List<Run> runs) {
        list.removeAllViews();
        if (runs.isEmpty()) {
            LinearLayout empty = card(list);
            empty.addView(text("No runs yet", 20, ink, true));
            space(empty, 6);
            empty.addView(text("Run a benchmark to see your results here.", 15, muted, false));
            return;
        }
        if (runs.size() >= 2) {
            Button compare = action("Compare runs", false);
            compare.setOnClickListener(view -> startActivity(new Intent(this, CompareRunsActivity.class)));
            list.addView(compare);
            space(list, 12);
        }
        for (Run run : runs) {
            LinearLayout item = card(list);
            item.setBackground(ripple(surface, 20));
            item.setFocusable(true);
            item.setOnClickListener(view -> startActivity(new Intent(this, ResultsActivity.class)
                    .putExtra(ResultsActivity.RUN_ID, run.id())));
            boolean complete = run.status() == Status.COMPLETE;
            int statusColor = complete ? positive : run.status() == Status.FAILED ? danger : muted;
            item.addView(text(ResultLabels.status(run.status()), 13, statusColor, true));
            item.addView(text(run.score() == null ? ResultLabels.date(run)
                    : "Score " + ResultLabels.score(run.score()), 21, ink, true));
            if (run.score() != null) {
                item.addView(text(ResultLabels.date(run), 14, muted, false));
            }
            item.addView(text(ResultLabels.device(run), 14, muted, false));
            item.addView(text(ResultLabels.kernel(this, run), 14, muted, false));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(HISTORY, showingHistory);
        super.onSaveInstanceState(state);
    }
}

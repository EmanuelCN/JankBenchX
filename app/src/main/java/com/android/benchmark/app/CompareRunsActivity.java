package com.android.benchmark.app;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.benchmark.results.FrameStats;
import com.android.benchmark.results.RunReport;
import com.android.benchmark.results.RunReport.Workload;
import com.android.benchmark.results.RunStore;
import com.android.benchmark.results.RunStore.Details;
import com.android.benchmark.results.RunStore.Run;
import com.android.benchmark.results.RunStore.Status;
import com.android.benchmark.ui.BenchmarkTest;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class CompareRunsActivity extends Screen {
    private static final String LEFT_ID = "left-run";
    private static final String RIGHT_ID = "right-run";
    private LinearLayout content;
    private List<Run> history;
    private long leftId;
    private long rightId;
    private int request;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            leftId = state.getLong(LEFT_ID);
            rightId = state.getLong(RIGHT_ID);
        }
        LinearLayout root = createScreen();
        LinearLayout bar = row();
        bar.setPadding(dp(20), dp(14), dp(20), dp(10));
        bar.setBackgroundColor(surface);
        Button back = action("Back", false);
        back.setOnClickListener(view -> finish());
        bar.addView(back);
        View title = text("Compare runs", 22, ink, true);
        title.setPaddingRelative(dp(12), 0, 0, 0);
        bar.addView(title);
        root.addView(bar);
        content = scroller(root);
        content.addView(text("Loading History…", 15, muted, false));
        RunStore store = RunStore.get(this);
        RunStore.DISK.execute(() -> {
            try {
                List<Run> runs = store.history();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (runs.size() < 2) {
                        showMessage("Save at least two runs to compare them.");
                        return;
                    }
                    history = runs;
                    Run first = findRun(leftId);
                    Run second = findRun(rightId);
                    if (first == null || second == null || leftId == rightId) {
                        leftId = runs.get(0).id();
                        rightId = runs.get(1).id();
                    }
                    loadPair();
                });
            } catch (RuntimeException error) {
                reportError("History could not be loaded.", error);
            }
        });
    }

    private Run findRun(long id) {
        for (Run run : history) {
            if (run.id() == id) {
                return run;
            }
        }
        return null;
    }

    private void loadPair() {
        content.removeAllViews();
        LinearLayout leftCard = runCard(content, findRun(leftId), "Run A", false);
        leftCard.setOnClickListener(view -> chooseRun("Run A", true));
        LinearLayout rightCard = runCard(content, findRun(rightId), "Run B", false);
        rightCard.setOnClickListener(view -> chooseRun("Run B", false));
        LinearLayout comparison = column();
        content.addView(comparison);
        comparison.addView(text("Loading comparison…", 15, muted, false));

        int current = ++request;
        long a = leftId;
        long b = rightId;
        RunStore store = RunStore.get(this);
        RunStore.DISK.execute(() -> {
            try {
                Details first = store.details(a);
                Details second = store.details(b);
                RunReport left = first == null ? null : new RunReport(first);
                RunReport right = second == null ? null : new RunReport(second);
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing() && current == request) {
                        comparison.removeAllViews();
                        if (left == null || right == null) {
                            comparison.addView(text("One of the selected runs is unavailable.",
                                    15, danger, true));
                        } else {
                            showComparison(comparison, left, right);
                        }
                    }
                });
            } catch (RuntimeException error) {
                Log.e("JankBenchX", "Could not compare runs", error);
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing() && current == request) {
                        comparison.removeAllViews();
                        comparison.addView(text("These benchmark runs could not be compared.",
                                15, danger, true));
                    }
                });
            }
        });
    }

    private LinearLayout runCard(LinearLayout parent, Run run, String label, boolean selected) {
        LinearLayout item = card(parent);
        item.setBackground(ripple(selected ? soft : surface, 20));
        item.setFocusable(true);
        item.setSelected(selected);
        int main = selected ? onSoft : ink;
        int secondary = selected ? onSoft : muted;
        if (label != null) {
            LinearLayout heading = row();
            heading.addView(text(label, 15, primary, true),
                    new LinearLayout.LayoutParams(0, -2, 1));
            heading.addView(text("Change", 14, primary, true));
            item.addView(heading);
            space(item, 6);
        }
        LinearLayout identity = row();
        identity.setGravity(Gravity.TOP);
        LinearLayout name = column();
        name.addView(text(ResultLabels.date(run), 17, main, true));
        name.addView(text("Run #" + run.id() + "  ·  " + ResultLabels.status(run.status()),
                13, secondary, false));
        identity.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

        if (run.score() != null) {
            TextView value = text(ResultLabels.score(run.score()), 19, main, true);
            value.setGravity(Gravity.END);
            value.setPaddingRelative(dp(8), 0, 0, 0);
            identity.addView(value);
        }
        item.addView(identity);
        space(item, 6);
        item.addView(text(ResultLabels.device(run), 13, secondary, false));
        item.addView(text(ResultLabels.kernel(this, run), 13, secondary, false));
        if (selected) {
            space(item, 4);
            item.addView(text("Selected", 13, onSoft, true));
        }
        return item;
    }

    private void chooseRun(String label, boolean left) {
        long current = left ? leftId : rightId;
        long other = left ? rightId : leftId;
        ScrollView scroll = new ScrollView(materialTheme);
        LinearLayout choices = column();
        choices.setPadding(dp(16), dp(6), dp(16), dp(8));
        scroll.addView(choices);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(materialTheme)
                .setTitle("Choose " + label)
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();

        for (Run run : history) {
            if (run.id() == other) {
                continue;
            }
            LinearLayout item = runCard(choices, run, null, run.id() == current);
            item.setOnClickListener(view -> {
                if (left) {
                    leftId = run.id();
                } else {
                    rightId = run.id();
                }
                dialog.dismiss();
                loadPair();
            });
        }
        showDialog(dialog);
    }

    private void showComparison(LinearLayout target, RunReport a, RunReport b) {
        LinearLayout overview = card(target);
        LinearLayout pair = row();
        pair.setGravity(Gravity.TOP);
        pair.addView(runOverview("Run A", a), pairParams(true));
        pair.addView(runOverview("Run B", b), pairParams(false));
        overview.addView(pair);

        ArrayList<String> differences = new ArrayList<>();
        if (!a.selectedTests().equals(b.selectedTests())) {
            differences.add("different workloads");
        }
        if (a.run.repetitions() != b.run.repetitions()) {
            differences.add("different repetition counts");
        }
        if (a.run.status() != Status.COMPLETE || b.run.status() != Status.COMPLETE) {
            differences.add("an incomplete run");
        }
        if (a.highHz > 0 && b.highHz > 0
                && (Math.abs(a.lowHz - b.lowHz) > 0.5f || Math.abs(a.highHz - b.highHz) > 0.5f)) {
            differences.add("different observed refresh rates");
        }
        if (a.run.scoringVersion() != b.run.scoringVersion()) {
            differences.add("different scoring versions");
        }
        if (!differences.isEmpty()) {
            space(overview, 10);
            overview.addView(text("Compare with care due to " + String.join(", ", differences) + ".",
                    14, muted, false));
        }

        section(target, "Workload comparison");
        LinkedHashSet<BenchmarkTest> tests = new LinkedHashSet<>(a.selectedTests());
        tests.addAll(b.selectedTests());
        for (BenchmarkTest test : tests) {
            Workload left = a.workload(test);
            Workload right = b.workload(test);
            LinearLayout block = card(target);
            block.addView(text(getString(test.title), 17, ink, true));
            space(block, 6);
            LinearLayout fields = row();
            fields.setGravity(Gravity.TOP);
            fields.addView(workloadMetrics("Run A", left), pairParams(true));
            fields.addView(workloadMetrics("Run B", right), pairParams(false));
            block.addView(fields);
        }
    }

    private LinearLayout runOverview(String label, RunReport report) {
        LinearLayout info = column();
        info.addView(text(label, 16, primary, true));
        info.addView(text(ResultLabels.device(report.run), 13, muted, false));
        info.addView(text(ResultLabels.kernel(this, report.run), 13, muted, false));
        info.addView(text(ResultLabels.status(report.run.status()), 13, muted, false));
        if (report.run.score() != null) {
            info.addView(text("Score " + ResultLabels.score(report.run.score()), 14, ink, true));
        }
        info.addView(text(report.selectedTests().size() + " workloads  ·  " + report.run.repetitions()
                + " repetitions", 13, muted, false));
        info.addView(text("Display " + ResultLabels.refresh(report.lowHz, report.highHz),
                13, muted, false));
        return info;
    }

    private LinearLayout workloadMetrics(String label, Workload workload) {
        LinearLayout metrics = column();
        metrics.addView(text(label, 14, primary, true));
        if (workload == null) {
            metrics.addView(text("Not selected", 14, muted, false));
            return metrics;
        }
        FrameStats stats = workload.stats();
        if (stats == null) {
            metrics.addView(text("No completed passes", 14, muted, false));
            return metrics;
        }
        metrics.addView(text(ResultLabels.percent(stats.jankPercent()) + " jank", 14, ink, true));
        metrics.addView(text("P95  " + ResultLabels.ms(stats.p95), 14, muted, false));
        metrics.addView(text("P99  " + ResultLabels.ms(stats.p99), 14, muted, false));
        metrics.addView(text("P95 overrun  " + ResultLabels.signedMs(stats.p95Overrun),
                13, muted, false));
        metrics.addView(text("P99 overrun  " + ResultLabels.signedMs(stats.p99Overrun),
                13, muted, false));
        if (workload.medianFirstDrawMs() != null) {
            metrics.addView(text("First detail draw  " + ResultLabels.ms(workload.medianFirstDrawMs()),
                    13, muted, false));
        }
        metrics.addView(text(workload.complete() + "/" + workload.steps().size() + " passes", 13,
                muted, false));
        return metrics;
    }

    private LinearLayout.LayoutParams pairParams(boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (first) {
            params.setMarginEnd(dp(12));
        }
        return params;
    }

    private void reportError(String message, RuntimeException error) {
        Log.e("JankBenchX", message, error);
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                showMessage(message);
            }
        });
    }

    private void showMessage(String message) {
        content.removeAllViews();
        content.addView(text(message, 15, muted, false));
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putLong(LEFT_ID, leftId);
        state.putLong(RIGHT_ID, rightId);
        super.onSaveInstanceState(state);
    }
}

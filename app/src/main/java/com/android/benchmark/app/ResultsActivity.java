package com.android.benchmark.app;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.android.benchmark.results.FrameStats;
import com.android.benchmark.results.FrameStats.Frame;
import com.android.benchmark.results.JankBreakdown;
import com.android.benchmark.results.RunReport;
import com.android.benchmark.results.RunReport.Workload;
import com.android.benchmark.results.RunStore;
import com.android.benchmark.results.RunStore.Details;
import com.android.benchmark.results.RunStore.Run;
import com.android.benchmark.results.RunStore.Status;
import com.android.benchmark.results.RunStore.Step;
import com.android.benchmark.results.RunStore.StepStatus;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;

public class ResultsActivity extends Screen {
    static final String RUN_ID = "result-run-id";
    private long runId;
    private LinearLayout content;
    private Button delete;

    private static final double P95_AXIS_MAX_MS = 1000.0 / 30;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        runId = getIntent().getLongExtra(RUN_ID, 0);
        LinearLayout root = createScreen();
        LinearLayout bar = row();
        bar.setPadding(dp(20), dp(14), dp(20), dp(10));
        bar.setBackgroundColor(surface);
        Button back = action("Back", false);
        back.setOnClickListener(view -> finish());
        bar.addView(back);
        TextView title = text("Results", 22, ink, true);
        title.setPaddingRelative(dp(12), 0, dp(4), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        delete = action("Delete", false);
        delete.setEnabled(false);
        delete.setOnClickListener(view -> confirmDelete());
        bar.addView(delete);
        root.addView(bar);
        content = scroller(root);
        content.addView(text("Loading result…", 15, muted, false));
        space(content, 8);
        ProgressBar loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setIndeterminateTintList(ColorStateList.valueOf(primary));
        loading.setIndeterminate(true);
        loading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(loading, new LinearLayout.LayoutParams(-1, dp(5)));

        RunStore store = RunStore.get(this);
        RunStore.DISK.execute(() -> {
            try {
                Details details = store.details(runId);
                RunReport report = details == null ? null : new RunReport(details);
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        if (report == null) {
                            showError("This benchmark result is unavailable.");
                        } else {
                            show(report);
                        }
                    }
                });
            } catch (RuntimeException error) {
                Log.e("JankBenchX", "Could not load result", error);
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        showError("This benchmark result could not be loaded.");
                    }
                });
            }
        });
    }

    private void show(RunReport report) {
        content.removeAllViews();
        Run run = report.run;
        delete.setEnabled(run.status() != Status.RUNNING);
        List<Workload> workloads = report.workloads();

        LinearLayout summary = card(content);
        summary.setBackground(shape(soft, 20, 0));
        int statusColor = switch (run.status()) {
            case COMPLETE -> positive;
            case FAILED -> danger;
            default -> muted;
        };
        summary.addView(text(ResultLabels.status(run.status()), 14, statusColor, true));
        space(summary, 6);
        summary.addView(text("JankBenchX score", 17, onSoft, true));
        summary.addView(text(run.score() == null ? "Unavailable"
                : ResultLabels.score(run.score()), run.score() == null ? 32 : 45, onSoft, true));
        summary.addView(text("Lower is better  ·  Version " + run.scoringVersion(), 14, onSoft, false));
        space(summary, 12);
        summary.addView(text(ResultLabels.device(run), 15, onSoft, true));
        summary.addView(text(ResultLabels.kernel(this, run), 14, onSoft, false));
        summary.addView(text(ResultLabels.date(run), 14, onSoft, false));
        summary.addView(text("Display refresh rate  " + ResultLabels.refresh(report.lowHz, report.highHz),
                14, onSoft, false));
        space(summary, 10);
        summary.addView(text(report.complete + " of " + report.passes + " passes complete  ·  "
                + report.failed + " failed  ·  " + (report.passes - report.complete - report.failed)
                + " unfinished", 14, onSoft, false));
        if (run.reason() != null) {
            space(summary, 6);
            summary.addView(text(run.reason(), 14, statusColor, false));
        }
        space(summary, 6);
        summary.addView(text("Selected workloads  " + workloads.size(), 14, onSoft, false));

        if (!workloads.isEmpty()) {
            showWorkloadSummary(workloads);
        }
        if (report.stats != null) {
            section(content, "Frame times across completed passes");
            LinearLayout frameCard = card(content);
            statistics(frameCard, report.stats);
            if (report.compositor.classified() > 0) {
                compositorDetails(frameCard, report.compositor, report.stats.frames);
            } else if (run.api() >= 36) {
                space(frameCard, 8);
                frameCard.addView(text("Compositor classification unavailable for this run.",
                        13, muted, false));
            }
            compareWorkloads(workloads);
        }

        section(content, "Workload details");
        for (Workload workload : workloads) {
            showWorkload(workload);
        }

        LinearLayout method = card(content);
        method.addView(text("How to read the score", 17, ink, true));
        space(method, 5);
        method.addView(text("The score increases with missed frame deadlines, the cost of slow frames, "
                + "and their variability. Compare runs with the same selected workloads, "
                + "repetition count, and similar observed refresh rates. Version 3 uses a different "
                + "scoring scale from JankBenchX 2.2 and older.",
                14, muted, false));
        space(method, 6);
        method.addView(text("A few long stalls can raise the score even when most frames are fast. "
                + "Expand a pass to see its score inputs. Negative deadline overrun means time "
                + "remained in the frame’s budget. A frame may have more than one compositor classification.",
                13, muted, false));
    }

    private void showWorkloadSummary(List<Workload> workloads) {
        section(content, "Workload summary");
        LinearLayout summary = card(content);
        TableLayout table = new TableLayout(this);
        table.setColumnStretchable(0, true);
        table.setColumnShrinkable(0, true);
        TableRow headings = new TableRow(this);
        headings.setGravity(Gravity.BOTTOM);
        headings.addView(text("Workload", 12, muted, true));
        headings.addView(summaryCell("Score", true));
        headings.addView(summaryCell("Jank\nframes", true));
        headings.addView(summaryCell("Bad\nframes", true));
        headings.setPadding(0, 0, 0, dp(8));
        table.addView(headings);
        View divider = new View(this);
        divider.setBackgroundColor(outline);
        table.addView(divider, new TableLayout.LayoutParams(-1, dp(1)));

        for (Workload workload : workloads) {
            TableRow line = new TableRow(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(8), 0, dp(8));
            LinearLayout label = column();
            label.addView(text(getString(workload.test().title), 14, ink, true));
            if (workload.score() == null) {
                String status = workload.failed() > 0 ? "Failed" : "Incomplete";
                label.addView(text(status, 12, workload.failed() > 0 ? danger : muted, false));
            }
            line.addView(label);
            FrameStats stats = workload.stats();
            line.addView(summaryCell(workload.score() == null ? "—"
                    : ResultLabels.score(workload.score()), false));
            line.addView(summaryCell(stats == null ? "—"
                    : summaryPercent(stats.jankPercent()), false));
            line.addView(summaryCell(stats == null ? "—"
                    : summaryPercent(stats.slowPercent()), false));
            table.addView(line);
        }
        summary.addView(table);
        space(summary, 6);
        summary.addView(text("Jank frames missed their deadline. Bad frames used at least 75% "
                + "of the frame budget. Percentages use completed passes.", 12, muted, false));
    }

    private TextView summaryCell(String label, boolean heading) {
        TextView cell = text(label, heading ? 12 : 14, heading ? muted : ink, true);
        cell.setGravity(Gravity.END);
        cell.setPaddingRelative(dp(8), 0, 0, 0);
        cell.setFontFeatureSettings("tnum");
        if (!heading) {
            cell.setSingleLine(true);
            cell.setEllipsize(null);
        }
        return cell;
    }

    private static String summaryPercent(double value) {
        return String.format(Locale.getDefault(), value == 100.0 ? "%.0f%%" : "%.2f%%", value);
    }

    private void compareWorkloads(List<Workload> workloads) {
        section(content, "95th-percentile frame time");
        LinearLayout chart = card(content);
        LinearLayout axis = row();
        String[] labels = {"0 ms", "16.7 ms", "33.3 ms"};
        for (int i = 0; i < labels.length; i++) {
            TextView tick = text(labels[i], 12, muted, false);
            tick.setGravity(i == 0 ? Gravity.START : i == 1 ? Gravity.CENTER : Gravity.END);
            axis.addView(tick, new LinearLayout.LayoutParams(0, -2, 1));
        }
        chart.addView(axis);
        space(chart, 8);
        for (Workload workload : workloads) {
            if (workload.stats() == null) {
                continue;
            }
            double p95 = workload.stats().p95;
            LinearLayout label = row();
            label.addView(text(getString(workload.test().title), 14, ink, true),
                    new LinearLayout.LayoutParams(0, -2, 1));
            label.addView(text(ResultLabels.ms(p95), 14, muted, true));
            chart.addView(label);
            space(chart, 4);
            LinearLayout track = row();
            track.setBackground(shape(outline, 4, 0));
            track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            float ratio = (float) Math.min(1, p95 / P95_AXIS_MAX_MS);
            View fill = new View(this);
            fill.setBackground(shape(primary, 4, 0));
            track.addView(fill, new LinearLayout.LayoutParams(0, dp(7), ratio));
            if (ratio < 1) {
                track.addView(new View(this), new LinearLayout.LayoutParams(0, dp(7), 1 - ratio));
            }
            chart.addView(track, new LinearLayout.LayoutParams(-1, dp(7)));
            space(chart, 10);
        }
        chart.addView(text("The scale is the same for every run. Values above 33.3 ms fill the bar "
                + "and keep their measured label.", 12, muted, false));
    }

    private void showWorkload(Workload workload) {
        LinearLayout block = card(content);
        block.addView(text(getString(workload.test().title), 19, ink, true));
        String status = workload.score() != null
                ? "Complete  ·  Score " + ResultLabels.score(workload.score())
                : workload.complete() + "/" + workload.steps().size() + " passes complete  ·  "
                + workload.failed() + " failed";
        block.addView(text(status, 14, workload.score() != null ? positive
                : workload.failed() > 0 ? danger : muted, true));
        if (workload.stats() != null) {
            space(block, 10);
            statistics(block, workload.stats());
            compositorDetails(block, workload.compositor(), workload.stats().frames);
        }
        firstDrawDetails(block, workload.medianFirstDrawMs());
        space(block, 10);
        Button expand = action("Show passes", false);
        block.addView(expand);
        LinearLayout passes = column();
        passes.setVisibility(View.GONE);
        block.addView(passes);
        expand.setOnClickListener(view -> {
            boolean opened = passes.getVisibility() == View.VISIBLE;
            if (!opened && passes.getChildCount() == 0) {
                for (Step step : workload.steps()) {
                    showStep(passes, step);
                }
            }
            passes.setVisibility(opened ? View.GONE : View.VISIBLE);
            expand.setText(opened ? "Show passes" : "Hide passes");
        });
    }

    private void showStep(LinearLayout target, Step step) {
        space(target, 12);
        View separator = new View(this);
        separator.setBackgroundColor(outline);
        target.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
        space(target, 10);
        String status = switch (step.status()) {
            case COMPLETE -> "Score " + ResultLabels.score(step.score());
            case FAILED -> "Failed";
            case PENDING -> "Incomplete";
        };
        target.addView(text("Pass " + step.repetition() + "  ·  " + status, 16,
                step.status() == StepStatus.FAILED ? danger : ink, true));
        RunStore.Measurement measurement = step.measurement();
        if (measurement.error() != null) {
            target.addView(text(measurement.error(), 14, danger, false));
        }
        if (measurement.dropped() != 0 || measurement.invalid() != 0) {
            target.addView(text(measurement.dropped() + " dropped reports  ·  "
                    + measurement.invalid() + " invalid reports", 14, muted, false));
        }
        List<Frame> frames = measurement.frames();
        if (!frames.isEmpty()) {
            space(target, 6);
            target.addView(text("Display  " + ResultLabels.refresh(measurement.lowHz(),
                    measurement.highHz()), 14, muted, false));
            FrameStats stats = FrameStats.measure(frames);
            statistics(target, stats);
            if (step.status() == StepStatus.COMPLETE) {
                scoreInputs(target, stats);
            }
            compositorDetails(target, measurement.compositor(), frames.size());
            firstDrawDetails(target, RunReport.medianFirstDrawMs(measurement.firstDrawsNs()));
            if (measurement.fallback() > 0) {
                target.addView(text(measurement.fallback() + " frames used the display refresh period",
                        13, muted, false));
            }
            space(target, 8);
            target.addView(text("Frame duration and deadline", 14, ink, true));
            FrameTimeline chart = new FrameTimeline(this, frames, primary, danger, muted, outline);
            target.addView(chart, new LinearLayout.LayoutParams(-1, dp(112)));
            target.addView(text("First → last frame  ·  Line shows the frame deadline",
                    12, muted, false));
        }
    }

    private void statistics(LinearLayout target, FrameStats stats) {
        target.addView(text(ResultLabels.percent(stats.jankPercent()) + " jank  ·  " + stats.janky
                + " of " + stats.frames + " frames missed their deadline",
                14, stats.janky == 0 ? positive : ink, true));
        metricRow(target, "Average", stats.mean, "P50", stats.p50);
        metricRow(target, "P90", stats.p90, "P95", stats.p95);
        metricRow(target, "P99", stats.p99, "Worst", stats.max);
        space(target, 6);
        target.addView(text("Deadline overrun", 14, ink, true));
        LinearLayout overrun = row();
        overrun.addView(text("P95  " + ResultLabels.signedMs(stats.p95Overrun), 14, muted, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        overrun.addView(text("P99  " + ResultLabels.signedMs(stats.p99Overrun), 14, muted, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        target.addView(overrun);
    }

    private void scoreInputs(LinearLayout target, FrameStats stats) {
        space(target, 8);
        target.addView(text("Score inputs", 14, ink, true));
        metricRow(target, "Slow-frame mean", stats.normalizedSlowMean,
                "Slow-frame spread", stats.normalizedSlowDeviation);
        target.addView(text("Slow-frame figures are normalized to a 60 Hz budget. "
                + "The score combines these with the jank percentage.",
                12, muted, false));
    }

    private void compositorDetails(LinearLayout target, JankBreakdown timing, int total) {
        if (timing.classified() == 0) {
            return;
        }
        space(target, 8);
        target.addView(text("Compositor classification", 14, ink, true));
        target.addView(text(timing.classified() + " of " + total + " frames classified",
                13, muted, false));
        target.addView(text("App " + timing.app() + "  ·  Compositor " + timing.composer()
                + "  ·  Other " + timing.other(), 13, muted, false));
    }

    private void firstDrawDetails(LinearLayout target, Double median) {
        if (median != null) {
            space(target, 8);
            target.addView(text("First detail draw  " + ResultLabels.ms(median) + " median",
                    14, ink, true));
        }
    }

    private void metricRow(LinearLayout target, String first, double a, String second, double b) {
        LinearLayout row = row();
        row.addView(text(first + "  " + ResultLabels.ms(a), 14, muted, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text(second + "  " + ResultLabels.ms(b), 14, muted, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        target.addView(row);
    }

    private void confirmDelete() {
        showDialog(new MaterialAlertDialogBuilder(materialTheme)
                .setTitle("Delete this result?")
                .setMessage("This run and its frame measurements will be removed from History.")
                .setNegativeButton("Keep result", null)
                .setPositiveButton("Delete", (dialog, button) -> {
                    delete.setEnabled(false);
                    RunStore store = RunStore.get(this);
                    RunStore.DISK.execute(() -> {
                        try {
                            store.delete(runId);
                            runOnUiThread(this::finish);
                        } catch (RuntimeException e) {
                            Log.e("JankBenchX", "Could not delete benchmark result", e);
                            runOnUiThread(() -> {
                                if (!isDestroyed()) {
                                    delete.setEnabled(true);
                                    android.widget.Toast.makeText(this, "Could not delete this result.",
                                            android.widget.Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
                })
                .create());
    }

    private void showError(String message) {
        content.removeAllViews();
        content.addView(text(message, 16, danger, true));
    }
}

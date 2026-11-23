package com.android.benchmark.results;

import com.android.benchmark.results.FrameStats.Frame;
import com.android.benchmark.results.RunStore.Details;
import com.android.benchmark.results.RunStore.Measurement;
import com.android.benchmark.results.RunStore.Run;
import com.android.benchmark.results.RunStore.Step;
import com.android.benchmark.results.RunStore.StepStatus;
import com.android.benchmark.ui.BenchmarkTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RunReport {
    public record Workload(BenchmarkTest test, List<Step> steps, FrameStats stats, Double score,
                           int complete, int failed, JankBreakdown compositor,
                           Double medianFirstDrawMs) {}

    public final Run run;
    public final FrameStats stats;
    public final JankBreakdown compositor;
    public final int passes;
    public final int complete;
    public final int failed;
    public final float lowHz;
    public final float highHz;
    private final Map<BenchmarkTest, Workload> workloads = new LinkedHashMap<>();

    public RunReport(Details details) {
        run = details.run();
        passes = details.steps().size();
        Map<BenchmarkTest, List<Step>> byWorkload = new LinkedHashMap<>();
        ArrayList<Frame> frames = new ArrayList<>();
        JankBreakdown classifications = JankBreakdown.EMPTY;
        float low = Float.POSITIVE_INFINITY;
        float high = 0;
        for (Step step : details.steps()) {
            byWorkload.computeIfAbsent(step.test().canonical(), key -> new ArrayList<>()).add(step);
            Measurement measurement = step.measurement();
            if (step.status() == StepStatus.COMPLETE) {
                frames.addAll(measurement.frames());
                classifications = classifications.plus(measurement.compositor());
            }
            if (measurement.highHz() > 0) {
                low = Math.min(low, measurement.lowHz());
                high = Math.max(high, measurement.highHz());
            }
        }
        int finished = 0;
        int failures = 0;
        for (Map.Entry<BenchmarkTest, List<Step>> entry : byWorkload.entrySet()) {
            Workload workload = summarize(entry.getKey(), entry.getValue());
            workloads.put(entry.getKey(), workload);
            finished += workload.complete();
            failures += workload.failed();
        }
        complete = finished;
        failed = failures;
        stats = frames.isEmpty() ? null : FrameStats.measure(frames);
        compositor = classifications;
        lowHz = high > 0 ? low : 0;
        highHz = high;
    }

    public Set<BenchmarkTest> selectedTests() {
        return Collections.unmodifiableSet(workloads.keySet());
    }

    public List<Workload> workloads() {
        return new ArrayList<>(workloads.values());
    }

    public Workload workload(BenchmarkTest test) {
        return workloads.get(test.canonical());
    }

    public static Double medianFirstDrawMs(List<Long> draws) {
        int count = draws.size();
        if (count == 0) {
            return null;
        }
        ArrayList<Long> ordered = new ArrayList<>(draws);
        Collections.sort(ordered);
        double centered = ordered.get(count / 2);
        if (count % 2 == 0) {
            centered = (centered + ordered.get(count / 2 - 1)) / 2;
        }
        return centered / 1_000_000;
    }

    private static Workload summarize(BenchmarkTest test, List<Step> steps) {
        ArrayList<Double> scores = new ArrayList<>();
        ArrayList<Frame> frames = new ArrayList<>();
        ArrayList<Long> draws = new ArrayList<>();
        JankBreakdown classifications = JankBreakdown.EMPTY;
        int failed = 0;
        for (Step step : steps) {
            if (step.status() == StepStatus.COMPLETE) {
                Measurement measurement = step.measurement();
                frames.addAll(measurement.frames());
                draws.addAll(measurement.firstDrawsNs());
                classifications = classifications.plus(measurement.compositor());
                scores.add(step.score());
            } else if (step.status() == StepStatus.FAILED) {
                failed++;
            }
        }
        Double score = scores.size() == steps.size() ? FrameStats.geometricMean(scores) : null;
        FrameStats stats = frames.isEmpty() ? null : FrameStats.measure(frames);
        return new Workload(test, steps, stats, score, scores.size(), failed, classifications,
                medianFirstDrawMs(draws));
    }
}

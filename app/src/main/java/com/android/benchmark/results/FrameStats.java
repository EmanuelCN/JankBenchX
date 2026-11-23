/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.benchmark.results;

import java.util.Arrays;
import java.util.List;

public final class FrameStats {
    private static final double NS_PER_MS = 1_000_000;
    private static final double REFERENCE_FRAME_MS = 1000.0 / 60;

    public record Frame(long durationNs, long budgetNs) {
        public Frame {
            if (durationNs <= 0 || budgetNs <= 0) {
                throw new IllegalArgumentException("Frame duration and budget must be positive");
            }
        }

        public boolean missedDeadline() {
            return durationNs >= budgetNs;
        }
    }

    public final int frames;
    public final int janky;
    public final int slow;
    public final double mean;
    public final double p50;
    public final double p90;
    public final double p95;
    public final double p99;
    public final double max;
    public final double p95Overrun;
    public final double p99Overrun;
    public final double score;
    public final double normalizedSlowMean;
    public final double normalizedSlowDeviation;

    private FrameStats(double[] durations, double[] overruns, int janky, int slow,
                       double total, double normalizedSlowMean, double normalizedSlowDeviation) {
        Arrays.sort(durations);
        Arrays.sort(overruns);
        frames = durations.length;
        this.janky = janky;
        this.slow = slow;
        mean = total / frames;
        p50 = percentile(durations, 0.50);
        p90 = percentile(durations, 0.90);
        p95 = percentile(durations, 0.95);
        p99 = percentile(durations, 0.99);
        max = durations[frames - 1];
        p95Overrun = percentile(overruns, 0.95);
        p99Overrun = percentile(overruns, 0.99);
        this.normalizedSlowMean = normalizedSlowMean;
        this.normalizedSlowDeviation = normalizedSlowDeviation;
        score = 1 + normalizedSlowMean * jankPercent() * Math.max(1, normalizedSlowDeviation);
    }

    public static FrameStats measure(List<Frame> samples) {
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("At least one measured frame is required");
        }
        double[] durations = new double[samples.size()];
        double[] overruns = new double[samples.size()];
        int janky = 0;
        int slow = 0;
        double total = 0;
        double slowMean = 0;
        double slowSquaredDeviation = 0;
        for (int i = 0; i < samples.size(); i++) {
            Frame frame = samples.get(i);
            durations[i] = frame.durationNs() / NS_PER_MS;
            total += durations[i];
            overruns[i] = (frame.durationNs() - frame.budgetNs()) / NS_PER_MS;
            if (frame.missedDeadline()) {
                janky++;
            }
            double budgetFraction = (double) frame.durationNs() / frame.budgetNs();
            if (budgetFraction >= 0.75) {
                double normalized = budgetFraction * REFERENCE_FRAME_MS;
                double delta = normalized - slowMean;
                slowMean += delta / ++slow;
                slowSquaredDeviation += delta * (normalized - slowMean);
            }
        }
        double deviation = slow > 1 ? Math.sqrt(slowSquaredDeviation / (slow - 1)) : 0;
        return new FrameStats(durations, overruns, janky, slow, total, slowMean, deviation);
    }

    public double jankPercent() {
        return 100.0 * janky / frames;
    }

    public double slowPercent() {
        return 100.0 * slow / frames;
    }

    public static double geometricMean(List<Double> scores) {
        if (scores.isEmpty()) {
            throw new IllegalArgumentException("At least one score is required");
        }
        double logs = 0;
        for (double score : scores) {
            if (!Double.isFinite(score) || score < 1) {
                throw new IllegalArgumentException("Invalid score");
            }
            logs += Math.log(score);
        }
        return Math.exp(logs / scores.size());
    }

    private static double percentile(double[] sorted, double percentile) {
        double rank = percentile * (sorted.length - 1);
        int lower = (int) rank;
        int upper = Math.min(lower + 1, sorted.length - 1);
        return sorted[lower] + (rank - lower) * (sorted[upper] - sorted[lower]);
    }
}

package com.android.benchmark.results;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.benchmark.results.FrameStats.Frame;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FrameStatsTest {
    @Test
    public void calculatesAverageAndInterpolatedPercentilesInMilliseconds() {
        FrameStats stats = FrameStats.measure(List.of(
                frame(40, 50), frame(10, 50), frame(30, 50), frame(20, 50)));

        assertEquals(25, stats.mean, 0.0001);
        assertEquals(25, stats.p50, 0.0001);
        assertEquals(37, stats.p90, 0.0001);
        assertEquals(38.5, stats.p95, 0.0001);
        assertEquals(39.7, stats.p99, 0.0001);
        assertEquals(40, stats.max, 0.0001);
        assertEquals(1, stats.score, 0.0001);
    }

    @Test
    public void usesEachFramesBudgetIncludingConsecutiveMisses() {
        FrameStats stats = FrameStats.measure(List.of(
                frame(10, 16), frame(10, 8), frame(10, 8), frame(10, 10)));

        assertEquals(4, stats.frames);
        assertEquals(3, stats.janky);
        assertEquals(75, stats.jankPercent(), 0);
    }

    @Test
    public void aSingleLongStallCanRaiseTheScoreWithOnlyTenPercentBadFrames() {
        List<Frame> input = new ArrayList<>(Collections.nCopies(90, frame(4, 8)));
        input.addAll(Collections.nCopies(9, frame(6, 8)));
        input.add(frame(160, 8));

        FrameStats stats = FrameStats.measure(input);
        assertEquals(10, stats.slowPercent(), 0);
        assertEquals(1, stats.jankPercent(), 0);
        assertEquals(160, stats.max, 0);
        assertTrue(stats.score > 4_000);
        assertEquals(1 + stats.normalizedSlowMean * stats.jankPercent()
                * stats.normalizedSlowDeviation, stats.score, 0.0001);
    }

    @Test
    public void deadlineOverrunPercentilesUseEachFramesOwnBudget() {
        FrameStats stats = FrameStats.measure(List.of(
                frame(6, 8), frame(10, 8), frame(14, 16), frame(20, 16)));
        assertEquals(3.7, stats.p95Overrun, 0.0001);
        assertEquals(3.94, stats.p99Overrun, 0.0001);
        assertEquals(-2, FrameStats.measure(List.of(frame(6, 8))).p95Overrun, 0.0001);
    }

    @Test
    public void badFramePercentageIncludesTheThresholdAndMissedDeadlines() {
        FrameStats stats = FrameStats.measure(List.of(
                frame(5, 8), frame(6, 8), frame(10, 8), frame(6, 10)));

        assertEquals(1, stats.janky);
        assertEquals(2, stats.slow);
        assertEquals(50, stats.slowPercent(), 0);
    }

    @Test
    public void equallySlowFramesStillPenalizeScoreAndNormalizeAcrossBudgets() {
        FrameStats fastDisplay = FrameStats.measure(List.of(frame(10, 8), frame(10, 8)));
        FrameStats slowDisplay = FrameStats.measure(List.of(frame(20, 16), frame(20, 16)));

        assertTrue(fastDisplay.score > 1);
        assertEquals(fastDisplay.score, slowDisplay.score, 0.0001);
    }

    @Test
    public void computesGeometricMeanInDoublePrecision() {
        assertEquals(4, FrameStats.geometricMean(List.of(1.0, 16.0)), 0.0001);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyCaptureHasNoStatistics() {
        FrameStats.measure(List.of());
    }

    private static Frame frame(int durationMs, int budgetMs) {
        return new Frame(durationMs * 1_000_000L, budgetMs * 1_000_000L);
    }
}

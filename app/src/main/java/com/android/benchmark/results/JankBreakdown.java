package com.android.benchmark.results;

/** Counts among frames matched to compositor reports. The causes can overlap. */
public record JankBreakdown(int classified, int app, int composer, int other) {
    public static final JankBreakdown EMPTY = new JankBreakdown(0, 0, 0, 0);

    public JankBreakdown plus(JankBreakdown breakdown) {
        return new JankBreakdown(classified + breakdown.classified,
                app + breakdown.app, composer + breakdown.composer, other + breakdown.other);
    }
}

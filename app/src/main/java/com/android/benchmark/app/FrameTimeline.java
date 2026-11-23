package com.android.benchmark.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import com.android.benchmark.results.FrameStats.Frame;

import java.util.List;

final class FrameTimeline extends View {
    private final List<Frame> frames;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int normal;
    private final int missed;
    private final int deadline;
    private final int baseline;
    private final long ceiling;

    FrameTimeline(Context context, List<Frame> frames, int normal, int missed, int deadline,
                  int baseline) {
        super(context);
        this.frames = frames;
        this.normal = normal;
        this.missed = missed;
        this.deadline = deadline;
        this.baseline = baseline;
        long top = 1;
        for (Frame frame : frames) {
            top = Math.max(top, Math.max(frame.durationNs(), frame.budgetNs()));
        }
        ceiling = top;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float bottom = getHeight() - 2;
        float height = bottom - 8;
        int columns = Math.min(frames.size(), getWidth());
        if (columns < 1 || height <= 0) {
            return;
        }
        float width = (float) getWidth() / columns;
        paint.setColor(baseline);
        canvas.drawLine(0, bottom, getWidth(), bottom, paint);
        float previous = 0;
        for (int i = 0; i < columns; i++) {
            int from = i * frames.size() / columns;
            int end = (i + 1) * frames.size() / columns;
            long peak = 0;
            long budget = 0;
            boolean jank = false;
            for (int j = from; j < end; j++) {
                Frame frame = frames.get(j);
                peak = Math.max(peak, frame.durationNs());
                budget += frame.budgetNs();
                jank |= frame.missedDeadline();
            }
            float x = i * width;
            paint.setColor(jank ? missed : normal);
            canvas.drawRect(x, bottom - height * peak / ceiling,
                    x + Math.max(1, width * 0.85f), bottom, paint);
            float budgetY = bottom - height * budget / (end - from) / ceiling;
            paint.setColor(deadline);
            paint.setStrokeWidth(2);
            canvas.drawLine(x, i == 0 ? budgetY : previous, x + width, budgetY, paint);
            previous = budgetY;
        }
    }
}

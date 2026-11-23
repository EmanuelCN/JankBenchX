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

package com.android.benchmark.ui.automation;

import android.os.Build;
import android.os.Handler;
import android.view.Display;
import android.view.FrameMetrics;
import android.view.Window;

import com.android.benchmark.results.FrameStats.Frame;
import com.android.benchmark.results.JankBreakdown;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class FrameCapture implements AutoCloseable {
    private static final long END_REPORT_TIMEOUT_MS = 1_800;
    private static final long COMPOSITOR_DRAIN_MS = 200;

    private final Window window;
    private final Handler handler;
    private final CompositorCapture compositor;
    private final Window.OnFrameMetricsAvailableListener listener = this::onFrame;
    private final Runnable publish = this::publish;
    private final ArrayList<Frame> frames = new ArrayList<>(256);
    private final ReportWindow reports = new ReportWindow();
    private volatile float currentHz;
    private volatile boolean closed;
    private int invalidTiming;
    private int fallback;
    private float lowHz = Float.POSITIVE_INFINITY;
    private float highHz;
    private Consumer<Capture> completion;

    record Capture(List<Frame> frames, int dropped, int invalid, int fallback,
                   float lowHz, float highHz, JankBreakdown jank, boolean confirmedEnd) {}

    FrameCapture(Window window, Handler handler) {
        this.window = window;
        this.handler = handler;
        window.addOnFrameMetricsAvailableListener(listener, handler);
        compositor = Build.VERSION.SDK_INT >= 36 ? CompositorCapture.attach(window, handler) : null;
    }

    void setRefreshRate(Display display) {
        currentHz = display == null ? 0 : display.getRefreshRate();
    }

    void begin(long frameTime) {
        reports.begin(frameTime);
    }

    void end(long frameTime, Consumer<Capture> onComplete) {
        reports.end(frameTime);
        handler.post(() -> {
            if (!closed) {
                completion = onComplete;
                if (reports.reachedEnd()) {
                    drainCompositor();
                } else {
                    handler.postDelayed(publish, END_REPORT_TIMEOUT_MS);
                }
            }
        });
    }

    private void onFrame(Window source, FrameMetrics metrics, int droppedSinceLast) {
        if (closed || reports.reachedEnd()) {
            return;
        }
        if (!reports.accepts(metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP), droppedSinceLast)) {
            if (reports.reachedEnd() && completion != null) {
                drainCompositor();
            }
            return;
        }
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1) {
            return;
        }

        long duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
        long budget = Build.VERSION.SDK_INT >= 31 ? metrics.getMetric(FrameMetrics.DEADLINE) : -1;
        float hz = currentHz;
        if (hz > 0 && Float.isFinite(hz)) {
            lowHz = Math.min(lowHz, hz);
            highHz = Math.max(highHz, hz);
            if (budget <= 0) {
                budget = Math.round(1_000_000_000.0 / hz);
                fallback++;
            }
        }
        if (duration <= 0 || budget <= 0) {
            invalidTiming++;
            return;
        }
        frames.add(new Frame(duration, budget));
        if (Build.VERSION.SDK_INT >= 36 && compositor != null) {
            compositor.addFrame(metrics);
        }
    }

    private void drainCompositor() {
        handler.removeCallbacks(publish);
        if (Build.VERSION.SDK_INT >= 36 && compositor != null) {
            compositor.flush();
            handler.postDelayed(publish, COMPOSITOR_DRAIN_MS);
        } else {
            publish();
        }
    }

    private void publish() {
        if (closed || completion == null) {
            return;
        }
        Capture capture = new Capture(new ArrayList<>(frames), reports.dropped(),
                reports.invalidTimestamps() + invalidTiming, fallback,
                Float.isFinite(lowHz) ? lowHz : 0, highHz,
                Build.VERSION.SDK_INT >= 36 && compositor != null
                        ? compositor.summary() : JankBreakdown.EMPTY,
                reports.confirmedEnd());
        Consumer<Capture> callback = completion;
        completion = null;
        callback.accept(capture);
    }

    @Override
    public void close() {
        closed = true;
        window.removeOnFrameMetricsAvailableListener(listener);
        handler.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 36 && compositor != null) {
            handler.post(compositor::close);
        }
    }

    // The UI thread sets the bounds. The metrics looper reads and accounts for reports.
    static final class ReportWindow {
        private volatile long first = Long.MAX_VALUE;
        private volatile long last = Long.MAX_VALUE;
        private long lastInWindow = Long.MIN_VALUE;
        private int dropped;
        private int invalidTimestamp;
        private boolean reachedEnd;

        void begin(long frameTime) {
            first = frameTime;
        }

        void end(long frameTime) {
            last = frameTime;
        }

        boolean accepts(long vsync, int droppedSinceLast) {
            long start = first;
            if (start == Long.MAX_VALUE || reachedEnd) {
                return false;
            }
            if (vsync <= 0) {
                invalidTimestamp++;
                dropped += droppedSinceLast;
                return false;
            }
            if (vsync < start) {
                return false;
            }
            long end = last;
            if (vsync > end) {
                reachedEnd = true;
                if (lastInWindow < end) {
                    dropped += droppedSinceLast;
                }
                return false;
            }
            // At the exact start, every drop since the previous report predates sampling.
            if (vsync != start) {
                dropped += droppedSinceLast;
            }
            lastInWindow = vsync;
            return true;
        }

        int dropped() {
            return dropped;
        }

        int invalidTimestamps() {
            return invalidTimestamp;
        }

        boolean reachedEnd() {
            return reachedEnd;
        }

        boolean confirmedEnd() {
            // A report at the exact end also proves the workload's frame stream was delivered.
            return reachedEnd || lastInWindow == last;
        }
    }
}

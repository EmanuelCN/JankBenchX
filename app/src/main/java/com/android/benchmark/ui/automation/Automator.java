/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.benchmark.ui.automation;

import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Display;
import android.view.View;
import android.view.Window;

import com.android.benchmark.results.RunStore.Measurement;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Runs one workload on the main thread and observes its window on a separate looper. */
public final class Automator implements AutoCloseable {
    private static final long WARMUP_NS = 250_000_000;
    private static final long TIMEOUT_MS = 15_000;
    private static final int MIN_FRAMES = 12;

    public interface Workload extends AutoCloseable {
        // Return true after making the last UI change for this iteration.
        boolean advance(long elapsedNanos);

        default boolean ready() { return true; }
        default void prepare() {}
        default List<Long> firstDrawsNs() { return Collections.emptyList(); }
        @Override
        default void close() {}
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Choreographer choreographer = Choreographer.getInstance();
    public record Result(Measurement measurement, boolean retryable) {}

    private final Window window;
    private final View workloadView;
    private final Workload workload;
    private final Handler metricsHandler;
    private final Consumer<Result> result;
    private final DisplayManager displays;
    private final Choreographer.FrameCallback frameCallback = this::frame;
    private long warmupStart = Long.MIN_VALUE;
    private long startTime = Long.MIN_VALUE;
    private FrameCapture collector;
    private boolean closed;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            Display current = window.getDecorView().getDisplay();
            if (collector != null && current != null && current.getDisplayId() == id) {
                collector.setRefreshRate(current);
            }
        }
    };

    public Automator(Window window, View workloadView, Workload workload,
                     Handler metricsHandler, Consumer<Result> result) {
        this.window = window;
        this.workloadView = workloadView;
        this.workload = workload;
        this.metricsHandler = metricsHandler;
        this.result = result;
        displays = window.getContext().getSystemService(DisplayManager.class);
    }

    public void start() {
        try {
            if (!window.getDecorView().isHardwareAccelerated()) {
                throw new IllegalStateException("Hardware-accelerated rendering is unavailable");
            }
            workload.prepare();
            collector = new FrameCapture(window, metricsHandler);
            collector.setRefreshRate(window.getDecorView().getDisplay());
            displays.registerDisplayListener(displayListener, main);
            main.postDelayed(() -> fail("Workload timed out"), TIMEOUT_MS);
            choreographer.postFrameCallback(frameCallback);
        } catch (RuntimeException e) {
            fail(e.getMessage());
        }
    }

    private void frame(long frameTime) {
        try {
            if (warmupStart == Long.MIN_VALUE) {
                warmupStart = frameTime;
            }
            if (frameTime - warmupStart < WARMUP_NS) {
                workloadView.invalidate();
                choreographer.postFrameCallback(frameCallback);
                return;
            }
            if (startTime == Long.MIN_VALUE) {
                startTime = frameTime;
                collector.begin(frameTime);
            }
            if (workload.advance(frameTime - startTime)) {
                collector.end(frameTime, capture -> main.post(() -> finish(capture)));
                // A subsequent draw gives the timing listener an end marker outside the sample.
                workloadView.postInvalidateOnAnimation();
            } else {
                choreographer.postFrameCallback(frameCallback);
            }
        } catch (RuntimeException e) {
            fail(e.getMessage());
        }
    }

    private void finish(FrameCapture.Capture capture) {
        if (closed) {
            return;
        }
        String error = null;
        if (capture.dropped() > 0) {
            error = "Android dropped frame timing reports during the capture";
        } else if (capture.invalid() > 0) {
            error = "Android supplied invalid frame timings during the capture";
        } else if (!capture.confirmedEnd()) {
            error = "Android did not report the end of the frame capture";
        } else if (capture.frames().size() < MIN_FRAMES) {
            error = "Too few frames were measured";
        }
        deliver(new Measurement(capture.frames(), capture.dropped(), capture.invalid(),
                capture.fallback(), capture.lowHz(), capture.highHz(), error, capture.jank(),
                workload.firstDrawsNs()), error != null);
    }

    private void fail(String reason) {
        deliver(new Measurement(Collections.emptyList(), 0, 0, 0, 0, 0,
                reason == null || reason.trim().isEmpty() ? "Workload could not execute" : reason), false);
    }

    private void deliver(Measurement measurement, boolean retryable) {
        if (!closed) {
            close();
            result.accept(new Result(measurement, retryable));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        main.removeCallbacksAndMessages(null);
        choreographer.removeFrameCallback(frameCallback);
        if (collector != null) {
            displays.unregisterDisplayListener(displayListener);
            collector.close();
        }
        workload.close();
    }
}

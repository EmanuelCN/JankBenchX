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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import com.android.benchmark.R;
import com.android.benchmark.results.RunStore;
import com.android.benchmark.results.RunStore.Measurement;
import com.android.benchmark.results.RunStore.Status;
import com.android.benchmark.ui.BenchmarkTest;
import com.android.benchmark.ui.WorkloadViews;
import com.android.benchmark.ui.WorkloadViews.Scene;
import com.android.benchmark.ui.automation.Automator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RunLocalBenchmarksActivity extends Screen {
    static final String TESTS = "tests";
    static final String REPETITIONS = "repetitions";
    private static final String RUN_ID = "run-id";
    private static final long SETTLE_MS = 350;
    private static final long CAPTURE_RETRY_DELAY_MS = 500;
    private static final long START_TIMEOUT_MS = 7_000;
    private static final long BETWEEN_STEPS_MS = 80;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable launchStep = this::startStep;
    private final Runnable beginCapture = this::beginCapture;
    private final List<BenchmarkTest> tests = new ArrayList<>();
    private long runId;
    private int repetitions;
    private int position;
    private long readyDeadline;
    private boolean resumed;
    private boolean focused;
    private boolean finishingOnDisk;
    private boolean resultReady;
    private boolean savingStep;
    private boolean retryingCapture;
    private boolean destroyed;
    private Status ending;
    private String endingReason;
    private FrameLayout holder;
    private TextView workloadName;
    private TextView progressLabel;
    private ProgressBar progress;
    private WaveProgressDrawable progressWave;
    private View cancelButton;
    private Scene scene;
    private Automator automator;
    private HandlerThread metricsThread;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout screen = createScreen();
        LinearLayout header = column();
        int progressHeight = dp(14);
        int progressGapDp = 4;
        // Rebalance the space below the name without changing the workload viewport.
        int progressArea = dp(8) + dp(7) + dp(10);
        int bottomPadding = progressArea - progressHeight - dp(progressGapDp);
        header.setPadding(dp(20), dp(16), dp(20), bottomPadding);
        header.setBackgroundColor(runnerColors.getHeader());
        LinearLayout bar = row();
        progressLabel = text("Preparing benchmark", 14, runnerColors.getSupporting(), true);
        progressLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bar.addView(progressLabel, new LinearLayout.LayoutParams(0, -2, 1));
        cancelButton = cancelAction();
        cancelButton.setOnClickListener(view -> end(Status.CANCELLED, "Cancelled by the user."));
        bar.addView(cancelButton);
        header.addView(bar);
        workloadName = text("JankBenchX", 20, runnerColors.getTitle(), true);
        header.addView(workloadName);
        space(header, progressGapDp);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setId(android.R.id.progress);
        progressWave = new WaveProgressDrawable(this, runnerColors.getProgress(), runnerColors.getTrack());
        progress.setProgressDrawable(progressWave);
        header.addView(progress, new LinearLayout.LayoutParams(-1, progressHeight));
        screen.addView(header);

        holder = new FrameLayout(this) {
            @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
            @Override public boolean onTouchEvent(MotionEvent event) { return true; }
        };
        holder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        holder.setClipChildren(true);
        screen.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                end(Status.CANCELLED, "Cancelled by the user.");
            }
        });
        if (state != null) {
            runId = state.getLong(RUN_ID);
            if (runId == 0) {
                finish();
            } else {
                end(Status.CANCELLED, "The benchmark screen was interrupted.");
            }
            return;
        }

        String[] selected = getIntent().getStringArrayExtra(TESTS);
        if (selected != null) {
            for (String name : selected) {
                BenchmarkTest test = BenchmarkTest.valueOf(name).canonical();
                if (test.isSupported(this)) {
                    tests.add(test);
                }
            }
        }
        repetitions = getIntent().getIntExtra(REPETITIONS, 5);
        progress.setMax(tests.size() * repetitions);
        RunStore store = RunStore.get(this);
        RunStore.DISK.execute(() -> {
            try {
                long id = store.start(tests, repetitions);
                main.post(() -> {
                    runId = id;
                    if (ending != null) {
                        finishOnDisk();
                    } else {
                        scheduleStep();
                    }
                });
            } catch (RuntimeException error) {
                main.post(() -> storageError("Could not start the benchmark.", error));
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        progressWave.start();
    }

    @Override
    protected void onStop() {
        progressWave.stop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (resultReady) {
            openResult();
        } else {
            scheduleStep();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (!isFinishing() && ending == null) {
            end(Status.CANCELLED, "The benchmark left the foreground.");
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        boolean lostFocus = focused && !hasFocus;
        focused = hasFocus;
        if (lostFocus && ending == null) {
            end(Status.CANCELLED, "The benchmark window lost focus.");
        } else if (hasFocus) {
            scheduleStep();
        }
    }

    private void scheduleStep() {
        if (resumed && focused && runId > 0 && ending == null && scene == null && !savingStep) {
            main.removeCallbacks(launchStep);
            main.postDelayed(launchStep, retryingCapture ? CAPTURE_RETRY_DELAY_MS : BETWEEN_STEPS_MS);
        }
    }

    private void startStep() {
        if (!resumed || !focused || ending != null || scene != null || savingStep) {
            return;
        }
        BenchmarkTest test = tests.get(position / repetitions);
        int round = 1 + position % repetitions;
        progressLabel.setText(getString(R.string.run_progress,
                position + 1, tests.size() * repetitions, round, repetitions));
        workloadName.setText(test.title);
        try {
            scene = WorkloadViews.create(this, holder, test);
            holder.addView(scene.view(), new FrameLayout.LayoutParams(-1, -1));
            // Advance the marker during the next workload's settling period.
            progress.setProgress(position);
            readyDeadline = SystemClock.uptimeMillis() + START_TIMEOUT_MS;
            main.postDelayed(beginCapture, SETTLE_MS);
        } catch (RuntimeException error) {
            Log.w("JankBenchX", "Workload setup failed", error);
            failStep("The workload could not be initialized.");
        }
    }

    private void beginCapture() {
        if (ending != null || scene == null) {
            return;
        }
        try {
            View view = scene.view();
            if (!view.isLaidOut() || view.isLayoutRequested()
                    || view.getWidth() < 1 || view.getHeight() < 1 || !scene.workload().ready()) {
                if (SystemClock.uptimeMillis() < readyDeadline) {
                    main.postDelayed(beginCapture, 80);
                } else {
                    failStep("The workload did not become ready.");
                }
                return;
            }
            if (metricsThread == null) {
                metricsThread = new HandlerThread("JankBench frame metrics");
                metricsThread.start();
            }
            automator = new Automator(getWindow(), view, scene.workload(),
                    new Handler(metricsThread.getLooper()), this::onCaptureFinished);
            automator.start();
        } catch (RuntimeException error) {
            Log.w("JankBenchX", "Workload preparation failed", error);
            failStep("The workload could not be prepared.");
        }
    }

    private void onCaptureFinished(Automator.Result result) {
        automator = null;
        // Automator already closed the workload that produced this measurement.
        scene = null;
        holder.removeAllViews();
        if (result.retryable() && !retryingCapture) {
            retryingCapture = true;
            progressLabel.setText(R.string.retrying_capture);
            scheduleStep();
        } else {
            saveStep(result.measurement());
        }
    }

    private void failStep(String error) {
        clearScene();
        saveStep(new Measurement(Collections.emptyList(), 0, 0, 0, 0, 0, error));
    }

    private void saveStep(Measurement measurement) {
        savingStep = true;
        int completedPosition = position;
        RunStore store = RunStore.get(this);
        RunStore.DISK.execute(() -> {
            try {
                boolean saved = store.saveStep(runId, completedPosition, measurement);
                main.post(() -> {
                    savingStep = false;
                    if (ending != null) {
                        return;
                    }
                    if (!saved) {
                        end(Status.FAILED, "The workload result could not be saved.");
                        return;
                    }
                    retryingCapture = false;
                    position++;
                    if (position == tests.size() * repetitions) {
                        end(Status.COMPLETE, null);
                    } else {
                        scheduleStep();
                    }
                });
            } catch (RuntimeException error) {
                Log.e("JankBenchX", "Could not save a benchmark workload", error);
                main.post(() -> end(Status.FAILED, "The workload result could not be saved."));
            }
        });
    }

    private void clearScene() {
        if (automator != null) {
            automator.close();
            automator = null;
        } else if (scene != null) {
            scene.workload().close();
        }
        scene = null;
        holder.removeAllViews();
    }

    private void end(Status status, String reason) {
        if (ending != null) {
            return;
        }
        ending = status;
        endingReason = reason;
        main.removeCallbacks(launchStep);
        main.removeCallbacks(beginCapture);
        clearScene();
        if (metricsThread != null) {
            metricsThread.quitSafely();
            metricsThread = null;
        }
        cancelButton.setEnabled(false);
        progressLabel.setText("Saving results");
        finishOnDisk();
    }

    private void finishOnDisk() {
        if (runId == 0 || finishingOnDisk) {
            return;
        }
        finishingOnDisk = true;
        RunStore store = RunStore.get(this);
        RunStore.DISK.execute(() -> {
            try {
                store.finish(runId, ending, endingReason);
                main.post(() -> {
                    resultReady = true;
                    openResult();
                });
            } catch (RuntimeException error) {
                // Let the next database open recover an interrupted row after a storage failure.
                try {
                    store.close();
                } catch (RuntimeException closeError) {
                    error.addSuppressed(closeError);
                }
                main.post(() -> storageError("Could not finalize the benchmark result.", error));
            }
        });
    }

    private void openResult() {
        if (!destroyed && !isFinishing() && resumed && resultReady) {
            startActivity(new Intent(this, ResultsActivity.class)
                    .putExtra(ResultsActivity.RUN_ID, runId));
            finish();
        }
    }

    private void storageError(String message, RuntimeException error) {
        Log.e("JankBenchX", message, error);
        if (!destroyed) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putLong(RUN_ID, runId);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (ending == null && !isFinishing()) {
            end(Status.CANCELLED, "The benchmark screen was interrupted.");
        }
        super.onDestroy();
    }
}

package com.android.benchmark.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.widget.ProgressBar;

import com.android.benchmark.results.FrameStats.Frame;
import com.android.benchmark.results.RunStore;
import com.android.benchmark.results.RunStore.Measurement;
import com.android.benchmark.results.RunStore.Status;
import com.android.benchmark.ui.BenchmarkTest;
import com.android.benchmark.ui.automation.Automator;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BenchmarkFlowTest {
    @After
    public void removeTestData() {
        Context context = RuntimeEnvironment.getApplication();
        RunStore.get(context).close();
        context.deleteDatabase("BenchmarkResults");
    }

    @Test
    @Config(sdk = 35)
    public void windowOptsOutOfAdaptiveRefreshWithoutVotingForAHardwareMode() {
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY,
                displayMode(1, 60), displayMode(2, 120), displayMode(3, 144));
        Context context = RuntimeEnvironment.getApplication();
        // The legacy AOSP setting can disagree with the cap stored by the device's settings UI.
        for (float peak : new float[]{120f, Float.POSITIVE_INFINITY}) {
            Settings.System.putFloat(context.getContentResolver(), "peak_refresh_rate", peak);
            try (ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup()) {
                assertEquals(0f, home.get().getWindow().getAttributes().preferredRefreshRate, 0);
                assertEquals(0, home.get().getWindow().getAttributes().preferredDisplayModeId);
                assertFalse(home.get().getWindow().isFrameRatePowerSavingsBalanced());
            }
        }
    }

    @Test
    public void invalidCaptureRetriesTheSamePassAndKeepsTheValidSlowResult() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(context, RunLocalBenchmarksActivity.class)
                .putExtra(RunLocalBenchmarksActivity.TESTS, new String[]{BenchmarkTest.UI_SAVE_LAYER.name()})
                .putExtra(RunLocalBenchmarksActivity.REPETITIONS, 2);
        try (ActivityController<RunLocalBenchmarksActivity> runner = Robolectric
                .buildActivity(RunLocalBenchmarksActivity.class, intent).setup()) {
            waitForDisk();
            long id = ReflectionHelpers.getField(runner.get(), "runId");
            RunStore store = RunStore.get(context);
            Measurement incomplete = new Measurement(List.of(), 1, 0, 0, 120, 120, "Dropped reports");
            Measurement slow = new Measurement(List.of(new Frame(25_000_000, 8_333_333)),
                    0, 0, 0, 120, 120, null);

            ProgressBar progress = runner.get().findViewById(android.R.id.progress);
            assertTrue(progress.isIndeterminate());
            progress.setIndeterminate(false);
            deliverCapture(runner.get(), incomplete, true);
            waitForDisk();
            assertTrue(progress.isIndeterminate());
            assertEquals(RunStore.StepStatus.PENDING, store.details(id).steps().get(0).status());
            deliverCapture(runner.get(), slow, false);
            waitForDisk();
            assertEquals(slow, store.details(id).steps().get(0).measurement());

            deliverCapture(runner.get(), incomplete, true);
            waitForDisk();
            assertEquals(RunStore.StepStatus.PENDING, store.details(id).steps().get(1).status());
            deliverCapture(runner.get(), incomplete, true);
            waitForDisk();
            waitForDisk();
            assertEquals(RunStore.StepStatus.FAILED, store.details(id).steps().get(1).status());
            assertEquals(Status.FAILED, store.details(id).run().status());
        }
    }

    @Test
    public void runnerSkipsRequestedP3OnAnUnsupportedDisplay() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(context, RunLocalBenchmarksActivity.class)
                .putExtra(RunLocalBenchmarksActivity.TESTS,
                        new String[]{BenchmarkTest.UI_WIDE_GAMUT.name(), BenchmarkTest.PHOTO_GALLERY.name()})
                .putExtra(RunLocalBenchmarksActivity.REPETITIONS, 1);
        try (ActivityController<RunLocalBenchmarksActivity> runner = Robolectric
                .buildActivity(RunLocalBenchmarksActivity.class, intent).setup()) {
            waitForDisk();
            long id = ReflectionHelpers.getField(runner.get(), "runId");
            List<RunStore.Step> steps = RunStore.get(context).details(id).steps();
            assertEquals(1, steps.size());
            assertEquals(BenchmarkTest.PHOTO_GALLERY, steps.get(0).test());
        }
    }

    @Test
    public void failedTransitionStillLetsTheFollowingWorkloadComplete() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(context, RunLocalBenchmarksActivity.class)
                .putExtra(RunLocalBenchmarksActivity.TESTS,
                        new String[]{BenchmarkTest.UI_TRANSITION.name(), BenchmarkTest.UI_UPLOAD.name()})
                .putExtra(RunLocalBenchmarksActivity.REPETITIONS, 1);
        try (ActivityController<RunLocalBenchmarksActivity> runner = Robolectric
                .buildActivity(RunLocalBenchmarksActivity.class, intent).setup()) {
            waitForDisk();
            long id = ReflectionHelpers.getField(runner.get(), "runId");
            RunStore store = RunStore.get(context);
            Measurement failure = new Measurement(List.of(), 0, 0, 0, 0, 0,
                    "The detail screen did not draw");
            Measurement next = new Measurement(List.of(new Frame(7_000_000, 8_333_333)),
                    0, 0, 0, 120, 120, null);

            deliverCapture(runner.get(), failure, false);
            waitForDisk();
            assertEquals(RunStore.StepStatus.FAILED, store.details(id).steps().get(0).status());
            assertEquals(RunStore.StepStatus.PENDING, store.details(id).steps().get(1).status());
            deliverCapture(runner.get(), next, false);
            waitForDisk();
            waitForDisk();
            assertEquals(RunStore.StepStatus.COMPLETE, store.details(id).steps().get(1).status());
            assertEquals(next, store.details(id).steps().get(1).measurement());
        }
    }

    private static void deliverCapture(RunLocalBenchmarksActivity runner, Measurement capture, boolean retry) {
        ReflectionHelpers.callInstanceMethod(runner, "onCaptureFinished",
                ClassParameter.from(Automator.Result.class, new Automator.Result(capture, retry)));
    }

    private static Display.Mode displayMode(int id, float rate) {
        return ReflectionHelpers.callConstructor(Display.Mode.class,
                ClassParameter.from(int.class, id), ClassParameter.from(int.class, 1080),
                ClassParameter.from(int.class, 1920), ClassParameter.from(float.class, rate));
    }

    private static void waitForDisk() throws Exception {
        RunStore.DISK.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

}

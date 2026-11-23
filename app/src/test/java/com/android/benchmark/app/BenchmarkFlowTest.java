package com.android.benchmark.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
import org.robolectric.annotation.GraphicsMode;
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
            assertFalse(progress.isIndeterminate());
            assertEquals(0, progress.getProgress());
            WaveProgressDrawable wave = (WaveProgressDrawable) progress.getProgressDrawable();
            deliverCapture(runner.get(), incomplete, true);
            waitForDisk();
            assertFalse(progress.isIndeterminate());
            assertEquals(0, progress.getProgress());
            assertSame(wave, progress.getProgressDrawable());
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
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void progressWaveMovesEvenBeforeTheFirstPassCompletes() {
        WaveProgressDrawable wave = new WaveProgressDrawable(RuntimeEnvironment.getApplication(),
                Color.WHITE, Color.GRAY);
        wave.setBounds(0, 0, 320, 14);
        // Sample the same vector animation on a software canvas in Robolectric.
        ReflectionHelpers.callInstanceMethod(wave.getDrawable(), "forceAnimationOnUI");
        wave.start();
        assertTrue(wave.isRunning());
        Object animator = ReflectionHelpers.getField(wave.getDrawable(), "mAnimatorSet");
        AnimatorSet timeline = ReflectionHelpers.getField(animator, "mSet");
        wave.stop();

        timeline.setCurrentPlayTime(0);
        Bitmap first = renderWave(wave);
        timeline.setCurrentPlayTime(210);
        Bitmap shifted = renderWave(wave);
        assertFalse(first.sameAs(shifted));
        // Only the wave to the left of the marker's center should move.
        for (int y = 0; y < first.getHeight(); y++) {
            assertEquals(first.getPixel(13, y), shifted.getPixel(13, y));
            assertEquals(Color.TRANSPARENT, shifted.getPixel(14, y));
        }
        assertEquals(0, wave.getLevel());
        assertEquals(Color.GRAY, first.getPixel(100, 6));
        assertEquals(Color.WHITE, first.getPixel(12, 2));
        assertEquals(Color.WHITE, first.getPixel(12, 11));

        int passes = BenchmarkTest.workloads().size() * 5;
        wave.setLevel(10_000 / passes);
        Bitmap second = renderWave(wave);
        assertTrue(Color.alpha(second.getPixel(14, 2)) > Color.alpha(first.getPixel(14, 2)));

        wave.setLevel(5_000);
        Bitmap atHalf = renderWave(wave);
        timeline.setCurrentPlayTime(420);
        assertFalse(atHalf.sameAs(renderWave(wave)));
        assertEquals(5_000, wave.getLevel());
        assertEquals(Color.GRAY, atHalf.getPixel(180, 6));
        assertEquals(Color.WHITE, atHalf.getPixel(165, 2));
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void progressMarkerGlidesToTheNextPass() {
        WaveProgressDrawable wave = new WaveProgressDrawable(RuntimeEnvironment.getApplication(),
                Color.WHITE, Color.GRAY);
        wave.setBounds(0, 0, 320, 14);
        ReflectionHelpers.callInstanceMethod(wave.getDrawable(), "forceAnimationOnUI");
        wave.start();
        try {
            wave.setLevel(5_000);
            ValueAnimator advance = ReflectionHelpers.getField(wave, "advance");
            assertTrue(advance.isRunning());
            assertEquals(5_000, wave.getLevel());

            advance.setCurrentPlayTime(120);
            float midpoint = (float) advance.getAnimatedValue();
            assertTrue(midpoint > 0 && midpoint < 5_000);
            int markerX = Math.round(14 + 306 * midpoint / 10_000f) - 2;
            assertEquals(Color.WHITE, renderWave(wave).getPixel(markerX, 2));
            assertTrue(wave.isRunning());
        } finally {
            wave.stop();
        }
    }

    private static Bitmap renderWave(WaveProgressDrawable wave) {
        Bitmap bitmap = Bitmap.createBitmap(320, 14, Bitmap.Config.ARGB_8888);
        wave.draw(new Canvas(bitmap));
        return bitmap;
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

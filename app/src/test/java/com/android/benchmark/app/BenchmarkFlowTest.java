package com.android.benchmark.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TextView;

import com.android.benchmark.R;
import com.android.benchmark.results.FrameStats;
import com.android.benchmark.results.FrameStats.Frame;
import com.android.benchmark.results.JankBreakdown;
import com.android.benchmark.results.RunStore;
import com.android.benchmark.results.RunStore.Measurement;
import com.android.benchmark.results.RunStore.Status;
import com.android.benchmark.ui.BenchmarkTest;
import com.android.benchmark.ui.automation.Automator;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    @Config(qualifiers = "notnight")
    public void launchFromHomeCancelAndOpenTheSavedResult() throws Exception {
        Intent runIntent;
        try (ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup()) {
            assertEquals(home.get().getColor(android.R.color.system_primary_light), home.get().primary);
            View root = home.get().getWindow().getDecorView();
            MaterialButton three = (MaterialButton) findText(root, "3");
            MaterialButton five = (MaterialButton) findText(root, "5");
            assertTrue(five.isChecked());
            assertTrue(three.isClickable());
            int width = home.get().dp(360);
            int height = home.get().dp(720);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            assertTrue(three.getWidth() >= home.get().dp(48));
            assertTrue(three.getHeight() >= home.get().dp(48));
            three.performClick();
            assertTrue(three.isChecked());
            three.performClick();
            assertTrue(three.isChecked());
            MaterialSwitch list = findWorkloadSwitch(home.get(), root, BenchmarkTest.UI_LIST);
            assertTrue(list.isChecked());
            assertTrue(list.getHeight() >= home.get().dp(70));
            assertNotNull(list.getThumbIconDrawable());
            assertTrue(list.getBackground() instanceof android.graphics.drawable.RippleDrawable);
            list.performClick();
            assertFalse(list.isChecked());

            home.get().findViewById(android.R.id.button1).performClick();
            runIntent = shadowOf(home.get()).getNextStartedActivity();
            assertEquals(new ComponentName(home.get(), RunLocalBenchmarksActivity.class),
                    runIntent.getComponent());
            List<String> selected = List.of(
                    runIntent.getStringArrayExtra(RunLocalBenchmarksActivity.TESTS));
            assertEquals(BenchmarkTest.workloads().size() - 2, selected.size());
            assertFalse(selected.contains(BenchmarkTest.UI_LIST.name()));
            assertEquals(3, runIntent.getIntExtra(RunLocalBenchmarksActivity.REPETITIONS, 0));
        }

        Intent resultIntent;
        try (ActivityController<RunLocalBenchmarksActivity> runner = Robolectric
                .buildActivity(RunLocalBenchmarksActivity.class, runIntent).setup()) {
            waitForDisk();
            TextView cancel = findText(runner.get().getWindow().getDecorView(), "Cancel");
            assertNotNull(cancel);
            assertTrue(runner.get().getOnBackPressedDispatcher().hasEnabledCallbacks());
            assertTrue(cancel.performClick());
            waitForDisk();
            resultIntent = shadowOf(runner.get()).getNextStartedActivity();
            assertNotNull(resultIntent);
            assertEquals(new ComponentName(runner.get(), ResultsActivity.class),
                    resultIntent.getComponent());
            long id = resultIntent.getLongExtra(ResultsActivity.RUN_ID, 0);
            RunStore.Run saved = RunStore.get(runner.get()).details(id).run();
            assertEquals(Status.CANCELLED, saved.status());
            assertNull(saved.score());
        }

        try (ActivityController<ResultsActivity> results = Robolectric
                .buildActivity(ResultsActivity.class, resultIntent).setup()) {
            waitForDisk();
            View root = results.get().getWindow().getDecorView();
            assertNotNull(findText(root, "Cancelled"));
            assertNotNull(findText(root, "Kernel " + System.getProperty("os.version")));
            assertNotNull(findText(root, "Unavailable"));
            assertNotNull(findText(root, "Workload details"));
        }

        try (ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup()) {
            View root = home.get().getWindow().getDecorView();
            assertTrue(((MaterialButton) findText(root, "3")).isChecked());
            MaterialSwitch list = findWorkloadSwitch(home.get(), root, BenchmarkTest.UI_LIST);
            assertFalse(list.isChecked());
            assertTrue(findText(root, "History").performClick());
            waitForDisk();
            assertNotNull(findText(root, "Kernel " + System.getProperty("os.version")));
            TextView row = findText(root, "Cancelled");
            assertNotNull(row);
            assertTrue(((View) row.getParent()).performClick());
            Intent previousRun = shadowOf(home.get()).getNextStartedActivity();
            assertEquals(resultIntent.getLongExtra(ResultsActivity.RUN_ID, 0),
                    previousRun.getLongExtra(ResultsActivity.RUN_ID, 0));
        }
    }

    @Test
    public void resultSummarizesCompletedFramesAndKeepsChartsAndPasses() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        RunStore store = RunStore.get(context);
        long id = store.start(List.of(BenchmarkTest.LIST, BenchmarkTest.OVERDRAW), 1);
        Measurement capture = new Measurement(List.of(
                new Frame(5_000_000, 8_000_000),
                new Frame(6_000_000, 8_000_000),
                new Frame(10_000_000, 8_000_000)),
                0, 0, 0, 120, 120, null, new JankBreakdown(3, 1, 1, 0), List.of());
        assertTrue(store.saveStep(id, 0, capture));
        store.finish(id, Status.CANCELLED, "Cancelled by the user.");
        Intent intent = new Intent(context, ResultsActivity.class)
                .putExtra(ResultsActivity.RUN_ID, id);
        try (ActivityController<ResultsActivity> results = Robolectric
                .buildActivity(ResultsActivity.class, intent).setup()) {
            waitForDisk();
            View root = results.get().getWindow().getDecorView();
            assertNotNull(findText(root, "Workload summary"));
            assertNotNull(findText(root, "Jank\nframes"));
            assertNotNull(findText(root, "Bad\nframes"));
            assertNotNull(findText(root, String.format(Locale.getDefault(), "%.2f%%", 100.0 / 3)));
            assertNotNull(findText(root, String.format(Locale.getDefault(), "%.2f%%", 200.0 / 3)));
            assertNotNull(findText(root, "Incomplete"));
            assertNotNull(findText(root, "95th-percentile frame time"));
            assertNotNull(findText(root, "33.3 ms"));
            assertNotNull(findText(root, "Deadline overrun"));
            assertNotNull(findText(root, "Compositor classification"));
            assertNotNull(findText(root, "3 of 3 frames classified"));
            assertTrue(findText(root, "Show passes").performClick());
            assertNotNull(findText(root, "Frame duration and deadline"));
            assertNotNull(findText(root, "Score inputs"));
        }
    }

    @Test
    public void historyOpensTwoRunComparisonAndDisplaysFrameMetrics() throws Exception {
        RunStore store = RunStore.get(RuntimeEnvironment.getApplication());
        ArrayList<Long> ids = new ArrayList<>();
        for (int hz : new int[]{90, 60, 120}) {
            long id = store.start(List.of(BenchmarkTest.LIST), 1);
            ids.add(id);
            assertTrue(store.saveStep(id, 0, new Measurement(List.of(new Frame(
                    10_000_000L, Math.round(1_000_000_000.0 / hz))), 0, 0, 0, hz, hz, null)));
            store.finish(id, Status.COMPLETE, null);
        }
        Intent intent;
        try (ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup()) {
            View root = home.get().getWindow().getDecorView();
            assertTrue(findText(root, "History").performClick());
            waitForDisk();
            assertTrue(findText(root, "Compare runs").performClick());
            intent = shadowOf(home.get()).getNextStartedActivity();
            assertEquals(new ComponentName(home.get(), CompareRunsActivity.class), intent.getComponent());
        }
        try (ActivityController<CompareRunsActivity> compare = Robolectric
                .buildActivity(CompareRunsActivity.class, intent).setup()) {
            waitForDisk();
            waitForDisk();
            View root = compare.get().getWindow().getDecorView();
            assertNotNull(findText(root, "Workload comparison"));
            assertNotNull(findText(root, "Display " + ResultLabels.refresh(60, 60)));
            assertNotNull(findText(root, "Display " + ResultLabels.refresh(120, 120)));
            assertNotNull(findText(root, "P99  " + ResultLabels.ms(10)));
            assertNotNull(findText(root, "Compare with care due to different observed refresh rates."));

            assertTrue(clickCard(findText(root, "Run #" + ids.get(2) + "  ·  Complete")));
            Dialog chooser = ShadowDialog.getLatestDialog();
            assertNotNull(chooser);
            View choices = chooser.getWindow().getDecorView();
            assertNotNull(findText(choices, "Choose Run A"));
            assertNotNull(findText(choices, "Selected"));
            assertNotNull(findText(choices, "Kernel " + System.getProperty("os.version")));
            assertNull(findText(choices, "Run #" + ids.get(1) + "  ·  Complete"));
            assertTrue(clickCard(findText(choices, "Run #" + ids.get(0) + "  ·  Complete")));
            waitForDisk();
            assertNotNull(findText(root, "Display " + ResultLabels.refresh(90, 90)));
            assertNull(findText(root, "Display " + ResultLabels.refresh(120, 120)));
        }
    }

    @Test
    public void oldTvRunsAndPhotoGalleryHaveSeparateComparisonRows() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        RunStore store = RunStore.get(context);
        for (BenchmarkTest test : List.of(BenchmarkTest.UI_TV_BROWSE, BenchmarkTest.PHOTO_GALLERY)) {
            long id = store.start(List.of(test), 1);
            Measurement capture = new Measurement(List.of(new Frame(7_000_000L, 8_333_333L)),
                    0, 0, 0, 120, 120, null);
            assertTrue(store.saveStep(id, 0, capture));
            store.finish(id, Status.COMPLETE, null);
        }
        try (ActivityController<CompareRunsActivity> compare =
                     Robolectric.buildActivity(CompareRunsActivity.class).setup()) {
            waitForDisk();
            waitForDisk();
            View root = compare.get().getWindow().getDecorView();
            assertNotNull(findText(root, context.getString(R.string.ui_tv_browse)));
            assertNotNull(findText(root, context.getString(R.string.photo_gallery_name)));
            assertNotNull(findText(root, "Compare with care due to different workloads."));
        }
    }

    @Test
    public void savedTvSelectionTransfersToThePhoneGallery() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("benchmark_settings", Context.MODE_PRIVATE).edit()
                .putStringSet("selected_workloads", Set.of(BenchmarkTest.UI_TV_BROWSE.name())).apply();
        try (ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup()) {
            View root = home.get().getWindow().getDecorView();
            assertNull(findWorkloadSwitch(home.get(), root, BenchmarkTest.UI_TV_BROWSE));
            assertTrue(findWorkloadSwitch(home.get(), root, BenchmarkTest.PHOTO_GALLERY).isChecked());
            assertTrue(home.get().findViewById(android.R.id.button1).performClick());
            String[] tests = shadowOf(home.get()).getNextStartedActivity()
                    .getStringArrayExtra(RunLocalBenchmarksActivity.TESTS);
            assertEquals(List.of(BenchmarkTest.PHOTO_GALLERY.name()), List.of(tests));
        }
    }

    @Test
    public void unifiedCatalogUsesNewerEntriesAndSelectsEverySupportedWorkload() {
        try (ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup()) {
            View root = home.get().getWindow().getDecorView();
            assertEquals(32, BenchmarkTest.workloads().size());
            assertNull(findText(root, "Classic"));
            assertNull(findText(root, "Modern UI"));
            assertNull(findText(root, "UiBench"));
            assertNull(findWorkloadSwitch(home.get(), root, BenchmarkTest.LIST));
            assertNull(findWorkloadSwitch(home.get(), root, BenchmarkTest.DETAIL_TRANSITION));
            assertTrue(findWorkloadSwitch(home.get(), root, BenchmarkTest.UI_LIST).isChecked());
            assertTrue(findWorkloadSwitch(home.get(), root, BenchmarkTest.UI_TRANSITION).isChecked());
            assertTrue(findWorkloadSwitch(home.get(), root, BenchmarkTest.COMPOSE_FEED).isChecked());
            assertTrue(findWorkloadSwitch(home.get(), root, BenchmarkTest.UI_INSETS).isChecked());
            assertNull(findWorkloadSwitch(home.get(), root, BenchmarkTest.UI_WIDE_GAMUT));

            home.get().findViewById(android.R.id.button1).performClick();
            List<String> selected = List.of(shadowOf(home.get()).getNextStartedActivity()
                    .getStringArrayExtra(RunLocalBenchmarksActivity.TESTS));
            assertEquals(31, selected.size());
            assertTrue(selected.contains(BenchmarkTest.UI_INSETS.name()));
            assertFalse(selected.contains(BenchmarkTest.UI_WIDE_GAMUT.name()));
        }
    }

    @Test
    @Config(qualifiers = "widecg")
    public void p3IsAvailableWhenAndroidSupportsWideGamutRendering() {
        try (ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup()) {
            View root = home.get().getWindow().getDecorView();
            assertTrue(findWorkloadSwitch(home.get(), root, BenchmarkTest.UI_WIDE_GAMUT).isChecked());
            assertTrue(home.get().findViewById(android.R.id.button1).performClick());
            List<String> selected = List.of(shadowOf(home.get()).getNextStartedActivity()
                    .getStringArrayExtra(RunLocalBenchmarksActivity.TESTS));
            assertTrue(selected.contains(BenchmarkTest.UI_WIDE_GAMUT.name()));
        }
    }

    @Test
    public void masterSwitchClearsAndRestoresSupportedWorkloads() {
        try (ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup()) {
            View root = home.get().getWindow().getDecorView();
            TextView heading = findText(root, home.get().getString(R.string.workloads));
            MaterialSwitch all = (MaterialSwitch) findText(root, home.get().getString(R.string.all_workloads));
            MaterialSwitch transition = findWorkloadSwitch(home.get(), root, BenchmarkTest.UI_TRANSITION);
            View start = home.get().findViewById(android.R.id.button1);

            assertEquals(heading.getParent(), all.getParent());
            assertTrue(all.isChecked());
            assertTrue(all.performClick());
            for (BenchmarkTest test : BenchmarkTest.workloads()) {
                MaterialSwitch option = findWorkloadSwitch(home.get(), root, test);
                if (test == BenchmarkTest.UI_WIDE_GAMUT) {
                    assertNull(option);
                } else {
                    assertFalse(option.isChecked());
                }
            }
            assertFalse(start.isEnabled());

            assertTrue(transition.performClick());
            assertTrue(start.isEnabled());
            assertFalse(all.isChecked());

            assertTrue(all.performClick());
            assertTrue(all.isChecked());
            assertTrue(start.performClick());
            List<String> selected = List.of(shadowOf(home.get()).getNextStartedActivity()
                    .getStringArrayExtra(RunLocalBenchmarksActivity.TESTS));
            assertEquals(31, selected.size());
            assertFalse(selected.contains(BenchmarkTest.UI_WIDE_GAMUT.name()));
        }
    }

    @Test
    public void summaryKeepsFourDigitScoreAndFullPercentagesOnOneLine() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        RunStore store = RunStore.get(context);
        long id = store.start(List.of(BenchmarkTest.UI_TRANSITION), 1);
        Measurement capture = new Measurement(List.of(
                new Frame(10_000_000, 8_000_000), new Frame(11_000_000, 8_000_000)),
                0, 0, 0, 120, 120, null);
        assertTrue(store.saveStep(id, 0, capture));
        store.finish(id, Status.COMPLETE, null);
        String formatted = ResultLabels.score(FrameStats.measure(capture.frames()).score);
        Intent intent = new Intent(context, ResultsActivity.class).putExtra(ResultsActivity.RUN_ID, id);
        try (ActivityController<ResultsActivity> controller =
                     Robolectric.buildActivity(ResultsActivity.class, intent).setup()) {
            waitForDisk();
            ResultsActivity activity = controller.get();
            View root = activity.getWindow().getDecorView();
            int width = activity.dp(320);
            int height = activity.dp(720);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);

            assertTrue(FrameStats.measure(capture.frames()).score > 1_000);
            assertTrue(FrameStats.measure(capture.frames()).score < 10_000);
            TextView heading = findText(root, "Jank\nframes");
            TableLayout table = (TableLayout) heading.getParent().getParent();
            TextView score = findText(table, formatted);
            assertEquals(1, score.getLineCount());
            assertTrue(score.getLayout().getLineWidth(0)
                    <= score.getWidth() - score.getPaddingLeft() - score.getPaddingRight());
            assertNotNull(findText(table, "100%"));
            assertNull(findText(table, "100.00%"));
        }
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

                Dialog dialog = new Dialog(home.get());
                home.get().showDialog(dialog);
                try {
                    assertEquals(0f, dialog.getWindow().getAttributes().preferredRefreshRate, 0);
                    assertEquals(0, dialog.getWindow().getAttributes().preferredDisplayModeId);
                    assertFalse(dialog.getWindow().isFrameRatePowerSavingsBalanced());
                } finally {
                    dialog.dismiss();
                }
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

    @Test
    @Config(qualifiers = "night")
    public void workloadCardsUseTheSystemDarkPalette() {
        try (ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup()) {
            int primary = home.get().getColor(android.R.color.system_primary_dark);
            assertEquals(primary, home.get().primary);
            View root = home.get().getWindow().getDecorView();
            for (BenchmarkTest test : BenchmarkTest.workloads()) {
                MaterialSwitch option = findWorkloadSwitch(home.get(), root, test);
                if (test == BenchmarkTest.UI_WIDE_GAMUT) {
                    assertNull(option);
                    continue;
                }
                assertNotNull(option);
                assertTrue(option.isChecked());
                assertEquals(primary, option.getThumbIconTintList().getColorForState(
                        new int[]{android.R.attr.state_checked}, 0));
            }
        }
    }

    private static boolean clickCard(View view) {
        View target = view;
        while (target != null) {
            if (target.isClickable()) {
                return target.performClick();
            }
            target = target.getParent() instanceof View parent ? parent : null;
        }
        return false;
    }

    private static MaterialSwitch findWorkloadSwitch(HomeActivity home, View root,
                                                     BenchmarkTest test) {
        return (MaterialSwitch) findText(root, home.getString(test.title) + "\n" + test.description);
    }

    private static void waitForDisk() throws Exception {
        RunStore.DISK.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText())) {
            return label;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}

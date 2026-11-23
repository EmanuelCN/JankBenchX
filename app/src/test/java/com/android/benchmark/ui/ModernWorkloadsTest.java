package com.android.benchmark.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.activity.ComponentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.benchmark.R;
import com.android.benchmark.ui.WorkloadViews.Scene;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ModernWorkloadsTest {
    @Test
    public void modernWorkloadsExecuteWithALaidOutWindow() {
        exercise(List.of(BenchmarkTest.VIEW_FEED, BenchmarkTest.COMPOSE_FEED,
                BenchmarkTest.UI_NESTED, BenchmarkTest.UI_TRANSITION, BenchmarkTest.LIVE_FEED));
    }

    @Test
    public void uiBenchScenesExecuteWithALaidOutWindow() {
        List<BenchmarkTest> tests = new ArrayList<>(BenchmarkTest.workloads());
        tests.removeIf(test -> !test.name().startsWith("UI_"));
        assertEquals(27, tests.size());
        // The GL, WebView, and wide-gamut scenes need device support. Classic covers typing.
        tests.removeAll(List.of(BenchmarkTest.UI_GL_TEXTURE, BenchmarkTest.UI_WEB,
                BenchmarkTest.UI_WIDE_GAMUT, BenchmarkTest.UI_EDIT));
        exercise(tests);
    }

    @Test
    @Config(sdk = {26, 34})
    public void resizeInsetsAndLayersCanRunInSuccession() {
        List<BenchmarkTest> workloads = List.of(BenchmarkTest.UI_RESIZE_LAYER,
                BenchmarkTest.UI_INSETS, BenchmarkTest.UI_SAVE_LAYER);
        List<BenchmarkTest> repeats = new ArrayList<>(workloads);
        repeats.addAll(workloads);
        exercise(repeats);
    }

    @Test
    @Config(qualifiers = "xxxhdpi")
    public void transitionBoundsPhotosInPixelsAndReleasesItsViews() {
        try (ActivityController<ComponentActivity> controller =
                     Robolectric.buildActivity(ComponentActivity.class).setup()) {
            ComponentActivity activity = controller.get();
            FrameLayout holder = new FrameLayout(activity);
            activity.setContentView(holder);
            Scene scene = WorkloadViews.create(activity, holder, BenchmarkTest.UI_TRANSITION);
            holder.addView(scene.view(), new FrameLayout.LayoutParams(-1, -1));
            ViewGroup root = (ViewGroup) scene.view();
            RecyclerView feed = (RecyclerView) root.getChildAt(0);
            try {
                layout(holder);
                scene.workload().prepare();
                scene.workload().advance(0);
                ViewGroup detail = (ViewGroup) root.getChildAt(1);
                ImageView photo = (ImageView) detail.getChildAt(0);
                Bitmap bitmap = ((BitmapDrawable) photo.getDrawable()).getBitmap();
                assertEquals(960, Math.max(bitmap.getWidth(), bitmap.getHeight()));
            } finally {
                scene.workload().close();
            }
            assertEquals(0, root.getChildCount());
            assertNull(feed.getAdapter());
        }
    }

    @Test
    public void photoGalleryFlingsImagesInBothDirections() {
        try (ActivityController<ComponentActivity> controller =
                     Robolectric.buildActivity(ComponentActivity.class).setup()) {
            ComponentActivity activity = controller.get();
            FrameLayout holder = new FrameLayout(activity);
            activity.setContentView(holder);
            Scene scene = WorkloadViews.create(activity, holder, BenchmarkTest.PHOTO_GALLERY);
            RecyclerView grid = (RecyclerView) scene.view();
            holder.addView(grid, new FrameLayout.LayoutParams(-1, -1));
            ArrayList<Integer> scrollDeltas = new ArrayList<>();
            ArrayList<Integer> flings = new ArrayList<>();
            grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                        flings.add(recyclerView.computeVerticalScrollOffset());
                    }
                }

                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    scrollDeltas.add(dy);
                }
            });
            try {
                layout(holder);
                assertEquals(4, ((GridLayoutManager) grid.getLayoutManager()).getSpanCount());
                assertTrue("The gallery should lay out at least five rows of tiles", grid.getChildCount() >= 20);
                ImageView thumbnail = (ImageView) grid.getChildAt(0);
                assertEquals(thumbnail.getWidth(), thumbnail.getHeight());
                ImageView nextRow = (ImageView) grid.getChildAt(4);
                assertNotSame(((BitmapDrawable) thumbnail.getDrawable()).getBitmap(),
                        ((BitmapDrawable) nextRow.getDrawable()).getBitmap());

                scene.workload().prepare();
                boolean done = false;
                for (long nanos = 0; nanos <= 10_000_000_000L && !done; nanos += 16_666_667L) {
                    done = scene.workload().advance(nanos);
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17));
                }
                assertTrue("The gallery did not finish flinging", done);
                assertEquals(4, flings.size());
                assertTrue(scrollDeltas.stream().anyMatch(delta -> delta > 0));
                assertTrue(scrollDeltas.stream().anyMatch(delta -> delta < 0));
            } finally {
                scene.workload().close();
            }
            assertNull(grid.getAdapter());
        }
    }

    private void exercise(List<BenchmarkTest> tests) {
        try (ActivityController<ComponentActivity> controller =
                     Robolectric.buildActivity(ComponentActivity.class).setup()) {
            ComponentActivity activity = controller.get();
            activity.setTheme(R.style.AppTheme);
            FrameLayout holder = new FrameLayout(activity);
            activity.setContentView(holder);
            Bitmap bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            for (BenchmarkTest test : tests) {
                Scene scene = WorkloadViews.create(activity, holder, test);
                holder.addView(scene.view(), new FrameLayout.LayoutParams(-1, -1));
                try {
                    layout(holder);
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
                    layout(holder);
                    scene.workload().prepare();
                    if (test == BenchmarkTest.UI_RESIZE_LAYER) {
                        layout(holder);
                        assertTrue(((ViewGroup) scene.view()).getChildAt(0).getWidth() > 1);
                    }
                    if (test == BenchmarkTest.UI_INSETS) {
                        View editor = ((ViewGroup) scene.view()).getChildAt(0);
                        int initialHeight = editor.getHeight();
                        scene.workload().advance(400_000_000L);
                        layout(holder);
                        assertTrue(editor.getHeight() < initialHeight);
                    }
                    boolean done = false;
                    for (long nanos = 0; nanos <= 10_000_000_000L && !done; nanos += 16_666_667L) {
                        done = scene.workload().advance(nanos);
                        layout(holder);
                        holder.draw(canvas);
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17));
                    }
                    assertTrue("Did not finish " + test, done);
                    int expectedDraws = test.driver() == BenchmarkTest.DETAIL_TRANSITION ? 4 : 0;
                    assertEquals(expectedDraws, scene.workload().firstDrawsNs().size());
                } finally {
                    scene.workload().close();
                    holder.removeAllViews();
                }
            }
            bitmap.recycle();
        }
    }

    private static void layout(FrameLayout holder) {
        holder.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        holder.layout(0, 0, 480, 640);
    }
}

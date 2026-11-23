package com.android.benchmark.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;

import java.util.List;

import com.android.benchmark.R;
import com.android.benchmark.ui.WorkloadViews.Scene;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WorkloadViewsTest {
    @Test
    public void allEightWorkloadViewsLayOutAndCompleteTheirActions() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            activity.setTheme(R.style.AppTheme);
            FrameLayout holder = new FrameLayout(activity);
            activity.setContentView(holder);
            for (BenchmarkTest test : List.of(BenchmarkTest.UI_LIST, BenchmarkTest.IMAGE_LIST,
                    BenchmarkTest.UI_SHADOW, BenchmarkTest.UI_TEXT_HIGH, BenchmarkTest.UI_TEXT_LOW,
                    BenchmarkTest.UI_EDIT, BenchmarkTest.UI_OVERDRAW, BenchmarkTest.UI_UPLOAD)) {
                Scene scene = WorkloadViews.create(activity, holder, test);
                holder.addView(scene.view(), new FrameLayout.LayoutParams(-1, -1));
                try {
                    holder.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
                    holder.layout(0, 0, 480, 640);
                    scene.workload().prepare();
                    boolean done = false;
                    boolean scrolled = false;
                    for (long nanos = 0; nanos <= 8_000_000_000L; nanos += 16_666_667L) {
                        done = scene.workload().advance(nanos);
                        if (scene.view() instanceof ListView list) {
                            scrolled |= list.getFirstVisiblePosition() > 0
                                    || list.getChildAt(0).getTop() < 0;
                        }
                        if (done) {
                            break;
                        }
                    }
                    assertTrue("Did not finish " + test, done);
                    if (scene.view() instanceof ListView) {
                        assertTrue("List did not scroll for " + test, scrolled);
                    }
                    if (scene.view() instanceof EditText field) {
                        assertEquals("abcdefghijklmnopqrstuvwxyz ".repeat(5), field.getText().toString());
                    }
                } finally {
                    scene.workload().close();
                    holder.removeAllViews();
                }
            }
        }
    }
}

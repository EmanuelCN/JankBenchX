package com.android.benchmark.ui.automation;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.AttachedSurfaceControl;
import android.view.FrameMetrics;
import android.view.SurfaceControl;
import android.view.Window;

import androidx.annotation.RequiresApi;

import com.android.benchmark.results.JankBreakdown;

/** Correlates SurfaceFlinger classifications with frames in this pass by frame timeline ID. */
@RequiresApi(36)
final class CompositorCapture implements AutoCloseable {
    private final LongSparseArray<Boolean> measured = new LongSparseArray<>();
    private final LongSparseArray<Integer> classifications = new LongSparseArray<>();
    private final SurfaceControl.OnJankDataListenerRegistration registration;

    static CompositorCapture attach(Window window, Handler handler) {
        try {
            AttachedSurfaceControl surface = window.getDecorView().getRootSurfaceControl();
            return surface == null ? null : new CompositorCapture(surface, handler);
        } catch (RuntimeException e) {
            Log.w("JankBenchX", "Compositor diagnostics unavailable", e);
            return null;
        }
    }

    private CompositorCapture(AttachedSurfaceControl surface, Handler handler) {
        registration = surface.registerOnJankDataListener(handler::post, batch -> {
            for (SurfaceControl.JankData frame : batch) {
                classifications.put(frame.getVsyncId(), frame.getJankType());
            }
        });
    }

    // The public frame-timeline metric is available from API 36. Lint's
    // older FrameMetrics @IntDef metadata still omits it.
    @SuppressLint("WrongConstant")
    void addFrame(FrameMetrics metrics) {
        long id = metrics.getMetric(FrameMetrics.FRAME_TIMELINE_VSYNC_ID);
        if (id > 0) {
            measured.put(id, true);
        }
    }

    void flush() {
        try {
            registration.flush();
        } catch (RuntimeException e) {
            Log.w("JankBenchX", "Could not flush compositor diagnostics", e);
        }
    }

    JankBreakdown summary() {
        int classified = 0;
        int app = 0;
        int composer = 0;
        int other = 0;
        for (int i = 0; i < measured.size(); i++) {
            Integer flags = classifications.get(measured.keyAt(i));
            if (flags == null) {
                continue;
            }
            classified++;
            if ((flags & SurfaceControl.JankData.JANK_APPLICATION) != 0) {
                app++;
            }
            if ((flags & SurfaceControl.JankData.JANK_COMPOSER) != 0) {
                composer++;
            }
            if ((flags & SurfaceControl.JankData.JANK_OTHER) != 0) {
                other++;
            }
        }
        return new JankBreakdown(classified, app, composer, other);
    }

    @Override
    public void close() {
        try {
            registration.removeAfter(0);
        } catch (RuntimeException e) {
            Log.w("JankBenchX", "Could not detach compositor diagnostics", e);
        }
    }
}

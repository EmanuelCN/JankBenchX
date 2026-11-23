package com.android.benchmark.ui

import android.content.Context
import com.android.benchmark.ui.WorkloadViews.Scene

/** Freestanding adaptations of the app-level Android 17 UiBench scenarios. */
object UiBenchWorkloads {
    @JvmStatic
    fun create(context: Context, test: BenchmarkTest): Scene = when (test) {
        BenchmarkTest.UI_DIALOG, BenchmarkTest.UI_FADING_LIST, BenchmarkTest.UI_CLIPPED_LIST,
        BenchmarkTest.UI_INFLATE -> uiBenchList(context, test)
        BenchmarkTest.UI_RECYCLER, BenchmarkTest.UI_SLOW_BIND ->
            uiBenchGrid(context, test)
        BenchmarkTest.UI_WEB -> offlineWebView(context)
        BenchmarkTest.UI_GL_TEXTURE -> GlTextureWorkload(context).scene()
        BenchmarkTest.UI_INVALIDATE, BenchmarkTest.UI_ANIMATION, BenchmarkTest.UI_WIDE_GAMUT,
        BenchmarkTest.UI_SAVE_LAYER, BenchmarkTest.UI_JITTER -> drawingScene(context, test)
        BenchmarkTest.UI_INVALIDATE_TREE -> invalidateTree(context)
        BenchmarkTest.UI_RESIZE_LAYER -> resizeLayer(context)
        BenchmarkTest.UI_DRAWER -> drawerScene(context)
        BenchmarkTest.UI_SHADE -> notificationScene(context)
        BenchmarkTest.UI_INSETS -> insetScene(context)
        else -> error("Unsupported UiBench workload: $test")
    }
}

package com.android.benchmark.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.android.benchmark.ui.WorkloadViews.Scene
import com.android.benchmark.ui.automation.Automator.Workload
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val DURATION = 3_400_000_000L
private const val PERIOD = 800_000_000L

private fun wave(time: Long, offset: Double = 0.0): Float =
    ((1 - cos(time % PERIOD / PERIOD.toDouble() * 2 * PI + offset)) / 2).toFloat()

internal fun drawingScene(context: Context, test: BenchmarkTest): Scene {
    val colors = FeedColors(context)
    val wide = test == BenchmarkTest.UI_WIDE_GAMUT
    val wideSpace = if (wide) ColorSpace.get(ColorSpace.Named.DISPLAY_P3) else null
    val activity = if (wide) context as Activity else null
    val window = activity?.window
    val priorColorMode = window?.colorMode
    if (wide && test.isSupported(context)) {
        window?.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
    }

    val scene = object : View(context) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var clock = 0L

        override fun onDraw(canvas: Canvas) {
            val x = width * wave(clock)
            val y = height * wave(clock, 1.2)
            val radius = context.px(36).toFloat()
            when (test) {
                BenchmarkTest.UI_INVALIDATE -> {
                    paint.color = 0xff447cb0.toInt()
                    canvas.drawRect(x, y, x + radius, y + radius, paint)
                }
                BenchmarkTest.UI_WIDE_GAMUT -> {
                    if (Build.VERSION.SDK_INT >= 29 && wideSpace != null) {
                        paint.setColor(Color.pack(wave(clock), 0.3f, 0.95f, 1f, wideSpace))
                    }
                    canvas.drawCircle(x, height / 2f, radius * 2, paint)
                    canvas.drawRect(0f, height * 0.72f, width.toFloat(), height.toFloat(), paint)
                }
                BenchmarkTest.UI_SAVE_LAYER -> {
                    for (index in 0..4) {
                        val offset = index * radius * 0.7f
                        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                        paint.color = Color.argb(175, 40 + index * 28, 102, 205)
                        canvas.drawCircle(x - offset, y + offset, radius * 3, paint)
                        paint.color = Color.argb(150, 253, 138, 61 + index * 21)
                        canvas.drawCircle(width - x + offset, y, radius * 2, paint)
                        canvas.restoreToCount(save)
                    }
                }
                BenchmarkTest.UI_JITTER -> {
                    for (index in 0..31) {
                        paint.color = Color.rgb(30 + index * 6, 160 - index * 3, 211)
                        val dx = width * wave(clock, index * 0.19)
                        val dy = height * wave(clock, index * 0.27)
                        canvas.drawRoundRect(dx, dy, dx + radius * 1.7f, dy + radius, 18f, 18f, paint)
                    }
                }
                else -> {
                    paint.color = 0xff437caf.toInt()
                    canvas.drawCircle(x, height * 0.5f, radius, paint)
                }
            }
        }
    }.apply { setBackgroundColor(colors.background) }

    return Scene(scene, object : Workload {
        private var lastX = 0
        private var lastY = 0

        override fun prepare() {
            if (wide) {
                check(test.isSupported(context)) {
                    "This display does not support the wide-gamut workload"
                }
            }
        }

        override fun advance(elapsedNanos: Long): Boolean {
            scene.clock = elapsedNanos
            if (test == BenchmarkTest.UI_INVALIDATE) {
                val x = (scene.width * wave(elapsedNanos)).toInt()
                val y = (scene.height * wave(elapsedNanos, 1.2)).toInt()
                @Suppress("DEPRECATION")
                scene.invalidate(minOf(x, lastX), minOf(y, lastY),
                    maxOf(x, lastX) + context.px(36), maxOf(y, lastY) + context.px(36))
                lastX = x
                lastY = y
            } else {
                scene.invalidate()
            }
            return elapsedNanos >= DURATION
        }

        override fun close() {
            if (window != null && priorColorMode != null) {
                window.colorMode = priorColorMode
            }
        }
    })
}

internal fun invalidateTree(context: Context): Scene {
    val colors = FeedColors(context)
    val leaves = ArrayList<View>()
    fun branch(depth: Int): View {
        if (depth == 0) {
            return View(context).also { leaves.add(it) }
        }
        return LinearLayout(context).apply {
            orientation = if (depth % 2 == 0) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            repeat(3) {
                val child = branch(depth - 1)
                addView(child, if (orientation == LinearLayout.VERTICAL)
                    LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(0, -1, 1f))
            }
        }
    }
    val root = branch(4)
    root.setBackgroundColor(colors.background)
    return Scene(root, Workload { time ->
        for (index in leaves.indices) {
            if (index % 3 == 0) {
                val value = (wave(time, index * 0.3) * 230).toInt()
                leaves[index].setBackgroundColor(Color.rgb(30 + value / 2, 50 + value / 3, 145 + value / 3))
            }
        }
        time >= DURATION
    })
}

internal fun resizeLayer(context: Context): Scene {
    val root = FrameLayout(context)
    val layer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(context, 0xff336696.toInt(), 20)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        repeat(12) { row ->
            addView(TextView(context).apply {
                text = "Layout row ${row + 1}"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(context.px(18), context.px(4), 0, context.px(4))
            })
        }
    }
    root.addView(layer, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
    return Scene(root, object : Workload {
        override fun prepare() {
            resize(0f)
        }

        override fun advance(elapsedNanos: Long): Boolean {
            resize(wave(elapsedNanos))
            return elapsedNanos >= DURATION
        }

        private fun resize(progress: Float) {
            val params = layer.layoutParams
            val width = (root.width * (0.43f + progress * 0.46f)).toInt().coerceAtLeast(1)
            val height = (root.height * (0.28f + progress * 0.61f)).toInt().coerceAtLeast(1)
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                layer.layoutParams = params
            }
        }

        override fun close() {
            layer.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    })
}

internal fun drawerScene(context: Context): Scene {
    val colors = FeedColors(context)
    val root = FrameLayout(context)
    root.setBackgroundColor(colors.background)
    val underlying = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        repeat(12) { addView(panelRow(context, colors, "Collection ${it + 1}", "Photos and notes")) }
    }
    root.addView(underlying, FrameLayout.LayoutParams(-1, -1))
    val scrim = View(context).apply { setBackgroundColor(Color.BLACK) }
    root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
    val drawer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colors.row)
        elevation = context.px(16).toFloat()
        repeat(9) { addView(panelRow(context, colors, "Album ${it + 1}", "Open collection")) }
    }
    root.addView(drawer, FrameLayout.LayoutParams(context.px(240), -1, Gravity.START))
    return Scene(root, Workload { time ->
        val progress = wave(time)
        drawer.translationX = -drawer.width * (1 - progress)
        scrim.alpha = progress * 0.54f
        time >= DURATION
    })
}

internal fun notificationScene(context: Context): Scene {
    val colors = FeedColors(context)
    val stack = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colors.background)
        setPadding(context.px(12), context.px(12), context.px(12), 0)
    }
    val notifications = List(14) { index ->
        panelRow(context, colors, "Notification ${index + 1}", "New photos and activity").apply {
            background = rounded(context, colors.row, 14)
            elevation = context.px(8).toFloat()
        }
    }
    return Scene(stack, Workload { time ->
        val expanded = wave(time) * notifications.size
        val visible = expanded.toInt()
        while (stack.childCount > visible + 1) {
            stack.removeViewAt(stack.childCount - 1)
        }
        while (stack.childCount <= visible && stack.childCount < notifications.size) {
            stack.addView(notifications[stack.childCount], LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = context.px(5)
            })
        }
        stack.getChildAt(stack.childCount - 1)?.alpha = expanded - visible
        for (i in 0 until stack.childCount - 1) {
            stack.getChildAt(i).alpha = 1f
            stack.getChildAt(i).translationY = -context.px(4) * wave(time, i * 0.3)
        }
        time >= DURATION
    })
}

internal fun insetScene(context: Context): Scene {
    val colors = FeedColors(context)
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colors.background)
        val editor = EditText(context).apply {
            setText("Window insets layout benchmark")
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(colors.row)
            showSoftInputOnFocus = false
            isCursorVisible = false
        }
        addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        repeat(8) { addView(panelRow(context, colors, "Editor row ${it + 1}", "Responds to inset changes")) }
    }
    return Scene(panel, Workload { time ->
        val inset = (panel.height * 0.38f * wave(time)).toInt()
        // Model the editor relayout directly. Real system/IME insets remain on the window.
        if (panel.paddingBottom != inset) {
            panel.setPadding(0, 0, 0, inset)
        }
        time >= DURATION
    })
}

private fun panelRow(context: Context, colors: FeedColors, label: String, body: String): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.px(16), context.px(7), context.px(16), context.px(7))
        addView(TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(colors.title)
        })
        addView(TextView(context).apply {
            text = body
            textSize = 13f
            setTextColor(colors.subtitle)
        })
    }

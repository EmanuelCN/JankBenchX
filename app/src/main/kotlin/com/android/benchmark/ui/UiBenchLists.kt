package com.android.benchmark.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Trace
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.benchmark.R
import com.android.benchmark.ui.WorkloadViews.Scene
import com.android.benchmark.ui.automation.Automator.Workload

internal fun uiBenchList(context: Context, test: BenchmarkTest): Scene {
    val colors = FeedColors(context)
    val labels = Utils.buildStringList(240)
    val rowHeight = context.px(56)
    val list = ListView(context).apply {
        setBackgroundColor(colors.row)
        divider = null
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = test == BenchmarkTest.UI_FADING_LIST
        setFadingEdgeLength(context.px(30))
        adapter = object : BaseAdapter() {
            override fun getCount() = labels.size
            override fun getItem(position: Int) = labels[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val row = if (test == BenchmarkTest.UI_INFLATE || recycled == null) {
                    LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false)
                        as TextView
                } else {
                    recycled as TextView
                }
                row.layoutParams = AbsListView.LayoutParams(-1, rowHeight)
                row.setTextColor(colors.title)
                row.text = labels[position]
                return row
            }
        }
    }
    val viewport: View = when (test) {
        BenchmarkTest.UI_DIALOG -> FrameLayout(context).apply {
            setBackgroundColor(0xff495363.toInt())
            setPadding(context.px(32), context.px(64), context.px(32), context.px(64))
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(context, colors.row, 22)
                clipToOutline = true
                elevation = context.px(12).toFloat()
                val title = TextView(context).apply {
                    text = context.getString(R.string.ui_dialog)
                    setTextColor(colors.title)
                    textSize = 22f
                    setPadding(context.px(20), context.px(18), context.px(14), context.px(14))
                }
                addView(title)
                addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            addView(panel, FrameLayout.LayoutParams(-1, -1))
        }
        BenchmarkTest.UI_CLIPPED_LIST -> object : FrameLayout(context) {
            private val clip = Path()
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                clip.reset()
                val inset = context.px(16).toFloat()
                if (w > inset * 2 && h > inset * 2) {
                    clip.addRoundRect(RectF(inset, inset, w - inset, h - inset),
                        context.px(38).toFloat(), context.px(38).toFloat(), Path.Direction.CW)
                }
            }

            override fun dispatchDraw(canvas: Canvas) {
                val save = canvas.save()
                canvas.clipPath(clip)
                super.dispatchDraw(canvas)
                canvas.restoreToCount(save)
            }
        }.apply {
            setBackgroundColor(colors.background)
            addView(list, FrameLayout.LayoutParams(-1, -1))
        }
        else -> list
    }

    fun position(): Int = list.firstVisiblePosition * rowHeight - (list.getChildAt(0)?.top ?: 0)
    return Scene(viewport, FeedMotion(list) { delta ->
        val prior = position()
        list.scrollListBy(delta)
        position() - prior
    })
}

internal fun uiBenchGrid(context: Context, test: BenchmarkTest): Scene {
    val colors = FeedColors(context)
    val view = recycler(context, colors)
    val slow = test == BenchmarkTest.UI_SLOW_BIND
    view.layoutManager = GridLayoutManager(context, 3)
    view.adapter = object : RecyclerView.Adapter<GridTile>() {
        override fun getItemCount() = 240

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridTile =
            GridTile(parent.context, colors)

        override fun onBindViewHolder(holder: GridTile, position: Int) {
            if (slow) {
                Trace.beginSection("UiBench bind delay")
                try {
                    // Occupy the UI thread for 3 ms to model CPU-bound binding.
                    val end = System.nanoTime() + 3_000_000L
                    while (System.nanoTime() < end) {
                        // Intentionally busy.
                    }
                } finally {
                    Trace.endSection()
                }
            }
            holder.title.text = "Item ${position + 1}"
        }
    }
    return Scene(view, FeedMotion(view, RecyclerScroll(view)::scroll))
}

private class GridTile(context: Context, colors: FeedColors) :
    RecyclerView.ViewHolder(LinearLayout(context)) {
    val title = TextView(context)

    init {
        val cell = itemView as LinearLayout
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.layoutParams = RecyclerView.LayoutParams(-1, context.px(76)).apply {
            setMargins(context.px(2), context.px(2), context.px(2), context.px(2))
        }
        cell.background = rounded(context, colors.row, 8)
        title.setTextColor(colors.title)
        title.gravity = Gravity.CENTER
        cell.addView(title, LinearLayout.LayoutParams(-1, -2))
    }
}

internal fun offlineWebView(context: Context): Scene {
    val web = WebView(context).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        webViewClient = WebViewClient()
        settings.blockNetworkLoads = true
        loadDataWithBaseURL(null, buildString {
            append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
            append("<style>body{font:16px sans-serif;padding:12px}article{background:#edf2f8;")
            append("padding:16px;margin:10px 0;border-radius:14px}h2{font-size:20px;margin:0}</style>")
            repeat(120) { row ->
                append("<article><h2>Field notes ${row + 1}</h2>")
                append("<p>The trail passes through a small town before reaching the coast.</p></article>")
            }
        }, "text/html", "UTF-8", null)
    }
    val motion = FeedMotion(web) { delta ->
        val before = web.scrollY
        web.scrollBy(0, delta)
        web.scrollY - before
    }
    return Scene(web, object : Workload {
        override fun ready(): Boolean = web.canScrollVertically(1)
        override fun prepare() = motion.prepare()
        override fun advance(elapsedNanos: Long) = motion.advance(elapsedNanos)

        override fun close() {
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    })
}

internal fun rounded(context: Context, color: Int, radius: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = context.px(radius).toFloat()
}

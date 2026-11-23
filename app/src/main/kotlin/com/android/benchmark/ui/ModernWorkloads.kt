package com.android.benchmark.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.benchmark.ui.WorkloadViews.Scene
import com.android.benchmark.ui.automation.Automator.Workload
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MS = 1_000_000L

object ModernWorkloads {
    @JvmStatic
    fun create(context: Context, test: BenchmarkTest): Scene = when (test) {
        BenchmarkTest.VIEW_FEED -> feed(context, live = false)
        BenchmarkTest.COMPOSE_FEED -> composeFeed(context)
        BenchmarkTest.NESTED_FEED -> NestedFeed(context).scene()
        BenchmarkTest.DETAIL_TRANSITION -> detailTransition(context)
        BenchmarkTest.LIVE_FEED -> feed(context, live = true)
        else -> error("Unsupported modern workload")
    }

    private fun feed(context: Context, live: Boolean): Scene {
        val colors = FeedColors(context)
        val adapter = FeedAdapter(feedItems(), loadFeedPhotos(context), colors)
        val view = recycler(context, colors)
        val scroll = RecyclerScroll(view)
        view.adapter = adapter
        if (!live) {
            return Scene(view, FeedMotion(view, scroll::scroll))
        }

        val animator = DefaultItemAnimator()
        view.itemAnimator = animator
        val motion = FeedMotion(view, scroll::scroll)
        return Scene(view, object : Workload {
            private var events = 0
            private var motionComplete = false

            override fun prepare() = motion.prepare()

            override fun advance(elapsedNanos: Long): Boolean {
                if (!motionComplete) {
                    motionComplete = motion.advance(elapsedNanos)
                }
                if (events < 6 && elapsedNanos >= (events + 1) * 320 * MS) {
                    val layout = view.layoutManager as LinearLayoutManager
                    val position = (layout.findFirstVisibleItemPosition() + 1)
                        .coerceIn(0, adapter.itemCount - 1)
                    adapter.applyUpdate(position, events++)
                }
                return motionComplete && events == 6 && !view.hasPendingAdapterUpdates() &&
                    !animator.isRunning
            }

            override fun close() = animator.endAnimations()
        })
    }

    private fun composeFeed(context: Context): Scene {
        val feed = ComposeFeed(context, feedItems(), loadFeedPhotos(context), FeedColors(context))
        val motion = FeedMotion(feed.view, feed::scroll)
        return Scene(feed.view, object : Workload {
            override fun ready() = feed.isReady()

            override fun prepare() {
                feed.prepare()
                motion.prepare()
            }

            override fun advance(elapsedNanos: Long) = motion.advance(elapsedNanos)
            override fun close() = feed.close()
        })
    }

    private fun detailTransition(context: Context): Scene {
        val colors = FeedColors(context)
        val photos = loadBoundedPhotos(context, context.px(400).coerceIn(1, 960))
        val items = feedItems()
        val base = recycler(context, colors)
        val thumbnails = loadBoundedPhotos(context, context.px(PHOTO_SIZE).coerceIn(1, 960))
        base.adapter = FeedAdapter(items, thumbnails, colors)
        val root = FrameLayout(context).apply {
            setBackgroundColor(colors.background)
            addView(base, FrameLayout.LayoutParams(-1, -1))
        }
        return Scene(root, object : Workload {
            private val draws = ArrayList<Long>()
            private var cycle = 0
            private var cycleStart = 0L
            private var detail: View? = null

            override fun prepare() {
                check(root.width > 0 && root.height > 0) { "No area for the detail transition" }
            }

            override fun advance(elapsedNanos: Long): Boolean {
                if (detail == null) {
                    cycleStart = elapsedNanos
                    val start = System.nanoTime()
                    val pane = detailView(context, colors, items[cycle], photos[cycle]) {
                        draws.add(System.nanoTime() - start)
                    }
                    pane.translationX = root.width * 0.2f
                    pane.alpha = 0.65f
                    root.addView(pane, FrameLayout.LayoutParams(-1, -1))
                    detail = pane
                }

                val time = (elapsedNanos - cycleStart) / MS
                val open = (time / 300f).coerceIn(0f, 1f)
                val close = ((time - 620) / 300f).coerceIn(0f, 1f)
                val progress = open - close
                detail?.translationX = root.width * (1 - progress) * 0.2f
                detail?.alpha = 0.65f + progress * 0.35f
                base.scaleX = 1f - progress * 0.04f
                base.scaleY = base.scaleX

                if (time < 920) {
                    return false
                }
                check(draws.size == cycle + 1) { "The detail screen did not draw" }
                root.removeView(detail)
                detail = null
                cycle++
                return cycle == 4
            }

            override fun firstDrawsNs(): List<Long> = draws.toList()

            override fun close() {
                root.removeAllViews()
                detail = null
                base.adapter = null
                photos.clear()
            }
        })
    }

    private fun detailView(
        context: Context,
        colors: FeedColors,
        item: FeedItem,
        bitmap: Bitmap,
        drawn: () -> Unit
    ): View = object : LinearLayout(context) {
        private var firstDraw = true

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (firstDraw) {
                firstDraw = false
                drawn()
            }
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colors.row)
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
        }
        addView(image, LinearLayout.LayoutParams(-1, context.px(300)))
        val title = TextView(context).apply {
            text = item.title
            textSize = 30f
            setTextColor(colors.title)
            setPadding(context.px(20), context.px(18), context.px(20), context.px(8))
        }
        addView(title)
        val body = TextView(context).apply {
            text = "${item.subtitle}\n\n" + "A collection of routes, landmarks, and photographs gathered on a weekend trip. ".repeat(6)
            textSize = 16f
            setTextColor(colors.subtitle)
            setPadding(context.px(20), 0, context.px(20), context.px(12))
        }
        addView(body)
    }
}

/** Shared motion for both feed implementations, driven from the runner's frame clock. */
internal class FeedMotion(private val view: View, private val scroll: (Int) -> Int) : Workload {
    private var distance = 0
    private var position = 0
    private var pass = 0
    private var passStart = 0L

    override fun prepare() {
        check(view.height >= 32) { "No scrollable feed viewport" }
        distance = view.height * 2
    }

    override fun advance(elapsedNanos: Long): Boolean {
        val fraction = ((elapsedNanos - passStart).toDouble() / (720 * MS)).coerceIn(0.0, 1.0)
        val motion = 1 - (1 - fraction).pow(3)
        val target = ((if (pass % 2 == 0) motion else 1 - motion) * distance).roundToInt()
        val delta = (target - position).coerceIn(-view.height / 2, view.height / 2)
        val consumed = if (delta != 0) scroll(delta) else 0
        position += consumed
        if (fraction == 1.0) {
            if (abs(target - position) <= 2) {
                pass++
                passStart = elapsedNanos
            } else {
                check(consumed != 0) { "The feed could not complete the prescribed scroll" }
            }
        }
        return pass == 4
    }
}

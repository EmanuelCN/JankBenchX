package com.android.benchmark.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.benchmark.ui.WorkloadViews.Scene
import com.android.benchmark.ui.automation.Automator.Workload
import java.util.concurrent.Executors

internal class NestedFeed(private val context: Context) {
    private val colors = FeedColors(context)
    private val view = recycler(context, colors)
    private val pool = RecyclerView.RecycledViewPool()
    private val images = CarouselPhotos(context)

    fun scene(): Scene {
        view.adapter = object : RecyclerView.Adapter<CarouselRow>() {
            override fun getItemCount() = 42
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                CarouselRow(parent.context)

            override fun onBindViewHolder(holder: CarouselRow, position: Int) {
                holder.label.text = "Photo collection ${position + 1}"
                holder.strip.stopScroll()
                holder.tiles.section = position
                holder.strip.scrollToPosition(0)
                holder.tiles.notifyDataSetChanged()
            }
        }
        val scroll = RecyclerScroll(view)
        val motion = FeedMotion(view) { delta ->
            val current = view.getChildAt(view.childCount / 2)
            if (current != null) {
                (view.getChildViewHolder(current) as CarouselRow).strip.scrollBy(delta, 0)
            }
            scroll.scroll(delta)
        }
        return Scene(view, object : Workload {
            override fun prepare() = motion.prepare()

            override fun advance(elapsedNanos: Long): Boolean {
                check(!images.failed) { "A carousel photo could not be decoded" }
                return motion.advance(elapsedNanos)
            }

            override fun close() {
                view.adapter = null
                images.close()
            }
        })
    }

    private inner class CarouselRow(context: Context) : RecyclerView.ViewHolder(LinearLayout(context)) {
        val label = TextView(context)
        val strip = RecyclerView(context)
        val tiles = Tiles()

        init {
            val wrapper = itemView as LinearLayout
            wrapper.orientation = LinearLayout.VERTICAL
            wrapper.layoutParams = RecyclerView.LayoutParams(-1, context.px(154))
            wrapper.setBackgroundColor(colors.row)
            label.textSize = 16f
            label.setTextColor(colors.title)
            label.setPaddingRelative(context.px(14), context.px(8), 0, context.px(6))
            wrapper.addView(label)
            strip.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false).apply {
                initialPrefetchItemCount = 3
            }
            strip.setRecycledViewPool(pool)
            strip.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            strip.itemAnimator = null
            strip.adapter = tiles
            wrapper.addView(strip, LinearLayout.LayoutParams(-1, context.px(104)))
        }
    }

    private inner class Tiles : RecyclerView.Adapter<Tile>() {
        var section = 0
        override fun getItemCount() = 16

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Tile {
            val image = ImageView(parent.context)
            image.layoutParams = RecyclerView.LayoutParams(context.px(128), -1).apply {
                marginStart = context.px(8)
            }
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setBackgroundColor(colors.background)
            return Tile(image)
        }

        override fun onBindViewHolder(holder: Tile, position: Int) {
            images.bind(holder.image, section * itemCount + position)
        }
    }

    private class Tile(val image: ImageView) : RecyclerView.ViewHolder(image)
}

private class CarouselPhotos(context: Context) : AutoCloseable {
    private val resources = context.resources
    private val pixels = context.px(128)
    private val cache = LruCache<Int, Bitmap>(24)
    private val decoding = HashMap<Int, MutableList<ImageView>>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    var failed = false
        private set

    fun bind(view: ImageView, key: Int) {
        view.tag = key
        val bitmap = cache.get(key)
        view.setImageBitmap(bitmap)
        if (bitmap != null || closed) {
            return
        }
        val waiting = decoding[key]
        if (waiting != null) {
            waiting.add(view)
            return
        }
        decoding[key] = mutableListOf(view)
        worker.execute {
            val result = try {
                Utils.decodeSampledBitmapFromResource(resources, photoResources[key % photoResources.size], pixels, pixels)
            } catch (_: RuntimeException) {
                null
            }
            if (!closed) {
                main.post {
                    if (!closed) {
                        val targets = decoding.remove(key).orEmpty()
                        if (result == null) {
                            failed = true
                        } else {
                            cache.put(key, result)
                            for (target in targets) {
                                if (target.tag == key) {
                                    target.setImageBitmap(result)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        decoding.clear()
        cache.evictAll()
    }
}

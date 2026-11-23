package com.android.benchmark.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.benchmark.ui.WorkloadViews.Scene
import com.android.benchmark.ui.automation.Automator.Workload

object PhotoGallery {
    private const val COLUMNS = 4
    private const val TILE_COUNT = 240
    private const val FLINGS = 4
    private const val FLING_VELOCITY_DP = 3_200

    @JvmStatic
    fun create(context: Context): Scene {
        val grid = recycler(context, FeedColors(context))
        grid.layoutManager = GridLayoutManager(context, COLUMNS)
        val photoSide = (context.resources.displayMetrics.widthPixels / COLUMNS).coerceIn(1, 640)
        val thumbnails = loadBoundedPhotos(context, photoSide)
        grid.adapter = object : RecyclerView.Adapter<PhotoTile>() {
            override fun getItemCount() = TILE_COUNT

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                PhotoTile(parent.context)

            override fun onBindViewHolder(holder: PhotoTile, position: Int) {
                // Stagger successive rows so the same photo does not repeat down each column.
                val photoIndex = (position - position / COLUMNS) % thumbnails.size
                holder.image.setImageBitmap(thumbnails[photoIndex])
            }
        }
        // Leave room on both sides so the flings can complete without hitting the edges.
        grid.scrollToPosition(TILE_COUNT / 2)

        return Scene(grid, object : Workload {
            private var flingsStarted = 0
            private var startOffset = 0

            override fun advance(elapsedNanos: Long): Boolean {
                if (grid.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                    return false
                }
                val offset = grid.computeVerticalScrollOffset()
                if (flingsStarted > 0) {
                    check(offset != startOffset) { "The photo gallery did not scroll" }
                }
                if (flingsStarted == FLINGS) {
                    return true
                }

                val direction = if (flingsStarted % 2 == 0) 1 else -1
                startOffset = offset
                check(grid.fling(0, direction * context.px(FLING_VELOCITY_DP))) {
                    "Could not start the photo gallery fling"
                }
                flingsStarted++
                return false
            }

            override fun close() {
                grid.stopScroll()
                grid.adapter = null
                thumbnails.clear()
            }
        })
    }

    private class PhotoTile(context: Context) : RecyclerView.ViewHolder(
        object : AppCompatImageView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val side = MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
            }
        }
    ) {
        val image = itemView as ImageView

        init {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                setMargins(context.px(1), context.px(1), context.px(1), context.px(1))
            }
        }
    }
}

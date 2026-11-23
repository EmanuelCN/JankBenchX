package com.android.benchmark.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.benchmark.R
import kotlin.math.roundToInt

internal val photoResources = intArrayOf(R.drawable.img1, R.drawable.img2, R.drawable.img3, R.drawable.img4)
internal const val PHOTO_SIZE = 80
private const val ROW_HEIGHT = 108

internal data class FeedItem(val id: Long, val title: String, val subtitle: String, val image: Int)

internal fun feedItems(): MutableList<FeedItem> = MutableList(120) { index ->
    val title = listOf("On the coast", "City walks", "Mountain light", "Weekend journal")[index % 4]
    FeedItem(index.toLong(), title, "Photo collection ${index + 1}", index % photoResources.size)
}

internal fun Context.px(dp: Int) = (dp * resources.displayMetrics.density).roundToInt()

internal class FeedColors(context: Context) {
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    val background = if (dark) 0xff16171b.toInt() else 0xfff5f6fa.toInt()
    val row = if (dark) 0xff25272c.toInt() else 0xffffffff.toInt()
    val title = if (dark) 0xfff1f2f5.toInt() else 0xff202128.toInt()
    val subtitle = if (dark) 0xffc3c5cf.toInt() else 0xff535662.toInt()
}

internal fun loadFeedPhotos(context: Context, sizeDp: Int = PHOTO_SIZE): List<Bitmap> {
    val pixels = context.px(sizeDp).coerceAtMost(960)
    return photoResources.map { resource ->
        checkNotNull(Utils.decodeSampledBitmapFromResource(context.resources, resource, pixels, pixels)) {
            "Could not decode a benchmark photo"
        }
    }
}

internal fun loadBoundedPhotos(context: Context, maxSide: Int): MutableList<Bitmap> =
    photoResources.mapTo(ArrayList()) { boundedPhoto(context, it, maxSide) }

private fun boundedPhoto(context: Context, resource: Int, maxSide: Int): Bitmap {
    // This limit is in pixels. Resource density scaling can otherwise multiply
    // the decoded dimensions on high-density phones.
    val options = BitmapFactory.Options().apply {
        inScaled = false
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeResource(context.resources, resource, options)
    val sourceSide = maxOf(options.outWidth, options.outHeight)
    var sample = 1
    while (sourceSide / sample >= maxSide * 2) {
        sample *= 2
    }
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    val decoded = checkNotNull(BitmapFactory.decodeResource(context.resources, resource, options)) {
        "Could not decode a benchmark photo"
    }
    val decodedSide = maxOf(decoded.width, decoded.height)
    if (decodedSide <= maxSide) {
        return decoded
    }
    val scale = maxSide.toFloat() / decodedSide
    return try {
        Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1), true)
    } finally {
        decoded.recycle()
    }
}

internal fun recycler(context: Context, colors: FeedColors): RecyclerView = RecyclerView(context).apply {
    layoutManager = LinearLayoutManager(context)
    itemAnimator = null
    overScrollMode = View.OVER_SCROLL_NEVER
    setBackgroundColor(colors.background)
}

internal class FeedRow(context: Context, colors: FeedColors) : LinearLayout(context) {
    private val image = ImageView(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)

    init {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(colors.row)
        setPadding(context.px(12), 0, context.px(12), 0)
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.px(ROW_HEIGHT))
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(image, LayoutParams(context.px(PHOTO_SIZE), context.px(PHOTO_SIZE)))
        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            setPaddingRelative(context.px(14), 0, 0, 0)
        }
        addView(labels, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        title.setTextColor(colors.title)
        title.textSize = 16f
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        title.includeFontPadding = false
        subtitle.setTextColor(colors.subtitle)
        subtitle.textSize = 13f
        subtitle.includeFontPadding = false
        labels.addView(title)
        labels.addView(subtitle, LayoutParams(-1, -2).apply { topMargin = context.px(4) })
    }

    fun bind(item: FeedItem, bitmap: Bitmap) {
        title.text = item.title
        subtitle.text = item.subtitle
        image.setImageBitmap(bitmap)
    }
}

internal class FeedAdapter(
    private val items: MutableList<FeedItem>,
    private val photos: List<Bitmap>,
    private val colors: FeedColors
) : RecyclerView.Adapter<FeedAdapter.Holder>() {
    class Holder(val row: FeedRow) : RecyclerView.ViewHolder(row)

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = items[position].id
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(FeedRow(parent.context, colors))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.row.bind(item, photos[item.image])
    }

    fun applyUpdate(index: Int, event: Int) {
        when (event % 3) {
            0 -> {
                items.add(index, FeedItem(1000L + event, "New photo", "Added while scrolling", event % photos.size))
                notifyItemInserted(index)
            }
            1 -> {
                items.removeAt(index)
                notifyItemRemoved(index)
            }
            2 -> {
                items[index] = items[index].copy(subtitle = "Updated during scrolling")
                notifyItemChanged(index)
            }
        }
    }
}

internal class RecyclerScroll(recycler: RecyclerView) {
    private var moved = 0
    private val list = recycler

    init {
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                moved += dy
            }
        })
    }

    fun scroll(delta: Int): Int {
        val before = moved
        list.scrollBy(0, delta)
        return moved - before
    }
}

internal class ComposeFeed(context: Context, items: List<FeedItem>, photos: List<Bitmap>, colors: FeedColors) {
    private var state: LazyListState? = null
    val view = ComposeView(context)

    init {
        val bitmaps = photos.map(Bitmap::asImageBitmap)
        view.setContent {
            val list = rememberLazyListState()
            SideEffect { state = list }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().background(Color(colors.background)),
                userScrollEnabled = false
            ) {
                items(items, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT.dp)
                            .background(Color(colors.row)).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(bitmaps[item.image], null, Modifier.size(PHOTO_SIZE.dp), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BasicText(item.title, style = TextStyle(color = Color(colors.title),
                                fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicText(item.subtitle, style = TextStyle(color = Color(colors.subtitle), fontSize = 13.sp))
                        }
                    }
                }
            }
        }
    }

    fun isReady(): Boolean = state != null

    fun prepare() {
        checkNotNull(state) { "Compose feed did not finish layout" }
    }

    fun scroll(delta: Int): Int =
        checkNotNull(state).dispatchRawDelta(delta.toFloat()).roundToInt()

    fun close() {
        view.disposeComposition()
    }
}

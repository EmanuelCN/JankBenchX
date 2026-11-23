package com.android.benchmark.ui;

import android.content.Context;
import android.os.Build;

import com.android.benchmark.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public enum BenchmarkTest {
    LIST(R.string.list_view_scroll_name, "Scroll a list of 400 short strings"),
    IMAGE_LIST(R.string.image_list_view_scroll_name, "Scroll photos while rows decode images"),
    SHADOW_GRID(R.string.shadow_grid_name, "Scroll rows with elevated cards"),
    TEXT_HIGH(R.string.text_high_hitrate_name, "Lay out paragraphs with 80% reusable words"),
    TEXT_LOW(R.string.text_low_hitrate_name, "Lay out paragraphs with 20% reusable words"),
    EDIT_TEXT(R.string.edit_text_input_name, "Type 135 characters into an editable field"),
    OVERDRAW(R.string.overdraw_name, "Redraw ten overlapping layers per frame"),
    BITMAP_UPLOAD(R.string.bitmap_upload_name, "Update and redraw a large bitmap each frame"),
    VIEW_FEED(R.string.view_feed_name, "Scroll a photo feed built with RecyclerView"),
    COMPOSE_FEED(R.string.compose_feed_name, "Scroll the same photo feed in Compose"),
    NESTED_FEED(R.string.nested_feed_name, "Scroll a feed with horizontal photo carousels"),
    DETAIL_TRANSITION(R.string.detail_transition_name, "Open and return from photo detail screens"),
    LIVE_FEED(R.string.live_feed_name, "Insert, remove, and update cards while scrolling"),
    UI_DIALOG(R.string.ui_dialog, "Scroll through rows inside an in-app dialog"),
    UI_GL_TEXTURE(R.string.ui_gl_texture, "Composite a rotating, OpenGL-rendered texture"),
    UI_OVERDRAW(R.string.ui_overdraw, OVERDRAW),
    UI_INVALIDATE(R.string.ui_invalidate, "Redraw a small moving region of the canvas"),
    UI_INVALIDATE_TREE(R.string.ui_invalidate_tree, "Invalidate leaves in a nested view hierarchy"),
    UI_RESIZE_LAYER(R.string.ui_resize_layer, "Resize a hardware-backed view through layout"),
    UI_ANIMATION(R.string.ui_animation, "Animate one shape over a static background"),
    UI_WIDE_GAMUT(R.string.ui_wide_gamut, "Animate Display P3 colors on a wide-gamut display"),
    UI_LIST(R.string.ui_list, LIST),
    UI_FADING_LIST(R.string.ui_fading_list, "Fling rows with the platform fading-edge effect"),
    UI_SAVE_LAYER(R.string.ui_save_layer, "Animate overlapping shapes in offscreen layers"),
    UI_CLIPPED_LIST(R.string.ui_clipped_list, "Scroll rows clipped by a rounded path"),
    UI_RECYCLER(R.string.ui_recycler, "Fling a three-column RecyclerView of plain tiles"),
    UI_SLOW_BIND(R.string.ui_slow_bind, "Fling a grid with an intentionally expensive bind"),
    UI_NESTED(R.string.ui_nested, NESTED_FEED),
    UI_TRANSITION(R.string.ui_transition, DETAIL_TRANSITION),
    UI_UPLOAD(R.string.ui_upload, BITMAP_UPLOAD),
    UI_SHADOW(R.string.ui_shadow, SHADOW_GRID),
    UI_JITTER(R.string.ui_jitter, "Track frame pacing during a repeating multi-shape motion"),
    UI_INFLATE(R.string.ui_inflate, "Inflate a fresh layout for every bound row"),
    UI_EDIT(R.string.ui_edit, EDIT_TEXT),
    UI_TEXT_LOW(R.string.ui_text_low, TEXT_LOW),
    UI_TEXT_HIGH(R.string.ui_text_high, TEXT_HIGH),
    UI_WEB(R.string.ui_web, "Scroll a bundled, offline document in WebView"),
    UI_DRAWER(R.string.ui_drawer, "Open and close a sliding panel with a scrim"),
    UI_SHADE(R.string.ui_shade, "Expand an in-app stack of notification cards"),
    UI_TV_BROWSE(R.string.ui_tv_browse, "Move focus and scroll across a TV-style photo grid"),
    PHOTO_GALLERY(R.string.photo_gallery_name, "Fling through a phone-style grid of photo thumbnails"),
    UI_INSETS(R.string.ui_insets, "Relayout an editor as simulated keyboard insets change");

    public final int title;
    public final String description;
    private final BenchmarkTest driver;

    private static final Map<BenchmarkTest, BenchmarkTest> REPLACEMENTS = new EnumMap<>(BenchmarkTest.class);
    private static final List<BenchmarkTest> WORKLOADS;

    static {
        // Keep legacy IDs readable for saved runs. The UiBench entries replace
        // their older menu entries where they use the very same workload driver.
        for (BenchmarkTest test : values()) {
            if (test.driver != test) {
                REPLACEMENTS.put(test.driver, test);
            }
        }
        LinkedHashSet<BenchmarkTest> unique = new LinkedHashSet<>();
        for (BenchmarkTest test : values()) {
            if (test != UI_TV_BROWSE) {
                unique.add(test.canonical());
            }
        }
        WORKLOADS = Collections.unmodifiableList(new ArrayList<>(unique));
    }

    BenchmarkTest(int title, String description) {
        this.title = title;
        this.description = description;
        this.driver = this;
    }

    BenchmarkTest(int title, BenchmarkTest driver) {
        this.title = title;
        this.description = driver.description;
        this.driver = driver;
    }

    public static List<BenchmarkTest> workloads() {
        return WORKLOADS;
    }

    public boolean isSupported(Context context) {
        return this != UI_WIDE_GAMUT
                || (Build.VERSION.SDK_INT >= 29
                    && context.getResources().getConfiguration().isScreenWideColorGamut());
    }

    public BenchmarkTest canonical() {
        return REPLACEMENTS.getOrDefault(this, this);
    }

    BenchmarkTest driver() {
        return driver;
    }
}

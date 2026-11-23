/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.benchmark.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;

import com.android.benchmark.R;
import com.android.benchmark.ui.automation.Automator.Workload;

public final class WorkloadViews {
    private static final long MS = 1_000_000L;
    private static final long ANIMATION_NS = 2_000 * MS;

    public record Scene(View view, Workload workload) {}

    private WorkloadViews() {}

    public static Scene create(Context context, ViewGroup parent, BenchmarkTest test) {
        return switch (test.driver()) {
            case LIST -> scrolling(context, words(context, 400), false);
            case IMAGE_LIST -> scrolling(context, new ImageListAdapter(context), false);
            case SHADOW_GRID -> scrolling(context, new ArrayAdapter<>(context,
                    R.layout.card_row, R.id.card_text, Utils.buildStringList(200)), true);
            case TEXT_HIGH -> scrolling(context, paragraphs(context, 80), false);
            case TEXT_LOW -> scrolling(context, paragraphs(context, 20), false);
            case EDIT_TEXT -> typing(context);
            case OVERDRAW -> overdraw(context);
            case BITMAP_UPLOAD -> upload(context, parent);
            case VIEW_FEED, COMPOSE_FEED, NESTED_FEED, DETAIL_TRANSITION, LIVE_FEED ->
                    ModernWorkloads.create(context, test.driver());
            case PHOTO_GALLERY -> PhotoGallery.create(context);
            default -> UiBenchWorkloads.create(context, test);
        };
    }

    private static ListAdapter words(Context context, int count) {
        return new ArrayAdapter<>(context, android.R.layout.simple_list_item_1,
                Utils.buildStringList(count));
    }

    private static ListAdapter paragraphs(Context context, int hitRate) {
        return new ArrayAdapter<>(context, android.R.layout.simple_list_item_1,
                Utils.buildParagraphListWithHitPercentage(200, hitRate));
    }

    private static Scene scrolling(Context context, ListAdapter adapter, boolean cards) {
        ListView list = new ListView(context);
        list.setAdapter(adapter);
        if (cards) {
            list.setDivider(null);
        }
        return new Scene(list, new ScrollWorkload(list, adapter));
    }

    private static final class ScrollWorkload implements Workload {
        private static final int PASSES = 8;
        private static final long PASS_NS = 620 * MS;
        private final ListView list;
        private final ListAdapter adapter;
        private int distance;
        private int pass;
        private int lastOffset;
        private int maxDelta;
        private long passStart;
        private boolean moved;

        ScrollWorkload(ListView list, ListAdapter adapter) {
            this.list = list;
            this.adapter = adapter;
        }

        @Override
        public void prepare() {
            // Cover the initial drag and additional travel from a fling. Cap each
            // scrollListBy call so ListView can recycle rows even after a missed vsync.
            distance = Math.max(500, list.getHeight() * 2);
            maxDelta = list.getHeight() / 2;
            if (maxDelta < 16 || list.getChildCount() == 0 || !list.canScrollList(1)) {
                throw new IllegalStateException("The workload list has no scrollable content");
            }
        }

        @Override
        public boolean advance(long elapsedNanos) {
            if (adapter instanceof ImageListAdapter images && images.decodeFailed()) {
                throw new IllegalStateException("The workload could not decode a benchmark image");
            }
            double progress = Math.min(1, (double) (elapsedNanos - passStart) / PASS_NS);
            double eased = 1 - Math.pow(1 - progress, 3);
            int offset = (int) Math.round(distance * (pass % 2 == 0 ? eased : 1 - eased));
            int delta = Math.max(-maxDelta, Math.min(maxDelta, offset - lastOffset));
            if (delta != 0) {
                int position = list.getFirstVisiblePosition();
                int top = list.getChildAt(0).getTop();
                list.scrollListBy(delta);
                moved |= position != list.getFirstVisiblePosition()
                        || top != list.getChildAt(0).getTop();
                lastOffset += delta;
            }
            if (progress == 1 && lastOffset == offset) {
                if (!moved) {
                    throw new IllegalStateException("The workload list did not move");
                }
                pass++;
                passStart = elapsedNanos;
                moved = false;
            }
            return pass == PASSES;
        }

        @Override
        public void close() {
            if (adapter instanceof ImageListAdapter images) {
                images.close();
            }
        }
    }

    private static Scene typing(Context context) {
        EditText field = new EditText(context);
        field.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        field.setShowSoftInputOnFocus(false);
        field.setOnKeyListener((view, keyCode, event) -> event.isPrintingKey());
        field.setCursorVisible(false);
        field.setPadding(24, 24, 24, 24);
        return new Scene(field, new Workload() {
            private static final String INPUT = "abcdefghijklmnopqrstuvwxyz ";
            private static final long KEY_INTERVAL_NS = 24 * MS;
            private int typed;

            @Override
            public void prepare() {
                field.requestFocus();
            }

            @Override
            public boolean advance(long elapsedNanos) {
                if (elapsedNanos >= typed * KEY_INTERVAL_NS) {
                    int offset = typed % INPUT.length();
                    field.getText().append(INPUT, offset, offset + 1);
                    typed++;
                }
                return typed == 5 * INPUT.length();
            }
        });
    }

    private static Scene overdraw(Context context) {
        OverdrawView view = new OverdrawView(context);
        return new Scene(view, elapsed -> {
            view.color = color(elapsed);
            view.invalidate();
            return elapsed >= ANIMATION_NS;
        });
    }

    private static Scene upload(Context context, ViewGroup parent) {
        View root = LayoutInflater.from(context).inflate(R.layout.activity_bitmap_upload, parent, false);
        UploadView upload = root.findViewById(R.id.upload_view);
        return new Scene(root, new Workload() {
            @Override
            public void prepare() {
                int size = Math.min(720, (int) (0.75 * Math.min(root.getWidth(), root.getHeight())));
                if (size < 1) {
                    throw new IllegalStateException("The bitmap workload has no drawing area");
                }
                upload.bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            }

            @Override
            public boolean advance(long elapsedNanos) {
                int color = color(elapsedNanos);
                upload.bitmap.eraseColor(Color.rgb(color, 255 - color, 255));
                upload.invalidate();
                root.setTranslationY(100f * color / 255);
                return elapsedNanos >= ANIMATION_NS;
            }

            @Override
            public void close() {
                // HWUI may still own the bitmap used by the previous frame.
                upload.bitmap = null;
            }
        });
    }

    private static int color(long elapsed) {
        long half = 300 * MS;
        long position = elapsed % (2 * half);
        return (int) (255 * (half - Math.abs(position - half)) / half);
    }

    private static final class OverdrawView extends View {
        private final Paint paint = new Paint();
        int color;

        OverdrawView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            for (int layer = 0; layer < 10; layer++) {
                // Translucency keeps all ten overlapping draws relevant to the output.
                paint.setColor(Color.argb(105, color, 255 - color, 155 + layer * 10));
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
        }
    }

    public static final class UploadView extends View {
        private final Rect bounds = new Rect();
        Bitmap bitmap;

        public UploadView(Context context, android.util.AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bitmap != null) {
                bounds.set(0, 0, getWidth(), getHeight());
                canvas.drawBitmap(bitmap, null, bounds, null);
            }
        }
    }
}

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
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.benchmark.R;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ImageListAdapter extends BaseAdapter implements AutoCloseable {
    private static final int[] IMAGE_IDS = {R.drawable.img1, R.drawable.img2,
            R.drawable.img3, R.drawable.img4};
    private static final String[] WORDS = Utils.buildStringList(100);

    private record Bind(ImageView view, Object token) {}

    private static final class ImageSlot {
        final ArrayList<Bind> waiting = new ArrayList<>();
        Bitmap bitmap;
        boolean decoding;
    }

    private final LayoutInflater inflater;
    private final android.content.res.Resources resources;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final ImageSlot[] slots = new ImageSlot[16];
    private volatile boolean closed;
    private boolean decodeFailed;

    ImageListAdapter(Context context) {
        inflater = LayoutInflater.from(context);
        resources = context.getResources();
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new ImageSlot();
        }
    }

    @Override public int getCount() { return WORDS.length; }
    @Override public Object getItem(int position) { return WORDS[position]; }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View recycled, ViewGroup parent) {
        View row = recycled == null ? inflater.inflate(R.layout.image_scroll_list_item, parent, false)
                : recycled;
        TextView label = row.findViewById(R.id.image_scroll_text);
        label.setText(WORDS[position]);
        ImageView image = row.findViewById(R.id.image_scroll_image);
        int index = position % slots.length;
        ImageSlot slot = slots[index];
        Object token = new Object();
        image.setTag(token);
        image.setImageBitmap(slot.bitmap);
        if (slot.bitmap == null && !closed) {
            slot.waiting.add(new Bind(image, token));
            if (!slot.decoding) {
                slot.decoding = true;
                decoder.execute(() -> {
                    Bitmap decoded;
                    try {
                        decoded = Utils.decodeSampledBitmapFromResource(resources,
                                IMAGE_IDS[index % IMAGE_IDS.length], 100, 100);
                    } catch (RuntimeException e) {
                        decoded = null;
                    }
                    Bitmap result = decoded;
                    if (!closed) {
                        main.post(() -> apply(slot, result));
                    }
                });
            }
        }
        return row;
    }

    private void apply(ImageSlot slot, Bitmap bitmap) {
        if (closed) {
            return;
        }
        slot.bitmap = bitmap;
        decodeFailed |= bitmap == null;
        for (Bind binding : slot.waiting) {
            if (binding.view().getTag() == binding.token()) {
                binding.view().setImageBitmap(bitmap);
            }
        }
        slot.waiting.clear();
    }

    boolean decodeFailed() {
        return decodeFailed;
    }

    @Override
    public void close() {
        closed = true;
        decoder.shutdownNow();
        main.removeCallbacksAndMessages(null);
        for (ImageSlot slot : slots) {
            slot.waiting.clear();
            slot.bitmap = null;
        }
    }
}

package com.android.benchmark.app;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.DrawableWrapper;
import android.view.animation.DecelerateInterpolator;

import com.android.benchmark.R;

/** Keeps the wave moving while the progress level controls how much of it is visible. */
final class WaveProgressDrawable extends DrawableWrapper implements Animatable {
    private final AnimatedVectorDrawable wave;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator advance = ValueAnimator.ofFloat(0f, 0f);
    private float visibleLevel;

    @SuppressLint("UseCompatLoadingForDrawables") // Keep the wave on the platform's render thread.
    WaveProgressDrawable(Context context, int foreground, int background) {
        this((AnimatedVectorDrawable) context.getDrawable(R.drawable.benchmark_wave).mutate(),
                foreground, background);
    }

    private WaveProgressDrawable(AnimatedVectorDrawable wave, int foreground, int background) {
        super(wave);
        this.wave = wave;
        wave.setTint(foreground);
        track.setColor(background);
        track.setStrokeCap(Paint.Cap.ROUND);
        marker.setColor(foreground);
        advance.setDuration(240);
        advance.setInterpolator(new DecelerateInterpolator());
        advance.addUpdateListener(frame -> {
            visibleLevel = (float) frame.getAnimatedValue();
            invalidateSelf();
        });
    }

    @Override
    protected boolean onLevelChange(int level) {
        advance.cancel();
        if (wave.isRunning() && ValueAnimator.areAnimatorsEnabled()) {
            advance.setFloatValues(visibleLevel, level);
            advance.start();
        } else {
            visibleLevel = level;
            invalidateSelf();
        }
        return true;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        int waveHeight = Math.min(getIntrinsicHeight(), bounds.height());
        int top = bounds.top + (bounds.height() - waveHeight) / 2;
        super.onBoundsChange(new Rect(bounds.left, top, bounds.right, top + waveHeight));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        Rect waveBounds = wave.getBounds();
        float stroke = waveBounds.height() * 0.42f;
        // Leave room for motion on pass one and use the rest of the width for progress.
        float firstPassWave = Math.min(bounds.width(), Math.max(waveBounds.height() * 2f, bounds.width() / 24f));
        float length = firstPassWave + (bounds.width() - firstPassWave) * visibleLevel / 10_000f;
        float end = Math.min(bounds.right, bounds.left + length);
        float trackStart = end + stroke * 1.9f;
        float trackEnd = bounds.right - stroke / 2;
        if (trackStart < trackEnd) {
            track.setStrokeWidth(stroke);
            canvas.drawLine(trackStart, waveBounds.exactCenterY(), trackEnd, waveBounds.exactCenterY(), track);
        }
        float markerStart = Math.max(bounds.left, end - stroke);
        float markerCenter = (markerStart + end) / 2;
        int save = canvas.save();
        canvas.clipRect(bounds.left, bounds.top, markerCenter, bounds.bottom);
        super.draw(canvas);
        canvas.restoreToCount(save);
        float inset = bounds.height() * 0.08f;
        canvas.drawRoundRect(markerStart, bounds.top + inset,
                end, bounds.bottom - inset, stroke / 2, stroke / 2, marker);
    }

    @Override
    public void start() {
        if (ValueAnimator.areAnimatorsEnabled()) {
            wave.start();
        }
    }

    @Override
    public void stop() {
        if (advance.isStarted()) {
            advance.end();
        }
        wave.stop();
    }

    @Override
    public boolean isRunning() {
        return wave.isRunning();
    }
}

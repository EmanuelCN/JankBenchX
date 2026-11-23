package com.android.benchmark.app;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import com.google.android.material.R;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

abstract class Screen extends ComponentActivity {
    int background;
    int surface;
    int primary;
    int onPrimary;
    int ink;
    int muted;
    int soft;
    int onSoft;
    int outline;
    int danger;
    int positive;
    Context materialTheme;
    private boolean dark;

    @Override
    protected void onStart() {
        super.onStart();
        optOutOfAdaptiveRefresh(getWindow());
    }

    @SuppressWarnings("deprecation")
    final Display screenDisplay() {
        Display attached = getWindow().getDecorView().getDisplay();
        return attached != null ? attached : getWindowManager().getDefaultDisplay();
    }

    final void showDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            optOutOfAdaptiveRefresh(window);
        }
        dialog.show();
    }

    private static void optOutOfAdaptiveRefresh(Window window) {
        if (Build.VERSION.SDK_INT >= 35) {
            // Leave mode selection to Android, including the device's user refresh-rate cap.
            window.setFrameRatePowerSavingsBalanced(false);
        }
    }

    @SuppressWarnings("deprecation")
    final LinearLayout createScreen() {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int theme = dark ? R.style.Theme_Material3Expressive_Dark_NoActionBar
                : R.style.Theme_Material3Expressive_Light_NoActionBar;
        materialTheme = DynamicColors.wrapContextIfAvailable(new ContextThemeWrapper(this, theme));
        background = themeColor(R.attr.colorSurface);
        surface = themeColor(R.attr.colorSurfaceContainer);
        ink = themeColor(R.attr.colorOnSurface);
        muted = themeColor(R.attr.colorOnSurfaceVariant);
        primary = themeColor(androidx.appcompat.R.attr.colorPrimary);
        onPrimary = themeColor(R.attr.colorOnPrimary);
        soft = themeColor(R.attr.colorPrimaryContainer);
        onSoft = themeColor(R.attr.colorOnPrimaryContainer);
        outline = themeColor(R.attr.colorOutlineVariant);
        danger = themeColor(androidx.appcompat.R.attr.colorError);
        positive = themeColor(R.attr.colorTertiary);
        getWindow().setBackgroundDrawable(new ColorDrawable(background));

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        LinearLayout root = column();
        root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                WindowInsetsController controller = view.getWindowInsetsController();
                if (controller != null) {
                    int bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    controller.setSystemBarsAppearance(dark ? 0 : bars, bars);
                }
            } else {
                int left = insets.getSystemWindowInsetLeft();
                int top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    android.view.DisplayCutout cutout = insets.getDisplayCutout();
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    top = Math.max(top, cutout.getSafeInsetTop());
                    right = Math.max(right, cutout.getSafeInsetRight());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
                view.setPadding(left, top, right, bottom);
            }
            return insets;
        });
        setContentView(root);
        return root;
    }

    final LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    final LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    final LinearLayout scroller(LinearLayout root) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout center = new FrameLayout(this);
        scroll.addView(center);
        LinearLayout content = new LinearLayout(this) {
            @Override
            protected void onMeasure(int width, int height) {
                int bounded = Math.min(MeasureSpec.getSize(width), dp(760));
                super.onMeasure(MeasureSpec.makeMeasureSpec(bounded, MeasureSpec.EXACTLY), height);
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);
        center.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP
                | Gravity.CENTER_HORIZONTAL));
        content.setPadding(dp(20), dp(14), dp(20), dp(28));
        return content;
    }

    final void scrollToTop(LinearLayout content) {
        if (content.getParent().getParent() instanceof ScrollView scroll) {
            scroll.scrollTo(0, 0);
        }
    }

    final TextView text(CharSequence label, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    final Button action(String label, boolean filled) {
        Button view = new Button(this);
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(15);
        view.setTextColor(filled ? onPrimary : onSoft);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        view.setStateListAnimator(null);
        view.setBackground(ripple(filled ? primary : soft, 14));
        return view;
    }

    final LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setBackground(shape(surface, 20, outline));
        card.setPadding(dp(17), dp(15), dp(17), dp(15));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        parent.addView(card, params);
        return card;
    }

    final void space(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    final void section(LinearLayout target, String label) {
        space(target, 12);
        TextView title = text(label, 20, ink, true);
        if (Build.VERSION.SDK_INT >= 28) {
            title.setAccessibilityHeading(true);
        }
        target.addView(title);
        space(target, 10);
    }

    final GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) {
            drawable.setStroke(dp(1), border);
        }
        return drawable;
    }

    final RippleDrawable ripple(int fill, int radius) {
        int foreground = fill == primary ? onPrimary : fill == soft ? onSoft : ink;
        return new RippleDrawable(ColorStateList.valueOf(MaterialColors.compositeARGBWithAlpha(
                foreground, 31)), shape(fill, radius, 0), null);
    }

    private int themeColor(int attr) {
        return MaterialColors.getColor(materialTheme, attr, "JankBenchX theme");
    }

    final int dp(float amount) {
        return Math.round(amount * getResources().getDisplayMetrics().density);
    }
}

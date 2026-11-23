package com.android.benchmark.app;

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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import com.google.android.material.R;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

public abstract class Screen extends ComponentActivity {
    RunnerColors runnerColors;

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

    static void optOutOfAdaptiveRefresh(Window window) {
        if (Build.VERSION.SDK_INT >= 35) {
            // Leave mode selection to Android, including the device's user refresh-rate cap.
            window.setFrameRatePowerSavingsBalanced(false);
        }
    }

    @SuppressWarnings("deprecation")
    final LinearLayout createScreen() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int theme = dark ? R.style.Theme_Material3Expressive_Dark_NoActionBar
                : R.style.Theme_Material3Expressive_Light_NoActionBar;
        Context materialTheme = DynamicColors.wrapContextIfAvailable(new ContextThemeWrapper(this, theme));
        int background = MaterialColors.getColor(materialTheme, R.attr.colorSurface, "JankBenchX theme");
        getWindow().setBackgroundDrawable(new ColorDrawable(background));
        runnerColors = new RunnerColors(this, dark);

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

    final TextView text(CharSequence label, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    final Button cancelAction() {
        Button view = new Button(this);
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setAllCaps(false);
        view.setText(com.android.benchmark.R.string.cancel_benchmark);
        view.setTextSize(15);
        int foreground = runnerColors.getOnAction();
        view.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{runnerColors.getSupporting(), foreground}));
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        view.setStateListAnimator(null);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(runnerColors.getAction());
        shape.setCornerRadius(dp(24));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(
                MaterialColors.compositeARGBWithAlpha(foreground, 31)), shape, null));
        return view;
    }

    final void space(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    final int dp(float amount) {
        return Math.round(amount * getResources().getDisplayMetrics().density);
    }
}

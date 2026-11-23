package com.android.benchmark.app;

import android.content.Context;

import com.android.benchmark.R;
import com.android.benchmark.results.RunStore.Run;
import com.android.benchmark.results.RunStore.Status;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;

final class ResultLabels {
    private ResultLabels() {}

    static String score(double value) {
        return measurement(value, false);
    }

    static String ms(double value) {
        return measurement(value, false) + " ms";
    }

    static String signedMs(double value) {
        return measurement(value, true) + " ms";
    }

    private static String measurement(double value, boolean signed) {
        // Two-place rounding at the boundary should not produce "1,000.00".
        if (Math.abs(value) >= 999.995) {
            return String.format(Locale.getDefault(), signed ? "%+,.0f" : "%,.0f", value);
        }
        return String.format(Locale.getDefault(), signed ? "%+.2f" : "%.2f", value);
    }

    static String percent(double value) {
        return String.format(Locale.getDefault(), value == 100.0 ? "%.0f%%" : "%.1f%%", value);
    }

    static String date(Run run) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(run.started()));
    }

    static String device(Run run) {
        return run.device() + "  ·  Android " + run.release() + " (API " + run.api() + ")";
    }

    static String kernel(Context context, Run run) {
        String release = run.kernelRelease();
        return release == null || release.isEmpty()
                ? context.getString(R.string.kernel_unavailable)
                : context.getString(R.string.kernel_release, release);
    }

    static String status(Status status) {
        return switch (status) {
            case RUNNING -> "In progress";
            case COMPLETE -> "Complete";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
        };
    }

    static String peakRefresh(float max) {
        if (!(max > 0)) {
            return "Unavailable";
        }
        NumberFormat formatter = NumberFormat.getNumberInstance();
        formatter.setMaximumFractionDigits(1);
        return formatter.format(max) + " Hz";
    }

    static String refresh(float min, float max) {
        if (!(min > 0 && max > 0)) {
            return "Unavailable";
        }
        if (max - min < 0.05f) {
            return String.format(Locale.getDefault(), "%.1f Hz", max);
        }
        return String.format(Locale.getDefault(), "%.1f–%.1f Hz", min, max);
    }
}

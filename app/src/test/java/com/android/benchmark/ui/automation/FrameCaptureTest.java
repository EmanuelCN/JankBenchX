package com.android.benchmark.ui.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.benchmark.ui.automation.FrameCapture.ReportWindow;

import org.junit.Test;

public class FrameCaptureTest {
    @Test
    public void reportsOutsideAnAnchoredCaptureDoNotCountAsDroppedFrames() {
        ReportWindow reports = new ReportWindow();
        assertFalse(reports.accepts(90, 3));
        reports.begin(100);
        assertFalse(reports.accepts(95, 2));
        assertTrue(reports.accepts(100, 4));
        assertTrue(reports.accepts(110, 0));
        reports.end(120);
        assertTrue(reports.accepts(120, 0));
        assertTrue(reports.confirmedEnd());
        assertFalse(reports.accepts(130, 5));
        assertTrue(reports.reachedEnd());
        assertEquals(0, reports.dropped());
    }

    @Test
    public void dropsAtUnconfirmedBoundariesCanOverlapTheCapture() {
        ReportWindow reports = new ReportWindow();
        reports.begin(100);
        assertTrue(reports.accepts(110, 2));
        assertTrue(reports.accepts(115, 1));
        reports.end(120);
        assertFalse(reports.accepts(130, 1));
        assertTrue(reports.reachedEnd());
        assertEquals(4, reports.dropped());
    }

    @Test
    public void aReportWithoutATimestampCannotConfirmTheEnd() {
        ReportWindow reports = new ReportWindow();
        reports.begin(100);
        reports.end(120);
        assertFalse(reports.accepts(0, 0));
        assertFalse(reports.confirmedEnd());
        assertEquals(1, reports.invalidTimestamps());
    }
}

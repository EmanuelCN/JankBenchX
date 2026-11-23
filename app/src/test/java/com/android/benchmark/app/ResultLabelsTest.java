package com.android.benchmark.app;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.Test;

public class ResultLabelsTest {
    @Test
    public void roundsFourDigitMeasurementsToWholeValues() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            assertEquals("416.84", ResultLabels.score(416.84));
            assertEquals("4,167", ResultLabels.score(4166.84));
            assertEquals("1,000", ResultLabels.score(999.999));
            assertEquals("+1,215 ms", ResultLabels.signedMs(1214.75));
            assertEquals("-1,215 ms", ResultLabels.signedMs(-1214.75));
            assertEquals("18.26 ms", ResultLabels.ms(18.256));
        } finally {
            Locale.setDefault(original);
        }
    }
}

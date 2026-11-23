package com.android.benchmark.results;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.android.benchmark.results.FrameStats.Frame;
import com.android.benchmark.results.RunStore.Details;
import com.android.benchmark.results.RunStore.Measurement;
import com.android.benchmark.results.RunStore.Run;
import com.android.benchmark.results.RunStore.Status;
import com.android.benchmark.results.RunStore.StepStatus;
import com.android.benchmark.ui.BenchmarkTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

import java.nio.ByteBuffer;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class RunStoreTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private RunStore store;

    @Before
    public void create() {
        context.deleteDatabase("BenchmarkResults");
        store = new RunStore(context);
    }

    @After
    public void close() {
        store.close();
        context.deleteDatabase("BenchmarkResults");
    }

    @Test
    public void completeRunSurvivesReopenWithFrameDataAndCanBeDeleted() {
        long id = store.start(List.of(BenchmarkTest.LIST, BenchmarkTest.BITMAP_UPLOAD), 2);
        Measurement smooth = measurement(4);
        Measurement janky = measurement(14);
        assertTrue(store.saveStep(id, 0, smooth));
        assertTrue(store.saveStep(id, 1, janky));
        assertTrue(store.saveStep(id, 2, smooth));
        assertTrue(store.saveStep(id, 3, smooth));
        assertFalse(store.saveStep(id, 3, janky));

        Run result = store.finish(id, Status.COMPLETE, null);
        assertEquals(Status.COMPLETE, result.status());
        assertEquals(Math.pow(FrameStats.measure(janky.frames()).score, 0.25), result.score(), 1e-6);

        reopen();
        assertEquals(List.of(result), store.history());
        Details saved = store.details(id);
        assertEquals(result, saved.run());
        assertEquals(System.getProperty("os.version"), saved.run().kernelRelease());
        assertEquals(BenchmarkTest.UI_UPLOAD, saved.steps().get(3).test());
        assertEquals(2, saved.steps().get(3).repetition());
        assertEquals(janky, saved.steps().get(1).measurement());
        assertTrue(store.delete(id));
        assertNull(store.details(id));
        assertTrue(store.history().isEmpty());
        try (Cursor steps = store.getReadableDatabase().rawQuery(
                "SELECT count(*) FROM run_steps", null)) {
            assertTrue(steps.moveToFirst());
            assertEquals(0, steps.getInt(0));
        }
    }

    @Test
    public void incompleteAndFailedRunsHaveNoScoreAndHistoryIsNewestFirst() {
        long failed = store.start(List.of(BenchmarkTest.LIST), 1);
        Measurement partial = new Measurement(measurement(4).frames(), 1, 0, 0,
                120, 120, "A frame report was dropped");
        assertTrue(store.saveStep(failed, 0, partial));
        Run result = store.finish(failed, Status.COMPLETE, null);
        assertEquals(Status.FAILED, result.status());
        assertNull(result.score());
        assertEquals(StepStatus.FAILED, store.details(failed).steps().get(0).status());
        assertEquals(partial, store.details(failed).steps().get(0).measurement());

        long pending = store.start(List.of(BenchmarkTest.OVERDRAW), 1);
        Run unfinished = store.finish(pending, Status.COMPLETE, null);
        assertEquals(Status.FAILED, unfinished.status());
        assertNull(unfinished.score());
        assertEquals(StepStatus.PENDING, store.details(pending).steps().get(0).status());
        assertEquals(List.of(unfinished, result), store.history());
    }

    @Test
    public void interruptedRunIsRecoveredAndStaleWritesAreRejected() {
        long id = store.start(List.of(BenchmarkTest.LIST, BenchmarkTest.EDIT_TEXT), 1);
        assertTrue(store.saveStep(id, 0, measurement(4)));
        assertThrows(IllegalStateException.class, () -> store.start(List.of(BenchmarkTest.LIST), 1));
        assertFalse(store.delete(id));
        assertTrue(store.history().isEmpty());

        reopen();
        Details recovered = store.details(id);
        assertEquals(Status.CANCELLED, recovered.run().status());
        assertNull(recovered.run().score());
        assertEquals(StepStatus.COMPLETE, recovered.steps().get(0).status());
        assertEquals(StepStatus.PENDING, recovered.steps().get(1).status());
        assertFalse(store.saveStep(id, 1, measurement(4)));
        assertEquals(Status.CANCELLED, store.finish(id, Status.COMPLETE, null).status());
        assertNotNull(store.details(store.start(List.of(BenchmarkTest.LIST), 1)));
    }

    @Test
    public void upgradePreservesLegacyTablesAndCreatesModernHistory() {
        store.close();
        try (SQLiteDatabase legacy = context.openOrCreateDatabase("BenchmarkResults", 0, null)) {
            legacy.execSQL("CREATE TABLE ui_results (_id INTEGER PRIMARY KEY, total_duration REAL)");
            legacy.execSQL("INSERT INTO ui_results VALUES (1, 4.2)");
            legacy.execSQL("CREATE TABLE refresh_rates (refresh_rate INTEGER)");
            legacy.setVersion(2);
        }

        reopen();
        assertTrue(store.history().isEmpty());
        try (Cursor legacy = store.getReadableDatabase().rawQuery(
                "SELECT total_duration FROM ui_results", null)) {
            assertTrue(legacy.moveToFirst());
            assertEquals(4.2, legacy.getDouble(0), 0.0001);
        }
        long id = store.start(List.of(BenchmarkTest.LIST), 1);
        assertNotNull(store.details(id));
    }

    @Test
    public void unifiedRunsPreserveCompositorAndDetailDrawData() {
        assertThrows(IllegalArgumentException.class, () -> store.start(
                List.of(BenchmarkTest.LIST, BenchmarkTest.UI_LIST), 1));
        long id = store.start(List.of(BenchmarkTest.UI_TRANSITION, BenchmarkTest.COMPOSE_FEED), 1);
        Measurement capture = new Measurement(measurement(4).frames(), 0, 0, 0,
                120, 120, null, new JankBreakdown(1, 0, 1, 0),
                List.of(3_000_000L, 5_000_000L, 8_000_000L, 10_000_000L));
        assertTrue(store.saveStep(id, 0, capture));
        assertTrue(store.saveStep(id, 1, measurement(4)));
        assertEquals(Status.COMPLETE, store.finish(id, Status.COMPLETE, null).status());

        reopen();
        Details details = store.details(id);
        assertEquals(capture, details.steps().get(0).measurement());
        RunReport report = new RunReport(details);
        assertEquals(capture.compositor(), report.compositor);
        assertEquals(6.5, report.workload(BenchmarkTest.DETAIL_TRANSITION).medianFirstDrawMs(), 1e-6);
    }

    @Test
    public void upgradesExistingVersionThreeRunsWithoutLosingData() {
        store.close();
        long id = 19;
        try (SQLiteDatabase prior = context.openOrCreateDatabase("BenchmarkResults", 0, null)) {
            prior.execSQL("CREATE TABLE runs (id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL,"
                    + " repetitions INTEGER NOT NULL, device TEXT NOT NULL, release TEXT NOT NULL,"
                    + " api INTEGER NOT NULL, scoring_version INTEGER NOT NULL, status TEXT NOT NULL,"
                    + " score REAL, reason TEXT)");
            prior.execSQL("CREATE TABLE run_steps (run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,"
                    + " position INTEGER NOT NULL, test TEXT NOT NULL, repetition INTEGER NOT NULL,"
                    + " status TEXT NOT NULL, score REAL, frames BLOB, dropped INTEGER DEFAULT 0,"
                    + " invalid INTEGER DEFAULT 0, fallback INTEGER DEFAULT 0, low_hz REAL DEFAULT 0,"
                    + " high_hz REAL DEFAULT 0, error TEXT, PRIMARY KEY (run_id, position))");
            prior.execSQL("INSERT INTO runs VALUES (19, 42, 1, 'Device', '16', 36, 3, 'COMPLETE', 1, NULL)");
            ContentValues step = new ContentValues();
            step.put("run_id", id);
            step.put("position", 0);
            step.put("test", BenchmarkTest.LIST.name());
            step.put("repetition", 1);
            step.put("status", StepStatus.COMPLETE.name());
            step.put("score", 1);
            step.put("low_hz", 120);
            step.put("high_hz", 120);
            step.put("frames", ByteBuffer.allocate(2 * Long.BYTES)
                    .putLong(4_000_000L).putLong(8_333_333L).array());
            prior.insertOrThrow("run_steps", null, step);
            prior.setVersion(3);
        }

        reopen();
        Details migrated = store.details(id);
        assertEquals(Status.COMPLETE, migrated.run().status());
        assertEquals(1.0, migrated.run().score(), 0);
        assertEquals(42, migrated.run().started());
        assertNull(migrated.run().kernelRelease());
        assertEquals(BenchmarkTest.LIST, migrated.steps().get(0).test());
        assertEquals(measurement(4), migrated.steps().get(0).measurement());
        assertEquals(java.util.Set.of(BenchmarkTest.UI_LIST), new RunReport(migrated).selectedTests());
    }

    private void reopen() {
        store.close();
        store = new RunStore(context);
    }

    private static Measurement measurement(long millis) {
        return new Measurement(List.of(new Frame(millis * 1_000_000, 8_333_333)),
                0, 0, 0, 120, 120, null);
    }
}

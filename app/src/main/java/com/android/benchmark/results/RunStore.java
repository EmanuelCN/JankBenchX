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

package com.android.benchmark.results;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;

import com.android.benchmark.results.FrameStats.Frame;
import com.android.benchmark.ui.BenchmarkTest;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RunStore extends SQLiteOpenHelper {
    public static final int SCORING_VERSION = 3;
    public static final ExecutorService DISK = Executors.newSingleThreadExecutor();
    private static final int SCHEMA = 5;
    private static RunStore instance;

    public enum Status { RUNNING, COMPLETE, FAILED, CANCELLED }
    public enum StepStatus { PENDING, COMPLETE, FAILED }

    public record Run(long id, long started, int repetitions, String device, String release,
                      int api, String kernelRelease, int scoringVersion, Status status, Double score,
                      String reason) {}

    public record Measurement(List<Frame> frames, int dropped, int invalid, int fallback,
                              float lowHz, float highHz, String error, JankBreakdown compositor,
                              List<Long> firstDrawsNs) {
        public Measurement(List<Frame> frames, int dropped, int invalid, int fallback,
                           float lowHz, float highHz, String error) {
            this(frames, dropped, invalid, fallback, lowHz, highHz, error,
                    JankBreakdown.EMPTY, Collections.emptyList());
        }
    }

    public record Step(BenchmarkTest test, int repetition, StepStatus status,
                       Double score, Measurement measurement) {}

    public record Details(Run run, List<Step> steps) {}

    public static synchronized RunStore get(Context context) {
        if (instance == null) {
            instance = new RunStore(context.getApplicationContext());
        }
        return instance;
    }

    RunStore(Context context) {
        super(context, "BenchmarkResults", null, SCHEMA);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE runs (id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL,"
                + " repetitions INTEGER NOT NULL, device TEXT NOT NULL, release TEXT NOT NULL,"
                + " api INTEGER NOT NULL, scoring_version INTEGER NOT NULL, status TEXT NOT NULL,"
                + " score REAL, reason TEXT, suite TEXT NOT NULL DEFAULT 'CLASSIC', kernel_release TEXT)");
        db.execSQL("CREATE TABLE run_steps (run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,"
                + " position INTEGER NOT NULL, test TEXT NOT NULL, repetition INTEGER NOT NULL,"
                + " status TEXT NOT NULL, score REAL, frames BLOB, dropped INTEGER DEFAULT 0,"
                + " invalid INTEGER DEFAULT 0, fallback INTEGER DEFAULT 0, low_hz REAL DEFAULT 0,"
                + " high_hz REAL DEFAULT 0, error TEXT, PRIMARY KEY (run_id, position))");
        addDiagnostics(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            // Preserve the original frame tables. They lack a record of the selected
            // workloads, device metadata, and whether the run actually completed.
            onCreate(db);
            return;
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE runs ADD COLUMN suite TEXT NOT NULL DEFAULT 'CLASSIC'");
            addDiagnostics(db);
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE runs ADD COLUMN kernel_release TEXT");
        }
    }

    private static void addDiagnostics(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE run_steps ADD COLUMN jank_classified INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE run_steps ADD COLUMN jank_app INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE run_steps ADD COLUMN jank_composer INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE run_steps ADD COLUMN jank_other INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE run_steps ADD COLUMN first_draws BLOB");
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // This helper lives for the process. Any persisted active run is from an earlier process.
        ContentValues interrupted = new ContentValues();
        interrupted.put("status", Status.CANCELLED.name());
        interrupted.put("reason", "The app closed before this run finished.");
        db.update("runs", interrupted, "status=?", new String[]{Status.RUNNING.name()});
    }

    public long start(List<BenchmarkTest> tests, int repetitions) {
        if (tests.isEmpty() || repetitions < 1 || repetitions > 5) {
            throw new IllegalArgumentException("Select workloads and one to five repetitions");
        }
        EnumSet<BenchmarkTest> unique = EnumSet.noneOf(BenchmarkTest.class);
        for (BenchmarkTest test : tests) {
            if (!unique.add(test.canonical())) {
                throw new IllegalArgumentException("Select each workload once");
            }
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor active = db.rawQuery("SELECT id FROM runs WHERE status='RUNNING'", null)) {
                if (active.moveToFirst()) {
                    throw new IllegalStateException("A benchmark is already running");
                }
            }
            ContentValues run = new ContentValues();
            run.put("started", System.currentTimeMillis());
            run.put("repetitions", repetitions);
            run.put("device", Build.MODEL);
            run.put("release", Build.VERSION.RELEASE);
            run.put("api", Build.VERSION.SDK_INT);
            run.put("kernel_release", System.getProperty("os.version"));
            run.put("scoring_version", SCORING_VERSION);
            run.put("status", Status.RUNNING.name());
            // Earlier databases still have this column. Current runs use one catalog.
            run.put("suite", "UNIFIED");
            long id = db.insertOrThrow("runs", null, run);
            int position = 0;
            for (BenchmarkTest test : tests) {
                for (int repetition = 1; repetition <= repetitions; repetition++) {
                    ContentValues step = new ContentValues();
                    step.put("run_id", id);
                    step.put("position", position++);
                    step.put("test", test.canonical().name());
                    step.put("repetition", repetition);
                    step.put("status", StepStatus.PENDING.name());
                    db.insertOrThrow("run_steps", null, step);
                }
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public boolean saveStep(long id, int position, Measurement measurement) {
        boolean valid = measurement.error() == null && !measurement.frames().isEmpty()
                && measurement.dropped() == 0 && measurement.invalid() == 0;
        ContentValues values = new ContentValues();
        values.put("status", (valid ? StepStatus.COMPLETE : StepStatus.FAILED).name());
        if (valid) {
            values.put("score", FrameStats.measure(measurement.frames()).score);
        } else {
            values.put("error", measurement.error() == null
                    ? "No valid frame capture" : measurement.error());
        }
        values.put("frames", encode(measurement.frames()));
        values.put("dropped", measurement.dropped());
        values.put("invalid", measurement.invalid());
        values.put("fallback", measurement.fallback());
        values.put("low_hz", measurement.lowHz());
        values.put("high_hz", measurement.highHz());
        values.put("jank_classified", measurement.compositor().classified());
        values.put("jank_app", measurement.compositor().app());
        values.put("jank_composer", measurement.compositor().composer());
        values.put("jank_other", measurement.compositor().other());
        if (!measurement.firstDrawsNs().isEmpty()) {
            ByteBuffer draws = ByteBuffer.allocate(measurement.firstDrawsNs().size() * Long.BYTES);
            for (long sample : measurement.firstDrawsNs()) {
                draws.putLong(sample);
            }
            values.put("first_draws", draws.array());
        }
        int changed = getWritableDatabase().update("run_steps", values,
                "run_id=? AND position=? AND status='PENDING'"
                        + " AND EXISTS (SELECT 1 FROM runs WHERE id=? AND status='RUNNING')",
                new String[]{String.valueOf(id), String.valueOf(position), String.valueOf(id)});
        return changed == 1;
    }

    public Run finish(long id, Status requested, String reason) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            Status status = requested;
            if (requested == Status.COMPLETE) {
                Double overall = overallScore(db, id);
                if (overall == null) {
                    status = Status.FAILED;
                    reason = "One or more workloads did not complete with valid frame data.";
                } else {
                    values.put("score", overall);
                }
            }
            values.put("status", status.name());
            values.put("reason", reason);
            db.update("runs", values, "id=? AND status='RUNNING'", args(id));
            Run run = readRun(db, id);
            db.setTransactionSuccessful();
            return run;
        } finally {
            db.endTransaction();
        }
    }

    private static Double overallScore(SQLiteDatabase db, long id) {
        ArrayList<Double> workloadScores = new ArrayList<>();
        ArrayList<Double> repetitionScores = new ArrayList<>();
        String last = null;
        try (Cursor cursor = db.rawQuery(
                "SELECT test, score, status FROM run_steps WHERE run_id=? ORDER BY test, position",
                args(id))) {
            while (cursor.moveToNext()) {
                if (!StepStatus.COMPLETE.name().equals(cursor.getString(2)) || cursor.isNull(1)) {
                    return null;
                }
                String test = cursor.getString(0);
                if (last != null && !last.equals(test)) {
                    workloadScores.add(FrameStats.geometricMean(repetitionScores));
                    repetitionScores.clear();
                }
                last = test;
                repetitionScores.add(cursor.getDouble(1));
            }
        }
        if (repetitionScores.isEmpty()) {
            return null;
        }
        workloadScores.add(FrameStats.geometricMean(repetitionScores));
        return FrameStats.geometricMean(workloadScores);
    }

    public List<Run> history() {
        ArrayList<Run> runs = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("runs", null, "status != 'RUNNING'",
                null, null, null, "started DESC, id DESC")) {
            while (cursor.moveToNext()) {
                runs.add(readRun(cursor));
            }
        }
        return runs;
    }

    public Details details(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Run run = readRun(db, id);
        if (run == null) {
            return null;
        }
        List<Step> steps = new ArrayList<>();
        String columns = "test, repetition, status, score, frames, dropped, invalid, "
                + "fallback, low_hz, high_hz, error, jank_classified, jank_app, "
                + "jank_composer, jank_other, first_draws";
        try (Cursor cursor = db.rawQuery(
                "SELECT " + columns + " FROM run_steps WHERE run_id=? ORDER BY position", args(id))) {
            while (cursor.moveToNext()) {
                Measurement measurement = new Measurement(decode(cursor.getBlob(4)),
                        cursor.getInt(5), cursor.getInt(6), cursor.getInt(7),
                        cursor.getFloat(8), cursor.getFloat(9), cursor.getString(10),
                        new JankBreakdown(cursor.getInt(11), cursor.getInt(12), cursor.getInt(13),
                                cursor.getInt(14)), decodeFirstDraws(cursor.getBlob(15)));
                steps.add(new Step(BenchmarkTest.valueOf(cursor.getString(0)), cursor.getInt(1),
                        StepStatus.valueOf(cursor.getString(2)),
                        cursor.isNull(3) ? null : cursor.getDouble(3), measurement));
            }
        }
        return new Details(run, steps);
    }

    public boolean delete(long id) {
        return getWritableDatabase().delete("runs", "id=? AND status != 'RUNNING'", args(id)) == 1;
    }

    private static Run readRun(SQLiteDatabase db, long id) {
        try (Cursor cursor = db.query("runs", null, "id=?", args(id), null, null, null)) {
            return cursor.moveToFirst() ? readRun(cursor) : null;
        }
    }

    private static Run readRun(Cursor cursor) {
        int score = cursor.getColumnIndexOrThrow("score");
        return new Run(cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("started")),
                cursor.getInt(cursor.getColumnIndexOrThrow("repetitions")),
                cursor.getString(cursor.getColumnIndexOrThrow("device")),
                cursor.getString(cursor.getColumnIndexOrThrow("release")),
                cursor.getInt(cursor.getColumnIndexOrThrow("api")),
                cursor.getString(cursor.getColumnIndexOrThrow("kernel_release")),
                cursor.getInt(cursor.getColumnIndexOrThrow("scoring_version")),
                Status.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                cursor.isNull(score) ? null : cursor.getDouble(score),
                cursor.getString(cursor.getColumnIndexOrThrow("reason")));
    }

    private static List<Long> decodeFirstDraws(byte[] bytes) {
        if (bytes == null) {
            return Collections.emptyList();
        }
        ByteBuffer data = ByteBuffer.wrap(bytes);
        List<Long> draws = new ArrayList<>(bytes.length / Long.BYTES);
        while (data.hasRemaining()) {
            draws.add(data.getLong());
        }
        return draws;
    }

    private static String[] args(long id) {
        return new String[]{String.valueOf(id)};
    }

    private static byte[] encode(List<Frame> frames) {
        ByteBuffer data = ByteBuffer.allocate(frames.size() * 2 * Long.BYTES);
        for (Frame frame : frames) {
            data.putLong(frame.durationNs());
            data.putLong(frame.budgetNs());
        }
        return data.array();
    }

    private static List<Frame> decode(byte[] bytes) {
        if (bytes == null) {
            return Collections.emptyList();
        }
        ByteBuffer data = ByteBuffer.wrap(bytes);
        List<Frame> frames = new ArrayList<>(bytes.length / (2 * Long.BYTES));
        while (data.hasRemaining()) {
            frames.add(new Frame(data.getLong(), data.getLong()));
        }
        return frames;
    }
}

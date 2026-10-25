package com.chronos.data;

import android.content.Context;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android-side wiring. Kept out of the engine and data packages so those stay
 * compilable on a plain JVM.
 */
public final class DatabaseWiring {
    private static volatile ChronosDb db;
    private static volatile ChronosRepository repo;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private DatabaseWiring() {}

    /**
     * Activities gained a changeover time: minutes that must be free before the
     * block, for the walk to the gym or the move to the kitchen. Existing rows
     * get null, which reads as "no changeover" — the behaviour they had.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE activities ADD COLUMN changeoverMin INTEGER");
        }
    };

    /**
     * Session logs gained the units a goal session finished, which the nightly
     * close-out adds to the goal's progress. Older rows get null: no progress.
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE logs ADD COLUMN units REAL");
        }
    };

    /**
     * Activities gained a "may be cut for a deadline" setting and the minutes
     * kept when cut; snapshots record which cuts a plan made. Null everywhere
     * reads as "never cut", the behaviour older rows had.
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE activities ADD COLUMN sacrifice INTEGER");
            db.execSQL("ALTER TABLE activities ADD COLUMN keepMin INTEGER");
            db.execSQL("ALTER TABLE snapshots ADD COLUMN cuts TEXT");
        }
    };

    /**
     * Past days became editable. Logs record how many of their units were
     * already added to progress, so a late edit settles by difference; the
     * state records up to when logs of closed days have been learned from;
     * activities can be the nap. Units logged on days that had already closed
     * were counted by then, and are marked so.
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE logs ADD COLUMN countedUnits REAL");
            db.execSQL("ALTER TABLE app_state ADD COLUMN appliedTs INTEGER");
            db.execSQL("ALTER TABLE activities ADD COLUMN nap INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE logs SET countedUnits = units WHERE units IS NOT NULL "
                     + "AND date < (SELECT todayAnchor FROM app_state WHERE id = 1)");
            db.execSQL("UPDATE app_state SET appliedTs = CAST(strftime('%s','now') AS INTEGER) * 1000");
        }
    };

    /** Rest days, and activities kept for them. Nothing is a rest day, nothing is rest-only. */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE activities ADD COLUMN restOnly INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE app_state ADD COLUMN restDays TEXT");
        }
    };

    /**
     * Navigator's tables: the goal graph, its parameters, day types, forecasts,
     * weekly allocations, triggers and LLM proposals. Created empty; nothing
     * Chronos already stored is touched.
     */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_graph` (`id` INTEGER NOT NULL, `schemaVersion` TEXT, `title` TEXT, `fileName` TEXT, `importedAt` INTEGER NOT NULL, `json` TEXT, PRIMARY KEY(`id`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_nodes` (`id` TEXT NOT NULL, `type` TEXT, `owner` TEXT, `status` TEXT, `phase` TEXT, `title` TEXT, `json` TEXT, `position` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_edges` (`fromId` TEXT NOT NULL, `toId` TEXT NOT NULL, `kind` TEXT NOT NULL, `grp` INTEGER NOT NULL, `coef` REAL, PRIMARY KEY(`fromId`, `toId`, `kind`, `grp`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_regimes` (`id` TEXT NOT NULL, `fromDate` TEXT, `toDate` TEXT, `json` TEXT, `position` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_params` (`key` TEXT NOT NULL, `dist` TEXT, `vals` TEXT, `source` TEXT NOT NULL, `note` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_links` (`linkKey` TEXT NOT NULL, `activityName` TEXT, PRIMARY KEY(`linkKey`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_day_types` (`date` TEXT NOT NULL, `type` TEXT, `anomaly` INTEGER NOT NULL, `suggested` TEXT, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`date`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_forecasts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runAt` INTEGER NOT NULL, `seed` INTEGER NOT NULL, `runs` INTEGER NOT NULL, `asOf` TEXT, `millis` INTEGER NOT NULL, `json` TEXT)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_forecast_nodes` (`forecastId` INTEGER NOT NULL, `nodeId` TEXT NOT NULL, `pByDeadline` REAL, `p10` TEXT, `p50` TEXT, `p90` TEXT, `critical` REAL, PRIMARY KEY(`forecastId`, `nodeId`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_allocations` (`week` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `plannedH` REAL NOT NULL, `reason` TEXT, `status` TEXT, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`week`, `nodeId`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_triggers` (`id` TEXT NOT NULL, `kind` TEXT, `node` TEXT, `value` REAL, `firedAt` INTEGER NOT NULL, `cooldownUntil` INTEGER NOT NULL, `armed` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_proposals` (`id` TEXT NOT NULL, `llmCallId` TEXT, `ops` TEXT, `status` TEXT, `validation` TEXT, `summary` TEXT, `createdAt` INTEGER NOT NULL, `decidedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `nav_llm_calls` (`id` TEXT NOT NULL, `task` TEXT, `trigger` TEXT, `requestHash` TEXT, `model` TEXT, `tokensIn` INTEGER NOT NULL, `tokensOut` INTEGER NOT NULL, `costUsd` REAL NOT NULL, `sources` TEXT, `status` TEXT, `error` TEXT, `createdAt` INTEGER NOT NULL, `doneAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        }
    };

    /** Activities may wait for a start date. Null on every existing row: planned from now, as before. */
    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE activities ADD COLUMN startDate TEXT");
        }
    };

    /** Nights of sleep are logged: when you got up, how long falling asleep took, how often you woke. */
    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE logs ADD COLUMN endAt TEXT");
            db.execSQL("ALTER TABLE logs ADD COLUMN latencyMin INTEGER");
            db.execSQL("ALTER TABLE logs ADD COLUMN wakeups INTEGER");
        }
    };

    /** Two kinds of fatigue: physical beside mental. New columns only; the values are filled in by the app. */
    /** v14: hours as well as dates — an activity's start, a goal's deadline, the plan's start — and an activity's last day. */
    /** v15: leisure and its stimulation (your request of 25 Oct 2026). */
    static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE activities ADD COLUMN leisure INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE activities ADD COLUMN dopamine REAL");
            db.execSQL("ALTER TABLE activities ADD COLUMN dopamineSeen INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE params ADD COLUMN dopCap REAL");
            db.execSQL("ALTER TABLE params ADD COLUMN dopHalfLifeH REAL");
            db.execSQL("ALTER TABLE params ADD COLUMN aD REAL");
            db.execSQL("ALTER TABLE logs ADD COLUMN stopHard INTEGER");
            db.execSQL("ALTER TABLE logs ADD COLUMN nextStart INTEGER");
            db.execSQL("ALTER TABLE logs ADD COLUMN craving INTEGER");
        }
    };

    static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE activities ADD COLUMN startTime TEXT");
            db.execSQL("ALTER TABLE activities ADD COLUMN endDate TEXT");
            db.execSQL("ALTER TABLE goals ADD COLUMN deadlineTime TEXT");
            db.execSQL("ALTER TABLE app_state ADD COLUMN planFrom TEXT");
        }
    };

    /** v13: goals as umbrellas — each goal activity's own target names the goal it belongs to. */
    static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE goals ADD COLUMN groupName TEXT");
            db.execSQL("ALTER TABLE goals ADD COLUMN note TEXT");
        }
    };

    /** v12: an activity paused between two dates (an approved Navigator package). */
    static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE activities ADD COLUMN pauseFrom TEXT");
            db.execSQL("ALTER TABLE activities ADD COLUMN pauseUntil TEXT");
        }
    };

    /** v11: an activity the Navigator archived (with your approval) is kept, with its log, but no longer planned. */
    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE activities ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            for (String c : new String[]{"aP", "pTheta", "fSafeP", "idleRecoveryP", "kMP"})
                db.execSQL("ALTER TABLE params ADD COLUMN " + c + " REAL");
            db.execSQL("ALTER TABLE activities ADD COLUMN physLoad REAL");
            db.execSQL("ALTER TABLE activities ADD COLUMN physRecovery REAL");
            db.execSQL("ALTER TABLE activities ADD COLUMN splitConfirmed INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE commitments ADD COLUMN physLoad REAL");
            db.execSQL("ALTER TABLE commitments ADD COLUMN physRecovery REAL");
            db.execSQL("ALTER TABLE logs ADD COLUMN mentalAfter INTEGER");
            db.execSQL("ALTER TABLE logs ADD COLUMN physicalAfter INTEGER");
        }
    };

    public static ChronosRepository repository(Context context) {
        if (repo == null) {
            synchronized (DatabaseWiring.class) {
                if (repo == null) {
                    db = Room.databaseBuilder(context.getApplicationContext(),
                            ChronosDb.class, ChronosDb.NAME)
                        // No fallbackToDestructiveMigration. Losing a term of
                        // logged sessions to a schema bump would destroy the
                        // only thing the model learns from.
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                        .build();
                    repo = new ChronosRepository(db);
                }
            }
        }
        return repo;
    }

    private static volatile com.chronos.navigator.store.NavStore nav;

    /** Navigator's tables, in the same database as Chronos's. */
    public static com.chronos.navigator.store.NavStore navigator(Context context) {
        repository(context);
        if (nav == null) {
            synchronized (DatabaseWiring.class) {
                if (nav == null) nav = new com.chronos.navigator.store.NavStore(db.nav());
            }
        }
        return nav;
    }

    /** Every repository call blocks; this is where they belong. */
    public static ExecutorService io() { return IO; }

    /** Call once on first launch so the anchor exists. */
    public static void seedStateIfMissing(ChronosRepository r) {
        if (r.state().today() == null) r.closeOutDay(r.loadConfig());
        else r.today();
    }

    public static LocalDate today(ChronosRepository r) { return r.today(); }
}

package com.chronos.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * The stored shapes. Deliberately flat and deliberately separate from
 * com.chronos.model: the engine stays free of androidx so it can be tested on
 * a plain JVM, and the schema can change without dragging the algorithm with it.
 *
 * Nullable boxed types everywhere a user answer is required. A null column is
 * the persisted form of "not answered yet", which is exactly what Readiness
 * turns into a blocking issue. Nothing here has a silent default.
 */
public final class Entities {
    private Entities() {}

    @Entity(tableName = "params")
    public static class ParamsRow {
        /** Single row. */
        @PrimaryKey public int id = 1;

        public String sleepStart;
        public String sleepEnd;
        public Integer phi;

        public Double a0, aC, aH, aF;
        public double tauR = 18.0, tauD = 4.2, fMax = 1.0;

        public Double fSafe, idleRecovery, slackRatio, lcbBeta, switchPenalty, fatiguePenalty;
        public Integer horizonDays;
        /** Physical fatigue (v10); null on rows written before it existed. */
        public Double aP, pTheta, fSafeP, idleRecoveryP, kMP;
        /** v15: stimulation — ceiling, half-life, what it takes off capacity; null: the defaults. */
        public Double dopCap, dopHalfLifeH, aD;
    }

    @Entity(tableName = "goals")
    public static class GoalRow {
        @PrimaryKey @NonNull public String id;
        public String name;
        public String mode;              // CLOSED | OPEN
        public boolean hard = true;
        public Double totalUnits, doneUnits, hoursPerUnit, weeklyFloorH, maxPerDayH;
        public String unit;
        public String deadline;          // ISO-8601, null for open goals
        public double weight;
        public int position;
        public String groupName, note;
        /** v14: "HH:MM" on the deadline day. */
        public String deadlineTime;
        /** v16: priority level and place within it; whether the app chose them. */
        public Integer tier, rank;
        public boolean tierAuto;
    }

    @Entity(tableName = "activities")
    public static class ActivityRow {
        @PrimaryKey @NonNull public String id;
        public String name;
        public String kind;              // GOAL | MAINTENANCE
        public String goalId;
        public String days;              // "0,1,4"
        public Integer minBlock, maxBlock;
        public Double load, recovery;
        /** Physical load and recovery (v10); null on older rows until the split is suggested. */
        public Double physLoad, physRecovery;
        public boolean splitConfirmed;
        public double q = 1.0, qSigma = 0.35, k = 0.20, u0 = 10, theta = 90;
        public String windows;           // "08:00-12:30;14:00-19:30"
        public String pins;              // "0@07:05;2@07:05"
        public String blocksMode = "AUTO";
        public int maxBlocks = 1;
        public boolean needsHighEnergy, away;
        /** Minutes that must be free before the block; null on rows written before it existed. */
        public Integer changeoverMin;
        /** May be cut for a deadline, and how much of it is kept; null on older rows: never. */
        public Integer sacrifice, keepMin;
        /** The afternoon nap, set up with sleep rather than with the other activities. */
        public boolean nap;
        /** Planned only on rest days. */
        public boolean restOnly;
        public boolean archived;
        public String pauseFrom, pauseUntil;
        /** ISO date of the first day it may be planned; null: already may. */
        public String startDate;
        /** v14: the hour on the start day, and the last day of the activity's period. */
        public String startTime, endDate;
        /** v15: leisure, how stimulating an hour of it is, and how many sessions taught that. */
        public boolean leisure;
        public Double dopamine;
        public int dopamineSeen;
        public int position;
    }

    @Entity(tableName = "commitments")
    public static class CommitmentRow {
        @PrimaryKey @NonNull public String id;
        public String name;
        public String kind;              // FIXED | VARIABLE
        public Double load, recovery;
        public Double physLoad, physRecovery;
        public boolean away = true;
        public String sessions;          // "0|09:00|13:00;..."
        public Double medianMin, sigma, quantile;
        public String outbound;          // "PINNED||" or "FLEXIBLE|12:00|22:00"
        public String inbound;
        public int position;
    }

    /** One pairwise judgement. Key is "goalA|goalB". */
    @Entity(tableName = "comparisons")
    public static class ComparisonRow {
        @PrimaryKey @NonNull public String pairKey;
        public double ratio;
    }

    @Entity(tableName = "logs")
    public static class LogRow {
        @PrimaryKey @NonNull public String id;
        public String kind;              // SESSION | CHECKIN | TRAVEL
        public String date;              // ISO-8601
        public String at;                // "HH:mm"
        public long ts;
        public String actId, comId;
        public Integer planned, actual, focus, fatigueAfter, restored, energy;
        /** How mentally and physically tired (v10); older rows carry fatigueAfter alone. */
        public Integer mentalAfter, physicalAfter;
        public Double attain;
        /** Units of the goal finished in the session; null on rows written before it existed. */
        public Double units;
        /**
         * How many of those units have been added to the goal's progress. Units
         * are settled by difference against this, so a session edited after its
         * day closed adds or takes back only what changed, never counts twice.
         */
        public Double countedUnits;
        /** SLEEP only: when you got up, minutes to fall asleep, times woken. Null on every other row. */
        public String endAt;
        public Integer latencyMin, wakeups;
        /** v15, leisure sessions: how hard to stop, how hard to start the next thing, wanting more (1..5). */
        public Integer stopHard, nextStart, craving;
        public String tags;
        public String note;
    }

    /**
     * A solved horizon, kept rather than recomputed. Reopening the app should
     * not quietly reshuffle next week because one parameter drifted; a new
     * snapshot is written only when the day rolls over, progress changes, or
     * the user asks for a rebuild.
     */
    @Entity(tableName = "snapshots")
    public static class SnapshotRow {
        @PrimaryKey(autoGenerate = true) public long id;
        public long createdAt;
        public String startDate;         // ISO-8601
        public int horizonDays;
        public String configHash;        // detects a stale snapshot
        public String allocation;        // Codec.horizon
        public String violations;        // Codec.lines
        public String shortfalls;
        public String overdue;
        /** Upkeep and ongoing-goal time given up for deadlines, per day (Codec.horizon); null: none. */
        public String cuts;
    }

    /** One arranged day, cached so the timetable redraws without re-solving. */
    @Entity(tableName = "day_plans")
    public static class DayPlanRow {
        @PrimaryKey @NonNull public String date;  // ISO-8601
        public long snapshotId;
        public long seed;
        public String blocks;            // Codec.blocks
        public String gaps;
        public String warnings;
        public double effectiveMinutes, elapsedMinutes, excess;
        public int goalBlocks;
        @ColumnInfo(name = "synced_to_calendar") public boolean syncedToCalendar;
    }

    /** Everything that is app state rather than user data. Single row. */
    @Entity(tableName = "app_state")
    public static class AppStateRow {
        @PrimaryKey public int id = 1;
        /** The date the planner treats as today; advanced by "close out this day". */
        public String todayAnchor;
        public String lastCalibration;   // ISO-8601 date
        public String lastAutoCalibration;
        public Long calendarId;          // the dedicated Chronos calendar
        public String syncedThrough;     // last date pushed to the calendar
        /** Log entries saved after this moment, for days already closed, are not yet learned from. */
        public Long appliedTs;
        /** Days given over to rest, ISO dates separated by commas. */
        public String restDays;
        /** v14: the moment the plan was built from ("2026-10-22T15:30"), when it was given an hour. */
        public String planFrom;
        public int schemaNotes;          // reserved for migrations
    }
}

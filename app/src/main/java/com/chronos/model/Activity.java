package com.chronos.model;

import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.BlocksMode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class Activity {
    /** A clock time this activity is nailed to on one weekday. */
    public static class Pin {
        public int day;
        public String start;
        public Pin() {}
        public Pin(int day, String start) { this.day = day; this.start = start; }
        public Pin copy() { return new Pin(day, start); }
    }

    /** A span of the day this activity may occupy. */
    public static class Window {
        public String from, to;
        public Window() {}
        public Window(String from, String to) { this.from = from; this.to = to; }
        public Window copy() { return new Window(from, to); }
    }

    public String id;
    public String name;
    public ActivityKind kind;
    public String goalId;                             // required when kind == GOAL

    public TreeSet<Integer> days = new TreeSet<>();   // 0 = Saturday
    public Integer minBlock;                          // minutes
    public Integer maxBlock;
    public Double load;                               // mental fatigue per hour, 0..1
    public Double recovery;                           // mental fatigue returned per hour, 0..1
    public Double physLoad;                           // physical fatigue per hour, 0..1
    public Double physRecovery;                       // physical fatigue returned per hour, 0..1
    /**
     * Whether you confirmed the physical/mental split. Activities made before
     * fatigue had two kinds got a suggested split; until confirmed it is used
     * as it is and Setup asks about it.
     */
    public boolean splitConfirmed = true;

    public double q = 1.0;                            // learned quality multiplier
    public double qSigma = 0.35;                      // its uncertainty
    public double k = 0.20;                           // warm-up sharpness
    public double u0 = 10;                            // warm-up midpoint, minutes
    public double theta = 90;                         // attention decay, minutes

    public List<Window> windows = new ArrayList<>();
    public List<Pin> pins = new ArrayList<>();

    public BlocksMode blocksMode = BlocksMode.AUTO;
    public int maxBlocks = 1;                         // used when blocksMode == FIXED
    public boolean needsHighEnergy;
    public boolean away;                              // happens out of the house

    /**
     * Minutes that must be free immediately before this activity, for changing
     * into it. Reading and language practice happen at the same desk and need
     * none; a workout needs changing and walking, a meal needs the kitchen.
     * Null or zero means the block may start the moment the previous one ends.
     */
    public Integer changeoverMin;

    /**
     * Whether this activity may give up its time when a dated goal would
     * otherwise miss its deadline: null or {@link #SACRIFICE_NEVER} never,
     * {@link #SACRIFICE_LAST} only once nothing marked "first" is left to cut,
     * {@link #SACRIFICE_FIRST} before anything else. Only upkeep and the work
     * of ongoing goals can be cut; a dated goal is what the cutting is for.
     */
    public Integer sacrifice;
    public static final int SACRIFICE_NEVER = 0, SACRIFICE_LAST = 1, SACRIFICE_FIRST = 2;

    /**
     * Upkeep only: minutes kept on a day it is cut. Zero or null drops it for
     * that day; a workout that may shrink to a 30-minute run says 30.
     */
    public Integer keepMin;

    /**
     * The afternoon nap. Planned like any upkeep — its window or pin, its days,
     * its length and how much it restores — but set up in the body-clock step
     * and shown as sleep, because that is what it is to the person living it.
     */
    public boolean nap;

    /**
     * Upkeep that happens only on rest days: a walk, a film, seeing family.
     * On a normal day it is not planned at all; on a rest day it is, alongside
     * the ordinary upkeep, while goal work stops.
     */
    public boolean restOnly;

    /**
     * Leisure (your request of 25 Oct 2026): upkeep in kind — no progress, it restores — but
     * never planned on its own. Rests are chosen from among these: what used to stay an open
     * buffer, and a short break inside a long run of goal work.
     */
    public boolean leisure;

    /**
     * Leisure only: how stimulating an hour of it is, on the scale where your daily ceiling is
     * about 1. Learned from what the log says about its after-effects (how hard it was to stop,
     * to get into the next thing, whether you wanted more), never asked for directly.
     */
    public Double dopamine;
    /** How many logged sessions the stimulation has been learned from. */
    public int dopamineSeen;

    /**
     * The first day this activity may be planned; null means it already may.
     * Before it the activity does not exist for the planner: no hours go to it,
     * no block is placed, and its goal is paced over the days from here on.
     * Must fall before its goal's deadline (Readiness says so if it does not).
     */
    public java.time.LocalDate startDate;

    /** "HH:MM" on {@link #startDate}: nothing of it is planned earlier that day. Null: from the day's start. */
    public String startTime;

    /**
     * The last day of its period (upkeep for a term, a training block): planned up to and
     * including this day, never after, and kept with its log. Null: no end.
     */
    public java.time.LocalDate endDate;

    /** Whether its period is over by a date. */
    public boolean endedBy(java.time.LocalDate date) {
        return endDate != null && date != null && date.isAfter(endDate);
    }

    /**
     * Archived: no longer planned on any day, but kept, with its log, so it can
     * come back. The Navigator archives instead of deleting (your rule).
     */
    public boolean archived;

    /**
     * A pause between two dates, both included (from an approved Navigator package): the activity is not
     * planned on those days and comes back by itself after them. Null when it is not paused.
     */
    public java.time.LocalDate pauseFrom, pauseUntil;

    public boolean pausedOn(java.time.LocalDate date) {
        return date != null && pauseFrom != null && pauseUntil != null
            && !date.isBefore(pauseFrom) && !date.isAfter(pauseUntil);
    }

    /** Whether the activity may be planned on a date. */
    public boolean startedBy(java.time.LocalDate date) {
        if (archived || pausedOn(date) || endedBy(date)) return false;
        return startDate == null || date == null || !date.isBefore(startDate);
    }

    public int sacrificeRank() { return sacrifice == null ? SACRIFICE_NEVER : sacrifice; }

    public Activity copy() {
        Activity a = new Activity();
        a.id = id; a.name = name; a.kind = kind; a.goalId = goalId;
        a.days = new TreeSet<>(days);
        a.minBlock = minBlock; a.maxBlock = maxBlock; a.load = load; a.recovery = recovery;
        a.physLoad = physLoad; a.physRecovery = physRecovery; a.splitConfirmed = splitConfirmed;
        a.q = q; a.qSigma = qSigma; a.k = k; a.u0 = u0; a.theta = theta;
        for (Window w : windows) a.windows.add(w.copy());
        for (Pin pin : pins) a.pins.add(pin.copy());
        a.blocksMode = blocksMode; a.maxBlocks = maxBlocks;
        a.needsHighEnergy = needsHighEnergy; a.away = away;
        a.changeoverMin = changeoverMin;
        a.sacrifice = sacrifice; a.keepMin = keepMin; a.nap = nap; a.restOnly = restOnly;
        a.startDate = startDate; a.archived = archived; a.pauseFrom = pauseFrom; a.pauseUntil = pauseUntil;
        a.startTime = startTime; a.endDate = endDate;
        a.leisure = leisure; a.dopamine = dopamine; a.dopamineSeen = dopamineSeen;
        return a;
    }
}

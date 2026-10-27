package com.chronos.model;

import java.util.ArrayList;
import java.util.List;

/** A candidate or placed span on one day's timeline. */
public class Block {
    public String id;
    public String name;
    public String goalId;
    public Activity act;

    public int dur;                    // slots
    public int start = -1;             // slots; -1 means unplaced
    public Integer pinned;             // forced start slot, or null

    public double load, recovery;            // mental
    public double physLoad, physRecovery;    // physical
    public double qPlan;
    public double priority;
    public double[] tab = new double[]{0};
    public List<Activity.Window> windows = new ArrayList<>();

    /** Slots that must be free immediately before this block, for changing into it. */
    public int changeover;

    public boolean fixed, away, gap, forced;
    /** A rest chosen from your leisure; [dopamine] is how stimulating an hour of it is. */
    public boolean leisure;
    public double dopamine;
    /** Arranged before the hour the day was built again from: stays where it is (not stored). */
    public boolean kept;
    public String reason;              // why it could not be placed
    public String why;                 // what a gap is for

    public double meanEta, yieldMin;

    public Block copy() {
        Block b = new Block();
        b.id = id; b.name = name; b.goalId = goalId; b.act = act;
        b.dur = dur; b.start = start; b.pinned = pinned; b.changeover = changeover;
        b.load = load; b.recovery = recovery; b.physLoad = physLoad; b.physRecovery = physRecovery; b.qPlan = qPlan; b.priority = priority;
        b.tab = tab; b.windows = windows;
        b.fixed = fixed; b.away = away; b.gap = gap; b.forced = forced;
        b.leisure = leisure; b.dopamine = dopamine; b.kept = kept;
        b.reason = reason; b.why = why; b.meanEta = meanEta; b.yieldMin = yieldMin;
        return b;
    }
}

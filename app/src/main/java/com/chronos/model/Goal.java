package com.chronos.model;

import com.chronos.model.Enums.GoalMode;
import java.time.LocalDate;

public class Goal {
    public String id;
    public String name;
    public GoalMode mode;
    public boolean hard = true;

    public Double totalUnits;      // closed goals only
    public Double doneUnits = 0.0;
    public String unit;
    public Double hoursPerUnit;
    public LocalDate deadline;     // closed goals only
    /** "HH:MM" on the deadline day: nothing of it is planned after this. Null: the whole day counts. */
    public String deadlineTime;

    public Double weeklyFloorH;    // open goals only
    public Double maxPerDayH;
    public double weight = 0.0;    // derived from pairwise comparison, never typed in

    /**
     * The goal this belongs to, as you name it (your decision of 17 Oct 2026). What you call a goal is an
     * umbrella over activities; each goal activity carries its own target — this object — so one Goal
     * here is the target of exactly one activity, and the goals you see are the groups of these.
     */
    public String groupName;
    /** What the goal said before its activities each got their own target, for you to fill them in. */
    public String note;

    public Goal copy() {
        Goal g = new Goal();
        g.id = id; g.name = name; g.mode = mode; g.hard = hard;
        g.totalUnits = totalUnits; g.doneUnits = doneUnits; g.unit = unit;
        g.hoursPerUnit = hoursPerUnit; g.deadline = deadline;
        g.weeklyFloorH = weeklyFloorH; g.maxPerDayH = maxPerDayH; g.weight = weight;
        g.groupName = groupName; g.note = note; g.deadlineTime = deadlineTime;
        return g;
    }

    public double remainingUnits() {
        if (mode != GoalMode.CLOSED || totalUnits == null) return 0;
        return Math.max(0, totalUnits - (doneUnits == null ? 0 : doneUnits));
    }
}

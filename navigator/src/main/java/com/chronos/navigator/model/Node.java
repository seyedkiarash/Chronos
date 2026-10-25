package com.chronos.navigator.model;

import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Kinds.Owner;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One node of the goal graph, with the fields the layers reason about lifted
 * out of the JSON. Anything else the file says about a node (notes, rules,
 * checkpoints, old forecasts) stays in {@link #raw}, which is also what is
 * stored, so nothing written in the file is lost by passing through the app.
 *
 * Boxed types throughout: null means the file did not say, which the
 * requirements check reports rather than fills in.
 */
public class Node {
    public String id;
    public NodeType type;
    public Owner owner;
    public String title;
    public String status;
    public String phase;
    public String granularity;      // fine | medium | coarse
    public String role;             // "support" for system suggestions that give way first
    public String parent;

    public LocalDate expandAt;
    public LocalDate decideBy;      // DECISION only

    public LocalDate earliest;
    public LocalDate deadline;
    public boolean hardDeadline;
    public boolean hard;            // must be kept whatever else happens (coursework)
    public boolean soft;            // missing it does not fail what depends on it

    /** Total hours, lognormal through its median and 90th percentile. */
    public Double effortP50, effortP90;
    /** Unit tasks: hours per unit, the prior median, what the log observed, and the spread. */
    public Double perUnitPrior, perUnitObserved, perUnitSigma;
    /** Skills: hours per level point. */
    public Double perPointP50, perPointP90;

    public Double levelFrom, levelTo;
    public Double weeklyFloorH, maxHPerDay;

    public Double unitsTotal, unitsDone;
    public String unitName;

    /** GATE: Beta(a, b) on success. Also accepted on a MILESTONE decided by a condition. */
    public Double successA, successB;

    public Double standaloneValue;

    public boolean consentRequired;
    public String consentChannel;

    /** Chronos activity names whose sessions count as work on this node. */
    public List<String> activities = new ArrayList<>();
    public Boolean needsHighFocus;

    public List<String> pausedDuring = new ArrayList<>();
    /** Pauses outside any regime, from approved proposals (node.ID.pause): {from, to} pairs. */
    public List<LocalDate[]> pausedSpans = new ArrayList<>();

    /** At least this many hours a week between two dates, from an approved proposal (node.ID.min_weekly). */
    public Double minWeeklyH;
    public LocalDate minWeeklyFrom, minWeeklyUntil;

    /** How much its deadline matters, as you set it (node.ID.priority); null: see {@link Priority#of}. */
    public Integer priority;

    /** Broken into finer nodes (an approved EXPAND_NODE): its children carry the work and it carries none. */
    public boolean expanded;
    public List<String> options = new ArrayList<>();

    /** The node exactly as the file (or an approved proposal) wrote it. */
    public JsonObject raw;

    /**
     * A consent channel that starts with "none" marks something that cannot be
     * planned at all: nothing may be put under it and no hours go to it.
     */
    public boolean plannable() {
        return consentChannel == null || !consentChannel.trim().toLowerCase().startsWith("none");
    }

    /** Whether an approved pause covers a date. */
    public boolean pausedSpanOn(LocalDate d) {
        for (LocalDate[] s : pausedSpans) if (!d.isBefore(s[0]) && !d.isAfter(s[1])) return true;
        return false;
    }

    public boolean takesHours() {
        return type == NodeType.STREAM || type == NodeType.SKILL || type == NodeType.TASK;
    }

    public double remainingUnits() {
        if (unitsTotal == null) return 0;
        return Math.max(0, unitsTotal - (unitsDone == null ? 0 : unitsDone));
    }

    @Override public String toString() { return id; }
}

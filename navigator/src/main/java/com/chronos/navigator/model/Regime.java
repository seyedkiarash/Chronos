package com.chronos.navigator.model;

import com.chronos.navigator.model.Kinds.DayType;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A stretch of the calendar with its own mix of day types (EARLY, MIDTERM, …),
 * or an overlay on one (SPRINT_CONVEX) that changes how hours are spent
 * without changing what kind of days they are.
 */
public class Regime {
    public String id;
    public LocalDate from, to;
    /** Share of IDEAL, BUSY and STRESS days; null when the file gave none. */
    public EnumMap<DayType, Double> mix;
    /** Set on an overlay: the calendar regime it sits on. */
    public String overlayOn;
    /** How much one observed day counts while this regime runs; null when the file did not say. */
    public Double dataWeight;
    /** The dates are a guess, to be confirmed when {@link #askWhen} comes. */
    public boolean assumption;
    public LocalDate askWhen;
    /**
     * How many observed days the mix is worth (Dirichlet strength). Null: the
     * graph-wide daytype.prior_strength applies. Low for a stretch years
     * ahead, whose mix is a rough guess.
     */
    public Double strength;
    /** Hours per day the user planned per node during this regime (overlays). */
    public Map<String, Double> plannedHPerDay = new LinkedHashMap<>();
    public String note;
    public JsonObject raw;

    public boolean isOverlay() { return overlayOn != null; }

    public boolean covers(LocalDate d) {
        return from != null && to != null && !d.isBefore(from) && !d.isAfter(to);
    }
}

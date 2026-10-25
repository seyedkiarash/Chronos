package com.chronos.navigator.estimate;

import com.chronos.navigator.model.Kinds.DayType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the layers read from Chronos, translated out of Chronos's own types so
 * this module stays free of them. The bridge on the Android side fills it from
 * the log and the configuration; tests fill it from an exported log.
 *
 * Activity names are normalised (Requirements.norm) throughout.
 */
public final class Inputs {

    /**
     * One logged session.
     *
     * @param planned    it filled a block of the day's plan (an extra or "instead" session did not)
     * @param skipped    the planned block was not done
     * @param goalWork   the activity serves a goal (upkeep is not goal time)
     */
    public record Session(LocalDate date, String activity, Integer plannedMin, Integer actualMin,
                          boolean planned, boolean skipped, Double units, boolean goalWork) {}

    /** One hourly energy rating, 1..5. */
    public record Rating(LocalDate date, int hour, int energy) {}

    /** The day type ticked at the evening check-in. */
    public record DayRecord(LocalDate date, DayType type, boolean anomaly) {}

    /** A Chronos goal with units, reached through one of its activities. */
    public record UnitGoal(double hoursPerUnit, double doneUnits, Double totalUnits) {}

    public final List<Session> sessions = new ArrayList<>();
    public final List<Rating> ratings = new ArrayList<>();
    public final List<DayRecord> days = new ArrayList<>();

    /** Node id to the Chronos activities its work is logged under (after links). */
    public final Map<String, List<String>> nodeActivities = new HashMap<>();

    /** Chronos activity to its goal's units, for goals counted in units. */
    public final Map<String, UnitGoal> unitGoals = new HashMap<>();

    /** Chronos activity to its goal's relative weight (the AHP weights from Priorities). */
    public final Map<String, Double> goalWeights = new HashMap<>();

    /**
     * Hours Chronos itself has planned for a node on the coming days: the work
     * of goals with a deadline, which Chronos paces on its own. For those days
     * the forecast follows this plan instead of its own sharing-out.
     */
    public final Map<String, Map<LocalDate, Double>> chronosPlan = new HashMap<>();

    public List<String> activitiesOf(String nodeId) {
        return nodeActivities.getOrDefault(nodeId, List.of());
    }
}

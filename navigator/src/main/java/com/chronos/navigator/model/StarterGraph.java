package com.chronos.navigator.model;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A first goal graph made from the goals someone already set up in Chronos (31 Oct 2026), so a new
 * person does not need a graph file written for them: each goal becomes a final goal, each piece of
 * work under it a task (an amount by a date) or a stream (hours a week), linked to its activity.
 * Nothing of anyone else's graph is in it; it grows from there through the Navigator's own
 * breakdowns and proposals. The file it writes goes through the same import and checks as any.
 */
public final class StarterGraph {
    private StarterGraph() {}

    /**
     * One piece of goal work, as Chronos holds it.
     *
     * @param closed     an amount by a date (else hours a week)
     * @param activity   the Chronos activity that does this work
     */
    public record Work(String goal, String title, String activity, boolean closed, LocalDate deadline,
                       Double totalUnits, Double doneUnits, String unit, Double hoursPerUnit,
                       Double weeklyFloorH, Double maxPerDayH, boolean hard) {}

    /** The graph file, as JSON text; null when there is no goal work to start from. */
    public static String json(List<Work> work, LocalDate today, String timezone) {
        if (work.isEmpty()) return null;
        LocalDate horizon = today.plusYears(2);
        for (Work w : work) if (w.deadline() != null && w.deadline().isAfter(horizon)) horizon = w.deadline().plusMonths(1);

        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "0.2");
        JsonObject meta = new JsonObject();
        meta.addProperty("title", "My goals");
        meta.addProperty("created", today.toString());
        meta.addProperty("updated", today.toString());
        meta.addProperty("timezone", timezone);
        meta.addProperty("horizon_end", horizon.toString());
        meta.addProperty("source", "made in Chronos from the goals set up there");
        root.add("meta", meta);

        JsonObject policies = new JsonObject();
        JsonObject objective = new JsonObject();
        objective.addProperty("primary", "reach each goal by its date");
        objective.addProperty("risk_tolerance", 3);
        policies.add("objective", objective);
        JsonObject wave = new JsonObject();
        wave.addProperty("fine_until_weeks", 6);
        wave.addProperty("medium_until_months", 6);
        wave.addProperty("coarse_beyond", true);
        policies.add("rolling_wave", wave);
        JsonArray triggers = new JsonArray();
        for (String[] t : new String[][]{
                {"prob_drop", "P(milestone) < 0.60"}, {"prob_jump", "dP > +0.15 within 7 days"},
                {"manual_change", "user edits goals, constraints or deadlines"}, {"expand_due", "now >= node.expand_at"}}) {
            JsonObject o = new JsonObject();
            o.addProperty("id", t[0]);
            o.addProperty("when", t[1]);
            triggers.add(o);
        }
        policies.add("llm_triggers", triggers);
        root.add("policies", policies);

        // One ordinary kind of day to start with; the log teaches the rest.
        JsonObject regimes = new JsonObject();
        JsonObject capacity = new JsonObject();
        capacity.addProperty("IDEAL", 8);
        capacity.addProperty("BUSY", 5);
        capacity.addProperty("STRESS", 2);
        regimes.add("capacity_goal_hours_per_day", capacity);
        JsonArray calendar = new JsonArray();
        JsonObject normal = new JsonObject();
        normal.addProperty("id", "NORMAL");
        normal.addProperty("from", today.toString());
        normal.addProperty("to", horizon.toString());
        JsonObject mix = new JsonObject();
        mix.addProperty("IDEAL", 0.6);
        mix.addProperty("BUSY", 0.3);
        mix.addProperty("STRESS", 0.1);
        normal.add("mix", mix);
        normal.addProperty("assumption", true);
        calendar.add(normal);
        regimes.add("calendar", calendar);
        root.add("regimes", regimes);
        root.add("user_state", new JsonObject());

        Map<String, List<Work>> byGoal = new LinkedHashMap<>();
        for (Work w : work) byGoal.computeIfAbsent(w.goal() == null || w.goal().isBlank() ? "My goal" : w.goal().trim(),
            k -> new ArrayList<>()).add(w);

        JsonArray nodes = new JsonArray();
        int g = 0;
        for (Map.Entry<String, List<Work>> e : byGoal.entrySet()) {
            g++;
            String goalId = "G" + g;
            LocalDate goalBy = null;
            for (Work w : e.getValue()) if (w.closed() && w.deadline() != null && (goalBy == null || w.deadline().isAfter(goalBy))) goalBy = w.deadline();
            int k = 0;
            for (Work w : e.getValue()) {
                k++;
                JsonObject n = new JsonObject();
                n.addProperty("id", goalId + "_W" + k);
                n.addProperty("owner", "user");
                n.addProperty("title", w.title() == null || w.title().isBlank() ? w.activity() : w.title().trim());
                n.addProperty("phase", "P1");
                if (w.closed()) {
                    n.addProperty("type", "TASK");
                    n.addProperty("granularity", "fine");
                    double per = w.hoursPerUnit() == null || w.hoursPerUnit() <= 0 ? 1.0 : w.hoursPerUnit();
                    JsonObject effort = new JsonObject();
                    effort.addProperty("per_unit_h_median_prior", per);
                    effort.addProperty("sigma", 0.4);
                    n.add("effort", effort);
                    if (w.totalUnits() != null && w.totalUnits() > 0) {
                        JsonObject units = new JsonObject();
                        double total = w.totalUnits();
                        units.addProperty("total", total);
                        units.addProperty("done", Math.min(total, w.doneUnits() == null ? 0 : w.doneUnits()));
                        units.addProperty("unit", w.unit() == null || w.unit().isBlank() ? "unit" : w.unit().trim());
                        n.add("units", units);
                    }
                    if (w.deadline() != null) {
                        JsonObject window = new JsonObject();
                        window.addProperty("deadline", w.deadline().toString());
                        window.addProperty("hard", w.hard());
                        n.add("window", window);
                    }
                } else {
                    n.addProperty("type", "STREAM");
                    JsonObject weekly = new JsonObject();
                    double max = w.maxPerDayH() == null || w.maxPerDayH() <= 0 ? 2.0 : Math.min(24, w.maxPerDayH());
                    double floor = w.weeklyFloorH() == null ? 1.0 : Math.min(Math.max(0, w.weeklyFloorH()), max * 7);
                    weekly.addProperty("floor_h", floor);
                    weekly.addProperty("max_h_per_day", max);
                    n.add("weekly", weekly);
                    n.addProperty("hard", w.hard());
                }
                if (w.activity() != null && !w.activity().isBlank()) {
                    JsonObject ch = new JsonObject();
                    ch.addProperty("activity", w.activity());
                    n.add("chronos", ch);
                }
                JsonArray to = new JsonArray();
                JsonObject c = new JsonObject();
                c.addProperty("node", goalId);
                c.addProperty("coef", Math.round(1000.0 / e.getValue().size()) / 1000.0);
                to.add(c);
                n.add("contributes_to", to);
                nodes.add(n);
            }
            JsonObject goal = new JsonObject();
            goal.addProperty("id", goalId);
            goal.addProperty("type", "TERMINAL");
            goal.addProperty("owner", "user");
            goal.addProperty("title", e.getKey());
            goal.addProperty("phase", "P1");
            if (goalBy != null) {
                JsonObject window = new JsonObject();
                window.addProperty("deadline", goalBy.toString());
                goal.add("window", window);
            }
            nodes.add(goal);
        }
        root.add("nodes", nodes);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    /** A short name for the file it is stored as. */
    public static String fileName(LocalDate today) {
        return String.format(Locale.ROOT, "my-goals-%s.json", today);
    }
}

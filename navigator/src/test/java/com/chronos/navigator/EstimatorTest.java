package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.estimate.DayTypes;
import com.chronos.navigator.estimate.Estimator;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.DayType;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.Source;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.model.Requirements;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The estimate layer: its formulas by hand, and a replay of the real log of 30 Sep 2026. */
public class EstimatorTest {

    /** The real graph with the answers the app asks for before stage 2 runs. */
    static Graph ready() {
        Graph g = GraphImportTest.real();
        for (String t : new String[]{"IDEAL", "BUSY", "STRESS"}) {
            Param p = g.params.get("capacity." + t);
            p.values.put("tau0", 1.5);
            p.values.put("sigma", 2.0);
        }
        return g;
    }

    static Map<String, Param> byKey(Estimator.Result r) {
        Map<String, Param> out = new HashMap<>();
        for (Param p : r.posteriors()) out.put(p.key, p);
        return out;
    }

    @Test
    public void adherenceIsTheShareOfPlannedTimeWorkedPerDay() {
        Graph g = ready();
        Inputs in = new Inputs();
        LocalDate d = LocalDate.parse("2026-10-14");          // EARLY: no data weight of its own → 1
        // x: one block done, one skipped, the skipped hour made up by an unplanned one → the day is kept in full
        in.sessions.add(new Inputs.Session(d, "x", 60, 60, true, false, null, true));
        in.sessions.add(new Inputs.Session(d, "x", 60, null, true, true, null, true));
        in.sessions.add(new Inputs.Session(d, "x", null, 60, false, false, null, true));
        // y: an hour planned, half of it worked
        in.sessions.add(new Inputs.Session(d, "y", 60, 30, true, false, null, true));
        Map<String, Param> post = byKey(Estimator.run(g, in, d.plusDays(1), 0));
        double w = Math.pow(0.5, 1 / 7.0);                    // a day old, half-life 7 days; the prior fades the same
        Param p = post.get("post.adherence");
        assertEquals(16 * w + w + 0.5 * w, p.get("a"), 1e-9);
        assertEquals(4 * w + 0.5 * w, p.get("b"), 1e-9);
        double mean = p.get("mean");
        Param y = post.get("post.adherence.y");
        assertEquals(4 * mean + 0.5 * w, y.get("a"), 1e-9);
        assertEquals(4 * (1 - mean) + 0.5 * w, y.get("b"), 1e-9);
        assertTrue(post.get("post.adherence.x").get("mean") > y.get("mean"));
    }

    @Test
    public void oneDayOfSpeedIsTheKalmanUpdateCarriedToToday() {
        Graph g = ready();
        Inputs in = new Inputs();
        LocalDate d = LocalDate.parse("2026-10-14");
        in.sessions.add(new Inputs.Session(d, "reading", 60, 120, true, false, null, true));   // y = ln 2, an hour planned
        Param p = byKey(Estimator.run(g, in, d.plusDays(1), 0)).get("post.bias.reading");
        double mu0 = Math.log(1.3), tau0 = 0.35, sigma = 0.35, y = Math.log(2);
        double precision = 1 / (tau0 * tau0) + 1 / (sigma * sigma);
        double mu = (mu0 / (tau0 * tau0) + y / (sigma * sigma)) / precision;
        assertEquals(mu, p.get("mu"), 1e-12);                 // no slope learned from one day: the level stays
        // a day later the level is less certain: the slope's spread and a day of drift are added
        assertEquals(Math.sqrt(1 / precision + 0.02 * 0.02 + 0.05 * 0.05), p.get("tau"), 1e-12);
    }

    @Test
    public void aSpeedThatKeepsImprovingShowsAsATrend() {
        Graph g = ready();
        Inputs in = new Inputs();
        LocalDate d = LocalDate.parse("2026-10-14");
        double[] minutes = {180, 165, 150, 135, 120, 105, 90};   // per planned hour, falling day by day
        for (int i = 0; i < minutes.length; i++)
            in.sessions.add(new Inputs.Session(d.plusDays(i), "reading", 60, (int) minutes[i], true, false, null, true));
        Param p = byKey(Estimator.run(g, in, d.plusDays(minutes.length), 0)).get("post.bias.reading");
        double meanOfAll = 0;
        for (double m : minutes) meanOfAll += Math.log(m / 60) / minutes.length;
        assertTrue("falling", p.get("slope") < 0);
        assertTrue("today's level is below the plain average", p.get("mu") < meanOfAll);
    }

    @Test
    public void capacityLearnsFromTickedDaysOnly() {
        Graph g = ready();
        Inputs in = new Inputs();
        LocalDate d = LocalDate.parse("2026-10-14");
        in.sessions.add(new Inputs.Session(d, "reading", 60, 240, true, false, null, true));   // 4 h
        in.days.add(new Inputs.DayRecord(d, DayType.BUSY, false));
        in.days.add(new Inputs.DayRecord(d.plusDays(1), DayType.BUSY, true));                   // unusual: not learned
        Param p = byKey(Estimator.run(g, in, d.plusDays(3), 0)).get("post.capacity.BUSY");
        double precision = 1 / (1.5 * 1.5) + 1 / (2.0 * 2.0);
        assertEquals((5 / (1.5 * 1.5) + 4 / (2.0 * 2.0)) / precision, p.get("mean"), 1e-12);
        assertEquals(1.0, p.get("n"), 0);
    }

    @Test
    public void energyIsShrunkTowardTheMean() {
        Graph g = ready();
        Inputs in = new Inputs();
        LocalDate d = LocalDate.parse("2026-10-14");
        in.ratings.add(new Inputs.Rating(d, 9, 5));
        in.ratings.add(new Inputs.Rating(d, 15, 1));
        Param p = byKey(Estimator.run(g, in, d.plusDays(1), 0)).get("post.energy");
        assertEquals((5 + 3 * 3.0) / (1 + 3), p.get("h9"), 1e-12);    // overall mean 3, k = 3
    }

    @Test
    public void theSuggestedDayTypeFollowsItsRule() {
        assertEquals(DayType.STRESS, DayTypes.suggest(4, List.of(), 0));
        assertEquals(DayType.STRESS, DayTypes.suggest(2, List.of("Overwhelmed"), 0));
        assertEquals(DayType.BUSY, DayTypes.suggest(2, List.of(), 5));
        assertEquals(DayType.IDEAL, DayTypes.suggest(2, List.of(), 1));
    }

    // -------------------------------------------------------- replay (spec)

    /** A real log export to replay, named by CHRONOS_LOG_EXPORT; the test is skipped without one. */
    static File export() {
        String p = System.getenv("CHRONOS_LOG_EXPORT");
        if (p == null) return null;
        File f = new File(p);
        return f.exists() ? f : null;
    }

    /** The exported log as the bridge would hand it over. Only on this PC: the log stays out of the repository. */
    static Inputs replay(Graph g, File f) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> kind = new HashMap<>(), goalOf = new HashMap<>();
        for (JsonElement e : root.getAsJsonArray("activities")) {
            JsonObject a = e.getAsJsonObject(), s = a.getAsJsonObject("settings");
            String name = Requirements.norm(a.get("name").getAsString());
            kind.put(name, s.get("kind").getAsString());
            if (s.has("goal") && !s.get("goal").isJsonNull()) goalOf.put(name, s.get("goal").getAsString());
        }
        Inputs in = new Inputs();
        for (JsonElement e : root.getAsJsonArray("goals")) {
            JsonObject goal = e.getAsJsonObject(), s = goal.getAsJsonObject("settings");
            if (!"CLOSED".equals(s.get("mode").getAsString())) continue;
            for (Map.Entry<String, String> a : goalOf.entrySet())
                if (a.getValue().equals(goal.get("name").getAsString()))
                    in.unitGoals.put(a.getKey(), new Inputs.UnitGoal(s.get("hoursPerUnit").getAsDouble(),
                        s.get("doneUnits").getAsDouble(), s.get("totalUnits").getAsDouble()));
        }
        for (JsonElement e : root.getAsJsonArray("logs")) {
            JsonObject l = e.getAsJsonObject();
            String k = l.get("kind").getAsString(), id = l.get("id").getAsString();
            LocalDate date = LocalDate.parse(l.get("date").getAsString());
            if (k.equals("CHECKIN") && id.startsWith("energy|") && !l.get("energy").isJsonNull())
                in.ratings.add(new Inputs.Rating(date, Integer.parseInt(id.substring(id.lastIndexOf('|') + 1)),
                    l.get("energy").getAsInt()));
            if (!k.equals("SESSION")) continue;
            String act = Requirements.norm(l.get("activity").getAsString());
            boolean planned = id.startsWith("session|") || (!id.contains("|") && !l.get("plannedMinutes").isJsonNull());
            boolean skipped = l.get("at").isJsonNull();
            in.sessions.add(new Inputs.Session(date, act,
                l.get("plannedMinutes").isJsonNull() ? null : l.get("plannedMinutes").getAsInt(),
                l.get("actualMinutes").isJsonNull() ? null : l.get("actualMinutes").getAsInt(),
                planned, skipped, l.get("unitsFinished").isJsonNull() ? null : l.get("unitsFinished").getAsDouble(),
                "GOAL".equals(kind.get(act))));
        }
        in.nodeActivities.put("T_CONVEX", List.of("watching convex videos"));
        return in;
    }

    @Test
    public void replayOfTheRealLog() throws Exception {
        File f = export();
        Assume.assumeTrue("the exported log is only on the user's PC", f != null);
        Graph g = ready();
        Inputs in = replay(g, f);
        Map<String, Param> post = byKey(Estimator.run(g, in, LocalDate.parse("2026-10-01"), 0));

        // Raw figures, before any weighting, as the spec states them.
        int done = 0, all = 0;
        double logSum = 0; int units = 0;
        for (Inputs.Session s : in.sessions) {
            if (s.planned() && s.goalWork()) { all++; if (!s.skipped()) done++; }
            if (s.activity().equals("watching convex videos") && s.units() != null && s.units() > 0) {
                logSum += Math.log((s.actualMin() / s.units()) / 60.0); units++;
            }
        }
        double rawAdherence = done / (double) all, rawBias = Math.exp(logSum / units);
        Param bias = post.get("post.bias.watching convex videos");
        System.out.printf("  raw adherence %.2f (%d of %d), raw convex bias %.2f over %d sessions%n",
            rawAdherence, done, all, rawBias, units);
        System.out.printf("  posterior adherence %.3f, convex bias median %.2f (prior 2.0, transition weight 0.3)%n",
            post.get("post.adherence").get("mean"), bias.get("median"));
        assertTrue("raw convex bias near 2.5", rawBias > 2.2 && rawBias < 2.8);
        assertTrue("raw adherence near 70%", rawAdherence > 0.55 && rawAdherence < 0.8);
        assertTrue("the posterior sits between the prior and the data",
            bias.get("median") > 2.0 && bias.get("median") < rawBias);
        assertEquals(Source.DATA, bias.source);
        assertEquals(Dist.LOGNORMAL, bias.dist);
    }
}

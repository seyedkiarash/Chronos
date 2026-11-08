package com.chronos.navigator.estimate;

import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.Source;
import com.chronos.navigator.model.Param;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How right the model has been (your request of 8 Nov 2026). Each of the past weeks is replayed with
 * only what was logged before it began, and what the model would then have said is set beside what
 * happened:
 *
 * <ul>
 *   <li><b>Hours</b>: of the goal time planned on each day of the week, how much you would work,
 *       Σ planned·adherence(activity), against Σ min(planned, worked), the count adherence learns from.
 *       Off by |predicted − worked| ÷ planned.</li>
 *   <li><b>Speed</b>: the minutes a unit takes, the speed filter's median times the goal's hours per unit,
 *       against the week's minutes over its units. Off by |predicted − actual| ÷ actual, weighted by units.</li>
 * </ul>
 *
 * Each is set beside a plain guess that learns nothing: the plan taken at its word (all of it worked) and
 * the hours per unit as Chronos has them. The model is worth its keep when it is off by less than they are.
 */
public final class Accuracy {
    private Accuracy() {}

    /** The stored weeks: {@code post.accuracy.0} is the week that ended yesterday, 1 the one before, … */
    public static final String KEY = Estimator.POST + "accuracy.";

    public record Week(LocalDate from, LocalDate to, double plannedH, double predictedH, double workedH,
                       double units, double speedErr, double plainSpeedErr) {
        /** Off by this share of the planned time; NaN with nothing planned. */
        public double hoursErr() { return plannedH > 0 ? Math.abs(predictedH - workedH) / plannedH : Double.NaN; }
        /** The plan taken at its word. */
        public double plainHoursErr() { return plannedH > 0 ? Math.abs(plannedH - workedH) / plannedH : Double.NaN; }
    }

    /** The last {@code weeks} seven-day stretches before today, latest first; a stretch with no log is left out. */
    public static List<Week> backtest(Graph g, Inputs in, LocalDate today, int weeks) {
        List<Week> out = new ArrayList<>();
        for (int i = 0; i < weeks; i++) {
            LocalDate to = today.minusDays(1 + 7L * i), from = to.minusDays(6);
            Week w = week(g, in, from, to);
            if (w != null) out.add(w);
        }
        return out;
    }

    static Week week(Graph g, Inputs in, LocalDate from, LocalDate to) {
        // What the model knew the evening before: both filters already skip what came after that day.
        LocalDate before = from.minusDays(1);
        List<Param> post = new ArrayList<>();
        Estimator.adherence(g, in, before, 0, post);
        Estimator.bias(g, in, before, 0, post);
        Map<String, Param> p = new HashMap<>();
        for (Param x : post) p.put(x.key, x);
        double pooled = p.get(Estimator.POST + "adherence").get("mean");

        Map<String, Map<LocalDate, double[]>> byAct = new TreeMap<>();    // activity -> day -> {planned, worked}
        Map<String, double[]> speed = new TreeMap<>();                     // activity -> {minutes, units}
        for (Inputs.Session s : in.sessions) {
            if (!s.goalWork() || s.date().isBefore(from) || s.date().isAfter(to)) continue;
            double[] d = byAct.computeIfAbsent(s.activity(), x -> new TreeMap<>()).computeIfAbsent(s.date(), x -> new double[2]);
            if (s.planned() && s.plannedMin() != null) d[0] += s.plannedMin();
            if (!s.skipped() && s.actualMin() != null) d[1] += Math.max(0, s.actualMin());
            Inputs.UnitGoal ug = in.unitGoals.get(s.activity());
            if (ug != null && ug.hoursPerUnit() > 0 && !s.skipped() && s.units() != null && s.units() > 0
                    && s.actualMin() != null && s.actualMin() > 0) {
                double[] v = speed.computeIfAbsent(s.activity(), x -> new double[2]);
                v[0] += s.actualMin();
                v[1] += s.units();
            }
        }
        double planned = 0, predicted = 0, worked = 0;
        for (Map.Entry<String, Map<LocalDate, double[]>> e : byAct.entrySet()) {
            Param own = p.get(Estimator.POST + "adherence." + e.getKey());
            double adh = own != null && own.get("mean") != null ? own.get("mean") : pooled;
            for (double[] d : e.getValue().values()) {
                if (d[0] <= 0) continue;                     // nothing planned: nothing to foresee
                planned += d[0];
                predicted += d[0] * adh;
                worked += Math.min(d[0], d[1]);
            }
        }
        double err = 0, plain = 0, units = 0;
        for (Map.Entry<String, double[]> e : speed.entrySet()) {
            Param bias = p.get(Estimator.POST + "bias." + e.getKey());
            if (bias == null || bias.get("median") == null) continue;
            double hpu = in.unitGoals.get(e.getKey()).hoursPerUnit();
            double actual = e.getValue()[0] / e.getValue()[1];
            double u = e.getValue()[1];
            err += u * Math.abs(bias.get("median") * hpu * 60 - actual) / actual;
            plain += u * Math.abs(hpu * 60 - actual) / actual;
            units += u;
        }
        if (planned <= 0 && units <= 0) return null;
        return new Week(from, to, planned / 60, predicted / 60, worked / 60, units,
            units > 0 ? err / units : Double.NaN, units > 0 ? plain / units : Double.NaN);
    }

    /** The weeks as parameters, kept with the posteriors and recomputed with them. */
    public static List<Param> asParams(List<Week> weeks, long now) {
        List<Param> out = new ArrayList<>();
        for (int i = 0; i < weeks.size(); i++) {
            Week w = weeks.get(i);
            Param x = new Param(KEY + i, Dist.POINT, Source.DATA,
                "The week " + w.from() + " to " + w.to() + ", replayed with only what was logged before it.",
                "from", (double) w.from().toEpochDay(), "to", (double) w.to().toEpochDay(),
                "planned_h", w.plannedH(), "predicted_h", w.predictedH(), "worked_h", w.workedH(), "units", w.units());
            if (!Double.isNaN(w.speedErr())) {
                x.values.put("speed_err", w.speedErr());
                x.values.put("plain_speed_err", w.plainSpeedErr());
            }
            x.updatedAt = now;
            out.add(x);
        }
        return out;
    }

    /**
     * Over all the weeks: {hours off, plan-at-its-word off, speed off, plain speed off}, each NaN when
     * nothing could be measured. Hours are weighted by planned time, speed by units.
     */
    public static double[] summary(List<Week> weeks) {
        double h = 0, hp = 0, hw = 0, s = 0, sp = 0, sw = 0;
        for (Week w : weeks) {
            if (w.plannedH() > 0) {
                h += Math.abs(w.predictedH() - w.workedH());
                hp += Math.abs(w.plannedH() - w.workedH());
                hw += w.plannedH();
            }
            if (!Double.isNaN(w.speedErr())) {
                s += w.units() * w.speedErr();
                sp += w.units() * w.plainSpeedErr();
                sw += w.units();
            }
        }
        return new double[]{hw > 0 ? h / hw : Double.NaN, hw > 0 ? hp / hw : Double.NaN,
                            sw > 0 ? s / sw : Double.NaN, sw > 0 ? sp / sw : Double.NaN};
    }
}

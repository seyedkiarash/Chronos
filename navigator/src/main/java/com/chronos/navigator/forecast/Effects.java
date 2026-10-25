package com.chronos.navigator.forecast;

import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Graph;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What a proposed change would do, measured rather than claimed: the graph is
 * forecast as it is and as it would be, with the same seed and the same number
 * of runs, so the two differ by the change and not by chance (common random
 * numbers, as in the weekly allocation).
 */
public final class Effects {
    private Effects() {}

    /** @param before/after P(by its deadline) without and with the change */
    public record Change(String nodeId, double before, double after) {
        public double delta() { return after - before; }
    }

    /** The baseline once, to compare several options against. */
    public static Forecaster.Result baseline(Graph g, Inputs in, LocalDate today, int runs, long seed) {
        return Forecaster.run(g, in, today, runs, seed);
    }

    /**
     * The nodes asked about, then any other deadline that moved by at least
     * {@code minDelta}, the largest moves first, at most {@code limit} in all.
     */
    public static List<Change> compare(Forecaster.Result base, Graph changed, Inputs in, LocalDate today,
                                       Collection<String> focus, double minDelta, int limit) {
        Forecaster.Result after = Forecaster.run(changed, in, today, base.runs(), base.seed());
        List<Change> out = new ArrayList<>();
        for (String id : focus) {
            Change c = change(base, after, id);
            if (c != null) out.add(c);
        }
        List<Change> others = new ArrayList<>();
        for (Map.Entry<String, Forecaster.NodeResult> e : after.nodes().entrySet()) {
            if (focus.contains(e.getKey())) continue;
            Change c = change(base, after, e.getKey());
            if (c != null && Math.abs(c.delta()) >= minDelta) others.add(c);
        }
        others.sort(Comparator.comparingDouble(c -> -Math.abs(c.delta())));
        for (Change c : others) { if (out.size() >= limit) break; out.add(c); }
        return out;
    }

    private static Change change(Forecaster.Result base, Forecaster.Result after, String id) {
        Forecaster.NodeResult a = base.nodes().get(id), b = after.nodes().get(id);
        if (a == null || b == null || a.pByDeadline() == null || b.pByDeadline() == null) return null;
        return new Change(id, a.pByDeadline(), b.pByDeadline());
    }
}

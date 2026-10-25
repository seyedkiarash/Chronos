package com.chronos.navigator.forecast;

import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Priority;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Whether a change keeps your goals within reach (your rule of 13 Oct 2026).
 * Each goal with a deadline has a priority; critical and important ones have
 * a floor on their odds, read at the low end of the 80% band, so a change that
 * only looks safe while the log is still thin does not pass. A goal already
 * below its floor is not held to it — the change must just not make it worse.
 * Optional goals have no floor; their moves are shown, not policed.
 *
 * Breaking a floor does not forbid a change: a new task from your advisor may
 * have to be taken whatever it costs. It means the package must say what makes
 * up for it, and you see the breach before you approve.
 */
public final class Guard {
    private Guard() {}

    /** Moves smaller than this are chance, not the change. */
    static final double NOISE = 0.02;

    public record Move(String nodeId, String title, int priority, double floor,
                       Bands.Band before, Bands.Band after, boolean breach) {
        public double delta() { return after.p() - before.p(); }
    }

    /** @param moves every goal that moved, breaches first, then by size of the move */
    public record Verdict(List<Move> moves) {
        public boolean ok() { return moves.stream().noneMatch(Move::breach); }

        public List<Move> breaches() { return moves.stream().filter(Move::breach).toList(); }
    }

    public static Verdict check(Graph after, Bands.Result before, Bands.Result changed) {
        List<Move> out = new ArrayList<>();
        for (Bands.Band b : changed.nodes().values()) {
            Bands.Band a = before.nodes().get(b.id());
            Node n = after.node(b.id());
            if (n == null) continue;
            int pr = Priority.of(n);
            double floor = Priority.floor(after, pr);
            if (a == null) {
                // A new goal: it must start within reach if it matters.
                boolean breach = floor > 0 && b.low() < floor;
                out.add(new Move(n.id, n.title, pr, floor, new Bands.Band(n.id, Double.NaN, Double.NaN, Double.NaN), b, breach));
                continue;
            }
            boolean moved = Math.abs(b.p() - a.p()) >= NOISE || Math.abs(b.low() - a.low()) >= NOISE;
            if (!moved) continue;
            // Newly below the floor; or already below it and lower still. The band's low end
            // stops at zero, so "lower still" is read on the odds themselves.
            boolean breach = floor > 0 && b.low() < floor - 1e-9
                && (a.low() >= floor - 1e-9 || b.p() < a.p() - NOISE);
            out.add(new Move(n.id, n.title, pr, floor, a, b, breach));
        }
        out.sort(Comparator.comparing((Move m) -> !m.breach())
            .thenComparing(m -> -m.priority())
            .thenComparing(m -> Double.isNaN(m.delta()) ? 0 : m.delta()));
        return new Verdict(out);
    }
}

package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Goal;
import com.chronos.navigator.estimate.Similarity;

import java.util.Objects;

/**
 * A new activity starts from the ones like it (your request of 8 Nov 2026). Its quality starts at
 * what the log has taught about activities of the same kind, weighted by how alike they are
 * (navigator.estimate.Similarity: shared words, the same goal) and by how sure each is; its
 * uncertainty starts wide enough to hold how much they disagree, so its own sessions soon take over.
 * Fatigue figures you did not give start at theirs too. With nothing alike learned yet, the defaults
 * stay.
 */
public final class Similar {
    private Similar() {}

    /** How much a merely same-kind activity counts beside a really similar one. */
    static final double BASE = 0.25;
    static final double DEFAULT_SD = new Activity().qSigma;

    /** Fills in {@code a}'s starting values from {@code cfg}'s other activities. Returns the names it drew on. */
    public static java.util.List<String> seed(Activity a, Config cfg) {
        java.util.List<String> from = new java.util.ArrayList<>();
        double w = 0, q = 0, q2 = 0, sd2 = 0;
        double lw = 0, load = 0, rec = 0, pl = 0, pr = 0;
        String group = groupOf(a, cfg);
        for (Activity o : cfg.activities) {
            if (o == a || Objects.equals(o.id, a.id) || o.kind != a.kind || o.leisure != a.leisure) continue;
            double s = BASE + Similarity.score(a.name, o.name, group != null && group.equals(groupOf(o, cfg)));
            if (o.load != null && o.recovery != null) {
                lw += s; load += s * o.load; rec += s * o.recovery;
                pl += s * (o.physLoad == null ? 0 : o.physLoad); pr += s * (o.physRecovery == null ? 0 : o.physRecovery);
            }
            // Only what the log has moved says anything about quality.
            if (o.qSigma >= DEFAULT_SD - 1e-9) continue;
            double sw = s * Math.min(3, DEFAULT_SD / Math.max(0.06, o.qSigma));
            w += sw; q += sw * o.q; q2 += sw * o.q * o.q; sd2 += sw * o.qSigma * o.qSigma;
            if (s > BASE) from.add(o.name);
        }
        if (w > 0) {
            double mean = q / w, spread = Math.max(0, q2 / w - mean * mean);
            a.q = Slots.clamp(mean, 0.1, 2);
            a.qSigma = Slots.clamp(Math.sqrt(sd2 / w + spread + 0.1 * 0.1), 0.15, DEFAULT_SD);
        }
        if (lw > 0) {
            if (a.load == null) a.load = load / lw;
            if (a.recovery == null) a.recovery = rec / lw;
            if (a.physLoad == null) a.physLoad = pl / lw;
            if (a.physRecovery == null) a.physRecovery = pr / lw;
        }
        return from;
    }

    private static String groupOf(Activity a, Config cfg) {
        if (a.goalId == null) return null;
        Goal g = cfg.goal(a.goalId);
        return g == null ? null : g.groupName;
    }
}

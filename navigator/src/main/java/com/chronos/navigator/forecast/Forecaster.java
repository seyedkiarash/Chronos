package com.chronos.navigator.forecast;

import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.NodeType;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The forecast layer (spec, "Forecast layer"): Monte Carlo over the whole
 * graph. Each run is one possible future from today to the graph's horizon:
 *
 *  1. parameters are drawn — adherence from its Beta, each kind of work's
 *     bias from its lognormal, each node's effort, each gate's odds;
 *  2. capacity is built — each day's type from its regime's Dirichlet, each
 *     day's goal hours from that type; daily for six weeks, weekly after;
 *  3. capacity is split — the week's allocation when one is given, else
 *     floors first and the rest by owner authority × value, within caps;
 *     real hours are the split × adherence × a little noise;
 *  4. a node starts when its AND dependencies are done, one OR group is
 *     complete, its earliest date has come and no overlay pauses it;
 *  5. a task ends when its hours reach its effort, a skill when its level is
 *     reached; a gate is decided as soon as what it needs is done (and closes
 *     if that misses its deadline); a milestone is reached with what it needs;
 *  6. each node's finishing day is recorded.
 *
 * Same graph, same inputs, same seed: same numbers (principle 5).
 */
public final class Forecaster {
    private Forecaster() {}

    /**
     * @param pByDeadline share of runs finished by its deadline; null without one
     * @param pReached    share of runs finished at all within the horizon
     * @param p10/p50/p90 finishing dates at those shares of runs; null when fewer runs finish
     * @param critical    share of runs where this node, finishing late, cost a deadline further on
     */
    public record NodeResult(String id, Double pByDeadline, double pReached, LocalDate p10, LocalDate p50,
                             LocalDate p90, double critical) {}

    public record Result(LocalDate asOf, long seed, int runs, long millis, Map<String, NodeResult> nodes) {}

    public static Result run(Graph g, Inputs in, LocalDate today, int runs, long seed) {
        return run(new Model(g, in, today, 0), runs, seed, null, 0);
    }

    /**
     * As many runs as fit the time budget, up to {@code runs} (spec: on the phone,
     * if the nightly run takes longer than its budget, the number of runs goes
     * down first). A pilot of a few hundred runs is timed and the rest sized to
     * it; never fewer than the pilot.
     */
    public static Result runWithin(Graph g, Inputs in, LocalDate today, int runs, long seed, double budgetSeconds) {
        Model m = new Model(g, in, today, 0);
        int pilot = Math.min(runs, 200);
        long t0 = System.nanoTime();
        run(m, pilot, seed, null, 0);
        double perRun = (System.nanoTime() - t0) / 1e9 / pilot;
        int fit = (int) Math.max(pilot, Math.min(runs, Math.floor(budgetSeconds / Math.max(perRun, 1e-6))));
        return run(m, fit, seed, null, 0);
    }

    /**
     * @param week     hours per node for the seven days from {@code weekFrom}, or null to split by the standing policy
     * @param weekFrom day offset the given week starts on
     */
    static Result run(Model m, int runs, long seed, double[] week, int weekFrom) {
        long t0 = System.nanoTime();
        double[][] finish = new double[m.n][runs];
        int[] critical = new int[m.n];
        Sim sim = new Sim(m);
        sim.weekFrom = weekFrom;
        for (int r = 0; r < runs; r++) {
            SplittableRandom rnd = new SplittableRandom(seed * 1_000_003L + r);
            double[] f = sim.run(rnd, week);
            for (int i = 0; i < m.n; i++) finish[i][r] = f[i];
            sim.markCritical(f, critical);
        }
        Map<String, NodeResult> out = new LinkedHashMap<>();
        for (int i = 0; i < m.n; i++) {
            double[] f = finish[i].clone();
            Arrays.sort(f);
            int reached = 0;
            for (double v : f) if (!Double.isNaN(v) && v < Sim.NEVER) reached++;
            Double p = null;
            if (m.deadline[i] != Model.NONE) {
                int ok = 0;
                for (double v : f) if (v <= m.deadline[i] + 1) ok++;
                p = ok / (double) runs;
            }
            out.put(m.nodes[i].id, new NodeResult(m.nodes[i].id, p, reached / (double) runs,
                quantile(m, f, 0.10), quantile(m, f, 0.50), quantile(m, f, 0.90), critical[i] / (double) runs));
        }
        return new Result(m.today, seed, runs, (System.nanoTime() - t0) / 1_000_000, out);
    }

    private static LocalDate quantile(Model m, double[] sorted, double q) {
        int k = (int) Math.ceil(q * sorted.length) - 1;
        double v = sorted[Math.max(0, k)];
        return v >= Sim.NEVER ? null : m.today.plusDays((long) Math.floor(v));
    }

    /** One run's state, reused across runs. */
    static final class Sim {
        static final double NEVER = 1e9;
        final Model m;
        final double[] done, hours, need, streamHours;
        final boolean[] failed, on;
        final double[] alloc;
        double[] capMu = new double[3];
        /** First day offset of the week a given allocation covers. */
        int weekFrom;
        /** This run's adherence per node: planned hours needed for the work left are the work left ÷ it. */
        final double[] adh;
        double[][] mix;

        Sim(Model m) {
            this.m = m;
            done = new double[m.n]; hours = new double[m.n]; need = new double[m.n]; streamHours = new double[m.n];
            failed = new boolean[m.n]; on = new boolean[m.n]; alloc = new double[m.n]; adh = new double[m.n];
            mix = new double[m.calendar.size()][];
        }

        /** One future. Returns each node's finishing day offset, or NEVER. */
        double[] run(SplittableRandom rnd, double[] week) { return run(rnd, rnd, week); }

        /**
         * One future, with what the log taught (adherence, speed, goal hours per
         * kind of day, each stretch's mix of days) drawn from {@code world} and
         * everything else from {@code rnd}. Several futures sharing one world say
         * how likely a deadline is if those learned values are what they are; see
         * {@link Bands}. With the same source for both it is the plain forecast.
         */
        double[] run(SplittableRandom world, SplittableRandom rnd, double[] week) {
            Arrays.fill(done, NEVER);
            Arrays.fill(hours, 0);
            Arrays.fill(streamHours, 0);
            Arrays.fill(failed, false);
            Arrays.fill(mix, null);

            for (int i = 0; i < m.n; i++) adh[i] = Math.max(0.05, Rand.beta(world, m.adhA[i], m.adhB[i]));
            for (int t = 0; t < 3; t++) capMu[t] = m.capMean[t] + m.capSd[t] * world.nextGaussian();
            for (int i = 0; i < m.n; i++) {
                need[i] = sampleNeed(i, world, rnd);
                if (m.doneAtStart[i]) done[i] = 0;
            }
            if (world != rnd) for (int r = 0; r < mix.length; r++) mix[r] = Rand.dirichlet(world, m.dayTypeAlpha.get(r));
            resolve(0, rnd);

            int day = 0;
            while (day < m.horizonDays) {
                int len = day < m.dailyDays ? 1 : Math.min(7, m.horizonDays - day);
                double capacity = capacity(day, len, rnd);
                split(day, len, capacity, week);
                double noise = Math.exp(m.noiseSigma * rnd.nextGaussian() - m.noiseSigma * m.noiseSigma / 2);
                for (int i = 0; i < m.n; i++) {
                    if (alloc[i] <= 0) continue;
                    if (m.stream[i]) { streamHours[i] += alloc[i]; continue; }
                    double real = alloc[i] * adh[i] * noise;
                    double before = hours[i];
                    hours[i] += real;
                    // A few minutes short is done: the split aims at the work left, not past it.
                    if (done[i] >= NEVER && hours[i] >= need[i] - 0.05)
                        done[i] = day + len * Math.min(1, (need[i] - before) / real);
                }
                resolve(day + len, rnd);
                day += len;
                // Once everything but the streams is settled, the rest of the horizon changes nothing.
                if (len > 1 && settled()) break;
            }
            return done.clone();
        }

        private boolean settled() {
            for (int i = 0; i < m.n; i++) if (!m.stream[i] && done[i] >= NEVER && !failed[i]) return false;
            return true;
        }

        /** Hours of this node's work still to do, drawn for this run: the speed from {@code world}, the size from {@code rnd}. */
        double sampleNeed(int i, SplittableRandom world, SplittableRandom rnd) {
            double bias = Math.exp(m.biasMu[i] + m.biasTau[i] * world.nextGaussian());
            return switch (m.effort[i]) {
                case UNITS -> m.unitsLeft[i] * m.hoursPerUnit[i] * bias;
                case TOTAL -> Math.max(0, Rand.lognormal(rnd, m.p50[i], m.p90[i]) * bias - m.hoursDone[i]);
                case SKILL -> Math.max(0, m.levelsLeft[i] * Rand.lognormal(rnd, m.p50[i], m.p90[i]) * bias - m.hoursDone[i]);
                case NONE -> 0;
            };
        }

        /** Goal hours of the step: each day's type from its regime's mix, each type's hours from its distribution. */
        double capacity(int day, int len, SplittableRandom rnd) {
            int reg = m.regimeOfDay[Math.min(day, m.horizonDays - 1)];
            if (reg < 0) return 0;
            double[] w = mix[reg];
            if (w == null) w = mix[reg] = Rand.dirichlet(rnd, m.dayTypeAlpha.get(reg));
            if (len == 1) {
                int t = Rand.categorical(rnd, w);
                return Math.max(0, capMu[t] + m.capSigma[t] * rnd.nextGaussian());
            }
            double mean = 0, var = 0;
            for (int d = 0; d < len; d++) {
                int t = Rand.categorical(rnd, w);
                mean += capMu[t];
                var += m.capSigma[t] * m.capSigma[t];
            }
            return Math.max(0, mean + Math.sqrt(var) * rnd.nextGaussian());
        }

        boolean active(int i, int day) {
            if (!m.schedulable(i) || done[i] < NEVER || failed[i]) return false;
            if (m.earliest[i] != Model.NONE && day < m.earliest[i]) return false;
            if (m.pausedOn(i, day)) return false;
            return depsDone(i, day) == 1;
        }

        /**
         * 1 when what node i needs is done by this day, 0 when not yet, -1 when
         * it never will be (a dependency failed). Streams never block a start.
         */
        int depsDone(int i, double day) {
            for (int d : m.and[i]) {
                if (m.stream[d]) continue;
                if (failed[d]) return -1;
                if (done[d] > day) return 0;
            }
            if (m.or[i].length == 0) return 1;
            boolean open = false;
            for (int[] grp : m.or[i]) {
                boolean all = true, dead = false;
                for (int d : grp) { if (failed[d]) dead = true; if (done[d] > day) all = false; }
                if (all && !dead) return 1;
                if (!dead) open = true;
            }
            return open ? 0 : -1;
        }

        /** When what node i needs finished: the latest of its AND dependencies and its earliest complete OR group. */
        double depsDate(int i) {
            double t = 0;
            for (int d : m.and[i]) if (!m.stream[d]) t = Math.max(t, done[d]);
            if (m.or[i].length > 0) {
                double best = NEVER;
                for (int[] grp : m.or[i]) {
                    double g = 0;
                    for (int d : grp) g = Math.max(g, failed[d] ? NEVER : done[d]);
                    best = Math.min(best, g);
                }
                t = Math.max(t, best);
            }
            return t;
        }

        /** The hours of the step: the given week, or overlay plans and floors first and the rest by weight. */
        void split(int day, int len, double capacity, double[] week) {
            Arrays.fill(alloc, 0);
            // Which nodes may take hours is settled once per step: the passes below read it many times.
            for (int i = 0; i < m.n; i++) on[i] = active(i, day);
            if (week != null && day >= weekFrom) {
                // A candidate week is judged as a way of working: kept every week from here on, so
                // two hours more for a long task shows in its odds instead of vanishing in one week.
                // A week that holds fewer hours than the plan (exams) shares the shortfall evenly.
                double total = 0;
                for (int i = 0; i < m.n; i++)
                    if (week[i] > 0 && on[i]) { alloc[i] = Math.min(week[i] / 7.0 * len, left(i)); total += alloc[i]; }
                if (total > capacity && total > 0) for (int i = 0; i < m.n; i++) alloc[i] *= capacity / total;
                return;
            }
            double left = capacity, wanted = 0;
            java.util.Map<Integer, Double> plan = m.planned.get(day);
            java.util.Map<Integer, Double> chronos = len == 1 ? m.chronos.get(day) : null;
            for (int i = 0; i < m.n; i++) {
                if (!on[i]) continue;
                // What Chronos has planned for this node today is what will be worked on it.
                Double p = chronos != null && chronos.get(i) != null ? chronos.get(i) : plan == null ? null : plan.get(i);
                double want = p != null ? p * len : m.floorOn(i, day) * len;
                if (chronos != null && chronos.get(i) != null) {
                    // Chronos re-plans every night: what was not done comes back on the days left before
                    // the deadline, so a run that fell behind gets more than the first plan said.
                    double[] ahead = m.chronosAhead.get(i);
                    if (ahead != null && day < ahead.length - 1 && ahead[day] > 1e-6) {
                        double behind = left(i) / ahead[day];
                        if (behind > 1) want *= Math.min(3, behind);
                    }
                }
                want = Math.min(want, m.capPerDay[i] * len);
                if (!m.stream[i]) want = Math.min(want, left(i));
                alloc[i] = Math.max(0, want);
                wanted += alloc[i];
            }
            if (wanted > left && wanted > 0) {
                double s = left / wanted;
                for (int i = 0; i < m.n; i++) alloc[i] *= s;
                return;
            }
            left -= wanted;
            if (plan != null) return;    // an overlay's plan is the plan: support work waits
            // the rest by weight, within each node's daily cap and remaining work
            for (int pass = 0; pass < 3 && left > 1e-6; pass++) {
                double total = 0;
                for (int i = 0; i < m.n; i++) if (on[i] && room(i, len) > 1e-6) total += m.weight[i];
                if (total <= 0) break;
                double given = 0;
                for (int i = 0; i < m.n; i++) {
                    double room = room(i, len);
                    if (!on[i] || room <= 1e-6) continue;
                    double add = Math.min(room, left * m.weight[i] / total);
                    alloc[i] += add;
                    given += add;
                }
                left -= given;
            }
        }

        private double room(int i, int len) {
            double cap = m.capPerDay[i] * len - alloc[i];
            if (!m.stream[i]) cap = Math.min(cap, left(i) - alloc[i]);
            return cap;
        }

        /** Planned hours that would finish the node's work at this run's rate of doing what is planned. */
        private double left(int i) {
            return Math.max(0, (need[i] - hours[i]) / adh[i] + 0.1);
        }

        /** Settles everything whose outcome follows from what is done by this day. */
        void resolve(double day, SplittableRandom rnd) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int i = 0; i < m.n; i++) {
                    if (done[i] < NEVER || failed[i]) continue;
                    NodeType t = m.type[i];
                    if (t == NodeType.TASK || t == NodeType.SKILL) {
                        if (depsDone(i, day) == -1) { failed[i] = true; changed = true; }
                        else if (need[i] <= 0 && depsDone(i, day) == 1) { done[i] = Math.max(depsDate(i), notBefore(i)); changed = true; }
                        continue;
                    }
                    if (t == NodeType.STREAM) continue;
                    if (t == NodeType.EXTERNAL_TRACK) {
                        if (m.children[i].length == 0) continue;
                        double last = 0; boolean all = true;
                        for (int c : m.children[i]) { if (done[c] >= NEVER) all = false; else last = Math.max(last, done[c]); }
                        if (all) { done[i] = last; changed = true; }
                        continue;
                    }
                    if (t == NodeType.DECISION) {
                        if (m.decideBy[i] != Model.NONE && day >= m.decideBy[i]) { done[i] = m.decideBy[i]; changed = true; }
                        continue;
                    }
                    int deps = depsDone(i, day);
                    if (deps == -1) { failed[i] = true; changed = true; continue; }
                    boolean noDeps = m.and[i].length == 0 && m.or[i].length == 0;
                    if (t == NodeType.GATE) {
                        if (deps == 1 && !noDeps) {
                            double at = Math.max(depsDate(i), notBefore(i));
                            if (m.deadline[i] != Model.NONE && at > m.deadline[i] + 1) { failed[i] = true; changed = true; continue; }
                            boolean open = rnd.nextDouble() < Rand.beta(rnd, m.successA[i], m.successB[i]);
                            if (open) done[i] = at; else failed[i] = true;
                            changed = true;
                        } else if (m.deadline[i] != Model.NONE && day > m.deadline[i] + 1) {
                            failed[i] = true; changed = true;
                        }
                        continue;
                    }
                    // MILESTONE, TERMINAL
                    if (noDeps) {
                        if (m.successA[i] > 0 && m.deadline[i] != Model.NONE && day >= m.deadline[i]) {
                            if (rnd.nextDouble() < Rand.beta(rnd, m.successA[i], m.successB[i])) done[i] = m.deadline[i];
                            else failed[i] = true;
                            changed = true;
                        }
                        continue;
                    }
                    if (deps != 1) continue;
                    if (m.hasStreamDep[i]) {
                        // A stream never finishes: it counts as met when, by the deadline, its floor was
                        // kept on average in the plan (5% short is rounding, not a missed floor).
                        if (m.deadline[i] == Model.NONE || day < m.deadline[i]) continue;
                        boolean kept = true;
                        double days = Math.max(1, m.deadline[i]);
                        for (int d : m.and[i])
                            if (m.stream[d] && streamHours[d] / days < m.floorPerDay[d] * 0.95) kept = false;
                        if (kept) done[i] = Math.max(Math.max(depsDate(i), notBefore(i)), m.deadline[i]); else failed[i] = true;
                        changed = true;
                        continue;
                    }
                    done[i] = Math.max(depsDate(i), notBefore(i));
                    changed = true;
                }
            }
        }

        /** A node's earliest date as a day offset; 0 when it has none. */
        double notBefore(int i) {
            return m.earliest[i] == Model.NONE ? 0 : Math.max(0, m.earliest[i]);
        }

        /**
         * For every deadline this run missed, the chain that made it late: from
         * the late node, step to whichever of its dependencies finished last,
         * and so on down. Each node on such a chain counts once per run.
         */
        void markCritical(double[] f, int[] count) {
            boolean[] seen = new boolean[m.n];
            for (int i = 0; i < m.n; i++) {
                if (m.deadline[i] == Model.NONE || f[i] <= m.deadline[i] + 1) continue;
                int cur = i;
                for (int guard = 0; guard < m.n; guard++) {
                    int latest = -1;
                    double t = -1;
                    for (int d : m.and[cur]) if (!m.stream[d] && f[d] > t) { t = f[d]; latest = d; }
                    for (int[] grp : m.or[cur]) for (int d : grp) if (f[d] > t) { t = f[d]; latest = d; }
                    if (latest < 0) break;
                    if (!seen[latest]) { seen[latest] = true; count[latest]++; }
                    cur = latest;
                }
                if (!seen[i] && m.takesHours(i)) { seen[i] = true; count[i]++; }
            }
        }
    }
}

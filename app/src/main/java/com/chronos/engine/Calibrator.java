package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Commitment;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.CommitmentKind;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;
import com.chronos.model.Params;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.chronos.engine.Slots.PER_DAY;
import static com.chronos.engine.Slots.SLOT_MIN;

/**
 * Turns the log into parameters. Fits the circadian phase, the capacity
 * weights and the physical-fatigue threshold, then each activity's quality and
 * its mental and physical load and recovery, then travel times.
 *
 * Ratings carrying an outside cause are excluded from the body-clock fit. A
 * score of two out of five after a bad night says something about that night,
 * not about your eleven o'clock.
 */
public final class Calibrator {
    private Calibrator() {}

    /** Caffeine is here too: a rating lifted by a stimulant says nothing about your natural curve. */
    public static final List<String> CONFOUNDERS =
        List.of("Poor sleep", "Unwell", "Stressed", "Skipped a meal", "Noisy or interrupted", "Caffeine",
                // from the morning and evening check-ins: circumstances, not the body's own rhythm
                "Slept badly", "Feeling unwell", "Emotionally drained", "Something happened",
                "Anxious or stressed", "Low mood", "Overwhelmed",
                // the Log's day and hour tags: what was around you and how you felt
                "Exam or deadline day", "Travel day", "Social day", "Family matter", "Holiday or day off",
                "Anxious", "Upset or angry", "Lonely", "Bad news", "Good news", "Happy or excited",
                "Hungry", "Too hot or cold", "On the phone or social media", "Pain or discomfort",
                "Worried about something", "Frustrated", "Bored", "Restless", "Upsetting conversation",
                "Good conversation", "Excited about something");
    public static final List<String> TAGS = new ArrayList<>(CONFOUNDERS) {{
        add("After exercise"); add("Late start");
    }};

    public record Change(String subject, String detail) {}
    public record Outcome(Params params, List<Activity> activities,
                          List<Commitment> commitments, List<Change> report,
                          int cleanObservations, int totalObservations) {}

    private record Observation(int slot, double fatigue, double physical, double rating, boolean clean, long ts) {}

    /** Thresholds tried for physical fatigue, above which it starts to cost mental capacity. */
    private static final double[] THETAS = {0.3, 0.4, 0.5, 0.6, 0.7, 0.8};

    public static Outcome calibrate(Config cfg, List<LogEntry> logs) {
        Params p = cfg.params;
        double[] h = Energy.homeostatic(p);
        Map<LocalDate, List<LogEntry>> byDate = new HashMap<>();
        for (LogEntry l : logs) byDate.computeIfAbsent(l.date, d -> new ArrayList<>()).add(l);

        List<Observation> obs = new ArrayList<>();
        int s0 = Slots.parse(p.sleepStart), s1 = Slots.parse(p.sleepEnd);
        for (Map.Entry<LocalDate, List<LogEntry>> e : byDate.entrySet()) {
            double[][] fp = fatigueFromLog(e.getValue(), cfg);
            double[] f = fp[0], ph = fp[1];
            for (LogEntry l : e.getValue()) {
                Integer rating = l.kind == LogKind.CHECKIN ? l.energy
                               : l.kind == LogKind.SESSION ? l.focus : null;
                if (rating == null || l.at == null) continue;
                int t = Slots.parse(l.at);
                if (Energy.isAsleep(t, s0, s1)) continue;
                boolean clean = l.tags == null || l.tags.stream().noneMatch(CONFOUNDERS::contains);
                obs.add(new Observation(t, f[t], ph[t], Slots.clamp(rating / 5.0, 0.05, 1), clean, l.ts));
            }
        }
        List<Observation> clean = obs.stream().filter(Observation::clean).toList();
        List<Change> report = new ArrayList<>();

        Params out = p.copy();
        if (clean.size() >= 6) {
            long newest = clean.stream().mapToLong(Observation::ts).max().orElse(0);
            double[] weights = new double[clean.size()];
            for (int i = 0; i < clean.size(); i++)
                weights[i] = Math.pow(0.98, (newest - clean.get(i).ts()) / 86_400_000.0);

            double[] prior = {p.a0, p.aC, p.aH, p.aF, p.aP == null ? 0.15 : p.aP};
            double[] bestAlpha = null;
            int bestPhi = p.phi;
            double bestTheta = p.pTheta == null ? 0.5 : p.pTheta;
            double bestSse = Double.MAX_VALUE;

            for (double theta : THETAS)
            for (int phi = 0; phi < 1440; phi += 30) {
                // The sharpest hour is one you are awake for. Ratings only cover
                // waking hours, so a curve peaking in the night fits a midday dip
                // just as well — and then names midnight as your best hour and
                // bends the whole day around it.
                if (Energy.isAsleep(phi / SLOT_MIN, s0, s1)) continue;
                double[][] x = new double[clean.size()][5];
                double[] y = new double[clean.size()];
                for (int i = 0; i < clean.size(); i++) {
                    Observation o = clean.get(i);
                    x[i] = new double[]{1, Energy.circadian(o.slot(), phi), -h[o.slot()], -o.fatigue(),
                                        -Math.max(0, o.physical() - theta)};
                    y[i] = o.rating();
                }
                double[] alpha = ridgeSolve(x, y, weights, prior, 1.2);
                // A phase twelve hours away with a negative amplitude fits
                // identically; only the positive-amplitude version names a peak.
                if (alpha[1] < 0) continue;
                double sse = 0;
                for (int i = 0; i < x.length; i++) {
                    double pred = 0;
                    for (int j = 0; j < 5; j++) pred += x[i][j] * alpha[j];
                    sse += weights[i] * Math.pow(y[i] - pred, 2);
                }
                if (sse < bestSse) { bestSse = sse; bestAlpha = alpha; bestPhi = phi; bestTheta = theta; }
            }
            if (bestAlpha != null) {
                out.a0 = Slots.clamp(bestAlpha[0], 0.30, 0.95);
                out.aC = Slots.clamp(bestAlpha[1], 0.00, 0.40);
                out.aH = Slots.clamp(bestAlpha[2], 0.00, 0.35);
                out.aF = Slots.clamp(bestAlpha[3], 0.00, 0.35);
                out.aP = Slots.clamp(bestAlpha[4], 0.00, 0.40);
                out.pTheta = bestTheta;
                out.phi = bestPhi;
                report.add(new Change("Body clock",
                    "fitted on " + clean.size() + " rated moments; your sharpest hour moved to "
                    + Slots.formatMinutes(bestPhi)
                    + (obs.size() > clean.size()
                        ? ", ignoring " + (obs.size() - clean.size()) + " affected by an outside cause" : "")));
            }
        } else {
            report.add(new Change("Body clock",
                "needs at least 6 clean energy ratings, has " + clean.size()
                + ". Still using your own estimates."));
        }

        List<Activity> acts = new ArrayList<>();
        for (Activity a : cfg.activities) acts.add(learnActivity(a, logs, byDate, cfg, report));

        List<Commitment> coms = new ArrayList<>();
        for (Commitment c : cfg.commitments) coms.add(learnTravel(c, logs, byDate, cfg, report));

        return new Outcome(out, acts, coms, report, clean.size(), obs.size());
    }

    private static Activity learnActivity(Activity a, List<LogEntry> logs,
                                          Map<LocalDate, List<LogEntry>> byDate,
                                          Config cfg, List<Change> report) {
        List<LogEntry> mine = logs.stream()
            .filter(l -> l.kind == LogKind.SESSION && a.id.equals(l.actId)).toList();
        if (mine.isEmpty()) return a;

        double oldQ = a.q, oldLoad = nz(a.load), oldRec = nz(a.recovery);
        double oldPL = nz(a.physLoad), oldPR = nz(a.physRecovery);

        if (a.kind == ActivityKind.GOAL) {
            List<LogEntry> rated = mine.stream().filter(l -> l.attain != null).toList();
            if (!rated.isEmpty()) {
                double ratio = 0;
                for (LogEntry l : rated)
                    ratio += (l.attain / 100.0) * (l.planned / (double) Math.max(1, l.actual));
                ratio /= rated.size();
                a.q = Slots.clamp(0.75 * a.q + 0.25 * ratio, 0.1, 2);
                a.qSigma = Math.max(0.08, a.qSigma * Math.pow(0.88, rated.size()));
            }
        } else {
            List<LogEntry> rest = mine.stream().filter(l -> l.restored != null).toList();
            if (rest.size() >= 2) {
                double rate = 0;
                for (LogEntry l : rest) {
                    double[] f = fatigueFromLog(byDate.getOrDefault(l.date, List.of()), cfg)[0];
                    double before = f[Slots.parse(l.at)];
                    rate += (before * (l.restored / 5.0)) / Math.max(0.25, l.actual / 60.0);
                }
                a.recovery = Slots.clamp(0.7 * nz(a.recovery) + 0.3 * (rate / rest.size()), 0, 1);
            }
        }
        // How tired it left you, mental and physical apart. A rating from before fatigue had two
        // kinds is one number: it is split by how physical the activity is.
        double[] net = netRates(mine, byDate, cfg, FatigueSplit.physicalShare(a.load, a.physLoad));
        if (net != null) {
            // Goal work's mental side moves its load; upkeep keeps learning its mental recovery from
            // "how restored", above. The physical side moves whichever of load and recovery it has.
            if (a.kind == ActivityKind.GOAL && !Double.isNaN(net[0])) {
                double[] lr = step(nz(a.load), nz(a.recovery), net[0]);
                a.load = Math.max(0.02, lr[0]); a.recovery = lr[1];
            }
            if (!Double.isNaN(net[1])) {
                double[] lr = step(nz(a.physLoad), nz(a.physRecovery), net[1]);
                a.physLoad = lr[0]; a.physRecovery = lr[1];
            }
        }
        if (a.leisure) learnStimulation(a, mine, logs, cfg, report);
        if (a.q != oldQ || nz(a.load) != oldLoad || nz(a.physLoad) != oldPL)
            report.add(new Change(a.name, String.format(
                "quality %.2f to %.2f, mental load %.2f to %.2f, physical load %.2f to %.2f (%d sessions)",
                oldQ, a.q, oldLoad, nz(a.load), oldPL, nz(a.physLoad), mine.size())));
        if (nz(a.recovery) != oldRec || nz(a.physRecovery) != oldPR)
            report.add(new Change(a.name, String.format(
                "recovery %.2f to %.2f, physical recovery %.2f to %.2f (%d sessions)",
                oldRec, nz(a.recovery), oldPR, nz(a.physRecovery), mine.size())));
        return a;
    }

    private static double nz(Double v) { return v == null ? 0 : v; }

    /**
     * Leisure's stimulation (your request of 25 Oct 2026), learned from its after-effects rather
     * than asked for: how hard it was to stop, how hard to get into the next thing, how much you
     * wanted more — and, when goal work followed within two hours, how far its focus fell below
     * the day's other sessions. Each session gives a rate; the activity moves toward it, quickly
     * while it has seen few sessions, then a fifth of the way.
     */
    private static void learnStimulation(Activity a, List<LogEntry> mine, List<LogEntry> logs, Config cfg,
                                         List<Change> report) {
        Double old = a.dopamine;
        int n = 0;
        for (LogEntry l : mine) {
            if (l.at == null || l.actual == null || l.actual <= 0) continue;
            Double seen = stimulationSeen(l, cfg.params, focusDropAfter(l, logs, cfg));
            if (seen == null) continue;
            double w = Math.max(0.2, 1.0 / (a.dopamineSeen + 2));
            a.dopamine = a.dopamine == null ? seen : a.dopamine + w * (seen - a.dopamine);
            a.dopamineSeen++;
            n++;
        }
        if (n > 0) report.add(new Change(a.name, String.format(
            "stimulation per hour %.2f to %.2f (%d sessions: how hard to stop, to start the next thing, wanting more)",
            old == null ? 0 : old, a.dopamine, n)));
    }

    /**
     * The stimulation rate one session points to. The answers (1..5) say where it left you on the
     * way to your ceiling — all three at their worst is a quarter past it — and the rate is what
     * reaches that level in the session's length, given how fast it fades. Null when nothing was said.
     *
     * @param focusDrop focus points the next goal session fell below the day's others, or null
     */
    public static Double stimulationSeen(LogEntry l, Params p, Double focusDrop) {
        double sum = 0;
        int k = 0;
        for (Integer x : new Integer[]{l.stopHard, l.nextStart, l.craving})
            if (x != null) { sum += Slots.clamp((x - 1) / 4.0, 0, 1); k++; }
        if (k == 0) return null;
        double s = sum / k;
        if (focusDrop != null) s = Math.max(0, Math.min(1, s + 0.15 * focusDrop));
        double hours = Math.max(0.25, l.actual / 60.0);
        double half = p.dopHalfLifeOr();
        double reach = half / Math.log(2) * (1 - Math.pow(0.5, hours / half));
        return Math.max(0, Math.min(3, s * 1.25 * p.dopCapOr() / reach));
    }

    /** How far the goal session started within two hours after [l] fell below the day's other goal sessions. */
    public static Double focusDropAfter(LogEntry l, List<LogEntry> logs, Config cfg) {
        int end = Slots.parse(l.at) * SLOT_MIN + l.actual;
        LogEntry next = null;
        int nextAt = Integer.MAX_VALUE;
        List<Integer> others = new ArrayList<>();
        for (LogEntry o : logs) {
            if (o.kind != LogKind.SESSION || o.at == null || o.focus == null || o.date == null || !o.date.equals(l.date)) continue;
            Activity oa = cfg.activity(o.actId);
            if (oa == null || oa.kind != ActivityKind.GOAL) continue;
            int at = Slots.parse(o.at) * SLOT_MIN;
            if (at >= end - 5 && at <= end + 120 && at < nextAt) { next = o; nextAt = at; }
        }
        if (next == null) return null;
        for (LogEntry o : logs) {
            if (o == next || o.kind != LogKind.SESSION || o.at == null || o.focus == null || o.date == null || !o.date.equals(l.date)) continue;
            Activity oa = cfg.activity(o.actId);
            if (oa != null && oa.kind == ActivityKind.GOAL) others.add(o.focus);
        }
        if (others.isEmpty()) return null;
        double mean = 0;
        for (int f : others) mean += f;
        return mean / others.size() - next.focus;
    }

    /**
     * Mean net change per hour of each reservoir over rated entries: {mental, physical}; NaN for a
     * side nothing was said about; null when fewer than two entries say anything.
     */
    static double[] netRates(List<LogEntry> entries, Map<LocalDate, List<LogEntry>> byDate, Config cfg, double share) {
        return netRates(entries, byDate, cfg, share, 2);
    }

    /** @param least how many rated entries a side needs before it says anything */
    static double[] netRates(List<LogEntry> entries, Map<LocalDate, List<LogEntry>> byDate, Config cfg, double share, int least) {
        double m = 0, ph = 0;
        int nm = 0, np = 0;
        for (LogEntry l : entries) {
            if (l.at == null || l.actual == null) continue;
            if (l.tags != null && l.tags.stream().anyMatch(CONFOUNDERS::contains)) continue;
            if (l.mentalAfter == null && l.physicalAfter == null && l.fatigueAfter == null) continue;
            double[][] fp = fatigueFromLog(byDate.getOrDefault(l.date, List.of()), cfg);
            int t = Slots.parse(l.at);
            double mb = fp[0][t], pb = fp[1][t];
            double hours = Math.max(0.25, l.actual / 60.0);
            if (l.mentalAfter != null || l.physicalAfter != null) {
                if (l.mentalAfter != null) { m += (Slots.clamp(l.mentalAfter / 5.0, 0, 1) - mb) / hours; nm++; }
                if (l.physicalAfter != null) { ph += (Slots.clamp(l.physicalAfter / 5.0, 0, 1) - pb) / hours; np++; }
            } else {
                double before = (1 - share) * mb + share * pb;
                double delta = Slots.clamp(l.fatigueAfter / 5.0, 0, 1) - before;
                m += delta * (1 - share) / hours; nm++;
                ph += delta * share / hours; np++;
            }
        }
        if (nm < least && np < least) return null;
        return new double[]{nm >= least ? m / nm : Double.NaN, np >= least ? ph / np : Double.NaN};
    }

    /**
     * One learning step on a reservoir's load and recovery from its observed
     * net rate (load − recovery per hour): an activity that mostly tires has
     * its load moved, one that mostly restores its recovery — the same 0.3
     * step the single load always took.
     */
    static double[] step(double load, double recovery, double net) {
        if (load >= recovery) return new double[]{Slots.clamp(0.7 * load + 0.3 * (net + recovery), 0, 1), recovery};
        return new double[]{load, Slots.clamp(0.7 * recovery + 0.3 * (load - net), 0, 1)};
    }

    private static Commitment learnTravel(Commitment c, List<LogEntry> logs,
                                          Map<LocalDate, List<LogEntry>> byDate, Config cfg,
                                          List<Change> report) {
        if (c.kind == CommitmentKind.FIXED) { learnSessions(c, logs, byDate, cfg, report); return c; }
        if (c.kind != CommitmentKind.VARIABLE) return c;
        List<LogEntry> trips = logs.stream()
            .filter(l -> l.kind == LogKind.TRAVEL && c.id.equals(l.comId) && l.actual != null && l.actual > 0)
            .toList();
        learnTripLoad(c, trips, byDate, cfg, report);
        if (trips.size() < 3) return c;

        double mu = 0;
        for (LogEntry l : trips) mu += Math.log(l.actual);
        mu /= trips.size();
        double var = 0;
        for (LogEntry l : trips) var += Math.pow(Math.log(l.actual) - mu, 2);
        double sd = Math.sqrt(var / Math.max(1, trips.size() - 1));

        double oldMedian = c.medianMin, oldSigma = c.sigma;
        c.medianMin = (double) Math.round(Math.exp(mu));
        c.sigma = Slots.clamp(sd, 0.05, 0.8);
        report.add(new Change(c.name, String.format(
            "typical %.0f to %.0f min, variability %.2f to %.2f (%d trips)",
            oldMedian, c.medianMin, oldSigma, c.sigma, trips.size())));
        return c;
    }

    /**
     * A class's sessions, each from how it left you (your request of 23 Oct 2026): the mental
     * load and recovery from "mentally tired after", the physical ones from "physically tired
     * after", apart — the same step an activity's take. A class meets once a week, so one rating
     * of a session is enough for a step.
     */
    private static void learnSessions(Commitment c, List<LogEntry> logs, Map<LocalDate, List<LogEntry>> byDate,
                                      Config cfg, List<Change> report) {
        for (Commitment.Session s : c.sessions) {
            List<LogEntry> mine = new ArrayList<>();
            for (LogEntry l : logs)
                if (l.kind == LogKind.CLASS && c.id.equals(l.comId) && l.date != null && l.at != null
                        && s.day == Slots.dow(l.date) && s.start != null && Slots.parse(s.start) == Slots.parse(l.at)) mine.add(l);
            if (mine.isEmpty()) continue;
            double share = FatigueSplit.physicalShare(s.loadOr(c.load), s.physLoadOr(c.physLoad));
            double[] net = netRates(mine, byDate, cfg, share, 1);
            if (net == null) continue;
            double oldM = s.loadOr(c.load), oldMR = s.recoveryOr(c.recovery);
            double oldP = s.physLoadOr(c.physLoad), oldPR = s.physRecoveryOr(c.physRecovery);
            if (!Double.isNaN(net[0])) { double[] lr = step(oldM, oldMR, net[0]); s.load = lr[0]; s.recovery = lr[1]; }
            if (!Double.isNaN(net[1])) { double[] lr = step(oldP, oldPR, net[1]); s.physLoad = lr[0]; s.physRecovery = lr[1]; }
            report.add(new Change(c.name + " (" + Slots.DOW[s.day] + " " + s.start + ")", String.format(
                "mental load %.2f to %.2f, mental recovery %.2f to %.2f, physical load %.2f to %.2f, physical recovery %.2f to %.2f",
                oldM, s.loadOr(c.load), oldMR, s.recoveryOr(c.recovery), oldP, s.physLoadOr(c.physLoad), oldPR, s.physRecoveryOr(c.physRecovery))));
        }
    }

    /**
     * The trip's own fatigue load, from how drained it left you — the same
     * step an activity's load takes, so a commute is learned like any other
     * part of the day instead of staying the number first typed in.
     */
    private static void learnTripLoad(Commitment c, List<LogEntry> trips,
                                      Map<LocalDate, List<LogEntry>> byDate, Config cfg,
                                      List<Change> report) {
        double[] net = netRates(trips, byDate, cfg, FatigueSplit.physicalShare(c.load, c.physLoad));
        if (net == null) return;
        double old = nz(c.load), oldP = nz(c.physLoad);
        if (!Double.isNaN(net[0])) { double[] lr = step(nz(c.load), nz(c.recovery), net[0]); c.load = lr[0]; c.recovery = lr[1]; }
        if (!Double.isNaN(net[1])) { double[] lr = step(nz(c.physLoad), nz(c.physRecovery), net[1]); c.physLoad = lr[0]; c.physRecovery = lr[1]; }
        if (nz(c.load) != old || nz(c.physLoad) != oldP)
            report.add(new Change(c.name, String.format(
                "fatigue load %.2f to %.2f, physical %.2f to %.2f (%d trips)", old, nz(c.load), oldP, nz(c.physLoad), trips.size())));
    }

    /** Rebuild the two fatigue traces of one logged day from its sessions, trips and classes: {mental, physical}. */
    static double[][] fatigueFromLog(List<LogEntry> dayLogs, Config cfg) {
        Params p = cfg.params;
        int s0 = Slots.parse(p.sleepStart), s1 = Slots.parse(p.sleepEnd);
        double[] load = new double[PER_DAY], rec = new double[PER_DAY];
        double[] pLoad = new double[PER_DAY], pRec = new double[PER_DAY];
        boolean[] busy = new boolean[PER_DAY];
        for (LogEntry l : dayLogs) {
            if (l.at == null) continue;
            double ld, rc, pl, pr;
            if (l.kind == LogKind.SESSION) {
                Activity a = cfg.activity(l.actId);
                ld = a == null ? 0.6 : nz(a.load);
                rc = a == null ? 0 : nz(a.recovery);
                pl = a == null ? 0.05 : nz(a.physLoad);
                pr = a == null ? 0 : nz(a.physRecovery);
            } else if (l.kind == LogKind.TRAVEL) {
                // A trip tires or rests you like anything else you spend time on.
                Commitment c = null;
                for (Commitment k : cfg.commitments) if (k.id.equals(l.comId)) c = k;
                if (c == null) continue;
                ld = nz(c.load); rc = nz(c.recovery); pl = nz(c.physLoad); pr = nz(c.physRecovery);
            } else if (l.kind == LogKind.CLASS) {
                // A class, by its own session's figures.
                Commitment c = null;
                for (Commitment k : cfg.commitments) if (k.id.equals(l.comId)) c = k;
                Commitment.Session s = c == null || l.date == null ? null : c.sessionAt(Slots.dow(l.date), Slots.parse(l.at));
                if (s == null) continue;
                ld = s.loadOr(c.load); rc = s.recoveryOr(c.recovery); pl = s.physLoadOr(c.physLoad); pr = s.physRecoveryOr(c.physRecovery);
            } else continue;
            int st = Slots.parse(l.at);
            int n = Math.max(1, (int) Math.round((l.actual == null ? 30 : l.actual) / (double) SLOT_MIN));
            for (int t = st; t < Math.min(PER_DAY, st + n); t++) {
                busy[t] = true;
                load[t] = ld; rec[t] = rc; pLoad[t] = pl; pRec[t] = pr;
            }
        }
        double idleP = p.idleRecoveryP == null ? p.idleRecovery : p.idleRecoveryP;
        double kMP = p.kMP == null ? 0 : p.kMP;
        double[] f = new double[PER_DAY], ph = new double[PER_DAY];
        double cur = 0.15, curP = 0.10;
        for (int t = 0; t < PER_DAY; t++) {
            f[t] = cur;
            ph[t] = curP;
            boolean asleep = Energy.isAsleep(t, s0, s1);
            double restore = asleep ? 1 : (busy[t] ? rec[t] : p.idleRecovery);
            double pRestore = asleep ? 1 : (busy[t] ? pRec[t] : idleP);
            double next = Slots.clamp(cur + (SLOT_MIN / 60.0) * (load[t] - restore), 0, p.fMax);
            curP = Slots.clamp(curP + (SLOT_MIN / 60.0) * (pLoad[t] * (1 + kMP * cur) - pRestore), 0, p.fMax);
            cur = next;
        }
        return new double[][]{f, ph};
    }

    /** Small ridge-regularised least squares, pulled toward a prior. */
    static double[] ridgeSolve(double[][] x, double[] y, double[] w, double[] prior, double lambda) {
        int k = prior.length;
        double[][] a = new double[k][k];
        double[] b = new double[k];
        for (int i = 0; i < x.length; i++)
            for (int r = 0; r < k; r++) {
                b[r] += w[i] * x[i][r] * y[i];
                for (int c = 0; c < k; c++) a[r][c] += w[i] * x[i][r] * x[i][c];
            }
        for (int r = 0; r < k; r++) { a[r][r] += lambda; b[r] += lambda * prior[r]; }

        double[][] m = new double[k][k + 1];
        for (int r = 0; r < k; r++) {
            System.arraycopy(a[r], 0, m[r], 0, k);
            m[r][k] = b[r];
        }
        for (int c = 0; c < k; c++) {
            int piv = c;
            for (int r = c + 1; r < k; r++) if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) piv = r;
            if (Math.abs(m[piv][c]) < 1e-9) return prior.clone();
            double[] tmp = m[c]; m[c] = m[piv]; m[piv] = tmp;
            for (int r = 0; r < k; r++) {
                if (r == c) continue;
                double f = m[r][c] / m[c][c];
                for (int j = c; j <= k; j++) m[r][j] -= f * m[c][j];
            }
        }
        double[] out = new double[k];
        for (int i = 0; i < k; i++) out[i] = m[i][k] / m[i][i];
        return out;
    }
}

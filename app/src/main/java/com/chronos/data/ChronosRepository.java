package com.chronos.data;

import com.chronos.data.dao.Daos;
import com.chronos.data.entity.Entities.*;
import com.chronos.engine.Calibrator;
import com.chronos.engine.DaySequencer;
import com.chronos.engine.Energy;
import com.chronos.engine.Feasibility;
import com.chronos.engine.HorizonPlanner;
import com.chronos.engine.RestAdvisor;
import com.chronos.engine.Sacrifice;
import com.chronos.model.*;
import com.chronos.model.Enums.GoalMode;
import com.chronos.model.Enums.LogKind;
import com.chronos.validate.Readiness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The only thing the UI talks to. Every method blocks; callers run it on a
 * background executor.
 *
 * The rule that shapes this class: a plan is stored, not recomputed. Reopening
 * the app should never quietly reshuffle next week. A new horizon is solved in
 * exactly three cases - the day was closed out, progress or a deadline changed,
 * or the user pressed rebuild - and anything else reads the snapshot.
 */
public class ChronosRepository {

    /**
     * A solved horizon plus the identity of the configuration that produced it.
     * {@code cuts} is what was given up for deadlines, per day index (see
     * {@link Sacrifice}); empty maps when nothing was.
     */
    public record PlanBundle(long snapshotId, LocalDate start, HorizonPlanner.Plan plan,
                             String configHash, boolean stale, List<Map<String, Double>> cuts) {

        /** The cuts of one date, or none. */
        public Map<String, Double> cutsOn(LocalDate date) {
            int i = (int) ChronoUnit.DAYS.between(start, date);
            return cuts != null && i >= 0 && i < cuts.size() ? cuts.get(i) : Map.of();
        }

        public PlanBundle markStale() {
            return new PlanBundle(snapshotId, start, plan, configHash, true, cuts);
        }
    }

    public record State(LocalDate today, LocalDate lastCalibration,
                        LocalDate lastAutoCalibration, Long calendarId, LocalDate syncedThrough) {}

    private final Daos.ConfigDao configDao;
    private final Daos.LogDao logDao;
    private final Daos.PlanDao planDao;
    private final Daos.StateDao stateDao;
    /** The Navigator's tables in the same database: read here only for the days marked unusual. */
    private final com.chronos.navigator.store.NavDao navDao;

    public ChronosRepository(ChronosDb db) {
        this.configDao = db.config();
        this.logDao = db.logs();
        this.planDao = db.plans();
        this.stateDao = db.state();
        this.navDao = db.nav();
    }

    // ------------------------------------------------------------------ config

    public Config loadConfig() {
        Config cfg = Mappers.toConfig(configDao.params(), configDao.goals(),
                                      configDao.activities(), configDao.commitments());
        // Goals as umbrellas: anything not yet brought over (an older configuration, or a target the
        // Navigator just made) is, once, here — whoever reads the configuration first.
        boolean pending = false;
        for (com.chronos.model.Goal g : cfg.goals) if (g.groupName == null) { pending = true; break; }
        if (!pending) {
            java.util.Map<String, Integer> per = new java.util.HashMap<>();
            for (com.chronos.model.Activity a : cfg.activities)
                if (a.kind == com.chronos.model.Enums.ActivityKind.GOAL && a.goalId != null) per.merge(a.goalId, 1, Integer::sum);
            for (int n : per.values()) if (n > 1) { pending = true; break; }
        }
        if (pending) synchronized (this) {
            Map<String, Double> comparisons = comparisons();
            if (com.chronos.engine.Umbrellas.apply(cfg, comparisons)) {
                saveConfig(cfg);
                configDao.dropComparisons("%");
                for (Map.Entry<String, Double> e : comparisons.entrySet()) {
                    ComparisonRow r = new ComparisonRow();
                    r.pairKey = e.getKey(); r.ratio = e.getValue();
                    configDao.putComparison(r);
                }
            }
        }
        // Targets without a priority level — all of them, the first time — are placed once.
        boolean unplaced = false;
        for (com.chronos.model.Goal g : cfg.goals) if (g.tier == null) { unplaced = true; break; }
        if (unplaced) synchronized (this) { saveConfig(cfg); }
        cfg.planFrom = planFrom();
        return cfg;
    }

    /** The moment the plan was built from, when an hour was given; null for the whole first day. */
    public java.time.LocalDateTime planFrom() {
        AppStateRow r = stateDao.get();
        if (r == null || r.planFrom == null) return null;
        try { return java.time.LocalDateTime.parse(r.planFrom); } catch (Exception e) { return null; }
    }

    /** Written as one transaction: a half-saved configuration reads as a set of
     *  missing answers and would lock the solver for no reason. */
    public void saveConfig(Config cfg) {
        // Weights follow the priority levels and places; a target without one is placed (26 Oct 2026).
        com.chronos.engine.Priorities.settle(cfg, logicalToday());
        configDao.replaceAll(Mappers.toRow(cfg.params), Mappers.goalRows(cfg),
                             Mappers.activityRows(cfg), Mappers.commitmentRows(cfg));
    }

    public Map<String, Double> comparisons() {
        Map<String, Double> out = new HashMap<>();
        for (ComparisonRow r : configDao.comparisons()) out.put(r.pairKey, r.ratio);
        return out;
    }

    public void putComparison(String a, String b, double ratio) {
        ComparisonRow r = new ComparisonRow();
        r.pairKey = a + "|" + b;
        r.ratio = ratio;
        configDao.putComparison(r);
    }

    /** A deleted goal leaves dangling judgements behind; drop them with it. */
    public void dropComparisonsFor(String goalId) {
        configDao.dropComparisons(goalId + "|%");
        configDao.dropComparisons("%|" + goalId);
    }

    public List<Readiness.Issue> readiness(Config cfg) {
        return Readiness.check(cfg, today());
    }

    // ------------------------------------------------------------------- state

    /**
     * When the day ends, in minutes past midnight. A day that ends at 03:00
     * still owns the hour after midnight: someone up until one is logging the
     * evening they are still in, not a morning that has not started. Set from
     * the user's preference by the Android side.
     */
    public static volatile int dayEndMinutes = 180;

    /** The date the user is living in, with the day boundary at {@link #dayEndMinutes}. */
    public static LocalDate logicalToday() {
        int end = dayEndMinutes;
        // An end before noon belongs to the next morning; one after noon cuts the evening short.
        long shift = end <= 720 ? -end : 1440 - end;
        return LocalDateTime.now().plusMinutes(shift).toLocalDate();
    }

    public State state() {
        AppStateRow r = stateDao.get();
        if (r == null) return new State(logicalToday(), null, null, null, null);
        return new State(
            r.todayAnchor == null ? logicalToday() : LocalDate.parse(r.todayAnchor),
            r.lastCalibration == null ? null : LocalDate.parse(r.lastCalibration),
            r.lastAutoCalibration == null ? null : LocalDate.parse(r.lastAutoCalibration),
            r.calendarId,
            r.syncedThrough == null ? null : LocalDate.parse(r.syncedThrough));
    }

    public LocalDate today() {
        State s = state();
        // never plan from a date already in the past
        LocalDate now = logicalToday();
        return s.today().isBefore(now) ? now : s.today();
    }

    private void mutateState(java.util.function.Consumer<AppStateRow> edit) {
        AppStateRow r = stateDao.get();
        if (r == null) { r = new AppStateRow(); r.todayAnchor = logicalToday().toString(); }
        edit.accept(r);
        stateDao.put(r);
    }

    public void setCalendarId(long id) { mutateState(r -> r.calendarId = id); }

    /** Days given over to rest, oldest first. */
    public java.util.TreeSet<LocalDate> restDays() {
        java.util.TreeSet<LocalDate> out = new java.util.TreeSet<>();
        AppStateRow r = stateDao.get();
        if (r == null || r.restDays == null || r.restDays.isBlank()) return out;
        for (String s : r.restDays.split(",")) if (!s.isBlank()) out.add(LocalDate.parse(s.trim()));
        return out;
    }

    /** Makes a day a rest day or a normal one again. A month of history is kept. */
    public void setRestDay(LocalDate date, boolean rest) {
        java.util.TreeSet<LocalDate> days = restDays();
        if (rest) days.add(date); else days.remove(date);
        LocalDate keepFrom = logicalToday().minusDays(PAST_DAYS_KEPT);
        String joined = String.join(",", days.stream().filter(d -> !d.isBefore(keepFrom))
            .map(LocalDate::toString).toList());
        mutateState(r -> r.restDays = joined);
    }

    public boolean isRestDay(LocalDate date) { return restDays().contains(date); }

    /** Rest days as a what-if sees them; the stored ones otherwise. */
    private final ThreadLocal<java.util.TreeSet<LocalDate>> restWhatIf = new ThreadLocal<>();

    private java.util.TreeSet<LocalDate> restSet() {
        java.util.TreeSet<LocalDate> w = restWhatIf.get();
        return w != null ? w : restDays();
    }

    /**
     * The plan Chronos would make for a configuration and set of rest days,
     * and the first {@code days} days arranged — without storing anything.
     * This is how the Navigator measures a change to Chronos before you
     * approve it.
     */
    public record WhatIf(LocalDate start, HorizonPlanner.Plan plan, List<DaySequencer.Result> days, List<Boolean> rest) {}

    public synchronized WhatIf whatIf(Config cfg, java.util.Set<LocalDate> rest, int days) {
        double[] keep = scale;
        restWhatIf.set(new java.util.TreeSet<>(rest));
        try {
            LocalDate start = today();
            Solved solved = solve(cfg, start);
            String hash = Mappers.configHash(cfg);
            List<DaySequencer.Result> out = new ArrayList<>();
            List<Boolean> restFlags = new ArrayList<>();
            for (int i = 0; i < Math.min(days, solved.plan().allocation().size()); i++) {
                LocalDate date = start.plusDays(i);
                boolean r = rest.contains(date);
                out.add(DaySequencer.run(cfg.on(date), com.chronos.engine.Slots.dow(date), solved.plan().allocation().get(i),
                    seedFor(date, hash), cutOf(solved.cuts(), i), r));
                restFlags.add(r);
            }
            return new WhatIf(start, solved.plan(), out, restFlags);
        } finally {
            restWhatIf.remove();
            scale = keep;
        }
    }

    /** This week's load against a normal one, for the check-in and the rest decision. */
    public com.chronos.engine.WeeklyLoad.Report weeklyLoad(Config cfg) {
        return com.chronos.engine.WeeklyLoad.report(cfg, logicalToday(), logs());
    }
    public void setSyncedThrough(LocalDate d) { mutateState(r -> r.syncedThrough = d.toString()); }

    /**
     * Where the next plan begins. A date in the future holds the plan back
     * until then; nothing is closed out before it. A past date means today.
     */
    public void setPlanStart(LocalDate start) { setPlanStart(start, null); }

    /**
     * @param time "HH:MM" on the first day: nothing is placed before it that day (your request of
     *             22 Oct 2026). Null: the whole first day, as before.
     */
    public void setPlanStart(LocalDate start, String time) {
        LocalDate now = logicalToday();
        LocalDate d = start == null || start.isBefore(now) ? now : start;
        String from = time == null ? null : d + "T" + time;
        mutateState(r -> { r.todayAnchor = d.toString(); r.planFrom = from; });
    }

    /**
     * Called once, the first time automatic close-out runs. Days that went by
     * before it existed were never closed, and their progress may already have
     * been typed in by hand; replaying them would count it twice. So the
     * anchor simply catches up to today without processing them.
     */
    public void startAutoCloseFromToday() {
        LocalDate now = logicalToday();
        if (state().today().isBefore(now)) mutateState(r -> r.todayAnchor = now.toString());
    }

    // ------------------------------------------------------------------ horizon

    /**
     * The stored plan, or null if there is none. A snapshot is reported stale
     * rather than discarded: the user is told the plan no longer matches their
     * settings and offered a rebuild, which is far less disorienting than the
     * week silently rearranging itself.
     */
    public PlanBundle storedPlan(Config cfg) {
        SnapshotRow row = planDao.latest();
        if (row == null) return null;
        LocalDate start = LocalDate.parse(row.startDate);
        String hash = Mappers.configHash(cfg);
        boolean stale = !hash.equals(row.configHash) || !start.equals(today());

        List<Map<String, Double>> alloc = Codec.parseHorizon(row.allocation);
        List<Map<String, Double>> cuts = Codec.parseHorizon(row.cuts);
        List<HorizonPlanner.DayCapacity> days = new ArrayList<>();
        for (int i = 0; i < alloc.size(); i++)
            days.add(HorizonPlanner.capacityOf(cfg, start.plusDays(i), i,
                i < cuts.size() ? cuts.get(i) : Map.of()));

        HorizonPlanner.Plan plan = new HorizonPlanner.Plan(days, alloc,
            List.of(), List.of(), List.of(), new double[alloc.size()]);
        return new PlanBundle(row.id, start, plan, row.configHash, stale, cuts);
    }

    /**
     * How far ahead the deliverability pass sequences days. The horizon can run
     * to ninety days; arranging all of them costs seconds, and the hours that
     * matter are the ones before a deadline.
     */
    private static final int VERIFY_DAYS = 14;

    /** Solve the horizon and store it, dropping the day plans it invalidates. */
    public synchronized PlanBundle rebuild(Config cfg) {
        LocalDate start = today();
        // Built from an hour of the day you are in (your decision of 23 Oct 2026): what was arranged
        // before that hour is behind you and stays as it was; only the rest of the day is arranged again.
        List<Block> kept = keptBefore(cfg, start);
        Solved solved = solve(cfg, start);
        HorizonPlanner.Plan plan = solved.plan();

        SnapshotRow row = new SnapshotRow();
        row.createdAt = System.currentTimeMillis();
        row.startDate = start.toString();
        row.horizonDays = cfg.params.horizonDays;
        row.configHash = Mappers.configHash(cfg);
        row.allocation = Codec.horizon(plan.allocation());
        row.violations = Codec.lines(plan.violations().stream()
            .map(v -> "By " + v.by() + ", " + String.join(" and ", v.goals()) + " need "
                    + String.format("%.1f", v.demandH()) + "h against "
                    + String.format("%.1f", v.supplyH()) + "h available").toList());
        row.shortfalls = Codec.lines(plan.shortfalls().stream()
            .map(s -> s.goal() + ": " + String.format("%.1f", s.missingH()) + "h unplaced").toList());
        row.overdue = Codec.lines(plan.overdue().stream()
            .map(s -> s.goal() + " was due " + s.deadline()).toList());
        row.cuts = Codec.horizon(solved.cuts());

        long id = planDao.put(row);
        // The days already lived keep the plan they had, so their boxes can
        // still be filled in; a month of them is kept.
        LocalDate lived = logicalToday();
        planDao.dropStaleDaysFrom(id, (start.isBefore(lived) ? start : lived).toString());
        planDao.dropBefore(lived.minusDays(PAST_DAYS_KEPT).toString());
        planDao.prune(5);
        PlanBundle bundle = new PlanBundle(id, start, plan, row.configHash, false, solved.cuts());
        // The day you are in loses its old arrangement above. Arrange it again now,
        // rather than when a screen happens to ask: if none asks before the day
        // closes, the day would be left with no kept plan and its log page empty.
        if (!start.isAfter(lived)) arrange(cfg, bundle, lived, start.equals(lived) ? kept : List.of(),
            start.equals(lived) ? keptFloor : 0);
        return bundle;
    }

    /** Where the arrangement kept by [keptBefore] ends, in slots: nothing new is placed before it. */
    private int keptFloor;

    /**
     * The first minute of a date that is still ahead (28 Oct 2026): past the hour the plan is built
     * from, and, on the day you are in, past the hour it is now. A plan built again at 23:00 — after a
     * setting changed, say — leaves the day behind you as it was: its blocks, and the entries you saved
     * under them, stay where they are. Before, the whole day was arranged anew and the evening's entries
     * were left under blocks that had moved.
     */
    public int livedFloor(Config cfg, LocalDate date) {
        int floor = cfg.dayFloor(date);
        if (!date.equals(logicalToday())) return floor;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int lived = now.toLocalDate().isAfter(date) ? 1440 : now.getHour() * 60 + now.getMinute();
        return Math.max(floor, lived);
    }

    /**
     * The blocks of today's arrangement that start before the hour the plan is built from, kept as
     * they were (classes aside: they are in every arrangement). A block running across that hour is
     * kept whole, and the plan then starts where it ends. The new arrangement is made around them.
     */
    private List<Block> keptBefore(Config cfg, LocalDate start) {
        keptFloor = 0;
        if (!start.equals(logicalToday())) return List.of();
        int floor = livedFloor(cfg, start);
        if (floor <= 0) return List.of();
        DayPlanRow old = planDao.day(start.toString());
        if (old == null) return List.of();
        int floorSlot = (floor + com.chronos.engine.Slots.SLOT_MIN - 1) / com.chronos.engine.Slots.SLOT_MIN;
        List<Block> kept = new ArrayList<>();
        int end = floorSlot;
        for (Block b : withoutRepeats(Codec.parseBlocks(old.blocks))) {
            if (b.start < 0 || b.start >= floorSlot || b.fixed || b.gap || tripNotMade(cfg, b, start)) continue;
            kept.add(b);
            end = Math.max(end, b.start + b.dur);
        }
        if (end > floorSlot && cfg.planFrom != null && cfg.dayFloor(start) > 0) {
            int m = Math.min(end * com.chronos.engine.Slots.SLOT_MIN, 1439);
            java.time.LocalDateTime from = start.atTime(m / 60, m % 60);
            cfg.planFrom = from;
            mutateState(r -> r.planFrom = from.toString());
        }
        // The hours already lived are not given out again: the horizon sees today from here on.
        int m = Math.min(Math.max(end, floorSlot) * com.chronos.engine.Slots.SLOT_MIN, 1439);
        if (cfg.dayFloor(start) < m) cfg.planFrom = start.atTime(m / 60, m % 60);
        keptFloor = Math.max(end, floorSlot);
        return kept;
    }

    /**
     * A trip leg the settings no longer make that day (29 Oct 2026): kept from an older arrangement, it
     * would hold a commute on a day without classes — and over the reading planned beside it.
     */
    private static boolean tripNotMade(Config cfg, Block b, LocalDate date) {
        if (b.name == null || !b.away) return false;
        int dow = com.chronos.engine.Slots.dow(date);
        for (Commitment c : cfg.commitments)
            if (c.kind == com.chronos.model.Enums.CommitmentKind.VARIABLE
                    && (b.name.equals(c.name + " (out)") || b.name.equals(c.name + " (back)")))
                return !c.travelledOn(dow);
        return false;
    }

    /** How far back a day's plan is kept for filling in its log late. */
    public static final int PAST_DAYS_KEPT = 30;

    private record Solved(HorizonPlanner.Plan plan, List<Map<String, Double>> cuts) {}

    /** Rounds of cutting before giving up; each round tries every short goal once. */
    private static final int CUT_ROUNDS = 4;

    /**
     * The arranged plan, then — if a dated goal is still short and the user
     * has marked anything that may give way — cuts, one short goal at a time.
     * A goal's cuts are kept only when re-planning shows they recovered hours
     * for it without costing another goal, so nothing is sacrificed for a goal
     * that freed time cannot help (one capped by its own daily ceiling, say).
     */
    /**
     * Each day's share of a normal day's capacity: nothing on a rest day, and
     * on the days just after one, the given ramp — so coming back from rest
     * starts gently instead of with every deadline's catch-up at once.
     */
    private double[] scaleFor(LocalDate start, int days, double[] ramp) {
        java.util.TreeSet<LocalDate> rest = restSet();
        double[] out = new double[days];
        for (int i = 0; i < days; i++) {
            LocalDate d = start.plusDays(i);
            if (rest.contains(d)) { out[i] = 0; continue; }
            LocalDate last = rest.lower(d);
            long since = last == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(last, d);
            out[i] = since >= 1 && since <= ramp.length ? ramp[(int) since - 1] : 1;
        }
        return out;
    }

    /**
     * The gentlest return from rest that costs no deadline: each ramp is tried,
     * gentlest first, and the first that leaves no more work unplaced than
     * coming straight back would is kept.
     */
    private double[] chooseScale(Config cfg, LocalDate start) {
        int n = Math.max(1, cfg.params.horizonDays);
        double[] full = scaleFor(start, n, new double[0]);
        double[] gentlest = scaleFor(start, n, RestAdvisor.RAMPS[0]);
        if (java.util.Arrays.equals(full, gentlest)) return full;
        double baseline = totalMissing(HorizonPlanner.plan(cfg, start, Map.of(), List.of(), full));
        for (double[] ramp : RestAdvisor.RAMPS) {
            double[] s = scaleFor(start, n, ramp);
            if (totalMissing(HorizonPlanner.plan(cfg, start, Map.of(), List.of(), s)) <= baseline + 0.05) return s;
        }
        return full;
    }

    private double[] scale = new double[0];

    private Solved solve(Config cfg, LocalDate start) {
        scale = chooseScale(cfg, start);
        List<Map<String, Double>> cuts = List.of();
        HorizonPlanner.Plan plan = deliverablePlan(cfg, start, cuts);
        if (!Sacrifice.anyCuttable(cfg)) return new Solved(plan, cuts);

        for (int round = 0; round < CUT_ROUNDS && !plan.shortfalls().isEmpty(); round++) {
            boolean progressed = false;
            for (HorizonPlanner.Shortfall s : List.copyOf(plan.shortfalls())) {
                double before = Sacrifice.missingH(plan, s.goal());
                if (before <= 0.05) continue;
                List<Map<String, Double>> next = Sacrifice.extend(cfg, start, plan, cuts, s.goal());
                if (count(next) == count(cuts)) continue;
                HorizonPlanner.Plan trial = deliverablePlan(cfg, start, next);
                boolean helped = Sacrifice.missingH(trial, s.goal()) < before - 0.1
                    && totalMissing(trial) < totalMissing(plan) - 0.1;
                if (helped) { cuts = next; plan = trial; progressed = true; }
            }
            if (!progressed) break;
        }
        return new Solved(plan, cuts);
    }

    private static int count(List<Map<String, Double>> cuts) {
        int n = 0;
        for (Map<String, Double> m : cuts) n += m.size();
        return n;
    }

    private static double totalMissing(HorizonPlanner.Plan plan) {
        double s = 0;
        for (HorizonPlanner.Shortfall f : plan.shortfalls()) s += f.missingH();
        return s;
    }

    private static Map<String, Double> cutOf(List<Map<String, Double>> cuts, int i) {
        return i < cuts.size() ? cuts.get(i) : Map.of();
    }

    /**
     * Allocate, arrange, then allocate again knowing what the arrangement could
     * actually hold.
     *
     * The capacity model counts a day's free hours. It does not know that an
     * activity's allowed window, after a class and a meal and the trip home,
     * has no unbroken stretch left long enough for one session. So the first
     * allocation can hand a day hours it cannot arrange, and the day quietly
     * drops them. Sequencing the days once and feeding the measurement back as
     * a per-day ceiling lets the water-filling move those hours onto a day that
     * can hold them.
     *
     * One correction pass, not a loop to convergence: the second allocation is
     * measurably better, a third is not worth seconds of the user's time, and
     * whatever still does not fit is reported rather than hidden.
     */
    private HorizonPlanner.Plan deliverablePlan(Config cfg, LocalDate start, List<Map<String, Double>> cuts) {
        HorizonPlanner.Plan plan = HorizonPlanner.plan(cfg, start, Map.of(), cuts, scale);
        String hash = Mappers.configHash(cfg);
        int days = Math.min(VERIFY_DAYS, plan.allocation().size());

        Map<Integer, Map<String, Double>> caps = new HashMap<>();
        boolean anyCap = false;
        for (int i = 0; i < days; i++) {
            LocalDate date = start.plusDays(i);
            Map<String, Double> demand = plan.allocation().get(i);
            if (demand.isEmpty()) continue;
            DaySequencer.Result r = DaySequencer.run(
                cfg.on(date), com.chronos.engine.Slots.dow(date), demand, seedFor(date, hash), cutOf(cuts, i),
                restSet().contains(date));
            if (r.undelivered().isEmpty()) continue;

            Map<String, Double> forDay = new HashMap<>();
            for (Map.Entry<String, Double> e : r.undelivered().entrySet())
                forDay.put(e.getKey(), Math.max(0, demand.getOrDefault(e.getKey(), 0.0) - e.getValue()));
            caps.put(i, forDay);
            anyCap = true;
        }
        return anyCap ? HorizonPlanner.plan(cfg, start, caps, cuts, scale) : plan;
    }

    /** Advancing the date is the whole re-planning mechanism: whatever was not
     *  finished stays in the remaining count and redistributes itself. */
    public PlanBundle closeOutDay(Config cfg) {
        LocalDate next = today().plusDays(1);
        mutateState(r -> r.todayAnchor = next.toString());
        return rebuild(cfg);
    }

    // ----------------------------------------------------------------- one day

    /**
     * Same day and same configuration must give the same timetable. Seeding the
     * local search from the clock would reshuffle the plan on every open, which
     * is worse than a slightly weaker plan you can rely on.
     */
    public static long seedFor(LocalDate date, String configHash) {
        return date.toEpochDay() * 1_000_003L + configHash.hashCode();
    }

    /**
     * The plan a day that has already begun was given, whatever snapshot it came
     * from; null when none was kept (days before this was stored).
     */
    public DaySequencer.Result pastDay(Config cfg, LocalDate date) {
        DayPlanRow cached = planDao.day(date.toString());
        if (cached != null) return fromRow(cached, cfg, Map.of());
        DaySequencer.Result rebuilt = fromLog(cfg, date);
        if (rebuilt != null) store(date, -1, 0, rebuilt);
        return rebuilt;
    }

    /**
     * Makes sure a day about to close has its plan kept. A rebuild earlier the
     * same day may have dropped it; the latest snapshot still covers the day,
     * so it is arranged from that.
     */
    private void keepDay(Config cfg, LocalDate date) {
        if (planDao.day(date.toString()) != null) return;
        PlanBundle latest = storedPlan(cfg);
        if (latest == null) return;
        long index = ChronoUnit.DAYS.between(latest.start(), date);
        if (index < 0 || index >= latest.plan().allocation().size()) return;
        dayPlan(cfg, latest, date);
    }

    /**
     * A day whose plan was lost, rebuilt from what was logged against it: every
     * session and trip entry names its block's activity and start slot, and the
     * classes come from the timetable. What was planned but never logged cannot
     * be recovered and is not invented.
     */
    DaySequencer.Result fromLog(Config cfg, LocalDate date) { return fromLog(cfg, date, logs()); }

    public static DaySequencer.Result fromLog(Config cfg, LocalDate date, List<LogEntry> logs) {
        Map<String, Block> byKey = new java.util.LinkedHashMap<>();
        String day = date.toString();
        for (LogEntry l : logs) {
            if (l.id == null || !day.equals(String.valueOf(l.date))) continue;
            String[] f = l.id.split("\\|");
            if (f.length < 4) continue;
            int slot;
            try { slot = Integer.parseInt(f[3]); } catch (NumberFormatException e) { continue; }
            if (slot < 0 || slot >= com.chronos.engine.Slots.PER_DAY) continue;
            Block b = new Block();
            b.start = slot;
            if (f[0].equals("session") || f[0].equals("instead")) {
                Activity a = cfg.activity(f[2]);
                if (a == null) continue;
                b.name = a.name;
                b.goalId = a.goalId;
                b.load = a.load == null ? 0 : a.load; b.recovery = a.recovery == null ? 0 : a.recovery;
                b.physLoad = a.physLoad == null ? 0 : a.physLoad; b.physRecovery = a.physRecovery == null ? 0 : a.physRecovery;
                b.leisure = a.leisure; b.dopamine = a.leisure && a.dopamine != null ? a.dopamine : 0;
                // The planned length lives on the block's own entry; an "instead" carries its own.
                Integer minutes = f[0].equals("session") ? l.planned : null;
                b.dur = Math.max(1, (minutes == null ? 30 : minutes) / com.chronos.engine.Slots.SLOT_MIN);
            } else if (f[0].equals("trip")) {
                Commitment c = null;
                for (Commitment x : cfg.commitments) if (x.id.equals(f[2])) c = x;
                if (c == null) continue;
                b.name = c.name + (slot < com.chronos.engine.Slots.PER_DAY / 2 ? " (out)" : " (back)");
                b.away = true;
                b.load = c.load == null ? 0 : c.load; b.recovery = c.recovery == null ? 0 : c.recovery;
                b.physLoad = c.physLoad == null ? 0 : c.physLoad; b.physRecovery = c.physRecovery == null ? 0 : c.physRecovery;
                int minutes = l.actual != null ? l.actual : c.medianMin == null ? 30 : c.medianMin.intValue();
                b.dur = Math.max(1, minutes / com.chronos.engine.Slots.SLOT_MIN);
            } else continue;
            String key = f[0].equals("trip") ? "trip|" + f[2] + "|" + slot : "act|" + f[2] + "|" + slot;
            Block old = byKey.get(key);
            if (old == null || (f[0].equals("session") && b.dur > 0)) byKey.put(key, b);
        }
        if (byKey.isEmpty()) return null;
        List<Block> blocks = new ArrayList<>(byKey.values());
        int dow = com.chronos.engine.Slots.dow(date);
        for (Commitment c : cfg.commitments) {
            if (c.kind != com.chronos.model.Enums.CommitmentKind.FIXED) continue;
            for (Commitment.Session ses : c.sessions) {
                if (ses.day != dow) continue;
                int a = com.chronos.engine.Slots.parse(ses.start), z = com.chronos.engine.Slots.parse(ses.end);
                if (z <= a) continue;
                Block b = new Block();
                b.name = c.name; b.start = a; b.dur = z - a; b.fixed = true; b.away = c.away;
                b.load = ses.loadOr(c.load); b.recovery = ses.recoveryOr(c.recovery);
                b.physLoad = ses.physLoadOr(c.physLoad); b.physRecovery = ses.physRecoveryOr(c.physRecovery);
                blocks.add(b);
            }
        }
        blocks.sort((x, y) -> Integer.compare(x.start, y.start));
        Energy.Trace trace = Energy.simulate(blocks, cfg.params, Energy.homeostatic(cfg.params));
        return new DaySequencer.Result(blocks, List.of(), List.of(),
            List.of("This day's plan was not kept; its blocks were rebuilt from what you logged."),
            new com.chronos.engine.Objective.Score(0, 0, 0, 0, trace.fatigue(), trace.physical(), trace.capacity(), trace.wastedRecoveryH(), trace.dopamine()),
            0, 0, 0, 0, 0, Map.of());
    }

    public DaySequencer.Result dayPlan(Config cfg, PlanBundle bundle, LocalDate date) {
        DayPlanRow cached = planDao.day(date.toString());
        long seed = seedFor(date, bundle.configHash());
        Map<String, Double> demand = allocationFor(bundle, date);
        if (cached != null && cached.snapshotId == bundle.snapshotId() && cached.seed == seed)
            return fromRow(cached, cfg, demand);
        // Arranged again during the day it is: what it held before the hour it is built from stays.
        List<Block> kept = cached == null ? List.of() : keptIn(cached, cfg, date);
        int floor = livedFloor(cfg, date);
        return arrange(cfg, bundle, date, kept, kept.isEmpty() ? 0 : (floor + com.chronos.engine.Slots.SLOT_MIN - 1) / com.chronos.engine.Slots.SLOT_MIN);
    }

    /**
     * Arranges a day and keeps the result. [kept] is what the day already held before the hour it is
     * built from (26 Oct 2026): the sequencer works around it and counts it — a commute already
     * travelled, a lunch already eaten, goal work already given its hours — instead of laying a
     * second plan over the first.
     */
    private DaySequencer.Result arrange(Config cfg, PlanBundle bundle, LocalDate date, List<Block> kept, int floorSlot) {
        long seed = seedFor(date, bundle.configHash());
        // A stored block carries no output curve: goal work kept from the morning gets its activity's back
        // (from the whole configuration — the day's own may no longer list it), or it would yield nothing.
        for (Block k : kept)
            for (Activity a : cfg.activities)
                if (k.act == null && a.name != null && a.name.equals(k.name) && (k.goalId == null || k.goalId.equals(a.goalId))) {
                    k.act = a;
                    k.tab = com.chronos.engine.YieldCurve.table(a);
                    k.qPlan = Math.max(0.05, a.q - cfg.params.lcbBeta * a.qSigma);
                }
        DaySequencer.Result r = DaySequencer.run(cfg.on(date), com.chronos.engine.Slots.dow(date), allocationFor(bundle, date),
            seed, bundle.cutsOn(date), isRestDay(date), kept, floorSlot);
        store(date, bundle.snapshotId(), seed, r);
        return r;
    }

    /** The blocks of a stored arrangement of today that start before the hour the plan is built from. */
    private List<Block> keptIn(DayPlanRow row, Config cfg, LocalDate date) {
        if (!date.equals(logicalToday())) return List.of();
        int floor = livedFloor(cfg, date);
        if (floor <= 0) return List.of();
        int floorSlot = (floor + com.chronos.engine.Slots.SLOT_MIN - 1) / com.chronos.engine.Slots.SLOT_MIN;
        List<Block> kept = new ArrayList<>();
        for (Block b : withoutRepeats(Codec.parseBlocks(row.blocks)))
            if (b.start >= 0 && b.start < floorSlot && !b.fixed && !b.gap && !tripNotMade(cfg, b, date)) kept.add(b);
        return kept;
    }

    /** What the horizon gave this date, or nothing if the date is outside it. */
    public Map<String, Double> allocationFor(PlanBundle bundle, LocalDate date) {
        int index = (int) ChronoUnit.DAYS.between(bundle.start(), date);
        return index >= 0 && index < bundle.plan().allocation().size()
            ? bundle.plan().allocation().get(index) : Map.of();
    }

    private void store(LocalDate date, long snapshotId, long seed, DaySequencer.Result r) {
        DayPlanRow row = new DayPlanRow();
        row.date = date.toString();
        row.snapshotId = snapshotId;
        row.seed = seed;
        row.blocks = Codec.blocks(r.blocks());
        // What could not be placed is kept with the gaps (start -1 tells them
        // apart), so a day read back from storage still says what is missing
        // and why. Without it the reasons were lost after the first solve.
        List<Block> gapsAndMisses = new ArrayList<>(r.gaps());
        gapsAndMisses.addAll(r.unplaced());
        row.gaps = Codec.blocks(gapsAndMisses);
        row.warnings = Codec.lines(r.warnings());
        row.effectiveMinutes = r.effectiveMinutes();
        row.elapsedMinutes = r.elapsedMinutes();
        row.excess = r.score().excess();
        row.goalBlocks = (int) r.blocks().stream().filter(b -> b.goalId != null).count();
        planDao.putDay(row);
    }

    /** Rebuild a result from cache. Only what the timetable needs is restored;
     *  the engine's internals are not worth persisting. The capacity and
     *  fatigue curves are not stored either — they are a pure function of the
     *  placed blocks and the body-clock parameters, so they are re-simulated
     *  here, which is what keeps the day plot drawn after a restart. */
    private DaySequencer.Result fromRow(DayPlanRow row, Config cfg, Map<String, Double> demand) {
        List<Block> blocks = withoutRepeats(Codec.parseBlocks(row.blocks));
        List<Block> gaps = new ArrayList<>(), unplaced = new ArrayList<>();
        for (Block b : Codec.parseBlocks(row.gaps)) (b.start < 0 ? unplaced : gaps).add(b);
        Energy.Trace trace = Energy.simulate(blocks, cfg.params, Energy.homeostatic(cfg.params));
        return new DaySequencer.Result(blocks, gaps, unplaced,
            Codec.parseLines(row.warnings),
            new com.chronos.engine.Objective.Score(0, 0, row.excess, row.goalBlocks,
                trace.fatigue(), trace.physical(), trace.capacity(), trace.wastedRecoveryH(), trace.dopamine()),
            row.effectiveMinutes, row.elapsedMinutes, 0, 0, 0,
            undeliveredOf(blocks, demand));
    }

    /**
     * A day stored before 26 Oct 2026 by a rebuild during the day can hold the morning twice over:
     * the same leg of a trip or the same session on top of itself. The later copy is dropped when
     * it is read; the stored row is left as it was.
     */
    static List<Block> withoutRepeats(List<Block> blocks) {
        List<Block> out = new ArrayList<>();
        for (Block b : blocks) {
            boolean repeat = false;
            for (Block o : out)
                if (o.start >= 0 && b.start >= 0 && o.name != null && o.name.equals(b.name)
                        && b.start < o.start + o.dur && o.start < b.start + b.dur) { repeat = true; break; }
            if (!repeat) out.add(b);
        }
        return out;
    }

    /**
     * Hours a day was given but did not place, derived from what is stored
     * rather than stored itself: the blocks and the allocation are both already
     * here, and a new column would mean a migration on a schema that holds the
     * only copy of the log.
     */
    private static Map<String, Double> undeliveredOf(List<Block> blocks, Map<String, Double> demand) {
        Map<String, Double> placed = new HashMap<>();
        for (Block b : blocks)
            if (b.goalId != null && b.start >= 0)
                placed.merge(b.goalId, b.dur * com.chronos.engine.Slots.SLOT_MIN / 60.0, Double::sum);
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Double> e : demand.entrySet()) {
            double short_ = e.getValue() - placed.getOrDefault(e.getKey(), 0.0);
            if (short_ > 0.01) out.put(e.getKey(), short_);
        }
        return out;
    }

    /** What did not fit on one day, and what the day had to bend to fit the rest. */
    public record DayProblems(LocalDate date, List<Missing> missing, List<String> notes) {}

    /** One thing left out of a day: its name, and why with what to do about it. */
    public record Missing(String name, String reason) {}

    /**
     * The coming days' trouble, for the Horizon screen: everything left out,
     * with its reason and fix, and everything that had to move or shrink.
     * Read from the arranged days themselves, so it is what will actually
     * happen, not an estimate.
     */
    public List<DayProblems> dayProblems(Config cfg, PlanBundle bundle, int days) {
        List<DayProblems> out = new ArrayList<>();
        if (bundle == null) return out;
        LocalDate from = bundle.start().isAfter(logicalToday()) ? bundle.start() : logicalToday();
        for (int i = 0; i < days; i++) {
            LocalDate date = from.plusDays(i);
            DaySequencer.Result r = dayPlan(cfg, bundle, date);
            List<Missing> missing = new ArrayList<>();
            for (Block b : r.unplaced()) missing.add(new Missing(b.name, b.reason == null ? "" : b.reason));
            if (!missing.isEmpty() || !r.warnings().isEmpty())
                out.add(new DayProblems(date, missing, r.warnings()));
        }
        return out;
    }

    /** The deadline check and its suggestions, for the Horizon screen. */
    public com.chronos.engine.Feasibility.Report feasibility(Config cfg) {
        // Nothing to check while Setup still lacks an answer (an activity's target being filled in).
        if (!Readiness.canSolve(readiness(cfg)))
            return new com.chronos.engine.Feasibility.Report(java.util.List.of(), java.util.List.of());
        return com.chronos.engine.Feasibility.analyse(cfg, today());
    }

    /**
     * Whether each dated goal actually finishes, measured against the arranged
     * days rather than against the capacity model.
     *
     * The horizon's own guarantee (§7.4) is about hours existing, not about a
     * day being able to arrange them, so it can pass while the timetable loses
     * work. This reads the real thing: the day plans are already cached against
     * this snapshot, so after the first pass it costs nothing.
     */
    public List<Feasibility.Coverage> coverage(Config cfg, PlanBundle bundle) {
        List<Feasibility.Coverage> out = new ArrayList<>();
        if (bundle == null) return out;
        LocalDate start = bundle.start();
        int horizon = bundle.plan().allocation().size();

        for (Goal g : cfg.goals) {
            if (g.mode != com.chronos.model.Enums.GoalMode.CLOSED || g.remainingUnits() <= 0) continue;
            int last = g.deadline == null ? horizon - 1
                : (int) Math.max(0, Math.min(horizon - 1, ChronoUnit.DAYS.between(start, g.deadline)));

            double allocated = 0, placed = 0;
            List<Feasibility.LostDay> lost = new ArrayList<>();
            for (int i = 0; i <= last && i < horizon; i++) {
                LocalDate date = start.plusDays(i);
                double given = bundle.plan().allocation().get(i).getOrDefault(g.id, 0.0);
                if (given <= 0) continue;
                allocated += given;

                double got = 0;
                for (Block b : dayPlan(cfg, bundle, date).blocks())
                    if (g.id.equals(goalOf(cfg, b)) && b.start >= 0)
                        got += b.dur * com.chronos.engine.Slots.SLOT_MIN / 60.0;
                placed += got;
                if (given - got > 0.25) lost.add(new Feasibility.LostDay(date, given - got));
            }
            lost.sort((a, b) -> Double.compare(b.hours(), a.hours()));
            if (lost.size() > 3) lost = new ArrayList<>(lost.subList(0, 3));

            out.add(new Feasibility.Coverage(g.id, g.name, g.deadline,
                Feasibility.neededHours(cfg, g), allocated, placed, lost));
        }
        return out;
    }

    /** A stored block carries the goal id directly; this guards a stale one. */
    private static String goalOf(Config cfg, Block b) {
        return b.goalId != null && cfg.goal(b.goalId) != null ? b.goalId : null;
    }

    public List<LocalDate> unsyncedDays(LocalDate from, int days) {
        List<LocalDate> out = new ArrayList<>();
        List<DayPlanRow> rows = planDao.range(from.toString(), from.plusDays(days - 1).toString());
        for (DayPlanRow r : rows) if (!r.syncedToCalendar) out.add(LocalDate.parse(r.date));
        return out;
    }

    public void markSynced(LocalDate date) { planDao.markSynced(date.toString()); }

    // --------------------------------------------------------------------- log

    public List<LogEntry> logs() {
        List<LogEntry> out = new ArrayList<>();
        for (LogRow r : logDao.all()) out.add(Mappers.toModel(r));
        return out;
    }

    /** Saves an entry, carrying over how much of it was already counted into progress. */
    public void addLog(LogEntry entry) {
        LogRow row = Mappers.toRow(entry);
        LogRow old = logDao.byId(entry.id);
        if (old != null) row.countedUnits = old.countedUnits;
        logDao.put(row);
    }

    /**
     * Deletes an entry. If its units were already added to a goal's progress,
     * they are taken back, and true is returned so the caller reloads the goals.
     */
    public boolean removeLog(String id) {
        LogRow old = logDao.byId(id);
        logDao.removeById(id);
        if (old == null || old.countedUnits == null || old.countedUnits == 0) return false;
        Config cfg = loadConfig();
        Activity a = cfg.activity(old.actId);
        Goal g = a == null || a.goalId == null ? null : cfg.goal(a.goalId);
        if (g == null || g.mode != GoalMode.CLOSED) return false;
        g.doneUnits = Math.max(0, (g.doneUnits == null ? 0 : g.doneUnits) - old.countedUnits);
        saveConfig(cfg);
        return true;
    }

    /** Runs the fit, saves the moved parameters, and returns what changed. */
    public Calibrator.Outcome calibrate(Config cfg) {
        Calibrator.Outcome outcome = Calibrator.calibrate(cfg, learnable(logs()));
        cfg.params = outcome.params();
        cfg.activities = outcome.activities();
        cfg.commitments = outcome.commitments();
        saveConfig(cfg);
        LocalDate now = today();
        mutateState(r -> r.lastCalibration = now.toString());
        return outcome;
    }

    /** What one automatic close-out did: the days it closed and every change it made. */
    public record NightlyResult(List<LocalDate> closed, List<Calibrator.Change> report) {}

    /**
     * Close every day that has ended and was not closed yet, oldest first.
     * Each closed day adds its logged units to its goals and teaches the model
     * from its log; the anchor then moves past it. Rebuilding the horizon is
     * left to the caller, once, after the last day.
     */
    public NightlyResult closeElapsedDays(Config cfg) {
        LocalDate now = logicalToday();
        LocalDate day = state().today();
        List<LocalDate> closed = new ArrayList<>();
        List<Calibrator.Change> report = new ArrayList<>();
        while (day.isBefore(now)) {
            keepDay(cfg, day);
            report.addAll(closeDay(cfg, day));
            closed.add(day);
            LocalDate next = day.plusDays(1);
            mutateState(r -> r.todayAnchor = next.toString());
            day = next;
        }
        return new NightlyResult(closed, report);
    }

    /**
     * Apply one finished day: progress first, then what its log teaches. Late
     * entries for days closed earlier ride along, so nothing waits on the
     * Apply button forever.
     */
    private List<Calibrator.Change> closeDay(Config cfg, LocalDate day) {
        // An unusual day still counts its units (settleUnits reads the log itself); it only teaches nothing.
        List<LogEntry> all = learnable(logs());
        long applied = appliedTs();
        List<LogEntry> step = new ArrayList<>();
        for (LogEntry l : all)
            if (day.equals(l.date) || (l.date != null && l.date.isBefore(day) && l.ts > applied)) step.add(l);

        List<Calibrator.Change> report = new ArrayList<>(settleUnits(cfg, day));
        // The fit reads the body-clock answers; with any of them missing there is nothing to refine.
        if (!step.isEmpty() && Readiness.canSolve(readiness(cfg)))
            report.addAll(learnFromDay(cfg, all, step));
        saveConfig(cfg);
        long now = System.currentTimeMillis();
        mutateState(r -> { r.lastCalibration = day.toString(); r.appliedTs = now; });
        return report;
    }

    private long appliedTs() {
        AppStateRow r = stateDao.get();
        return r == null || r.appliedTs == null ? 0 : r.appliedTs;
    }

    /**
     * Brings every goal's progress in line with the units logged on days up to
     * and including {@code through}. Each session remembers how much of it was
     * already counted, so this adds only the difference: a session filled in a
     * week late adds its units, one corrected from 2 to 1.5 takes back half.
     */
    private List<Calibrator.Change> settleUnits(Config cfg, LocalDate through) {
        Map<String, Double> delta = new HashMap<>();
        for (LogRow r : logDao.all()) {
            if (!"SESSION".equals(r.kind) || r.date == null || LocalDate.parse(r.date).isAfter(through)) continue;
            double want = r.units == null ? 0 : Math.max(0, r.units);
            double had = r.countedUnits == null ? 0 : r.countedUnits;
            if (Math.abs(want - had) < 1e-9) continue;
            Activity a = cfg.activity(r.actId);
            if (a == null || a.goalId == null) continue;
            delta.merge(a.goalId, want - had, Double::sum);
            logDao.setCounted(r.id, want);
        }
        List<Calibrator.Change> report = new ArrayList<>();
        for (Map.Entry<String, Double> e : delta.entrySet()) {
            Goal g = cfg.goal(e.getKey());
            if (g == null || g.mode != GoalMode.CLOSED || Math.abs(e.getValue()) < 1e-9) continue;
            double before = g.doneUnits == null ? 0 : g.doneUnits;
            double after = Math.max(0, before + e.getValue());
            if (g.totalUnits != null) after = Math.min(g.totalUnits, after);
            g.doneUnits = after;
            report.add(new Calibrator.Change(g.name, String.format(
                "progress %.1f to %.1f %s", before, after, g.unit == null ? "" : g.unit).trim()));
        }
        return report;
    }

    /** Entries for days already closed that the model has not learned from yet. */
    public int pendingLateEntries() {
        LocalDate open = state().today();
        long applied = appliedTs();
        int n = 0;
        for (LogRow r : logDao.all()) {
            if (r.date == null || !LocalDate.parse(r.date).isBefore(open)) continue;
            boolean unsettled = "SESSION".equals(r.kind) && !java.util.Objects.equals(
                r.units == null ? 0.0 : r.units, r.countedUnits == null ? 0.0 : r.countedUnits);
            if (r.ts > applied || unsettled) n++;
        }
        return n;
    }

    /**
     * The Apply button: what was filled in or corrected for days already closed
     * goes into progress and the model now, without waiting for tonight. Leaves
     * the plan alone — the caller reports whether it has gone out of date.
     */
    public List<Calibrator.Change> applyLateEntries(Config cfg) {
        LocalDate open = state().today();
        List<LogEntry> all = learnable(logs());
        long applied = appliedTs();
        List<LogEntry> step = new ArrayList<>();
        for (LogEntry l : all)
            if (l.date != null && l.date.isBefore(open) && l.ts > applied) step.add(l);

        List<Calibrator.Change> report = new ArrayList<>(settleUnits(cfg, open.minusDays(1)));
        if (!step.isEmpty() && Readiness.canSolve(readiness(cfg)))
            report.addAll(learnFromDay(cfg, all, step));
        saveConfig(cfg);
        long now = System.currentTimeMillis();
        mutateState(r -> r.appliedTs = now);
        return report;
    }

    /**
     * Two fits, because the engine's two kinds of estimate behave differently.
     *
     * The body clock and travel times are refitted on the whole history each
     * time (the fit weights recent days itself), so feeding it everything is
     * right. An activity's quality, load and recovery are an update step
     * instead: each call moves them a fraction toward what the given sessions
     * show. Feeding that step every old session every night would count them
     * again and again, so it only sees the day that just ended.
     */
    private List<Calibrator.Change> learnFromDay(Config cfg, List<LogEntry> all, List<LogEntry> ofDay) {
        Calibrator.Outcome whole = Calibrator.calibrate(cfg.copy(), all);
        Calibrator.Outcome step = Calibrator.calibrate(cfg.copy(), ofDay);

        java.util.Set<String> activityNames = new java.util.HashSet<>();
        for (Activity a : cfg.activities) if (a.name != null) activityNames.add(a.name);

        cfg.params = whole.params();
        cfg.commitments = whole.commitments();
        cfg.activities = step.activities();
        // A trip's duration is refitted on its whole history; its fatigue load is
        // an update step like an activity's, so it comes from the step pass.
        for (Commitment c : cfg.commitments)
            for (Commitment s : step.commitments())
                if (s.id.equals(c.id)) {
                    c.load = s.load; c.recovery = s.recovery; c.physLoad = s.physLoad; c.physRecovery = s.physRecovery;
                    copySessionLoads(s, c);
                }

        List<Calibrator.Change> out = new ArrayList<>();
        for (Calibrator.Change c : whole.report())
            if (!activityNames.contains(c.subject()) && !c.detail().startsWith("fatigue load")) out.add(c);
        for (Calibrator.Change c : step.report())
            if (activityNames.contains(c.subject()) || c.detail().startsWith("fatigue load")) out.add(c);
        return out;
    }

    // ----------------------------------------------------------- unusual days

    /** Days you marked unusual (in the Log, for that day): their entries are kept but not learned from. */
    public java.util.Set<LocalDate> unusualDays() {
        java.util.Set<LocalDate> out = new java.util.HashSet<>();
        for (com.chronos.navigator.store.NavEntities.DayTypeRow r : navDao.dayTypes())
            if (r.anomaly && r.date != null) out.add(LocalDate.parse(r.date));
        return out;
    }

    /** The log the model may learn from: everything but the unusual days. */
    private List<LogEntry> learnable(List<LogEntry> all) {
        java.util.Set<LocalDate> skip = unusualDays();
        if (skip.isEmpty()) return all;
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry l : all) if (l.date == null || !skip.contains(l.date)) out.add(l);
        return out;
    }

    /**
     * A day already closed was marked unusual, or taken back (your decision of 20 Oct 2026: the
     * effect goes all the way back). The body clock and trip times are whole-history fits, so they
     * are simply refitted without the unusual days. Quality, load and recovery are update steps,
     * each night's starting from the last, so the nights from that day on are replayed twice from
     * the values as they stand, once with the day and once without, and only the difference is
     * applied. What you changed by hand in Setup since then stays.
     *
     * Call after the day's flag is stored. A day not closed yet needs nothing: its close-out reads the flag.
     */
    public List<Calibrator.Change> relearnAround(Config cfg, LocalDate day, boolean nowUnusual) {
        LocalDate open = state().today();
        if (open == null || !day.isBefore(open) || !Readiness.canSolve(readiness(cfg))) return List.of();
        List<LogEntry> all = logs();
        java.util.Set<LocalDate> skip = unusualDays();
        java.util.Set<LocalDate> withIt = new java.util.HashSet<>(skip);
        withIt.remove(day);
        java.util.Set<LocalDate> withoutIt = new java.util.HashSet<>(skip);
        withoutIt.add(day);
        Config target = replaySteps(cfg, all, day, open, nowUnusual ? withoutIt : withIt);
        Config base = replaySteps(cfg, all, day, open, nowUnusual ? withIt : withoutIt);

        List<Calibrator.Change> report = new ArrayList<>();
        for (Activity a : cfg.activities) {
            Activity t = target.activity(a.id), b = base.activity(a.id);
            if (t == null || b == null) continue;
            double oldQ = a.q, oldLoad = nz(a.load), oldRec = nz(a.recovery);
            a.q = Math.max(0.1, Math.min(2, a.q + t.q - b.q));
            a.qSigma = Math.max(0.08, a.qSigma + t.qSigma - b.qSigma);
            a.load = shifted(a.load, t.load, b.load, a.kind == Enums.ActivityKind.GOAL ? 0.02 : 0);
            a.recovery = shifted(a.recovery, t.recovery, b.recovery, 0);
            a.physLoad = shifted(a.physLoad, t.physLoad, b.physLoad, 0);
            a.physRecovery = shifted(a.physRecovery, t.physRecovery, b.physRecovery, 0);
            if (a.leisure && a.dopamine != null && t.dopamine != null && b.dopamine != null)
                a.dopamine = Math.max(0, Math.min(3, a.dopamine + t.dopamine - b.dopamine));
            if (Math.abs(a.q - oldQ) > 1e-4 || Math.abs(nz(a.load) - oldLoad) > 1e-4 || Math.abs(nz(a.recovery) - oldRec) > 1e-4)
                report.add(new Calibrator.Change(a.name, String.format(
                    "quality %.2f to %.2f, mental load %.2f to %.2f, recovery %.2f to %.2f",
                    oldQ, a.q, oldLoad, nz(a.load), oldRec, nz(a.recovery))));
        }
        Calibrator.Outcome whole = Calibrator.calibrate(cfg.copy(), learnable(all));
        cfg.params = whole.params();
        for (Commitment c : cfg.commitments) {
            Commitment t = find(target.commitments, c.id), b = find(base.commitments, c.id), w = find(whole.commitments(), c.id);
            if (t != null && b != null) {
                c.load = shifted(c.load, t.load, b.load, 0);
                c.recovery = shifted(c.recovery, t.recovery, b.recovery, 0);
                c.physLoad = shifted(c.physLoad, t.physLoad, b.physLoad, 0);
                c.physRecovery = shifted(c.physRecovery, t.physRecovery, b.physRecovery, 0);
            }
            if (w != null) { c.medianMin = w.medianMin; c.sigma = w.sigma; }
        }
        for (Calibrator.Change c : whole.report())
            if ("Body clock".equals(c.subject())) report.add(c);
        saveConfig(cfg);
        return report;
    }

    /** The update steps of the closed nights from {@code first} on, skipping {@code skip}, from {@code from}'s values. */
    private static Config replaySteps(Config from, List<LogEntry> all, LocalDate first, LocalDate open,
                                      java.util.Set<LocalDate> skip) {
        Config c = from.copy();
        for (LocalDate d = first; d.isBefore(open); d = d.plusDays(1)) {
            if (skip.contains(d)) continue;
            List<LogEntry> ofDay = new ArrayList<>();
            for (LogEntry l : all) if (d.equals(l.date)) ofDay.add(l);
            if (ofDay.isEmpty()) continue;
            Calibrator.Outcome step = Calibrator.calibrate(c.copy(), ofDay);
            c.activities = step.activities();
            for (Commitment m : c.commitments) {
                Commitment s = find(step.commitments(), m.id);
                if (s != null) { m.load = s.load; m.recovery = s.recovery; m.physLoad = s.physLoad; m.physRecovery = s.physRecovery;
                    copySessionLoads(s, m); }
            }
        }
        return c;
    }

    /** A class's sessions learn their own loads (a step, like an activity's): taken from the step pass by position. */
    private static void copySessionLoads(Commitment from, Commitment to) {
        for (int i = 0; i < Math.min(from.sessions.size(), to.sessions.size()); i++) {
            Commitment.Session a = from.sessions.get(i), b = to.sessions.get(i);
            b.load = a.load; b.recovery = a.recovery; b.physLoad = a.physLoad; b.physRecovery = a.physRecovery;
        }
    }

    private static Commitment find(List<Commitment> list, String id) {
        for (Commitment c : list) if (c.id.equals(id)) return c;
        return null;
    }

    /** {@code now} moved by what the replay says the change makes (target minus base), kept in floor..1. */
    private static Double shifted(Double now, Double target, Double base, double floor) {
        if (now == null || target == null || base == null) return now;
        double d = target - base;
        if (Math.abs(d) < 1e-9) return now;
        return Math.max(floor, Math.min(1, now + d));
    }

    private static double nz(Double v) { return v == null ? 0 : v; }

    /** Once a week, unattended. The report is kept for the user to read later
     *  rather than shown as an interruption. */
    public Calibrator.Outcome autoCalibrateIfDue(Config cfg) {
        State s = state();
        LocalDate last = s.lastAutoCalibration();
        if (last != null && ChronoUnit.DAYS.between(last, today()) < 7) return null;
        if (logDao.checkinCount() < 6) return null;

        Calibrator.Outcome outcome = calibrate(cfg);
        LocalDate now = today();
        mutateState(r -> r.lastAutoCalibration = now.toString());
        return outcome;
    }
}

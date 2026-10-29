package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Commitment;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.BlocksMode;
import com.chronos.model.Enums.CommitmentKind;
import com.chronos.model.Enums.LegMode;
import com.chronos.model.Goal;
import com.chronos.model.Params;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.chronos.engine.Slots.PER_DAY;
import static com.chronos.engine.Slots.SLOT_MIN;

/**
 * Arranges one day. The hours are already decided by the horizon planner; what
 * is decided here is where each block sits, and how many blocks there are.
 *
 * Placement runs in four phases so that travel can be constrained properly:
 * anything that takes you out of the house goes down first, then the trip that
 * wraps it, then the rest of the upkeep, then goal work.
 */
public final class DaySequencer {

    /**
     * @param undelivered hours of each goal the horizon allocated to this day
     *                    that the day could not place. Empty when the day
     *                    delivered everything it was given.
     */
    public record Result(List<Block> blocks, List<Block> gaps, List<Block> unplaced,
                         List<String> warnings, Objective.Score score,
                         double effectiveMinutes, double elapsedMinutes,
                         int freeSlots, int usedSlots, int usableSlots,
                         Map<String, Double> undelivered) {}

    /** Per minute of a goal's allocation the day could not place. */
    private static final double UNDELIVERED_PENALTY = 100;

    private final Config cfg;
    private final Params p;
    private final int dow;
    private final double[] h;
    private final Map<String, Double> weights = new HashMap<>();
    private final String[] occupant;
    private final int usableSlots;
    private final int freeSlots;
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Double> undelivered = new HashMap<>();
    private final Random rng;
    /** Activities cut today to make room for a deadline: id to minutes kept. */
    private final Map<String, Double> cut;

    private List<Block> placed = new ArrayList<>();
    private boolean[] taken;
    /** Slots held free for getting into a block, and which block: named in reports. */
    private String[] heldFor = new String[PER_DAY];
    private int used = 0;

    /** A rest day: rest-only upkeep is planned; on any other day it is not. */
    private final boolean restDay;

    /**
     * What today already held before the hour it was built again from (26 Oct 2026). Without it the new
     * arrangement did the morning over: a second commute on top of the first, a second lunch, the
     * morning's goal work again in the afternoon — two plans side by side in one day.
     */
    private final List<Block> keptBlocks;
    /** Slots before the hour the day is built from: nothing new goes there. */
    private final int floorSlot;

    private DaySequencer(Config cfg, int dow, long seed, Map<String, Double> cut, boolean restDay,
                         List<Block> kept, int floorSlot) {
        this.keptBlocks = new ArrayList<>();
        this.floorSlot = Math.max(0, Math.min(floorSlot, PER_DAY));
        this.restDay = restDay;
        this.cut = cut == null ? Map.of() : cut;
        this.cfg = cfg;
        this.p = cfg.params;
        this.dow = dow;
        this.h = Energy.homeostatic(p);
        this.rng = new Random(seed);
        for (Goal g : cfg.goals) weights.put(g.id, g.weight);

        FixedFrame.Frame frame = FixedFrame.of(p, cfg.commitments, dow);
        this.occupant = frame.occupant();
        this.freeSlots = frame.freeSlots();
        this.usableSlots = (int) Math.floor(freeSlots * (1 - p.slackRatio));
        this.placed.addAll(frame.items());
        this.taken = new boolean[PER_DAY];
        for (int t = 0; t < PER_DAY; t++) taken[t] = occupant[t] != null;
        for (Block b : frame.items())
            for (int t = b.start; t < b.start + b.dur; t++) taken[Slots.wrap(t)] = true;
        for (Block k : kept == null ? List.<Block>of() : kept) {
            if (k.start < 0 || k.fixed || k.gap) continue;     // classes are in the frame already
            Block c = k.copy();
            c.kept = true;

            keptBlocks.add(c);
            placed.add(c);
            for (int t = c.start; t < c.start + c.dur; t++) taken[Slots.wrap(t)] = true;
        }
        for (int t = 0; t < this.floorSlot; t++) taken[t] = true;
    }

    /**
     * @param seed same day, same configuration, same schedule. A random local
     *             search that reshuffles the plan on every open is worse than
     *             a slightly weaker plan you can trust.
     */
    public static Result run(Config cfg, int dow, Map<String, Double> demandHours, long seed) {
        return run(cfg, dow, demandHours, seed, Map.of());
    }

    /** @param cut what {@link Sacrifice} took from this day: activity id to minutes kept. */
    public static Result run(Config cfg, int dow, Map<String, Double> demandHours, long seed,
                             Map<String, Double> cut) {
        return run(cfg, dow, demandHours, seed, cut, false);
    }

    /** @param restDay goal work is not asked for; rest-only activities join the upkeep. */
    public static Result run(Config cfg, int dow, Map<String, Double> demandHours, long seed,
                             Map<String, Double> cut, boolean restDay) {
        return run(cfg, dow, demandHours, seed, cut, restDay, List.of(), 0);
    }

    /**
     * @param kept      blocks the day already held before [floorSlot]: they stay, count as sessions
     *                  of their upkeep, legs of their trips and hours of their goals
     * @param floorSlot the slot the day is built from; nothing new is placed before it
     */
    public static Result run(Config cfg, int dow, Map<String, Double> demandHours, long seed,
                             Map<String, Double> cut, boolean restDay, List<Block> kept, int floorSlot) {
        DaySequencer s = new DaySequencer(cfg, dow, seed, cut, restDay, kept, floorSlot);
        return s.sequence(restDay ? Map.of() : s.lessKept(demandHours));
    }

    /** Today's hours of each goal less what the kept part of the day already gave it. */
    private Map<String, Double> lessKept(Map<String, Double> demand) {
        if (keptBlocks.isEmpty()) return demand;
        Map<String, Double> out = new HashMap<>(demand);
        for (Block k : keptBlocks)
            if (k.goalId != null && out.containsKey(k.goalId))
                out.put(k.goalId, Math.max(0, out.get(k.goalId) - k.dur * SLOT_MIN / 60.0));
        return out;
    }

    /** Kept blocks of this name: sessions of an upkeep, or a leg of a trip, already there today. */
    private int keptNamed(String name) {
        if (name == null) return 0;
        int n = 0;
        for (Block k : keptBlocks) if (k.name != null && k.name.trim().equals(name.trim())) n++;
        return n;
    }

    private List<Block> live() {
        List<Block> out = new ArrayList<>();
        for (Block b : placed) if (b.start >= 0) out.add(b);
        return out;
    }

    private double scoreNow() {
        return Objective.score(live(), p, h, weights).z();
    }

    private boolean[] maskOf(List<Activity.Window> windows) {
        boolean[] mask = new boolean[PER_DAY];
        if (windows == null || windows.isEmpty()) {
            java.util.Arrays.fill(mask, true);
            return mask;
        }
        // A window that ends before it starts is ignored; if every window is
        // like that, nothing is allowed rather than everything.
        for (Activity.Window w : windows) {
            int[] span = Slots.span(w.from, w.to);
            if (span == null) continue;
            for (int t = span[0]; t < span[1]; t++) mask[Slots.wrap(t)] = true;
        }
        return mask;
    }

    /** A predicate over start slots, used for travel precedence. */
    private interface Bound { boolean ok(int startSlot); }

    /** Try every legal start and keep the best-scoring one. */
    private Block put(Block b, Bound bound, boolean silent) {
        boolean[] mask = maskOf(b.windows);
        Integer best = null;
        double bestZ = Double.NEGATIVE_INFINITY;
        int lo = b.pinned != null ? b.pinned : 0;
        int hi = b.pinned != null ? b.pinned : PER_DAY - b.dur;

        for (int st = lo; st <= hi; st++) {
            if (st < 0 || st + b.dur > PER_DAY) continue;
            boolean ok = true;
            for (int t = st; t < st + b.dur; t++) {
                if (taken[t]) { ok = false; break; }
                if (b.pinned == null && !mask[t]) { ok = false; break; }
                if (bound != null && !bound.ok(st)) { ok = false; break; }
            }
            // The changeover is time, not a preference: you cannot be at the
            // desk and in the gym in the same minute. It is required free
            // rather than occupied, so it is not charged against the slack
            // budget twice and it stays out of the timetable as a gap.
            if (ok) for (int t = st - b.changeover; t < st; t++) {
                if (t < 0 || taken[t]) { ok = false; break; }
            }
            if (!ok) continue;
            if (b.priority < 1e5 && used + b.dur > usableSlots) continue;

            List<Block> trial = live();
            Block candidate = b.copy();
            candidate.start = st;
            trial.add(candidate);
            double z = Objective.score(trial, p, h, weights).z();
            if (z > bestZ) { bestZ = z; best = st; }
        }

        if (best != null) {
            Block rec = b.copy();
            rec.start = best;
            placed.add(rec);
            for (int t = best; t < best + b.dur; t++) taken[t] = true;
            // Hold the changeover so nothing else backs onto the block.
            for (int t = Math.max(0, best - b.changeover); t < best; t++) {
                taken[t] = true;
                if (heldFor[t] == null) heldFor[t] = "getting into " + b.name.trim();
            }
            if (b.priority < 1e5) used += b.dur;
            return rec;
        }
        if (!silent) {
            Block miss = b.copy();
            miss.start = -1;
            miss.reason = b.pinned != null
                ? "its pinned time " + Slots.format(b.pinned) + " is taken by "
                  + occupantOver(b.pinned, b.dur) + "; move the pin, or remove it for this weekday"
                : used + b.dur > usableSlots
                    ? "the day's slack budget was already spent"
                    : b.changeover > 0
                        ? "no free stretch inside its allowed hours was long enough for it plus its "
                          + (b.changeover * SLOT_MIN) + " minutes of changeover; widen a window, "
                          + "or shorten the changeover"
                        : "no free stretch of " + (b.dur * SLOT_MIN) + " minutes was left inside its "
                          + "allowed hours" + fillers(b) + "; widen a window, or move something out of it";
            placed.add(miss);
        }
        return null;
    }

    /**
     * Travel is not optional. If you left the house you are coming back, so
     * rather than dropping the leg we force it in and report the real conflict.
     */
    private void putUnskippable(Block b, Bound bound, int forcedStart, String conflictNote) {
        if (put(b, bound, true) != null) return;

        Block anywhere = b.copy();
        anywhere.windows = new ArrayList<>();
        anywhere.pinned = null;
        if (put(anywhere, bound, true) != null) {
            warnings.add(b.name + " had to go outside its usual window.");
            return;
        }

        int st = Slots.clamp(forcedStart, 0, PER_DAY - b.dur);
        // Never over what is behind you (29 Oct 2026): a trip whose time has passed, or that would cover a
        // block kept from earlier in the day, is not laid over it — that put two things at 06:30.
        boolean overKept = st < floorSlot;
        for (Block o : placed)
            if (o.kept && o.start >= 0 && o.start < st + b.dur && st < o.start + o.dur) overKept = true;
        if (overKept) {
            warnings.add(b.name + " was not planned: the time it needed is already behind you today.");
            return;
        }
        List<Block> kept = new ArrayList<>();
        List<Block> evicted = new ArrayList<>();
        for (Block o : placed) {
            boolean overlaps = o.start >= 0 && o.start < st + b.dur && st < o.start + o.dur;
            boolean movable = !o.fixed && !o.away && !o.kept;
            if (overlaps && movable) { evicted.add(o); continue; }
            kept.add(o);
        }
        placed = kept;
        rebuildTaken();

        Block forced = b.copy();
        forced.start = st;
        forced.forced = true;
        placed.add(forced);
        for (int t = st; t < st + b.dur; t++) taken[t] = true;
        warnings.add(conflictNote.replace("{start}", Slots.format(st))
                                .replace("{end}", Slots.format(st + b.dur)));

        // What the trip pushed out is found another time, or reported — never
        // dropped without a word.
        for (Block o : evicted) {
            if (o.priority < 1e5) used -= o.dur;
            if (o.goalId != null) {
                undelivered.merge(o.goalId, o.dur * SLOT_MIN / 60.0, Double::sum);
                warnings.add(o.name + " was pushed out by " + b.name + "; its "
                    + String.format("%.1f", o.dur * SLOT_MIN / 60.0) + "h roll into the horizon.");
                continue;
            }
            Block again = o.copy();
            again.start = -1;
            again.pinned = null;
            Block moved = put(again, null, true);
            if (moved == null && again.changeover > 0) {
                again.changeover = 0;
                moved = put(again, null, true);
            }
            if (moved != null)
                warnings.add(o.name + " was pushed out by " + b.name + " and moved to "
                    + Slots.format(moved.start) + ".");
            else
                miss(o, "pushed out by " + b.name + ", which has to happen then, and no other free "
                    + "stretch inside its allowed hours was long enough; widen its window");
        }
    }

    /** Records a block that could not be placed, with why and what to do about it. */
    private void miss(Block b, String reason) {
        Block m = b.copy();
        m.start = -1;
        m.reason = reason;
        placed.add(m);
    }

    /** What occupies a slot: a placed block, a class, sleep, or a changeover. Null when it is free. */
    private String occupantAt(int slot) {
        int s = Slots.wrap(slot);
        for (Block o : placed)
            if (o.start >= 0 && s >= o.start && s < o.start + o.dur) return o.name;
        if (occupant[s] != null) return occupant[s];
        return taken[s] ? heldFor[s] : null;
    }

    /** Recomputes what is taken from the fixed frame and the placed blocks, changeovers included. */
    private void rebuildTaken() {
        taken = new boolean[PER_DAY];
        heldFor = new String[PER_DAY];
        for (int t = 0; t < PER_DAY; t++) taken[t] = occupant[t] != null || t < floorSlot;
        for (Block o : placed) {
            if (o.start < 0) continue;
            for (int t = o.start; t < o.start + o.dur; t++) taken[Slots.wrap(t)] = true;
            for (int t = Math.max(0, o.start - o.changeover); t < o.start; t++) {
                taken[t] = true;
                if (heldFor[t] == null) heldFor[t] = "getting into " + o.name.trim();
            }
        }
    }

    /**
     * A second chance for upkeep that found no room: lift one flexible upkeep
     * session that sits in its windows, place the missing one, and put the
     * lifted one back somewhere else it is allowed. Kept only if both fit —
     * breakfast at 06:30 instead of 06:15 lets the dawn prayer have 06:00.
     */
    private void repairMisses() {
        for (Block m : new ArrayList<>(placed)) {
            if (m.start >= 0 || m.priority < 1e5 || m.goalId != null) continue;
            boolean[] mask = maskOf(m.windows);
            for (Block o : new ArrayList<>(placed)) {
                if (o.start < 0 || o.fixed || o.kept || o.away || o.forced || o.pinned != null || o.priority < 1e5) continue;
                boolean inTheWay = false;
                for (int t = Math.max(0, o.start - o.changeover); t < o.start + o.dur && t < PER_DAY; t++)
                    if (mask[t]) inTheWay = true;
                if (!inTheWay) continue;

                placed.remove(o);
                rebuildTaken();
                Block again = m.copy();
                again.start = -1;
                again.pinned = null;
                again.reason = null;
                Block pm = put(again, null, true);
                if (pm != null) {
                    Block back = o.copy();
                    back.start = -1;
                    if (put(back, null, true) != null) {
                        placed.remove(m);
                        if (m.pinned != null)
                            warnings.add(m.name + " could not be at its pinned " + Slots.format(m.pinned)
                                + ", so it moved to " + Slots.format(pm.start)
                                + ". Move the pin, or remove it for this weekday.");
                        break;
                    }
                    placed.remove(pm);
                }
                placed.add(o);
                rebuildTaken();
            }
        }
    }

    private String occupantOver(int start, int dur) {
        for (int t = start; t < start + dur; t++) {
            String who = occupantAt(t);
            if (who != null) return who;
        }
        String before = occupantAt(start - 1);
        return before != null ? before : "something else";
    }

    private boolean spanFree(int start, int dur) {
        for (int t = start; t < start + dur; t++) if (t < 0 || t >= PER_DAY || taken[t]) return false;
        return true;
    }

    /**
     * Places one upkeep session, and if it cannot go where it was asked,
     * still places it when there is any honest way to, saying what gave:
     *
     *  - a pinned session whose changeover has no room (lunch pinned at the
     *    minute class ends, breakfast at the minute you wake) goes in at its
     *    pin anyway — you asked for that time — with a note to pin it later;
     *  - a pinned session whose time is taken moves inside its window, with a
     *    note naming what is in the way;
     *  - only when there is nowhere at all is it left out, with why and what
     *    to change.
     */
    /**
     * Places one phase of upkeep sessions. Pins first, then the sessions that
     * go wherever their window allows, and only then the pins that could not
     * have their time — so a displaced session never takes a slot another pin
     * or another session was counting on.
     */
    private void placeUpkeep(List<Block> blocks) {
        List<Block> displaced = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        for (Block b : blocks) {
            if (b.pinned == null) continue;
            if (placePinned(b)) continue;
            displaced.add(b);
            blockers.add(occupantOver(b.pinned, b.dur));
        }
        for (Block b : blocks) if (b.pinned == null && put(b, null, true) == null) put(b, null, false);
        for (int i = 0; i < displaced.size(); i++) moveDisplaced(displaced.get(i), blockers.get(i));
        repairMisses();
    }

    /**
     * A pinned session at its pin. When only its changeover has no room —
     * lunch pinned the minute class ends, breakfast the minute you wake — it
     * goes in anyway, because that is the time you asked for, with a note to
     * pin it a little later. False when the time itself is taken.
     */
    private boolean placePinned(Block b) {
        if (put(b, null, true) != null) return true;
        if (b.changeover > 0 && spanFree(b.pinned, b.dur)) {
            Block tight = b.copy();
            tight.changeover = 0;
            if (put(tight, null, true) != null) {
                String before = occupantAt(b.pinned - 1);
                warnings.add(b.name + " starts at " + Slots.format(b.pinned) + " with no time to get into it: "
                    + (before == null ? "the block before it" : before) + " runs right up to it. Pin it "
                    + (b.changeover * SLOT_MIN) + " minutes later, or shorten its changeover.");
                return true;
            }
        }
        return false;
    }

    /** A pinned session whose time was taken: its own window first, then any of its windows. */
    private void moveDisplaced(Block b, String who) {
        String at = Slots.format(b.pinned);
        Block moved = null;
        for (Activity.Window w : b.windows) {
            int[] span = Slots.span(w.from, w.to);
            if (span == null || b.pinned < span[0] || b.pinned >= span[1]) continue;
            Block near = b.copy();
            near.pinned = null;
            near.windows = List.of(w);
            moved = put(near, null, true);
            break;
        }
        if (moved == null) {
            Block loose = b.copy();
            loose.pinned = null;
            moved = put(loose, null, true);
        }
        if (moved != null) {
            warnings.add(b.name + " could not be at its pinned " + at + " because " + who
                + " is there, so it moved to " + Slots.format(moved.start)
                + ". Move the pin, or remove it for this weekday.");
            return;
        }
        miss(b, "its pinned time " + at + " is taken by " + who + ", and no other free stretch inside "
            + "its allowed hours was long enough" + fillers(b) + "; move the pin, or widen its window");
    }

    /** What fills a block's allowed windows, as " (12:30–13:30: lunch, Nap)", or nothing. */
    private String fillers(Block b) {
        List<String> parts = new ArrayList<>();
        for (Activity.Window w : b.windows) {
            int[] span = Slots.span(w.from, w.to);
            if (span == null) continue;
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            for (int t = span[0]; t < span[1]; t++) {
                String who = occupantAt(t);
                if (who != null) names.add(who.trim());
            }
            if (!names.isEmpty()) parts.add(w.from + "–" + w.to + ": " + String.join(", ", names));
        }
        return parts.isEmpty() ? "" : " (" + String.join("; ", parts) + ")";
    }

    private Block blockOf(Activity a, double minutes, double priority) {
        Block b = new Block();
        b.id = UUID.randomUUID().toString();
        b.act = a;
        b.name = a.name;
        b.goalId = a.kind == ActivityKind.GOAL ? a.goalId : null;
        b.dur = Math.max(1, (int) Math.round(minutes / SLOT_MIN));
        b.load = a.load;
        b.recovery = a.recovery;
        b.physLoad = a.physLoad == null ? 0 : a.physLoad;
        b.physRecovery = a.physRecovery == null ? 0 : a.physRecovery;
        b.tab = YieldCurve.table(a);
        b.qPlan = Math.max(0.05, a.q - p.lcbBeta * a.qSigma);
        b.windows = a.windows;
        b.priority = priority;
        b.away = a.away;
        b.changeover = a.changeoverMin == null ? 0
            : (int) Math.ceil(a.changeoverMin / (double) SLOT_MIN);
        b.leisure = a.leisure;
        b.dopamine = a.leisure && a.dopamine != null ? a.dopamine : 0;
        return b;
    }

    /**
     * Every session of an upkeep activity today: as many as its "sessions per
     * day", each pinned time its own session, and the rest one to a window.
     * Three prayers with pins at dawn and noon and a window in the evening are
     * three blocks — before, only one was ever made, the last pin won, and the
     * others vanished without a word.
     */
    private List<Block> upkeepBlocks(Activity a) {
        int minutes = Sacrifice.upkeepMinutes(a, cut);
        int sessions = Math.max(1, a.maxBlocks);
        java.util.TreeSet<Integer> pins = new java.util.TreeSet<>();
        for (Activity.Pin pin : a.pins) if (pin.day == dow && pin.start != null) pins.add(Slots.parse(pin.start));

        List<Block> out = new ArrayList<>();
        for (int slot : pins) {
            if (out.size() >= sessions) break;
            Block b = blockOf(a, minutes, 1e6);
            b.pinned = slot;
            out.add(b);
        }
        int rest = sessions - out.size();
        if (rest <= 0) return out;

        // A window that already holds a pinned session is spoken for; with
        // enough other windows, each remaining session takes one of its own.
        List<Activity.Window> open = new ArrayList<>();
        for (Activity.Window w : a.windows) {
            int[] span = Slots.span(w.from, w.to);
            if (span == null) continue;
            boolean holdsPin = false;
            for (int s : pins) if (s >= span[0] && s < span[1]) holdsPin = true;
            if (!holdsPin) open.add(w);
        }
        for (int i = 0; i < rest; i++) {
            Block b = blockOf(a, minutes, 1e6);
            if (open.size() >= rest) b.windows = List.of(open.get(i));
            out.add(b);
        }
        return out;
    }

    private Result sequence(Map<String, Double> demandHours) {
        List<Activity> upkeep = new ArrayList<>();
        for (Activity a : cfg.activities) {
            if (a.kind != ActivityKind.MAINTENANCE || !a.days.contains(dow)) continue;
            if (a.restOnly && !restDay) continue;
            if (a.leisure) continue;            // chosen for the rests, below, never planned on its own
            int done = keptNamed(a.name);
            if (done >= Math.max(1, a.maxBlocks)) continue;  // its sessions today are already behind you
            if (done > 0) { a = a.copy(); a.maxBlocks = Math.max(1, a.maxBlocks) - done; }
            if (cut.containsKey(a.id)) {
                int kept = Sacrifice.upkeepMinutes(a, cut);
                warnings.add(kept <= 0
                    ? a.name + " is left out today to make room for a deadline."
                    : a.name + " is shortened to " + kept + " min today to make room for a deadline.");
                if (kept <= 0) continue;
            }
            upkeep.add(a);
        }

        // Pinned sessions go first within each phase: a time you fixed should
        // not be lost to something that could have gone anywhere.
        List<Block> awayBlocks = new ArrayList<>(), homeBlocks = new ArrayList<>();
        for (Activity a : upkeep) (a.away ? awayBlocks : homeBlocks).addAll(upkeepBlocks(a));
        Comparator<Block> pinnedFirst = Comparator.comparing(b -> b.pinned == null);
        awayBlocks.sort(pinnedFirst);
        homeBlocks.sort(pinnedFirst);

        // PHASE 1 - everything that takes you out of the house
        placeUpkeep(awayBlocks);

        // PHASE 2 - the trip that wraps it
        placeTravel();

        // PHASE 3 - the rest of the upkeep
        placeUpkeep(homeBlocks);

        // PHASE 4 - goal work, with the number of sessions chosen by the model
        placeGoalWork(demandHours);

        localSearch();

        // PHASE 5 - the rests, chosen from your leisure (your request of 25 Oct 2026)
        placeLeisure();

        List<Block> finalBlocks = live();
        finalBlocks.sort(Comparator.comparingInt(b -> b.start));
        Objective.Score score = Objective.score(finalBlocks, p, h, weights);

        double effective = 0, elapsed = 0;
        for (Block b : finalBlocks) if (b.goalId != null) { effective += b.yieldMin; elapsed += b.dur * SLOT_MIN; }

        List<Block> unplaced = new ArrayList<>();
        for (Block b : placed) if (b.start < 0) unplaced.add(b);

        return new Result(finalBlocks, describeGaps(finalBlocks), unplaced, warnings,
                          score, effective, elapsed, freeSlots, used, usableSlots, undelivered);
    }

    private void placeTravel() {
        List<Block> awayItems = new ArrayList<>();
        for (Block b : placed) if (b.away && b.start >= 0) awayItems.add(b);
        if (awayItems.isEmpty()) return;

        int firstAway = Integer.MAX_VALUE, lastAway = Integer.MIN_VALUE;
        for (Block b : awayItems) {
            // Arriving means arriving in time to change: the trip ends where the
            // first outing's changeover begins. Ending at the outing itself ran
            // into the changeover it holds free, and the trip was thrown to
            // whatever time scored best — six in the morning for an evening gym.
            firstAway = Math.min(firstAway, b.start - b.changeover);
            lastAway = Math.max(lastAway, b.start + b.dur);
        }
        final int first = firstAway, last = lastAway;

        for (Commitment c : cfg.commitments) {
            // Only on the days it is travelled (29 Oct 2026): a walk outside is not a trip to the university.
            if (c.kind != CommitmentKind.VARIABLE || !c.travelledOn(dow)) continue;
            int dur = FixedFrame.tripSlots(c);

            if (keptNamed(c.name + " (out)") == 0) leg(c, c.outbound, c.name + " (out)", dur, first - dur,
                st -> st + dur <= first,
                "To be there by " + Slots.format(first) + " you would have to leave at {start}, "
                + "which is earlier than you meant to be up.");

            if (keptNamed(c.name + " (back)") == 0) leg(c, c.inbound, c.name + " (back)", dur, last,
                st -> st >= last,
                "You are out until " + Slots.format(last) + ", so you do not get home until {end}. "
                + "Shorten the last thing you do away from home, or accept the late finish.");
        }
    }

    private void leg(Commitment c, Commitment.Leg cfgLeg, String name, int dur,
                     int anchor, Bound bound, String note) {
        LegMode mode = cfgLeg == null ? LegMode.PINNED : cfgLeg.mode;
        if (mode == LegMode.NONE) return;

        Block b = new Block();
        b.id = UUID.randomUUID().toString();
        b.name = name;
        b.dur = dur;
        b.load = c.load == null ? 0 : c.load;
        b.recovery = c.recovery == null ? 0 : c.recovery;
        b.physLoad = c.physLoad == null ? 0 : c.physLoad;
        b.physRecovery = c.physRecovery == null ? 0 : c.physRecovery;
        b.priority = 1e6;
        b.away = true;
        if (mode == LegMode.FLEXIBLE) {
            b.windows = List.of(new Activity.Window(cfgLeg.from, cfgLeg.to));
        } else {
            b.pinned = anchor;
        }

        if (mode == LegMode.PINNED) {
            if (put(b, null, true) != null) return;
            // Its time is taken: the nearest free time on the right side of the
            // outing, not the best-scoring time anywhere in the day.
            boolean outbound = bound.ok(anchor) && !bound.ok(anchor + 1);
            for (int k = 1; k < PER_DAY; k++) {
                int st = outbound ? anchor - k : anchor + k;
                if (st < 0 || st + dur > PER_DAY) break;
                if (!bound.ok(st) || !spanFree(st, dur)) continue;
                Block moved = b.copy();
                moved.pinned = st;
                if (put(moved, null, true) == null) continue;
                warnings.add(name + " had to move to " + Slots.format(st) + " because "
                    + occupantOver(anchor, dur) + " is at its usual time.");
                return;
            }
            Block loose = b.copy();
            loose.pinned = null;
            putUnskippable(loose, bound, anchor, note);
        } else {
            putUnskippable(b, bound, anchor, note);
        }
    }

    /**
     * For each goal activity, try every legal way of splitting the day's hours
     * into sessions and keep whichever scores best. One long block on a calm
     * day, two shorter ones when fatigue or a class gets in the way.
     */
    private void placeGoalWork(Map<String, Double> demandHours) {
        List<Activity> goalActs = new ArrayList<>();
        for (Activity a : cfg.activities) {
            if (a.kind != ActivityKind.GOAL || !a.days.contains(dow)) continue;
            if (cut.containsKey(a.id)) {
                warnings.add(a.name + " is left out today to make room for a deadline.");
                continue;
            }
            goalActs.add(a);
        }
        goalActs.sort((x, y) -> Double.compare(weights.getOrDefault(y.goalId, 0.0),
                                               weights.getOrDefault(x.goalId, 0.0)));

        for (Activity a : goalActs) {
            int peers = 0;
            for (Activity o : cfg.activities)
                if (o.kind == ActivityKind.GOAL && a.goalId.equals(o.goalId)
                        && o.days.contains(dow)) peers++;
            if (peers == 0) continue;

            double allocMin = demandHours.getOrDefault(a.goalId, 0.0) * 60.0 / peers;
            // Less than a session: the horizon gives that only for the last of a target's work (or
            // what a full day leaves). One short block finishes it; dropping it left the goal short
            // with the day free (seen 23 Oct 2026).
            boolean shortOne = allocMin < a.minBlock * 0.85;
            if (shortOne && allocMin < SLOT_MIN) continue;

            // Enough blocks to hold the whole allocation, given the longest
            // session this activity allows.
            int enough = Math.max(1, (int) Math.ceil(allocMin / a.maxBlock));
            int mostThatFit = (int) Math.floor(allocMin / a.minBlock);
            int nMax = Slots.clamp(Math.max(enough, mostThatFit), 1, 4);

            // The enumeration starts at one, not at `enough`. When the day
            // genuinely cannot hold the whole allocation — a class eats the
            // morning window, travel eats the evening — every split that would
            // deliver all of it fails, and the old range offered no alternative
            // but to place a single block and drop the rest. Fewer, longer
            // sessions deliver more of the goal in a day that is short of room.
            List<Integer> options = new ArrayList<>();
            if (shortOne) options.add(1);
            else if (a.blocksMode == BlocksMode.AUTO) for (int i = 1; i <= nMax; i++) options.add(i);
            else options.add(Slots.clamp(a.maxBlocks, 1, 4));

            List<Block> snapPlaced = new ArrayList<>(placed);
            boolean[] snapTaken = taken.clone();
            int snapUsed = used;

            List<Block> bestPlaced = null;
            boolean[] bestTaken = null;
            int bestUsed = 0;
            double bestUndelivered = 0;
            double bestZ = Double.NEGATIVE_INFINITY;

            for (int n : options) {
                placed = new ArrayList<>(snapPlaced);
                taken = snapTaken.clone();
                used = snapUsed;
                double chunk = shortOne ? allocMin : Slots.clamp(allocMin / n, a.minBlock, a.maxBlock);
                double deliveredMin = 0;
                for (int i = 0; i < n; i++) {
                    Block b = blockOf(a, chunk, weights.getOrDefault(a.goalId, 0.1));
                    if (put(b, null, true) != null) deliveredMin += b.dur * SLOT_MIN;
                }
                // Charged on the minutes not delivered rather than on the number
                // of blocks that would not fit: two sessions that cover five of
                // six hours beat one that covers two, which counting blocks got
                // backwards. The rate dwarfs any arrangement difference, so a
                // split that covers the goal always wins.
                double z = scoreNow() - Math.max(0, allocMin - deliveredMin) * UNDELIVERED_PENALTY;
                if (z > bestZ) {
                    bestZ = z; bestPlaced = new ArrayList<>(placed);
                    bestTaken = taken.clone(); bestUsed = used;
                    bestUndelivered = Math.max(0, allocMin - deliveredMin);
                }
            }
            if (bestPlaced != null) {
                placed = bestPlaced; taken = bestTaken; used = bestUsed;
                bestUndelivered = topUp(a, bestUndelivered);
                if (bestUndelivered >= 1) {
                    undelivered.merge(a.goalId, bestUndelivered / 60.0, Double::sum);
                    warnings.add(String.format(
                        "%.1fh of %s did not fit today; those hours roll into the horizon.",
                        bestUndelivered / 60.0, a.name));
                }
            }
        }
    }

    /**
     * Offer whatever the split could not place back to the day, in decreasing
     * sizes, until nothing more fits.
     *
     * The splits above divide the allocation into equal chunks, which is the
     * right shape for a day with room to spare. It is the wrong shape for a day
     * whose leftovers are uneven — a two-hour gap before a class and a
     * seventy-minute one after it. Equal chunks of eighty minutes waste the
     * first and cannot use the second, so asking for more hours could deliver
     * fewer than asking for less. Topping up removes that: the placement can
     * only grow, never shrink, as the allocation rises.
     *
     * @return the minutes still undelivered
     */
    private double topUp(Activity a, double undeliveredMin) {
        double remaining = undeliveredMin;
        boolean progress = true;
        while (remaining >= a.minBlock && progress) {
            progress = false;
            double largest = Math.min(remaining, a.maxBlock);
            for (double size = largest; size >= a.minBlock; size -= SLOT_MIN) {
                Block b = blockOf(a, size, weights.getOrDefault(a.goalId, 0.1));
                if (put(b, null, true) == null) continue;
                remaining -= b.dur * SLOT_MIN;
                progress = true;
                break;
            }
        }
        return Math.max(0, remaining);
    }

    private void localSearch() {
        for (int iter = 0; iter < 260; iter++) {
            List<Block> pool = new ArrayList<>();
            for (Block b : placed)
                if (!b.fixed && !b.kept && b.start >= 0 && b.pinned == null && !b.away && !b.forced) pool.add(b);
            if (pool.isEmpty()) return;

            Block b = pool.get(rng.nextInt(pool.size()));
            List<Block> others = new ArrayList<>();
            for (Block o : placed) if (o != b && o.start >= 0) others.add(o);

            boolean[] busy = new boolean[PER_DAY];
            for (int t = 0; t < PER_DAY; t++) busy[t] = occupant[t] != null;
            for (Block o : others) {
                for (int t = o.start; t < o.start + o.dur; t++) busy[Slots.wrap(t)] = true;
                // Another block's changeover is as occupied as the block itself.
                for (int t = o.start - o.changeover; t < o.start; t++)
                    if (t >= 0) busy[t] = true;
            }

            boolean[] mask = maskOf(b.windows);
            double current = scoreNow();
            int bestStart = -1;
            double bestZ = current + 1e-6;

            for (int st = 0; st + b.dur <= PER_DAY; st++) {
                boolean ok = true;
                for (int t = st; t < st + b.dur; t++) if (busy[t] || !mask[t]) { ok = false; break; }
                // And its own changeover has to fit wherever it lands.
                if (ok) for (int t = st - b.changeover; t < st; t++)
                    if (t < 0 || busy[t]) { ok = false; break; }
                if (!ok) continue;
                List<Block> trial = new ArrayList<>(others);
                Block moved = b.copy();
                moved.start = st;
                trial.add(moved);
                double z = Objective.score(trial, p, h, weights).z();
                if (z > bestZ) { bestZ = z; bestStart = st; }
            }
            if (bestStart >= 0) b.start = bestStart;
        }
    }

    // ------------------------------------------------------------- leisure

    /** Goal work running this long without a pause is offered a break (minutes). */
    static final int BREAK_AFTER_MIN = 100;
    /**
     * What a minute of leisure may cost the day's goal value and still be placed. Enjoyment is not
     * in the objective; without this a rest would only go in where it raised the goal work, and
     * never where its stimulation dulls what follows a little. With it, a rest that would dull
     * the work badly still stays out.
     */
    static final double LEISURE_TOLERANCE = 0.12;
    /**
     * The same for a break you asked for inside long work: it moves the rest of the run later, into
     * hours a little less sharp, so it may cost about the work its own minutes would have done.
     */
    static final double BREAK_TOLERANCE = 0.45;

    private List<Activity> leisureToday() {
        List<Activity> out = new ArrayList<>();
        for (Activity a : cfg.activities)
            if (a.leisure && a.kind == ActivityKind.MAINTENANCE && a.days.contains(dow) && !a.nap
                    && a.minBlock != null && a.maxBlock != null) out.add(a);
        return out;
    }

    /** Whether the day with these blocks stays under your ceiling of stimulation. */
    private boolean underCeiling(List<Block> blocks) {
        return Energy.simulate(blocks, p, h).peakDopamine() <= p.dopCapOr() + 1e-9;
    }

    /** Sessions of a leisure activity already placed today. */
    private int sessionsOf(Activity a) {
        int n = 0;
        for (Block b : placed)
            if (b.start >= 0 && b.leisure && (b.act != null ? a.id.equals(b.act.id) : b.kept && a.name != null
                    && b.name != null && a.name.trim().equals(b.name.trim()))) n++;
        return n;
    }

    /**
     * The rests. First a break inside each long run of goal work (the later block moves on by the
     * break, or the earlier one back), then every stretch that would have stayed an open buffer.
     * Each time the best of your leisure that fits: allowed then, sessions left today, under the
     * ceiling of stimulation, and costing the goal work at most a little.
     */
    private void placeLeisure() {
        List<Activity> pool = leisureToday();
        if (pool.isEmpty()) return;
        breaks(pool);
        for (int round = 0; round < 24; round++) if (!fillOne(pool)) break;
    }

    /** One leisure block of [a] at [st] for [len] slots. */
    private Block leisureBlock(Activity a, int st, int len) {
        Block b = blockOf(a, len * SLOT_MIN, 0.5);
        b.dur = len;
        b.start = st;
        return b;
    }

    private boolean allowed(Activity a, int st, int len) {
        boolean[] mask = maskOf(a.windows);
        for (int t = st; t < st + len; t++) if (t < 0 || t >= PER_DAY || taken[t] || !mask[t]) return false;
        return true;
    }

    /** Whether a session of [a] would touch another of its own: one longer rest, not two halves. */
    private boolean besideItself(Activity a, int st, int len) {
        for (Block o : placed)
            if (o.start >= 0 && o.leisure && o.act != null && a.id.equals(o.act.id)
                    && (o.start + o.dur == st || o.start == st + len)) return true;
        return false;
    }

    private void take(Block b) {
        placed.add(b);
        for (int t = b.start; t < b.start + b.dur; t++) taken[Slots.wrap(t)] = true;
    }

    private double peakWith(Block b) {
        List<Block> with = live();
        with.add(b);
        return Energy.simulate(with, p, h).peakDopamine();
    }

    /** Places the best leisure block over the free stretches; false when none fits. */
    private boolean fillOne(List<Activity> pool) {
        int s0 = Slots.parse(p.sleepStart), s1 = Slots.parse(p.sleepEnd);
        double now = scoreNow();
        Block best = null;
        double bestRank = Double.NEGATIVE_INFINITY;
        int t = 0;
        while (t < PER_DAY) {
            if (taken[t] || Energy.isAsleep(t, s0, s1)) { t++; continue; }
            int end = t;
            while (end < PER_DAY && !taken[end] && !Energy.isAsleep(end, s0, s1)) end++;
            for (Activity a : pool) {
                if (sessionsOf(a) >= Math.max(1, a.maxBlocks)) continue;
                int min = Math.max(1, (int) Math.ceil(a.minBlock / (double) SLOT_MIN));
                int max = Math.max(min, a.maxBlock / SLOT_MIN);
                for (int len : new int[]{max, (min + max) / 2, min}) {
                    if (len > end - t || len < min) continue;
                    for (int st : new int[]{t, end - len}) {
                        if (!allowed(a, st, len) || besideItself(a, st, len)) continue;
                        List<Block> trial = live();
                        Block b = leisureBlock(a, st, len);
                        trial.add(b);
                        if (!underCeiling(trial)) continue;
                        double z = Objective.score(trial, p, h, weights).z();
                        if (z < now - LEISURE_TOLERANCE * len * SLOT_MIN) continue;
                        // A little for the longer rest and for variety: a second of the same is worth less.
                        double rank = z + 0.02 * len * SLOT_MIN - 3.0 * sessionsOf(a);
                        if (rank > bestRank) { bestRank = rank; best = b; }
                    }
                }
            }
            t = end;
        }
        if (best == null) return false;
        best.why = String.format(java.util.Locale.ROOT,
            "A rest chosen from your leisure for free time; stimulation peaks at %.2f of your ceiling %.2f.",
            peakWith(best), p.dopCapOr());
        take(best);
        return true;
    }

    /** A break inside every run of goal work longer than [BREAK_AFTER_MIN] with no pause in it. */
    private void breaks(List<Activity> pool) {
        int shortest = Integer.MAX_VALUE;
        for (Activity a : pool) shortest = Math.min(shortest, Math.max(1, (int) Math.ceil(a.minBlock / (double) SLOT_MIN)));
        for (int pass = 0; pass < 6; pass++) {
            List<Block> work = new ArrayList<>();
            for (Block b : live()) if (b.goalId != null) work.add(b);
            work.sort(Comparator.comparingInt(b -> b.start));
            boolean done = false;
            for (int i = 0; i + 1 < work.size() && !done; i++) {
                Block first = work.get(i), second = work.get(i + 1);
                int between = second.start - second.changeover - (first.start + first.dur);
                if (between >= shortest || first.dur + second.dur < BREAK_AFTER_MIN / SLOT_MIN) continue;
                if (occupiedBetween(first, second)) continue;
                done = breakBetween(pool, first, second, shortest - Math.max(0, between));
            }
            if (!done) return;
        }
    }

    /** Whether something other than free time sits between two blocks (a meal, a class): a pause already. */
    private boolean occupiedBetween(Block first, Block second) {
        for (Block o : live())
            if (o != first && o != second && o.start >= first.start + first.dur && o.start < second.start) return true;
        for (int t = first.start + first.dur; t < second.start - second.changeover; t++) if (occupant[t] != null) return true;
        return false;
    }

    /**
     * The blocks that move together with [from] when it shifts: it and every movable block that
     * touches the one before it in that direction. Null when one of them cannot move.
     */
    private List<Block> chain(Block from, boolean later) {
        List<Block> sorted = live();
        sorted.sort(Comparator.comparingInt(b -> b.start));
        List<Block> out = new ArrayList<>();
        out.add(from);
        Block edge = from;
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Block o : sorted) {
                if (out.contains(o)) continue;
                boolean touches = later ? o.start - o.changeover <= edge.start + edge.dur && o.start >= edge.start + edge.dur
                                        : o.start + o.dur >= edge.start - edge.changeover && o.start + o.dur <= edge.start;
                if (!touches) continue;
                if (o.fixed || o.kept || o.pinned != null || o.away || o.forced) return null;
                out.add(o);
                edge = o;
                grew = true;
                break;
            }
        }
        for (Block b : out) if (b.fixed || b.kept || b.pinned != null || b.away || b.forced) return null;
        return out;
    }

    /**
     * Opens [need] slots between two blocks of goal work and rests in them: the later block moves on
     * (with whatever runs on straight after it), or the earlier one back (with what runs straight
     * before it). False if neither can.
     */
    private boolean breakBetween(List<Activity> pool, Block first, Block second, int need) {
        for (int side = 0; side < 2; side++) {
            List<Block> moving = chain(side == 0 ? second : first, side == 0);
            if (moving == null) continue;
            int shift = side == 0 ? need : -need;
            double before = scoreNow();
            placed.removeAll(moving);
            rebuildTaken();
            List<Block> moved = new ArrayList<>();
            boolean fits = true;
            for (Block m : moving) {
                Block c = m.copy();
                c.start = m.start + shift;
                boolean[] mask = maskOf(c.windows);
                if (c.start - c.changeover < 0 || c.start + c.dur > PER_DAY) { fits = false; break; }
                for (int t = c.start - c.changeover; fits && t < c.start + c.dur; t++)
                    if (taken[t] || (t >= c.start && !mask[t])) fits = false;
                if (!fits) break;
                moved.add(c);
            }
            if (fits) {
                for (Block c : moved) take(c);
                Block movedSecond = side == 0 ? moved.get(0) : second;
                Block movedFirst = side == 0 ? first : moved.get(0);
                int gapStart = movedFirst.start + movedFirst.dur;
                int gapEnd = movedSecond.start - movedSecond.changeover;
                Block best = null;
                double bestZ = Double.NEGATIVE_INFINITY;
                for (Activity a : pool) {
                    int len = gapEnd - gapStart;
                    if (sessionsOf(a) >= Math.max(1, a.maxBlocks) || len * SLOT_MIN < a.minBlock
                            || !allowed(a, gapStart, len) || besideItself(a, gapStart, len)) continue;
                    List<Block> trial = live();
                    Block b = leisureBlock(a, gapStart, len);
                    trial.add(b);
                    if (!underCeiling(trial)) continue;
                    double z = Objective.score(trial, p, h, weights).z();
                    if (z > bestZ) { bestZ = z; best = b; }
                }
                if (best != null && bestZ >= before - BREAK_TOLERANCE * best.dur * SLOT_MIN) {
                    best.why = String.format(java.util.Locale.ROOT,
                        "A break inside %.1f h of goal work, chosen from your leisure; stimulation peaks at %.2f of your ceiling %.2f.",
                        (first.dur + second.dur) * SLOT_MIN / 60.0, peakWith(best), p.dopCapOr());
                    take(best);
                    return true;
                }
                placed.removeAll(moved);
            }
            placed.addAll(moving);
            rebuildTaken();
        }
        return relocate(second, first, need);
    }

    /**
     * Neither block could shift (a meal or a class right behind them): the later block goes to the
     * best other time its hours allow that leaves a pause after the first, if the day loses little
     * by it. The pause is filled later like any other free time, or is the meal itself.
     */
    private boolean relocate(Block second, Block first, int need) {
        if (second.fixed || second.kept || second.pinned != null || second.away || second.forced) return false;
        double before = scoreNow();
        placed.remove(second);
        rebuildTaken();
        boolean[] mask = maskOf(second.windows);
        int firstEnd = first.start + first.dur;
        Integer best = null;
        double bestZ = Double.NEGATIVE_INFINITY;
        for (int st = 0; st + second.dur <= PER_DAY; st++) {
            if (st - second.changeover < 0) continue;
            if (st >= firstEnd && st - second.changeover < firstEnd + need) continue;       // still no pause after the first
            if (st + second.dur <= first.start && st + second.dur > first.start - first.changeover - need) continue;
            boolean ok = true;
            for (int t = st - second.changeover; t < st + second.dur && ok; t++)
                if (taken[t] || (t >= st && !mask[t])) ok = false;
            if (!ok) continue;
            List<Block> trial = live();
            Block moved = second.copy();
            moved.start = st;
            trial.add(moved);
            double z = Objective.score(trial, p, h, weights).z();
            if (z > bestZ) { bestZ = z; best = st; }
        }
        if (best != null && bestZ >= before - BREAK_TOLERANCE * need * SLOT_MIN) {
            Block moved = second.copy();
            moved.start = best;
            take(moved);
            for (int t = Math.max(0, best - moved.changeover); t < best; t++) taken[t] = true;
            return true;
        }
        placed.add(second);
        rebuildTaken();
        return false;
    }

    /** The block that begins exactly where a gap ends, if there is one. */
    private static Block startsAt(List<Block> blocks, int slot) {
        for (Block b : blocks) if (b.start == slot) return b;
        return null;
    }

    /** Nothing on the timeline should be unexplained, including the holes. */
    private List<Block> describeGaps(List<Block> finalBlocks) {
        boolean[] filled = new boolean[PER_DAY];
        for (Block b : finalBlocks) for (int t = b.start; t < b.start + b.dur; t++) filled[Slots.wrap(t)] = true;

        int shortest = Integer.MAX_VALUE;
        for (Activity a : cfg.activities) if (a.days.contains(dow)) shortest = Math.min(shortest, a.minBlock);
        if (shortest == Integer.MAX_VALUE) shortest = 30;

        int s0 = Slots.parse(p.sleepStart), s1 = Slots.parse(p.sleepEnd);
        List<Block> gaps = new ArrayList<>();
        int t = 0;
        while (t < PER_DAY) {
            if (filled[t] || t < floorSlot || Energy.isAsleep(t, s0, s1)) { t++; continue; }
            int end = t;
            while (end < PER_DAY && !filled[end] && !Energy.isAsleep(end, s0, s1)) end++;
            int len = end - t;
            Block g = new Block();
            g.id = UUID.randomUUID().toString();
            g.start = t;
            g.dur = len;
            g.gap = true;
            g.recovery = p.idleRecovery;
            g.physRecovery = p.idleRecoveryP == null ? p.idleRecovery : p.idleRecoveryP;
            Block next = startsAt(finalBlocks, end);
            boolean changeover = next != null && next.changeover > 0
                                 && len <= next.changeover;
            boolean tooShort = len * SLOT_MIN < shortest;
            g.name = changeover ? "Changeover" : tooShort ? "Transition" : "Open buffer";
            g.why = changeover
                ? "Held free to get into " + next.name + ", which needs "
                  + (next.changeover * SLOT_MIN) + " minutes to change into."
                : tooShort
                ? "Only " + (len * SLOT_MIN) + " minutes, shorter than the smallest block you allow ("
                  + shortest + " min), so nothing can go here."
                : used >= usableSlots
                    ? "Held back deliberately: this is the slack reserve that absorbs overruns."
                    : "Nothing left to schedule. Resting here also lowers fatigue for later blocks.";
            gaps.add(g);
            t = end;
        }
        return gaps;
    }
}

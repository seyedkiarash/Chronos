package com.chronos.model;

import com.chronos.model.Enums.CommitmentKind;
import com.chronos.model.Enums.LegMode;
import java.util.ArrayList;
import java.util.List;

public class Commitment {
    /**
     * One recurring session of a fixed commitment. Two classes of the same
     * course rarely cost the same: a four-hour lab drains more than a lecture.
     * {@code load} and {@code recovery} are therefore per session, and null
     * means "use the commitment's own figure".
     */
    public static class Session {
        public int day;
        public String start, end;
        public Double load, recovery;            // mental
        public Double physLoad, physRecovery;    // physical; null: the commitment's own
        public Session() {}
        public Session(int day, String start, String end) { this.day = day; this.start = start; this.end = end; }

        /** The load in force for this session, falling back to the commitment's. */
        public double loadOr(Double fallback) {
            Double v = load != null ? load : fallback;
            return v == null ? 0 : v;
        }

        public double recoveryOr(Double fallback) {
            Double v = recovery != null ? recovery : fallback;
            return v == null ? 0 : v;
        }

        public double physLoadOr(Double fallback) {
            Double v = physLoad != null ? physLoad : fallback;
            return v == null ? 0 : v;
        }

        public double physRecoveryOr(Double fallback) {
            Double v = physRecovery != null ? physRecovery : fallback;
            return v == null ? 0 : v;
        }

        public Session copy() {
            Session s = new Session(day, start, end);
            s.load = load; s.recovery = recovery; s.physLoad = physLoad; s.physRecovery = physRecovery;
            return s;
        }
    }

    /** One direction of a trip. */
    public static class Leg {
        public LegMode mode = LegMode.PINNED;
        public String from, to;                 // only when mode == FLEXIBLE
        public Leg() {}
        public Leg(LegMode m) { this.mode = m; }
        public Leg(String from, String to) { this.mode = LegMode.FLEXIBLE; this.from = from; this.to = to; }

        public Leg copy() {
            Leg l = new Leg(mode);
            l.from = from; l.to = to;
            return l;
        }
    }

    public String id;
    public String name;
    public CommitmentKind kind;
    public Double load;                                  // mental fatigue per hour
    public Double recovery;
    public Double physLoad;                              // physical fatigue per hour
    public Double physRecovery;
    public boolean away = true;

    public List<Session> sessions = new ArrayList<>();   // FIXED

    public Double medianMin;                             // VARIABLE
    public Double sigma;
    public Double quantile;
    public Leg outbound = new Leg(LegMode.PINNED);
    public Leg inbound = new Leg(LegMode.PINNED);
    /**
     * VARIABLE only: the weekdays it is travelled (0 = Saturday), your report of 29 Oct 2026 — a walk
     * outside on a day without classes is out of the house, not a trip to the university. Null: every
     * day something happens away, as before.
     */
    public java.util.TreeSet<Integer> days;

    /** Whether this trip is made on a weekday. */
    public boolean travelledOn(int dow) { return days == null || days.contains(dow); }

    /** The session of this commitment held on a weekday at a start slot, or null. */
    public Session sessionAt(int dow, int startSlot) {
        for (Session s : sessions)
            if (s.day == dow && s.start != null && com.chronos.engine.Slots.parse(s.start) == startSlot) return s;
        return null;
    }

    public Commitment copy() {
        Commitment c = new Commitment();
        c.id = id; c.name = name; c.kind = kind; c.load = load; c.recovery = recovery; c.away = away;
        c.physLoad = physLoad; c.physRecovery = physRecovery;
        for (Session s : sessions) c.sessions.add(s.copy());
        c.medianMin = medianMin; c.sigma = sigma; c.quantile = quantile;
        c.outbound = outbound == null ? null : outbound.copy();
        c.inbound = inbound == null ? null : inbound.copy();
        c.days = days == null ? null : new java.util.TreeSet<>(days);
        return c;
    }
}

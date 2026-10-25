package com.chronos.data;

import com.chronos.data.entity.Entities.*;
import com.chronos.model.*;
import com.chronos.model.Enums.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Row to model and back. The only place that knows both shapes. */
public final class Mappers {
    private Mappers() {}

    private static LocalDate date(String s) { return s == null || s.isBlank() ? null : LocalDate.parse(s); }
    private static String date(LocalDate d) { return d == null ? null : d.toString(); }

    // ---------------- params ----------------
    public static Params toModel(ParamsRow r) {
        Params p = new Params();
        if (r == null) return p;
        p.sleepStart = r.sleepStart; p.sleepEnd = r.sleepEnd; p.phi = r.phi;
        p.a0 = r.a0; p.aC = r.aC; p.aH = r.aH; p.aF = r.aF;
        p.aP = r.aP; p.pTheta = r.pTheta; p.fSafeP = r.fSafeP; p.idleRecoveryP = r.idleRecoveryP; p.kMP = r.kMP;
        p.tauR = r.tauR; p.tauD = r.tauD; p.fMax = r.fMax;
        p.fSafe = r.fSafe; p.idleRecovery = r.idleRecovery; p.slackRatio = r.slackRatio;
        p.lcbBeta = r.lcbBeta; p.switchPenalty = r.switchPenalty; p.fatiguePenalty = r.fatiguePenalty;
        p.horizonDays = r.horizonDays;
        p.dopCap = r.dopCap; p.dopHalfLifeH = r.dopHalfLifeH; p.aD = r.aD;
        return p;
    }

    public static ParamsRow toRow(Params p) {
        ParamsRow r = new ParamsRow();
        r.sleepStart = p.sleepStart; r.sleepEnd = p.sleepEnd; r.phi = p.phi;
        r.a0 = p.a0; r.aC = p.aC; r.aH = p.aH; r.aF = p.aF;
        r.aP = p.aP; r.pTheta = p.pTheta; r.fSafeP = p.fSafeP; r.idleRecoveryP = p.idleRecoveryP; r.kMP = p.kMP;
        r.tauR = p.tauR; r.tauD = p.tauD; r.fMax = p.fMax;
        r.fSafe = p.fSafe; r.idleRecovery = p.idleRecovery; r.slackRatio = p.slackRatio;
        r.lcbBeta = p.lcbBeta; r.switchPenalty = p.switchPenalty; r.fatiguePenalty = p.fatiguePenalty;
        r.horizonDays = p.horizonDays;
        r.dopCap = p.dopCap; r.dopHalfLifeH = p.dopHalfLifeH; r.aD = p.aD;
        return r;
    }

    // ---------------- goals ----------------
    public static Goal toModel(GoalRow r) {
        Goal g = new Goal();
        g.id = r.id; g.name = r.name;
        g.mode = r.mode == null ? null : GoalMode.valueOf(r.mode);
        g.hard = r.hard;
        g.totalUnits = r.totalUnits; g.doneUnits = r.doneUnits; g.unit = r.unit;
        g.hoursPerUnit = r.hoursPerUnit; g.deadline = date(r.deadline);
        g.weeklyFloorH = r.weeklyFloorH; g.maxPerDayH = r.maxPerDayH; g.weight = r.weight;
        g.groupName = r.groupName; g.note = r.note; g.deadlineTime = r.deadlineTime;
        return g;
    }

    public static GoalRow toRow(Goal g, int position) {
        GoalRow r = new GoalRow();
        r.id = g.id; r.name = g.name;
        r.mode = g.mode == null ? null : g.mode.name();
        r.hard = g.hard;
        r.totalUnits = g.totalUnits; r.doneUnits = g.doneUnits; r.unit = g.unit;
        r.hoursPerUnit = g.hoursPerUnit; r.deadline = date(g.deadline);
        r.weeklyFloorH = g.weeklyFloorH; r.maxPerDayH = g.maxPerDayH; r.weight = g.weight;
        r.groupName = g.groupName; r.note = g.note; r.deadlineTime = g.deadlineTime;
        r.position = position;
        return r;
    }

    // ---------------- activities ----------------
    public static Activity toModel(ActivityRow r) {
        Activity a = new Activity();
        a.id = r.id; a.name = r.name;
        a.kind = r.kind == null ? null : ActivityKind.valueOf(r.kind);
        a.goalId = r.goalId;
        a.days = Codec.parseDays(r.days);
        a.minBlock = r.minBlock; a.maxBlock = r.maxBlock;
        a.load = r.load; a.recovery = r.recovery;
        a.physLoad = r.physLoad; a.physRecovery = r.physRecovery; a.splitConfirmed = r.splitConfirmed;
        a.q = r.q; a.qSigma = r.qSigma; a.k = r.k; a.u0 = r.u0; a.theta = r.theta;
        a.windows = Codec.parseWindows(r.windows);
        a.pins = Codec.parsePins(r.pins);
        a.blocksMode = r.blocksMode == null ? BlocksMode.AUTO : BlocksMode.valueOf(r.blocksMode);
        a.maxBlocks = r.maxBlocks;
        a.needsHighEnergy = r.needsHighEnergy; a.away = r.away;
        a.changeoverMin = r.changeoverMin;
        a.sacrifice = r.sacrifice; a.keepMin = r.keepMin; a.nap = r.nap; a.restOnly = r.restOnly; a.archived = r.archived;
        a.pauseFrom = r.pauseFrom == null ? null : java.time.LocalDate.parse(r.pauseFrom);
        a.pauseUntil = r.pauseUntil == null ? null : java.time.LocalDate.parse(r.pauseUntil);
        a.startDate = r.startDate == null ? null : java.time.LocalDate.parse(r.startDate);
        a.startTime = r.startTime;
        a.endDate = r.endDate == null ? null : java.time.LocalDate.parse(r.endDate);
        a.leisure = r.leisure; a.dopamine = r.dopamine; a.dopamineSeen = r.dopamineSeen;
        return a;
    }

    public static ActivityRow toRow(Activity a, int position) {
        ActivityRow r = new ActivityRow();
        r.id = a.id; r.name = a.name;
        r.kind = a.kind == null ? null : a.kind.name();
        r.goalId = a.goalId;
        r.days = Codec.days(a.days);
        r.minBlock = a.minBlock; r.maxBlock = a.maxBlock;
        r.load = a.load; r.recovery = a.recovery;
        r.physLoad = a.physLoad; r.physRecovery = a.physRecovery; r.splitConfirmed = a.splitConfirmed;
        r.q = a.q; r.qSigma = a.qSigma; r.k = a.k; r.u0 = a.u0; r.theta = a.theta;
        r.windows = Codec.windows(a.windows);
        r.pins = Codec.pins(a.pins);
        r.blocksMode = a.blocksMode.name();
        r.maxBlocks = a.maxBlocks;
        r.needsHighEnergy = a.needsHighEnergy; r.away = a.away;
        r.changeoverMin = a.changeoverMin;
        r.sacrifice = a.sacrifice; r.keepMin = a.keepMin; r.nap = a.nap; r.restOnly = a.restOnly; r.archived = a.archived;
        r.pauseFrom = a.pauseFrom == null ? null : a.pauseFrom.toString();
        r.pauseUntil = a.pauseUntil == null ? null : a.pauseUntil.toString();
        r.startDate = a.startDate == null ? null : a.startDate.toString();
        r.startTime = a.startTime;
        r.endDate = a.endDate == null ? null : a.endDate.toString();
        r.leisure = a.leisure; r.dopamine = a.dopamine; r.dopamineSeen = a.dopamineSeen;
        r.position = position;
        return r;
    }

    // ---------------- commitments ----------------
    public static Commitment toModel(CommitmentRow r) {
        Commitment c = new Commitment();
        c.id = r.id; c.name = r.name;
        c.kind = r.kind == null ? null : CommitmentKind.valueOf(r.kind);
        c.load = r.load; c.recovery = r.recovery; c.away = r.away;
        c.physLoad = r.physLoad; c.physRecovery = r.physRecovery;
        c.sessions = Codec.parseSessions(r.sessions);
        c.medianMin = r.medianMin; c.sigma = r.sigma; c.quantile = r.quantile;
        c.outbound = Codec.parseLeg(r.outbound);
        c.inbound = Codec.parseLeg(r.inbound);
        return c;
    }

    public static CommitmentRow toRow(Commitment c, int position) {
        CommitmentRow r = new CommitmentRow();
        r.id = c.id; r.name = c.name;
        r.kind = c.kind == null ? null : c.kind.name();
        r.load = c.load; r.recovery = c.recovery; r.away = c.away;
        r.physLoad = c.physLoad; r.physRecovery = c.physRecovery;
        r.sessions = Codec.sessions(c.sessions);
        r.medianMin = c.medianMin; r.sigma = c.sigma; r.quantile = c.quantile;
        r.outbound = Codec.leg(c.outbound);
        r.inbound = Codec.leg(c.inbound);
        r.position = position;
        return r;
    }

    // ---------------- logs ----------------
    public static LogEntry toModel(LogRow r) {
        LogEntry l = new LogEntry();
        l.id = r.id; l.kind = LogKind.valueOf(r.kind); l.date = date(r.date); l.at = r.at; l.ts = r.ts;
        l.actId = r.actId; l.comId = r.comId;
        l.planned = r.planned; l.actual = r.actual; l.attain = r.attain;
        l.focus = r.focus; l.fatigueAfter = r.fatigueAfter;
        l.mentalAfter = r.mentalAfter; l.physicalAfter = r.physicalAfter; l.restored = r.restored; l.energy = r.energy;
        l.units = r.units;
        l.endAt = r.endAt; l.latencyMin = r.latencyMin; l.wakeups = r.wakeups;
        l.stopHard = r.stopHard; l.nextStart = r.nextStart; l.craving = r.craving;
        l.tags = Codec.parseTags(r.tags); l.note = r.note;
        return l;
    }

    public static LogRow toRow(LogEntry l) {
        LogRow r = new LogRow();
        r.id = l.id; r.kind = l.kind.name(); r.date = date(l.date); r.at = l.at; r.ts = l.ts;
        r.actId = l.actId; r.comId = l.comId;
        r.planned = l.planned; r.actual = l.actual; r.attain = l.attain;
        r.focus = l.focus; r.fatigueAfter = l.fatigueAfter;
        r.mentalAfter = l.mentalAfter; r.physicalAfter = l.physicalAfter; r.restored = l.restored; r.energy = l.energy;
        r.units = l.units;
        r.endAt = l.endAt; r.latencyMin = l.latencyMin; r.wakeups = l.wakeups;
        r.stopHard = l.stopHard; r.nextStart = l.nextStart; r.craving = l.craving;
        r.tags = Codec.tags(l.tags); r.note = l.note;
        return r;
    }

    // ---------------- whole configuration ----------------
    public static Config toConfig(ParamsRow p, List<GoalRow> goals,
                                  List<ActivityRow> activities, List<CommitmentRow> commitments) {
        Config c = new Config();
        c.params = toModel(p);
        for (GoalRow r : goals) c.goals.add(toModel(r));
        for (ActivityRow r : activities) c.activities.add(toModel(r));
        for (CommitmentRow r : commitments) c.commitments.add(toModel(r));
        return c;
    }

    public static List<GoalRow> goalRows(Config c) {
        List<GoalRow> out = new ArrayList<>();
        for (int i = 0; i < c.goals.size(); i++) out.add(toRow(c.goals.get(i), i));
        return out;
    }

    public static List<ActivityRow> activityRows(Config c) {
        List<ActivityRow> out = new ArrayList<>();
        for (int i = 0; i < c.activities.size(); i++) out.add(toRow(c.activities.get(i), i));
        return out;
    }

    public static List<CommitmentRow> commitmentRows(Config c) {
        List<CommitmentRow> out = new ArrayList<>();
        for (int i = 0; i < c.commitments.size(); i++) out.add(toRow(c.commitments.get(i), i));
        return out;
    }

    /**
     * Fingerprint of everything that can change a plan. A stored snapshot whose
     * hash no longer matches is stale, which is how the app knows to offer a
     * rebuild instead of silently showing an old week.
     */
    public static String configHash(Config c) {
        StringBuilder sb = new StringBuilder();
        Params p = c.params;
        sb.append(p.sleepStart).append(p.sleepEnd).append(p.phi)
          .append(p.a0).append(p.aC).append(p.aH).append(p.aF)
          .append(p.fSafe).append(p.idleRecovery).append(p.slackRatio)
          .append(p.lcbBeta).append(p.switchPenalty).append(p.fatiguePenalty).append(p.horizonDays)
          .append(p.aP).append(p.pTheta).append(p.fSafeP).append(p.idleRecoveryP).append(p.kMP);
        for (GoalRow r : goalRows(c))
            sb.append(r.id).append(r.mode).append(r.totalUnits).append(r.doneUnits)
              .append(r.hoursPerUnit).append(r.deadline).append(r.weeklyFloorH)
              .append(r.maxPerDayH).append(r.weight);
        for (ActivityRow r : activityRows(c))
            sb.append(r.id).append(r.kind).append(r.goalId).append(r.days).append(r.minBlock)
              .append(r.maxBlock).append(r.load).append(r.recovery).append(r.physLoad).append(r.physRecovery).append(r.q).append(r.qSigma)
              .append(r.k).append(r.u0).append(r.theta).append(r.windows).append(r.pins)
              .append(r.blocksMode).append(r.maxBlocks).append(r.away).append(r.changeoverMin);
        // Appended only when set, so a configuration saved before these existed keeps its hash
        // and its stored plan is not reported out of date for no reason.
        for (ActivityRow r : activityRows(c))
            if (r.sacrifice != null || r.keepMin != null)
                sb.append("cut").append(r.id).append(r.sacrifice).append(r.keepMin);
        for (ActivityRow r : activityRows(c))
            if (r.nap) sb.append("nap").append(r.id);
        for (ActivityRow r : activityRows(c))
            if (r.restOnly) sb.append("rest").append(r.id);
        for (ActivityRow r : activityRows(c))
            if (r.startDate != null) sb.append("start").append(r.id).append(r.startDate);
        for (ActivityRow r : activityRows(c)) {
            if (r.startTime != null) sb.append("at").append(r.id).append(r.startTime);
            if (r.endDate != null) sb.append("end").append(r.id).append(r.endDate);
        }
        for (GoalRow r : goalRows(c))
            if (r.deadlineTime != null) sb.append("due").append(r.id).append(r.deadlineTime);
        if (c.planFrom != null) sb.append("from").append(c.planFrom);
        for (ActivityRow r : activityRows(c))
            if (r.leisure) sb.append("fun").append(r.id).append(r.dopamine);
        if (p.dopCap != null || p.dopHalfLifeH != null || p.aD != null)
            sb.append("dop").append(p.dopCap).append(p.dopHalfLifeH).append(p.aD);
        for (CommitmentRow r : commitmentRows(c))
            sb.append(r.id).append(r.kind).append(r.load).append(r.recovery).append(r.physLoad).append(r.physRecovery).append(r.away)
              .append(r.sessions).append(r.medianMin).append(r.sigma).append(r.quantile)
              .append(r.outbound).append(r.inbound);
        return Integer.toHexString(sb.toString().hashCode());
    }
}

package com.chronos.model;

/**
 * Every user-supplied number the engine needs. Boxed types throughout: null
 * means "not answered yet", which {@link com.chronos.validate.Readiness} turns
 * into a blocking issue rather than a silent default.
 *
 * tauR and tauD are the only presets. They are time constants of the
 * two-process sleep model, not preferences, and asking a user for them would
 * be asking a question nobody can answer.
 */
public class Params {
    public String sleepStart;      // "HH:mm"
    public String sleepEnd;
    public Integer phi;            // minutes past midnight of peak alertness

    public Double a0;              // baseline capacity
    public Double aC;              // weight on the circadian rhythm
    public Double aH;              // weight on sleep pressure
    public Double aF;              // weight on accumulated mental fatigue
    public Double aP;              // weight on physical fatigue above its threshold
    public Double pTheta;          // physical fatigue above which mental capacity suffers

    public double tauR = 18.0;     // hours: sleep pressure rise constant
    public double tauD = 4.2;      // hours: sleep pressure decay constant
    public double fMax = 1.0;

    public Double fSafe;           // mental fatigue above this is penalised
    public Double idleRecovery;    // mental fatigue given back per idle waking hour
    public Double fSafeP;          // physical fatigue above this is penalised
    public Double idleRecoveryP;   // physical fatigue given back per idle waking hour
    public Double kMP;             // how much mental fatigue makes physical effort cost more
    public Double slackRatio;      // share of free time never scheduled
    public Double lcbBeta;         // pessimism: how far below q we plan
    public Double switchPenalty;   // cost of starting one more block
    public Double fatiguePenalty;  // cost of one unit-hour above fSafe
    public Integer horizonDays;

    /*
     * Stimulation (your request of 25 Oct 2026). Leisure raises it; it fades by itself, faster
     * while you do goal work; above zero it takes a little off the capacity for goal work, and
     * the plan never lets it climb past the ceiling. Null means the default.
     */
    public Double dopCap;          // the most stimulation a day may reach (default 1.0)
    public Double dopHalfLifeH;    // hours for it to halve when nothing adds to it (default 1.5)
    public Double aD;              // capacity taken per unit of it (default 0.12)

    public static final double DOP_CAP = 1.0, DOP_HALF_LIFE_H = 1.5, A_D = 0.12;
    public double dopCapOr() { return dopCap == null ? DOP_CAP : dopCap; }
    public double dopHalfLifeOr() { return dopHalfLifeH == null ? DOP_HALF_LIFE_H : dopHalfLifeH; }
    public double aDOr() { return aD == null ? A_D : aD; }

    public Params copy() {
        Params p = new Params();
        p.sleepStart = sleepStart; p.sleepEnd = sleepEnd; p.phi = phi;
        p.a0 = a0; p.aC = aC; p.aH = aH; p.aF = aF; p.aP = aP; p.pTheta = pTheta;
        p.fSafeP = fSafeP; p.idleRecoveryP = idleRecoveryP; p.kMP = kMP;
        p.tauR = tauR; p.tauD = tauD; p.fMax = fMax;
        p.fSafe = fSafe; p.idleRecovery = idleRecovery; p.slackRatio = slackRatio;
        p.lcbBeta = lcbBeta; p.switchPenalty = switchPenalty;
        p.fatiguePenalty = fatiguePenalty; p.horizonDays = horizonDays;
        p.dopCap = dopCap; p.dopHalfLifeH = dopHalfLifeH; p.aD = aD;
        return p;
    }
}

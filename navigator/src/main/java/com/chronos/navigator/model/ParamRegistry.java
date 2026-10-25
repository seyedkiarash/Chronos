package com.chronos.navigator.model;

import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.Source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every parameter a layer reads, with the named values it needs.
 *
 * Constants written in the architecture spec (the forgetting factor, the
 * shrinkage strength, …) are seeded as EXPLICIT_PRIOR at import: stated,
 * visible and editable, never buried in code. Values neither the spec nor the
 * goal graph gives are left missing and reported, with a suggestion the user
 * can accept — accepting it makes it theirs (source USER).
 */
public final class ParamRegistry {
    private ParamRegistry() {}

    /**
     * @param fields     the named values the layer reads
     * @param stage      the implementation stage whose layer reads it
     * @param specValues values the architecture spec states, seeded at import; null if it states none
     * @param suggest    values offered to the user for what is missing; never applied unasked
     */
    public record Spec(String key, Dist dist, List<String> fields, int stage, String why,
                       Map<String, Double> specValues, Map<String, Double> suggest) {}

    public static final String SPEC_NOTE = "From the architecture spec (1 Oct 2026). ";
    /** For the few numbers the spec needs but does not give: said plainly, and editable like the rest. */
    public static final String ASSUMPTION_NOTE = "Implementation assumption, not in the spec — change it if it is wrong. ";

    /** Stages built so far. A parameter only a later stage reads is not asked for yet. */
    public static final int BUILT_STAGE = 6;

    public static final List<Spec> ALL = new ArrayList<>();

    private static Map<String, Double> m(Object... kv) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return out;
    }

    static {
        ALL.add(new Spec("adherence", Dist.BETA, List.of("a", "b"), 2,
            "Each planned session is a trial — done or skipped. Beta(a, b) is your prior on the share done.",
            null, m("a", 16, "b", 4)));
        ALL.add(new Spec("forgetting.lambda", Dist.POINT, List.of("value"), 2,
            "Each week old evidence is multiplied by this, so the model follows habits that change.",
            m("value", 0.98), null));
        ALL.add(new Spec("bias.default", Dist.LOGNORMAL, List.of("median", "sigma"), 2,
            "How much longer than planned a new kind of work takes, before the log says anything about it.",
            null, m("median", 1.3, "sigma", 0.35)));
        ALL.add(new Spec("kalman.level_q", Dist.POINT, List.of("value"), 2,
            "How far the speed of a kind of work may drift in a day, as a spread on the log scale. Larger: "
                + "recent days count more and old ones fade faster.", m("value", 0.05), null));
        ALL.add(new Spec("kalman.slope_q", Dist.POINT, List.of("value"), 2,
            "How fast the trend of that speed may itself change in a day (log scale per day).", m("value", 0.005), null));
        ALL.add(new Spec("kalman.slope_prior_sd", Dist.POINT, List.of("value"), 2,
            "How steep a trend is believed before the log shows one: a spread of the change per day (log scale).",
            m("value", 0.02), null));
        ALL.add(new Spec("adherence.half_life_days", Dist.POINT, List.of("value"), 2,
            "Days after which a logged day counts half as much in how much of the planned time you work.",
            m("value", 7), null));
        ALL.add(new Spec("adherence.activity_strength", Dist.POINT, List.of("value"), 2,
            "Each activity's own adherence starts from your overall one as if by this many days.", m("value", 4), null));
        ALL.add(new Spec("bias.obs_sigma", Dist.POINT, List.of("value"), 2,
            "Spread of one session's log(actual / planned) around the class's true bias.",
            m("value", 0.35), null));
        for (String t : new String[]{"IDEAL", "BUSY", "STRESS"}) {
            boolean stress = t.equals("STRESS");
            ALL.add(new Spec("capacity." + t, Dist.NORMAL, List.of("mean", "tau0", "sigma"), 2,
                "Goal hours on a " + t + " day. mean is your prior; tau0 is how unsure that prior is; "
                    + "sigma is how much one such day varies around it (all in hours).",
                null, stress ? m("tau0", 1.0, "sigma", 1.0) : m("tau0", 1.5, "sigma", 2.0)));
        }
        ALL.add(new Spec("energy.shrink_k", Dist.POINT, List.of("value"), 2,
            "An hour's average energy is pulled toward your overall average as if by this many extra check-ins.",
            m("value", 3), null));
        ALL.add(new Spec("daytype.prior_strength", Dist.POINT, List.of("value"), 2,
            "How many days the stated mix of a regime counts for before your ticked day types take over.",
            m("value", 10), null));
        // stage 3 — forecast
        ALL.add(new Spec("forecast.runs_nightly", Dist.POINT, List.of("value"), 3,
            "Futures simulated by the nightly forecast.", m("value", 5000), null));
        ALL.add(new Spec("forecast.runs_weekly", Dist.POINT, List.of("value"), 3,
            "Futures simulated by the weekly forecast, which runs with the allocation.", m("value", 20000), null));
        ALL.add(new Spec("forecast.budget_s", Dist.POINT, List.of("value"), 3,
            "Seconds the nightly forecast may take on the phone; over it, fewer futures are simulated.",
            m("value", 5), null));
        ALL.add(new Spec("forecast.daily_weeks", Dist.POINT, List.of("value"), 3,
            "Weeks simulated day by day; after them the forecast steps a week at a time.", m("value", 6), null));
        ALL.add(new Spec("forecast.noise_sigma", Dist.POINT, List.of("value"), 3,
            "Spread of the day-to-day noise on the hours actually worked (log scale).", m("value", 0.2), null));
        ALL.add(new Spec("allocate.default_value", Dist.POINT, List.of("value"), 3,
            "Value of a node that has no standalone value in the graph and no goal weight in Chronos.",
            m("value", 0.5), null));
        // stage 4 — weekly allocation
        ALL.add(new Spec("allocate.runs", Dist.POINT, List.of("value"), 4,
            "Futures simulated for each candidate when the week's hours are shared out.", m("value", 500), null));
        ALL.add(new Spec("allocate.chunk_h", Dist.POINT, List.of("value"), 4,
            "Hours handed out at a time when the week is shared out.", m("value", 2), null));
        ALL.add(new Spec("allocate.horizon_weeks", Dist.POINT, List.of("value"), 4,
            "Deadlines up to this many weeks ahead count when the week is shared out.", m("value", 26), null));
        ALL.add(new Spec("allocate.min_gain", Dist.POINT, List.of("value"), 4,
            "When another chunk of hours would raise the objective by less than this, the rest of the week stays free.",
            m("value", 0.001), null));
        ALL.add(new Spec("allocate.weekday", Dist.POINT, List.of("value"), 4,
            "Day the week's hours are shared out, at night (0 Saturday … 5 Thursday, 6 Friday).", m("value", 5), null));
        // stage 5 — triggers and the LLM
        ALL.add(new Spec("trigger.prob_drop", Dist.POINT, List.of("below", "reset"), 5,
            "prob_drop fires when a deadline's probability falls below \"below\", and not again until it has "
                + "been back above \"reset\" (hysteresis).", m("below", 0.60, "reset", 0.70), null));
        ALL.add(new Spec("trigger.prob_jump", Dist.POINT, List.of("delta", "days"), 5,
            "prob_jump fires when a deadline's probability rises by more than \"delta\" within \"days\" days.",
            m("delta", 0.15, "days", 7), null));
        ALL.add(new Spec("trigger.cooldown_h", Dist.POINT, List.of("value"), 5,
            "Hours a trigger stays quiet after it fired.", m("value", 24), null));
        ALL.add(new Spec("trigger.retry_days", Dist.POINT, List.of("value"), 5,
            "Days before a trigger whose proposal you rejected (or that failed the checks) may ask again.",
            m("value", 7), null));
        ALL.add(new Spec("effects.runs", Dist.POINT, List.of("value"), 5,
            "Futures simulated to measure what each proposed option would change.", m("value", 1500), null));
        // stage 6 — onboarding and the rolling wave
        ALL.add(new Spec("info.runs", Dist.POINT, List.of("value"), 6,
            "Futures simulated per variant when ranking which of your answers would change the forecast most.",
            m("value", 400), null));
        ALL.add(new Spec("info.budget_s", Dist.POINT, List.of("value"), 6,
            "Seconds the ranking of questions may take on the phone; past it, the uncertain inputs not reached are left out.",
            m("value", 40), null));
        // the Navigator in control — your decisions of 13 Oct 2026
        ALL.add(new Spec("guard.floor.critical", Dist.POINT, List.of("value"), 6,
            "A change may not push a critical goal's odds below this, read at the low end of the 80% band "
                + "(if they are already below it, a change may not lower them further).", m("value", 0.75), null));
        ALL.add(new Spec("guard.floor.important", Dist.POINT, List.of("value"), 6,
            "The same for an important goal. Optional goals have no floor.", m("value", 0.50), null));
        ALL.add(new Spec("bands.worlds", Dist.POINT, List.of("value"), 6,
            "Worlds simulated for the 80% band: each draws your speed, adherence and goal hours once.", m("value", 40), null));
        ALL.add(new Spec("bands.futures", Dist.POINT, List.of("value"), 6,
            "Futures simulated inside each world for the 80% band.", m("value", 40), null));
        ALL.add(new Spec("crew.max_sleep_shift_min", Dist.POINT, List.of("value"), 6,
            "The most bedtime or wake time may move in one week's proposal: the body clock follows slowly.",
            m("value", 30), null));
        ALL.add(new Spec("data_weight.default", Dist.POINT, List.of("value"), 2,
            "How much one logged day counts in a regime that does not set its own data weight.",
            m("value", 1.0), null));
    }

    /** Seeded values the spec does not state. */
    public static final java.util.Set<String> ASSUMED = java.util.Set.of(
        "forecast.noise_sigma", "allocate.default_value", "allocate.min_gain", "data_weight.default",
        "trigger.retry_days", "effects.runs", "info.runs", "info.budget_s",
        "kalman.level_q", "kalman.slope_q", "kalman.slope_prior_sd", "adherence.half_life_days", "adherence.activity_strength",
        "bands.worlds", "bands.futures", "crew.max_sleep_shift_min");

    /** Whether a parameter is one the registry seeds rather than one the file states. */
    public static boolean seeded(String key) {
        Spec s = spec(key);
        return s != null && s.specValues != null;
    }

    public static Spec spec(String key) {
        for (Spec s : ALL) if (s.key.equals(key)) return s;
        return null;
    }

    /** Adds what the spec states and the graph does not already have. */
    public static void seed(Graph g, long now) {
        for (Spec s : ALL) {
            if (s.specValues == null) continue;
            Param p = g.params.get(s.key);
            if (p == null) {
                p = new Param(s.key, s.dist, Source.EXPLICIT_PRIOR, (ASSUMED.contains(s.key) ? ASSUMPTION_NOTE : SPEC_NOTE) + s.why);
                p.values.putAll(s.specValues);
                p.updatedAt = now;
                g.params.put(s.key, p);
            }
        }
    }

    /** The fields of a required parameter that have no value yet. */
    public static List<String> missing(Graph g, Spec s) {
        Param p = g.params.get(s.key);
        List<String> out = new ArrayList<>();
        for (String f : s.fields) if (p == null || p.get(f) == null) out.add(f);
        return out;
    }
}

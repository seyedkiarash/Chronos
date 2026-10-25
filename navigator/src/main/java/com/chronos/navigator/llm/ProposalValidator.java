package com.chronos.navigator.llm;

import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.Owner;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Overrides;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.model.Regime;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The pipeline every LLM answer goes through before it may become a proposal
 * (spec, "LLM layer"): 1 shape, 2 references, 3 consent, 4 ownership,
 * 5 numbers. The first failure rejects the answer and its reason is kept.
 * Passing yields operations that still wait for the user's approval.
 */
public final class ProposalValidator {
    private ProposalValidator() {}

    public record Source(String url, String usedFor) {}

    /** What an option would change in the forecast, measured by the app itself, never stated by the LLM. */
    public record Effect(String nodeId, double before, double after) {}

    /**
     * @param ops       what approving would write; empty when rejected
     * @param reason    why it was rejected, or null
     * @param checks    the steps passed, in order, for the record
     * @param title     one option's name, when the answer offered several; else null
     * @param effects   the measured change in P(by deadline) of the nodes concerned; empty until measured
     */
    public record Result(boolean ok, List<Op> ops, String summary, String rationale,
                         List<Source> sources, String reason, List<String> checks,
                         String title, List<Effect> effects) {

        public Result(boolean ok, List<Op> ops, String summary, String rationale,
                      List<Source> sources, String reason, List<String> checks) {
            this(ok, ops, summary, rationale, sources, reason, checks, null, List.of());
        }

        static Result reject(String reason, List<String> checks) {
            return new Result(false, List.of(), null, null, List.of(), reason, checks);
        }

        public Result withEffects(List<Effect> e) {
            return new Result(ok, ops, summary, rationale, sources, reason, checks, title, e);
        }
    }

    /** The last JSON object in the model's text: answers end with one, sometimes inside a code fence. */
    public static JsonObject extractJson(String text) {
        if (text == null) return null;
        int end = text.lastIndexOf('}');
        while (end >= 0) {
            int depth = 0;
            for (int i = end; i >= 0; i--) {
                char c = text.charAt(i);
                if (c == '}') depth++;
                else if (c == '{' && --depth == 0) {
                    try {
                        JsonElement el = JsonParser.parseString(text.substring(i, end + 1));
                        if (el.isJsonObject()) return el.getAsJsonObject();
                    } catch (RuntimeException ignored) { }
                    break;
                }
            }
            end = text.lastIndexOf('}', end - 1);
        }
        return null;
    }

    public static Result validate(Graph g, LocalDate today, LlmRequest req, String answerText, List<String> searchedUrls) {
        List<String> checks = new ArrayList<>();
        JsonObject o = extractJson(answerText);
        if (o == null) return Result.reject("The answer contains no JSON object", checks);

        // 1. shape
        String kind = req.targetKind();
        List<String> required = switch (kind) {
            case "REGIME_GAP" -> List.of("regimes");
            case "REGIME_MIX" -> List.of("ideal", "busy", "stress", "strength_days");
            case "NODE_EFFORT" -> isStream(g, req.subject()) ? List.of("weekly_floor_hours") : List.of("p50_hours", "p90_hours");
            case "NODE_PRIOR" -> List.of("probability", "strength_cases");
            default -> null;
        };
        if (required == null) return Result.reject("Unknown question kind " + kind, checks);
        for (String f : required) if (!o.has(f) || o.get(f).isJsonNull())
            return Result.reject("The answer has no " + f, checks);
        if (!o.has("consent_self_check") || !o.get("consent_self_check").isJsonObject()
                || !o.getAsJsonObject("consent_self_check").has("involves_other_person"))
            return Result.reject("The answer has no consent self-check", checks);
        checks.add("shape");

        // 2. references
        Node node = null;
        if (kind.startsWith("NODE_")) {
            node = g.node(req.subject());
            if (node == null) return Result.reject("The node " + req.subject() + " is no longer in the graph", checks);
        }
        if (kind.equals("REGIME_MIX") && g.regime(req.subject()) == null)
            return Result.reject("The regime " + req.subject() + " is no longer in the graph", checks);
        checks.add("references");

        // 3. consent: the model's own statement, then the rules
        JsonElement involves = o.getAsJsonObject("consent_self_check").get("involves_other_person");
        if (!involves.isJsonPrimitive() || !involves.getAsJsonPrimitive().isBoolean())
            return Result.reject("The consent self-check is not a yes or no", checks);
        if (involves.getAsBoolean())
            return Result.reject("The answer says it involves another person; such things are not proposed", checks);
        if (node != null && EstimatePrior.privateNodes(g).contains(node.id))
            return Result.reject(node.id + " involves another person and is outside what the LLM may change", checks);
        checks.add("consent");

        // 4. ownership: an advisor's node is discussed, never changed
        boolean discussOnly = node != null && node.owner == Owner.ADVISOR;
        checks.add(discussOnly ? "ownership (advisor: to discuss only)" : "ownership");

        // 5. numbers, and the operations they become
        String rationale = str(o, "rationale");
        List<Op> ops = new ArrayList<>();
        String failure = switch (kind) {
            case "REGIME_GAP" -> regimeGap(g, req, o, ops);
            case "REGIME_MIX" -> regimeMix(g, req.subject(), o, ops, rationale, update(req));
            case "NODE_EFFORT" -> nodeEffort(g, node, o, ops, rationale, discussOnly, update(req));
            case "NODE_PRIOR" -> nodePrior(g, node, o, ops, rationale, discussOnly,
                req.target().has("update") && req.target().get("update").getAsBoolean());
            default -> "Unknown question kind";
        };
        if (failure != null) return Result.reject(failure, checks);
        checks.add("numbers");

        List<Source> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        // Pages are listed only when the model could search; one that could not would be naming pages it never read.
        JsonArray src = req.webSearch() && o.has("sources") && o.get("sources").isJsonArray()
            ? o.getAsJsonArray("sources") : new JsonArray();
        for (JsonElement e : src) {
            if (!e.isJsonObject()) continue;
            String url = str(e.getAsJsonObject(), "url");
            if (url != null && url.startsWith("http") && seen.add(url))
                sources.add(new Source(url, str(e.getAsJsonObject(), "used_for")));
        }
        for (String url : searchedUrls) if (seen.add(url)) sources.add(new Source(url, "found by the search"));
        return new Result(true, ops, str(o, "summary"), rationale, sources, null, checks);
    }

    /** Step 3's first half, shared by every question: the model's own statement about other people. */
    static String consentSelfCheck(JsonObject o) {
        if (!o.has("consent_self_check") || !o.get("consent_self_check").isJsonObject()
                || !o.getAsJsonObject("consent_self_check").has("involves_other_person"))
            return "The answer has no consent self-check";
        JsonElement involves = o.getAsJsonObject("consent_self_check").get("involves_other_person");
        if (!involves.isJsonPrimitive() || !involves.getAsJsonPrimitive().isBoolean())
            return "The consent self-check is not a yes or no";
        if (involves.getAsBoolean())
            return "The answer says it involves another person; such things are not proposed";
        return null;
    }

    /** The pages the model listed (only when it could search) and the ones its searches returned. */
    static List<Source> sources(LlmRequest req, JsonObject o, List<String> searchedUrls) {
        List<Source> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JsonArray src = req.webSearch() && o.has("sources") && o.get("sources").isJsonArray()
            ? o.getAsJsonArray("sources") : new JsonArray();
        for (JsonElement e : src) {
            if (!e.isJsonObject()) continue;
            String url = str(e.getAsJsonObject(), "url");
            if (url != null && url.startsWith("http") && seen.add(url))
                sources.add(new Source(url, str(e.getAsJsonObject(), "used_for")));
        }
        for (String url : searchedUrls) if (seen.add(url)) sources.add(new Source(url, "found by the search"));
        return sources;
    }

    // ----------------------------------------------------------- per question

    private static String regimeGap(Graph g, LlmRequest req, JsonObject o, List<Op> ops) {
        LocalDate from = LocalDate.parse(req.target().get("from").getAsString());
        LocalDate to = LocalDate.parse(req.target().get("to").getAsString());
        if (!o.get("regimes").isJsonArray() || o.getAsJsonArray("regimes").isEmpty())
            return "The answer proposes no regimes";
        LocalDate expect = from;
        Set<String> ids = new HashSet<>();
        for (JsonElement e : o.getAsJsonArray("regimes")) {
            if (!e.isJsonObject()) return "A proposed regime is not an object";
            JsonObject r = e.getAsJsonObject();
            String id = str(r, "id");
            if (id == null || !id.matches("[A-Za-z0-9_]{1,40}")) return "A proposed regime has no usable id";
            id = id.toUpperCase(Locale.ROOT);
            if (!ids.add(id) || g.regime(id) != null) return "The regime id " + id + " is already taken";
            LocalDate a = date(r, "from"), b = date(r, "to");
            if (a == null || b == null) return id + ": dates are missing or not YYYY-MM-DD";
            if (!a.equals(expect)) return id + " starts on " + a + "; the stretches must run on from " + expect + " without a gap or overlap";
            if (b.isBefore(a)) return id + " ends before it starts";
            if (b.isAfter(to)) return id + " runs past " + to;
            Map<String, Double> mix = mix(r);
            if (mix == null) return id + ": the shares of days must be numbers from 0 to 1 adding up to 1";
            Double strength = num(r, "strength_days");
            if (strength == null || strength <= 0 || strength > 30) return id + ": strength_days must be above 0 and at most 30";
            mix.put("strength", strength);
            String why = str(r, "rationale");
            ops.add(new Op(Overrides.regimeKey(id, "span"), Dist.POINT,
                Map.of("from", (double) a.toEpochDay(), "to", (double) b.toEpochDay()), Map.of(),
                "Regime " + id + " proposed by the LLM" + (why == null ? "" : ": " + why), false));
            ops.add(new Op(Overrides.regimeKey(id, "mix"), Dist.DIRICHLET, mix, Map.of(),
                "Mix of days in " + id + ", proposed by the LLM" + (why == null ? "" : ": " + why), false));
            expect = b.plusDays(1);
        }
        if (!expect.equals(to.plusDays(1))) return "The stretches stop at " + expect.minusDays(1) + ", not at " + to;
        return null;
    }

    /** A question that refines a value the file (or an earlier answer) already gives: the answer replaces it. */
    static boolean update(LlmRequest req) {
        return req.target().has("update") && req.target().get("update").getAsBoolean();
    }

    private static String regimeMix(Graph g, String id, JsonObject o, List<Op> ops, String why, boolean update) {
        Map<String, Double> mix = mix(o);
        if (mix == null) return "The shares of days must be numbers from 0 to 1 adding up to 1";
        Double strength = num(o, "strength_days");
        if (strength == null || strength <= 0 || strength > 30) return "strength_days must be above 0 and at most 30";
        mix.put("strength", strength);
        if (update) {
            mix.put("override", 1.0);
            Param old = g.params.get(Overrides.regimeKey(id, "mix"));
            Map<String, Double> was = new LinkedHashMap<>();
            if (old != null) old.values.forEach((k, v) -> { if (k.startsWith("was")) was.put(k, v); });
            mix.putAll(was.isEmpty() ? Overrides.was(g.regime(id)) : was);
        }
        ops.add(new Op(Overrides.regimeKey(id, "mix"), Dist.DIRICHLET, mix, before(g, Overrides.regimeKey(id, "mix")),
            "Mix of days in " + id + ", proposed by the LLM" + (why == null ? "" : ": " + why), false));
        return null;
    }

    private static String nodeEffort(Graph g, Node n, JsonObject o, List<Op> ops, String why, boolean discuss, boolean update) {
        String note = "Proposed by the LLM" + (why == null ? "" : ": " + why);
        if (isStream(g, n.id)) {
            Double floor = num(o, "weekly_floor_hours");
            if (floor == null || floor < 0 || floor > 80) return "weekly_floor_hours must be between 0 and 80";
            if (n.maxHPerDay != null && floor > 7 * n.maxHPerDay) return "The weekly floor does not fit under the node's daily cap";
            ops.add(new Op(Overrides.nodeKey(n.id, "floor"), Dist.POINT, Map.of("value", floor),
                before(g, Overrides.nodeKey(n.id, "floor")), note, discuss));
            return null;
        }
        Double p50 = num(o, "p50_hours"), p90 = num(o, "p90_hours");
        if (p50 == null || p90 == null || p50 <= 0) return "p50_hours must be above zero";
        if (p90 < p50) return "p90_hours is below p50_hours";
        if (p90 > 20000) return "p90_hours is beyond any plausible amount of work";
        Map<String, Double> v = new LinkedHashMap<>();
        v.put("p50", p50);
        v.put("p90", p90);
        if (update) {
            v.put("override", 1.0);
            v.putAll(was(g, n, "effort"));
        }
        ops.add(new Op(Overrides.nodeKey(n.id, "effort"), Dist.LOGNORMAL, v,
            before(g, Overrides.nodeKey(n.id, "effort")), note, discuss));
        return null;
    }

    private static String nodePrior(Graph g, Node n, JsonObject o, List<Op> ops, String why, boolean discuss, boolean update) {
        Double p = num(o, "probability"), k = num(o, "strength_cases");
        if (p == null || p <= 0 || p >= 1) return "probability must be strictly between 0 and 1";
        if (k == null || k <= 0 || k > 200) return "strength_cases must be above 0 and at most 200";
        Map<String, Double> v = new LinkedHashMap<>();
        v.put("a", p * k);
        v.put("b", (1 - p) * k);
        if (update) {
            // A later decision over what the file states: marked, with what it replaces.
            v.put("override", 1.0);
            v.putAll(was(g, n, "success"));
        }
        ops.add(new Op(Overrides.nodeKey(n.id, "success"), Dist.BETA, v,
            before(g, Overrides.nodeKey(n.id, "success")),
            "Proposed by the LLM" + (why == null ? "" : ": " + why), discuss));
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isStream(Graph g, String id) {
        Node n = g.node(id);
        return n != null && n.type == com.chronos.navigator.model.Kinds.NodeType.STREAM;
    }

    static Map<String, Double> before(Graph g, String key) {
        Param p = g.params.get(key);
        return p == null ? Map.of() : new LinkedHashMap<>(p.values);
    }

    /**
     * What a later decision replaces, as {@link Overrides#was} records it: carried over from an
     * earlier decision on the same value, so it always names what the file said.
     */
    static Map<String, Double> was(Graph g, Node n, String what) {
        Param old = g.params.get(Overrides.nodeKey(n.id, what));
        Map<String, Double> out = new LinkedHashMap<>();
        if (old != null) for (Map.Entry<String, Double> e : old.values.entrySet())
            if (e.getKey().startsWith("was")) out.put(e.getKey(), e.getValue());
        return out.isEmpty() ? Overrides.was(n, what) : out;
    }

    /** IDEAL, BUSY and STRESS shares, normalised when they add up to 1 within rounding; null otherwise. */
    private static Map<String, Double> mix(JsonObject r) {
        Double i = num(r, "ideal"), b = num(r, "busy"), s = num(r, "stress");
        if (i == null || b == null || s == null || i < 0 || b < 0 || s < 0) return null;
        double sum = i + b + s;
        if (Math.abs(sum - 1) > 0.02) return null;
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("IDEAL", i / sum);
        out.put("BUSY", b / sum);
        out.put("STRESS", s / sum);
        return out;
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        JsonElement e = o == null ? null : o.get(k);
        if (e != null && e.isJsonArray())
            for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
        return out;
    }

    /** A number, also when the model wrote it as text ("5", "5.5", "۵"); null otherwise. */
    static Double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return null;
        double v;
        if (e.getAsJsonPrimitive().isNumber()) v = e.getAsDouble();
        else if (e.getAsJsonPrimitive().isString()) {
            try { v = Double.parseDouble(latinDigits(e.getAsString()).trim().replace(',', '.')); }
            catch (NumberFormatException x) { return null; }
        } else return null;
        return Double.isFinite(v) ? v : null;
    }

    /** Persian and Arabic digits as Latin ones. */
    static String latinDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '\u06F0' && c <= '\u06F9') b.append((char) ('0' + c - '\u06F0'));
            else if (c >= '\u0660' && c <= '\u0669') b.append((char) ('0' + c - '\u0660'));
            else b.append(c);
        }
        return b.toString();
    }

    static LocalDate date(JsonObject o, String k) {
        String s = str(o, k);
        if (s == null) return null;
        // 2026-10-25, also written 2026/10/25 or with a time after it.
        String t = latinDigits(s).trim().replace('/', '-');
        if (t.length() > 10 && t.charAt(10) == 'T') t = t.substring(0, 10);
        try { return LocalDate.parse(t); } catch (DateTimeParseException e) { return null; }
    }
}

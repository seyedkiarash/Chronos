package com.chronos.navigator.model;

import com.chronos.navigator.model.Kinds.DayType;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.EdgeKind;
import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Kinds.Owner;
import com.chronos.navigator.model.Kinds.Source;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the goal-graph JSON (schema 0.2) into a {@link Graph}.
 *
 * Reading never guesses. A value of the wrong shape becomes an INVALID issue
 * naming the node and the field; a value that is simply absent stays null and
 * is left for {@link Requirements} to ask about.
 */
public final class GraphJson {
    private GraphJson() {}

    public record Read(Graph graph, List<Issue> issues) {}

    public static Read read(String json) {
        List<Issue> issues = new ArrayList<>();
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                issues.add(Issue.invalid("file", "The file is not a JSON object"));
                return new Read(null, issues);
            }
            root = el.getAsJsonObject();
        } catch (RuntimeException e) {
            issues.add(Issue.invalid("file", "The file is not valid JSON: " + e.getMessage()));
            return new Read(null, issues);
        }
        return new Read(read(root, issues), issues);
    }

    /** Reads an already-parsed document. Problems are appended to {@code issues}. */
    public static Graph read(JsonObject root, List<Issue> issues) {
        Graph g = new Graph();
        g.raw = root.deepCopy();
        g.raw.remove("nodes");

        g.schemaVersion = str(root, "schema_version");
        JsonObject meta = obj(root, "meta");
        if (meta != null) {
            g.title = str(meta, "title");
            g.timezone = str(meta, "timezone");
            g.created = date(meta, "created", "meta", issues);
            g.updated = date(meta, "updated", "meta", issues);
            g.horizonEnd = date(meta, "horizon_end", "meta", issues);
        }
        if (g.schemaVersion == null) issues.add(Issue.invalid("file", "schema_version is missing"));
        if (g.horizonEnd == null) issues.add(Issue.invalid("meta", "meta.horizon_end is missing"));

        readPolicies(g, obj(root, "policies"), issues);
        readRegimes(g, obj(root, "regimes"), issues);
        readUserState(g, obj(root, "user_state"));

        JsonArray nodes = arr(root, "nodes");
        if (nodes == null || nodes.isEmpty()) {
            issues.add(Issue.invalid("file", "The file has no nodes"));
            return g;
        }
        for (JsonElement el : nodes) {
            if (!el.isJsonObject()) { issues.add(Issue.invalid("nodes", "A node is not an object")); continue; }
            Node n = node(el.getAsJsonObject(), issues);
            if (n.id == null) continue;
            if (g.nodes.containsKey(n.id)) issues.add(Issue.invalid(n.id, "Two nodes share the id " + n.id));
            g.nodes.put(n.id, n);
        }
        g.edges.addAll(edges(g.nodes.values()));
        return g;
    }

    /**
     * The graph as stored: the file without its nodes and calendar, the nodes
     * and regimes as rows, and the parameters as rows. The parameters are the
     * stored ones, not the file's again — those may since have been learned
     * from the log or answered in the app.
     */
    public static Graph assemble(JsonObject rest, List<JsonObject> nodes, List<JsonObject> regimes,
                                 Iterable<Param> params) {
        JsonObject root = rest == null ? new JsonObject() : rest.deepCopy();
        JsonArray na = new JsonArray();
        for (JsonObject n : nodes) na.add(n);
        root.add("nodes", na);
        JsonObject reg = obj(root, "regimes");
        if (reg == null) { reg = new JsonObject(); root.add("regimes", reg); }
        JsonArray ra = new JsonArray();
        for (JsonObject r : regimes) ra.add(r);
        reg.add("calendar", ra);

        Graph g = read(root, new ArrayList<>());
        g.raw.remove("nodes");
        g.params.clear();
        for (Param p : params) g.params.put(p.key, p);
        Param risk = g.params.get("risk_tolerance");
        if (risk != null && risk.get("value") != null) g.riskTolerance = (int) Math.round(risk.get("value"));
        Overrides.apply(g);
        return g;
    }

    /** What is stored besides the rows: the file minus its nodes and its calendar. */
    public static JsonObject rest(Graph g) {
        JsonObject rest = g.raw.deepCopy();
        rest.remove("nodes");
        JsonObject reg = obj(rest, "regimes");
        if (reg != null) reg.remove("calendar");
        return rest;
    }

    // ------------------------------------------------------------------ nodes

    /** One node. Used both for the file and for nodes read back from storage. */
    public static Node node(JsonObject o, List<Issue> issues) {
        Node n = new Node();
        n.raw = o.deepCopy();
        n.id = str(o, "id");
        String at = n.id == null ? "nodes" : n.id;
        if (n.id == null || n.id.isBlank()) { issues.add(Issue.invalid(at, "A node has no id")); n.id = null; return n; }

        n.type = enumOf(NodeType.class, str(o, "type"), at, "type", issues);
        n.owner = enumOf(Owner.class, str(o, "owner"), at, "owner", issues);
        n.title = str(o, "title");
        if (n.title == null) issues.add(Issue.invalid(at, n.id + " has no title"));
        n.status = str(o, "status");
        n.phase = str(o, "phase");
        n.granularity = str(o, "granularity");
        n.role = str(o, "role");
        n.parent = str(o, "parent");
        n.expandAt = date(o, "expand_at", at, issues);
        n.decideBy = date(o, "decide_by", at, issues);
        n.hard = bool(o, "hard");
        n.soft = bool(o, "soft");
        n.expanded = bool(o, "expanded");
        n.priority = Priority.parse(str(o, "priority"));
        n.standaloneValue = num(o, "standalone_value", at, issues);

        JsonObject w = obj(o, "window");
        if (w != null) {
            n.earliest = date(w, "earliest", at, issues);
            n.deadline = date(w, "deadline", at, issues);
            n.hardDeadline = bool(w, "hard");
        }
        JsonObject e = obj(o, "effort");
        if (e != null) {
            n.effortP50 = num(e, "total_h_p50", at, issues);
            n.effortP90 = num(e, "total_h_p90", at, issues);
            n.perUnitPrior = num(e, "per_unit_h_median_prior", at, issues);
            n.perUnitObserved = num(e, "per_unit_h_median_observed", at, issues);
            n.perUnitSigma = num(e, "sigma", at, issues);
            n.perPointP50 = num(e, "hours_per_point_p50", at, issues);
            n.perPointP90 = num(e, "hours_per_point_p90", at, issues);
        }
        JsonObject lv = obj(o, "level");
        if (lv != null) {
            n.levelFrom = num(lv, "from", at, issues);
            n.levelTo = num(lv, "to", at, issues);
        }
        JsonObject wk = obj(o, "weekly");
        if (wk != null) {
            n.weeklyFloorH = num(wk, "floor_h", at, issues);
            n.maxHPerDay = num(wk, "max_h_per_day", at, issues);
        }
        JsonObject u = obj(o, "units");
        if (u != null) {
            n.unitsTotal = num(u, "total", at, issues);
            n.unitsDone = num(u, "done", at, issues);
            n.unitName = str(u, "unit");
        }
        JsonObject sp = obj(o, "success_prior");
        if (sp != null) {
            JsonArray beta = arr(sp, "beta");
            if (beta != null && beta.size() == 2) {
                n.successA = asNum(beta.get(0));
                n.successB = asNum(beta.get(1));
            }
            if (n.successA == null || n.successB == null)
                issues.add(Issue.invalid(at, n.id + ": success_prior.beta must be two numbers"));
        }
        JsonObject c = obj(o, "consent");
        if (c != null) {
            n.consentRequired = bool(c, "requires_other_party");
            n.consentChannel = str(c, "channel");
        }
        JsonObject ch = obj(o, "chronos");
        if (ch != null) {
            JsonElement act = ch.get("activity");
            if (act != null && act.isJsonPrimitive()) n.activities.add(act.getAsString());
            else if (act != null && act.isJsonArray())
                for (JsonElement a : act.getAsJsonArray()) n.activities.add(a.getAsString());
            if (ch.has("needs_high_focus")) n.needsHighFocus = bool(ch, "needs_high_focus");
        }
        n.pausedDuring.addAll(strings(o, "paused_during"));
        n.options.addAll(strings(o, "options"));
        return n;
    }

    /** Every edge the nodes imply, drawn from the node that comes first. */
    public static List<Edge> edges(Iterable<Node> nodes) {
        List<Edge> out = new ArrayList<>();
        for (Node n : nodes) {
            JsonObject o = n.raw;
            for (String d : strings(o, "depends_on")) out.add(new Edge(d, n.id, EdgeKind.AND, -1, null));
            JsonArray any = arr(o, "any_of");
            if (any != null)
                for (int i = 0; i < any.size(); i++)
                    if (any.get(i).isJsonArray())
                        for (JsonElement d : any.get(i).getAsJsonArray())
                            out.add(new Edge(d.getAsString(), n.id, EdgeKind.OR_GROUP, i, null));
            for (String d : strings(o, "soft_depends_on")) out.add(new Edge(d, n.id, EdgeKind.SOFT, -1, null));
            for (JsonObject c : objects(o, "contributes_to")) {
                String to = str(c, "node");
                if (to != null) out.add(new Edge(n.id, to, EdgeKind.CONTRIBUTES, -1, asNum(c.get("coef"))));
            }
            for (JsonObject c : objects(o, "dual_credit_from")) {
                String from = str(c, "node");
                if (from != null) out.add(new Edge(from, n.id, EdgeKind.CREDIT, -1, asNum(c.get("coef"))));
            }
            for (String child : strings(o, "children")) out.add(new Edge(child, n.id, EdgeKind.CHILD, -1, null));
            String parent = str(o, "parent");
            if (parent != null) {
                Edge e = new Edge(n.id, parent, EdgeKind.CHILD, -1, null);
                if (!out.contains(e)) out.add(e);
            }
        }
        // A parent written on both ends appears twice; keep one.
        return new ArrayList<>(new java.util.LinkedHashSet<>(out));
    }

    // ---------------------------------------------------------------- regimes

    private static void readRegimes(Graph g, JsonObject regimes, List<Issue> issues) {
        if (regimes == null) return;
        JsonObject cap = obj(regimes, "capacity_goal_hours_per_day");
        if (cap != null)
            for (DayType t : DayType.values()) {
                Double v = num(cap, t.name(), "regimes", issues);
                if (v != null) g.params.put("capacity." + t.name(), new Param("capacity." + t.name(),
                    Dist.NORMAL, Source.USER, "Goal hours on a " + t.name() + " day, as you stated them", "mean", v));
            }
        for (JsonObject r : objects(regimes, "calendar")) {
            Regime x = regime(r, issues);
            if (x.id != null) g.regimes.add(x);
        }
    }

    public static Regime regime(JsonObject r, List<Issue> issues) {
        Regime x = new Regime();
        x.raw = r.deepCopy();
        x.id = str(r, "id");
        String at = x.id == null ? "regimes" : x.id;
        if (x.id == null) issues.add(Issue.invalid("regimes", "A regime has no id"));
        x.from = date(r, "from", at, issues);
        x.to = date(r, "to", at, issues);
        if (x.from == null || x.to == null) issues.add(Issue.invalid(at, at + " needs both from and to"));
        x.overlayOn = str(r, "overlay_on");
        x.dataWeight = num(r, "data_weight", at, issues);
        x.strength = num(r, "strength", at, issues);
        x.assumption = bool(r, "assumption");
        x.askWhen = date(r, "ask_when", at, issues);
        x.note = str(r, "note");
        JsonObject mix = obj(r, "mix");
        if (mix != null) {
            x.mix = new EnumMap<>(DayType.class);
            for (DayType t : DayType.values()) {
                Double v = num(mix, t.name(), at, issues);
                if (v != null) x.mix.put(t, v);
            }
        }
        JsonObject planned = obj(r, "planned_h_per_day");
        if (planned != null)
            for (Map.Entry<String, JsonElement> e : planned.entrySet()) {
                Double v = asNum(e.getValue());
                if (v != null) x.plannedHPerDay.put(e.getKey(), v);
            }
        return x;
    }

    // ------------------------------------------------------- policies, state

    private static void readPolicies(Graph g, JsonObject p, List<Issue> issues) {
        if (p == null) return;
        JsonObject objective = obj(p, "objective");
        if (objective != null) {
            Double risk = num(objective, "risk_tolerance", "policies", issues);
            if (risk != null) {
                g.riskTolerance = (int) Math.round(risk);
                g.params.put("risk_tolerance", new Param("risk_tolerance", Dist.POINT, Source.USER,
                    "How much risk you accept, 1 (cautious) to 5", "value", risk));
            }
        }
        JsonObject consent = obj(p, "consent");
        if (consent != null) g.consentAppliesTo.addAll(strings(consent, "applies_to"));
        JsonObject wave = obj(p, "rolling_wave");
        if (wave != null) {
            Double fine = num(wave, "fine_until_weeks", "policies", issues);
            Double medium = num(wave, "medium_until_months", "policies", issues);
            g.fineUntilWeeks = fine == null ? null : (int) Math.round(fine);
            g.mediumUntilMonths = medium == null ? null : (int) Math.round(medium);
        }
    }

    private static void readUserState(Graph g, JsonObject s) {
        if (s == null) return;
        JsonObject adherence = obj(s, "adherence");
        if (adherence != null && num(adherence, "a", "user_state", null) != null
                && num(adherence, "b", "user_state", null) != null)
            g.params.put("adherence", new Param("adherence", Dist.BETA, sourceOf(str(adherence, "source")),
                "Share of planned sessions you do. " + nz(str(adherence, "source")),
                "a", num(adherence, "a", "", null), "b", num(adherence, "b", "", null)));

        JsonObject bias = obj(s, "estimation_bias");
        if (bias != null)
            for (Map.Entry<String, JsonElement> e : bias.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject b = e.getValue().getAsJsonObject();
                Double median = num(b, "median", "", null), sigma = num(b, "sigma", "", null);
                if (median == null) continue;
                String key = "bias." + e.getKey().trim();
                String src = str(b, "source");
                Source source = "default".equals(e.getKey()) ? Source.EXPLICIT_PRIOR : sourceOf(src);
                String note = "default".equals(e.getKey())
                    ? "How much longer than planned work takes, before anything is learned. " + nz(str(b, "note"))
                    : "How much longer than planned \"" + e.getKey().trim() + "\" takes. " + nz(src);
                Param p = sigma == null
                    ? new Param(key, Dist.LOGNORMAL, source, note.trim(), "median", median)
                    : new Param(key, Dist.LOGNORMAL, source, note.trim(), "median", median, "sigma", sigma);
                g.params.put(key, p);
            }

        JsonObject weights = obj(s, "path_prior_weights");
        if (weights != null) {
            Param p = new Param("path_prior_weights", Dist.POINT, Source.USER,
                "Your prior weight on each graduate path");
            for (Map.Entry<String, JsonElement> e : weights.entrySet()) {
                Double v = asNum(e.getValue());
                if (v != null) p.values.put(e.getKey(), v);
            }
            g.params.put(p.key, p);
        }
    }

    /** A source string from the file, read by what it says: logged data, the user, or neither. */
    static Source sourceOf(String s) {
        if (s == null) return Source.EXPLICIT_PRIOR;
        String l = s.toLowerCase(Locale.ROOT);
        if (l.contains("user")) return Source.USER;
        if (l.contains("log") || l.contains("session") || l.contains("check-in")) return Source.DATA;
        return Source.EXPLICIT_PRIOR;
    }

    // ---------------------------------------------------------------- helpers

    static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    static boolean bool(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        JsonArray a = arr(o, k);
        if (a != null) for (JsonElement e : a) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }

    static List<JsonObject> objects(JsonObject o, String k) {
        List<JsonObject> out = new ArrayList<>();
        JsonArray a = arr(o, k);
        if (a != null) for (JsonElement e : a) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    static Double asNum(JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        return e.getAsDouble();
    }

    /** A number, or null when absent; a present value that is not a number is reported. */
    static Double num(JsonObject o, String k, String at, List<Issue> issues) {
        JsonElement e = o == null ? null : o.get(k);
        if (e == null || e.isJsonNull()) return null;
        Double v = asNum(e);
        if (v == null && issues != null) issues.add(Issue.invalid(at, at + ": " + k + " is not a number"));
        return v;
    }

    static LocalDate date(JsonObject o, String k, String at, List<Issue> issues) {
        String s = str(o, k);
        if (s == null) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException ex) {
            issues.add(Issue.invalid(at, at + ": " + k + " is not a date (YYYY-MM-DD): " + s));
            return null;
        }
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String s, String at, String field, List<Issue> issues) {
        if (s == null) { issues.add(Issue.invalid(at, at + " has no " + field)); return null; }
        try {
            return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            issues.add(Issue.invalid(at, at + ": unknown " + field + " \"" + s + "\""));
            return null;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}

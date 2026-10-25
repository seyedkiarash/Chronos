package com.chronos.navigator.store;

import com.chronos.navigator.model.Edge;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.GraphImport;
import com.chronos.navigator.model.GraphJson;
import com.chronos.navigator.model.Issue;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.Source;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Overrides;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.model.ParamRegistry;
import com.chronos.navigator.model.Regime;
import com.chronos.navigator.model.Requirements;
import com.chronos.navigator.store.NavEntities.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Navigator's side of the database. Every method blocks; callers use the IO
 * executor. Chronos's own tables are never written from here.
 */
public class NavStore {
    private final NavDao dao;

    public NavStore(NavDao dao) { this.dao = dao; }

    public NavDao dao() { return dao; }

    // ------------------------------------------------------------------ graph

    /** The stored graph with the in-app answers applied, or null before the first import. */
    public Graph load() {
        GraphRow row = dao.graph();
        if (row == null) return null;
        List<JsonObject> nodes = new ArrayList<>();
        for (NodeRow n : dao.nodes()) nodes.add(JsonParser.parseString(n.json).getAsJsonObject());
        List<JsonObject> regimes = new ArrayList<>();
        for (RegimeRow r : dao.regimes()) regimes.add(JsonParser.parseString(r.json).getAsJsonObject());
        JsonObject rest = row.json == null ? new JsonObject() : JsonParser.parseString(row.json).getAsJsonObject();
        Graph g = GraphJson.assemble(rest, nodes, regimes, params());
        // A graph imported before a stage was built lacks that stage's stated constants: add them once.
        java.util.Set<String> before = new java.util.HashSet<>(g.params.keySet());
        ParamRegistry.seed(g, System.currentTimeMillis());
        for (Param p : g.params.values()) if (!before.contains(p.key)) dao.putParam(toRow(p));
        return g;
    }

    /**
     * The stored graph as it would be with these parameters too (a parameter
     * with a stored key replaces it). Nothing is written: this is how a
     * proposal's effect is measured before you decide on it.
     */
    public Graph loadWith(List<Param> extra) {
        GraphRow row = dao.graph();
        if (row == null) return null;
        List<Param> params = params();
        Map<String, Param> byKey = new java.util.LinkedHashMap<>();
        for (Param p : params) byKey.put(p.key, p);
        for (Param p : extra) byKey.put(p.key, p);
        return assemble(row, nodeJson(), byKey.values());
    }

    /** The stored graph with its nodes replaced by these (and the parameters as stored). Nothing is written. */
    public Graph loadWithNodes(List<JsonObject> nodes) {
        GraphRow row = dao.graph();
        return row == null ? null : assemble(row, nodes, params());
    }

    /** The stored graph with these nodes and these parameters too. Nothing is written. */
    public Graph loadWithBoth(List<JsonObject> nodes, List<Param> extra) {
        GraphRow row = dao.graph();
        if (row == null) return null;
        Map<String, Param> byKey = new java.util.LinkedHashMap<>();
        for (Param p : params()) byKey.put(p.key, p);
        for (Param p : extra) byKey.put(p.key, p);
        return assemble(row, nodes, byKey.values());
    }

    private Graph assemble(GraphRow row, List<JsonObject> nodes, Iterable<Param> params) {
        List<JsonObject> regimes = new ArrayList<>();
        for (RegimeRow r : dao.regimes()) regimes.add(JsonParser.parseString(r.json).getAsJsonObject());
        JsonObject rest = row.json == null ? new JsonObject() : JsonParser.parseString(row.json).getAsJsonObject();
        Graph g = GraphJson.assemble(rest, nodes, regimes, params);
        ParamRegistry.seed(g, System.currentTimeMillis());
        return g;
    }

    /** The nodes as stored: each one's JSON, in order. */
    public List<JsonObject> nodeJson() {
        List<JsonObject> out = new ArrayList<>();
        for (NodeRow n : dao.nodes()) out.add(JsonParser.parseString(n.json).getAsJsonObject());
        return out;
    }

    /** Rewrites the nodes (and the edges they imply) after an approved change to the structure. */
    public void saveNodes(List<JsonObject> json) {
        List<Issue> issues = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        for (JsonObject o : json) {
            Node n = GraphJson.node(o, issues);
            if (n.id != null) nodes.add(n);
        }
        List<NodeRow> rows = new ArrayList<>();
        int i = 0;
        for (Node n : nodes) rows.add(toRow(n, i++));
        dao.replaceNodes(rows, edgeRows(GraphJson.edges(nodes)));
    }

    /** Applies an approved proposal's structural operations to the stored nodes. */
    public void applyStructure(List<com.chronos.navigator.llm.Op> ops) {
        List<JsonObject> after = com.chronos.navigator.llm.Structure.apply(nodeJson(), ops);
        saveNodes(after);
    }

    /** Sets a node's status ("done", or null to reopen it): for finished fine tasks, ticked in the app. */
    public void setStatus(String nodeId, String status) {
        List<JsonObject> nodes = nodeJson();
        for (JsonObject o : nodes) {
            if (!o.has("id") || !o.get("id").getAsString().equals(nodeId)) continue;
            if (status == null) o.remove("status"); else o.addProperty("status", status);
        }
        saveNodes(nodes);
    }

    public GraphRow info() { return dao.graph(); }

    /**
     * Imports a goal-graph file. A refused file changes nothing. An accepted
     * one replaces the structure whole and merges the parameters: a value the
     * file states replaces the stored one (the import is the later decision),
     * values it does not state are kept, what the log taught is kept, and the
     * spec's constants are never reset. An answer given in the app to a gap is
     * dropped once the file fills that gap itself.
     */
    public GraphImport.Result importGraph(String json, String fileName) {
        long now = System.currentTimeMillis();
        GraphImport.Result r = GraphImport.read(json, now);
        if (!r.ok()) return r;
        Graph g = r.graph();

        Map<String, Param> stored = new HashMap<>();
        for (Param p : params()) stored.put(p.key, p);
        for (String key : Overrides.superseded(g, stored.values())) dao.dropParam(key);

        List<ParamRow> rows = new ArrayList<>();
        for (Param p : g.params.values()) {
            Param old = stored.get(p.key);
            boolean seeded = ParamRegistry.seeded(p.key);
            if (old != null && (old.source == Source.DATA || seeded)) continue;
            // The file states only some of a parameter's values (capacity: the mean);
            // values answered in the app for the rest are kept.
            if (old != null) for (Map.Entry<String, Double> e : old.values.entrySet())
                p.values.putIfAbsent(e.getKey(), e.getValue());
            rows.add(toRow(p));
        }

        GraphRow graph = new GraphRow();
        graph.schemaVersion = g.schemaVersion;
        graph.title = g.title;
        graph.fileName = fileName;
        graph.importedAt = now;
        graph.json = GraphJson.rest(g).toString();

        List<NodeRow> nodes = new ArrayList<>();
        int i = 0;
        for (Node n : g.nodes.values()) nodes.add(toRow(n, i++));
        List<RegimeRow> regimes = new ArrayList<>();
        i = 0;
        for (Regime x : g.regimes) {
            RegimeRow rr = new RegimeRow();
            rr.id = x.id;
            rr.fromDate = x.from == null ? null : x.from.toString();
            rr.toDate = x.to == null ? null : x.to.toString();
            rr.json = x.raw.toString();
            rr.position = i++;
            regimes.add(rr);
        }
        dao.replaceGraph(graph, nodes, edgeRows(g.edges), regimes, rows);
        replayExpansions();
        return r;
    }

    /**
     * Expansions you approved live in the inbox, not in the file. After a new
     * version of the file, each is applied again — unless the file breaks that
     * node down itself, no longer has it, or already has one of its tasks.
     */
    private void replayExpansions() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<com.chronos.navigator.llm.Op>>() {}.getType();
        List<ProposalRow> approved = new ArrayList<>(dao.proposals());
        approved.sort((a, b) -> Long.compare(a.decidedAt, b.decidedAt));
        for (ProposalRow p : approved) {
            if (!"APPROVED".equals(p.status) || p.ops == null) continue;
            List<com.chronos.navigator.llm.Op> ops = gson.fromJson(p.ops, type);
            String parent = com.chronos.navigator.llm.Structure.expandedNode(ops);
            if (parent == null) continue;
            List<JsonObject> nodes = nodeJson();
            Set<String> ids = new HashSet<>();
            JsonObject target = null;
            for (JsonObject o : nodes) {
                String id = o.get("id").getAsString();
                ids.add(id);
                if (id.equals(parent)) target = o;
            }
            if (target == null || (target.has("expanded") && target.get("expanded").getAsBoolean())) continue;
            boolean clash = false;
            for (com.chronos.navigator.llm.Op op : ops)
                if (com.chronos.navigator.llm.Op.NODE_ADD.equals(op.kind()) && ids.contains(op.node().get("id").getAsString())) clash = true;
            if (clash || com.chronos.navigator.llm.Structure.check(nodes, ops).isPresent()) continue;
            saveNodes(com.chronos.navigator.llm.Structure.apply(nodes, ops));
        }
    }

    // --------------------------------------------------------------- triggers

    public Map<String, com.chronos.navigator.trigger.Triggers.State> triggers() {
        Map<String, com.chronos.navigator.trigger.Triggers.State> out = new java.util.LinkedHashMap<>();
        for (TriggerRow r : dao.triggers()) {
            com.chronos.navigator.trigger.Triggers.State s = new com.chronos.navigator.trigger.Triggers.State();
            s.id = r.id; s.kind = r.kind; s.node = r.node; s.value = r.value;
            s.firedAt = r.firedAt; s.cooldownUntil = r.cooldownUntil; s.armed = r.armed;
            out.put(s.id, s);
        }
        return out;
    }

    public void saveTriggers(Map<String, com.chronos.navigator.trigger.Triggers.State> state) {
        for (com.chronos.navigator.trigger.Triggers.State s : state.values()) {
            TriggerRow r = new TriggerRow();
            r.id = s.id; r.kind = s.kind; r.node = s.node; r.value = s.value;
            r.firedAt = s.firedAt; r.cooldownUntil = s.cooldownUntil; r.armed = s.armed;
            dao.putTrigger(r);
        }
    }

    /** P(by deadline) of each node in every forecast since a moment, for prob_jump's window. */
    public Map<String, Double> lowestSince(long sinceMs) {
        Map<String, Double> out = new HashMap<>();
        for (ForecastRow f : dao.forecastsSince(sinceMs))
            for (ForecastNodeRow n : dao.forecastNodes(f.id))
                if (n.pByDeadline != null) out.merge(n.nodeId, n.pByDeadline, Math::min);
        return out;
    }

    static NodeRow toRow(Node n, int position) {
        NodeRow row = new NodeRow();
        row.id = n.id;
        row.type = n.type == null ? null : n.type.name();
        row.owner = n.owner == null ? null : n.owner.name();
        row.status = n.status;
        row.phase = n.phase;
        row.title = n.title;
        row.json = n.raw.toString();
        row.position = position;
        return row;
    }

    static List<EdgeRow> edgeRows(List<Edge> edges) {
        List<EdgeRow> out = new ArrayList<>();
        for (Edge e : edges) {
            EdgeRow r = new EdgeRow();
            r.fromId = e.from(); r.toId = e.to(); r.kind = e.kind().name(); r.grp = e.group(); r.coef = e.coef();
            out.add(r);
        }
        return out;
    }

    // ----------------------------------------------------------------- params

    public List<Param> params() {
        List<Param> out = new ArrayList<>();
        for (ParamRow r : dao.params()) out.add(fromRow(r));
        return out;
    }

    /** Saves a value the user typed or accepted. Its source becomes USER. */
    public void answer(String key, Dist dist, String note, Map<String, Double> values) {
        ParamRow old = dao.param(key);
        Param p = old == null ? new Param() : fromRow(old);
        p.key = key;
        if (p.dist == null) p.dist = dist;
        p.values.putAll(values);
        p.source = Source.USER;
        if (note != null) p.note = note;
        p.updatedAt = System.currentTimeMillis();
        dao.putParam(toRow(p));
    }

    public void putParam(Param p) { dao.putParam(toRow(p)); }

    static ParamRow toRow(Param p) {
        ParamRow r = new ParamRow();
        r.key = p.key;
        r.dist = p.dist == null ? null : p.dist.name();
        JsonObject vals = new JsonObject();
        for (Map.Entry<String, Double> e : p.values.entrySet()) vals.addProperty(e.getKey(), e.getValue());
        r.vals = vals.toString();
        r.source = (p.source == null ? Source.EXPLICIT_PRIOR : p.source).name();
        r.note = p.note;
        r.updatedAt = p.updatedAt;
        return r;
    }

    static Param fromRow(ParamRow r) {
        Param p = new Param();
        p.key = r.key;
        p.dist = r.dist == null ? null : Dist.valueOf(r.dist);
        if (r.vals != null) {
            JsonObject vals = JsonParser.parseString(r.vals).getAsJsonObject();
            for (String k : vals.keySet()) p.values.put(k, vals.get(k).getAsDouble());
        }
        p.source = Source.valueOf(r.source);
        p.note = r.note;
        p.updatedAt = r.updatedAt;
        return p;
    }

    // ------------------------------------------------------------------ links

    public Map<String, String> links() {
        Map<String, String> out = new HashMap<>();
        for (LinkRow r : dao.links()) out.put(r.linkKey, r.activityName);
        return out;
    }

    public void link(String linkKey, String activityName) {
        if (activityName == null) { dao.dropLink(linkKey); return; }
        LinkRow r = new LinkRow();
        r.linkKey = linkKey;
        r.activityName = activityName;
        dao.putLink(r);
    }

    // ----------------------------------------------------------------- checks

    /** What still has to be answered, against the Chronos activities that exist now. */
    public List<Issue> issues(Graph g, LocalDate today, Iterable<String> chronosActivityNames) {
        Set<String> acts = new HashSet<>();
        for (String a : chronosActivityNames) acts.add(Requirements.norm(a));
        return Requirements.check(g, today, acts, links());
    }

    // ------------------------------------------------------------ estimate

    /** Replaces every learned value at once: they are recomputed from the priors and the whole log. */
    public void savePosteriors(List<Param> posteriors) {
        dao.dropParamsLike(com.chronos.navigator.estimate.Estimator.POST);
        List<ParamRow> rows = new ArrayList<>();
        for (Param p : posteriors) rows.add(toRow(p));
        dao.putParams(rows);
    }

    // ------------------------------------------------------------ forecast

    public long saveForecast(com.chronos.navigator.forecast.Forecaster.Result r) {
        ForecastRow row = new ForecastRow();
        row.runAt = System.currentTimeMillis();
        row.seed = r.seed();
        row.runs = r.runs();
        row.asOf = r.asOf().toString();
        row.millis = r.millis();
        long id = dao.putForecast(row);
        List<ForecastNodeRow> nodes = new ArrayList<>();
        for (com.chronos.navigator.forecast.Forecaster.NodeResult n : r.nodes().values()) {
            ForecastNodeRow f = new ForecastNodeRow();
            f.forecastId = id;
            f.nodeId = n.id();
            f.pByDeadline = n.pByDeadline();
            f.p10 = n.p10() == null ? null : n.p10().toString();
            f.p50 = n.p50() == null ? null : n.p50().toString();
            f.p90 = n.p90() == null ? null : n.p90().toString();
            f.critical = n.critical();
            nodes.add(f);
        }
        dao.putForecastNodes(nodes);
        // Ninety days of history: enough for every trend the screens and the triggers read.
        long keep = row.runAt - 90L * 86_400_000L;
        dao.pruneForecastNodes(keep);
        dao.pruneForecasts(keep);
        return id;
    }

    /** A stored forecast and its nodes by id; null before the first run. */
    public record StoredForecast(ForecastRow run, Map<String, ForecastNodeRow> nodes) {}

    public StoredForecast latestForecast() { return stored(dao.latestForecast()); }

    /** The newest forecast at least a week old, for the trend arrows. */
    public StoredForecast weekOldForecast() {
        return stored(dao.forecastBefore(System.currentTimeMillis() - 7L * 86_400_000L));
    }

    private StoredForecast stored(ForecastRow row) {
        if (row == null) return null;
        Map<String, ForecastNodeRow> nodes = new HashMap<>();
        for (ForecastNodeRow f : dao.forecastNodes(row.id)) nodes.put(f.nodeId, f);
        return new StoredForecast(row, nodes);
    }

    // ---------------------------------------------------------- allocation

    public void saveAllocation(com.chronos.navigator.allocate.Allocator.Result r) {
        String week = r.weekStart().toString();
        dao.dropAllocation(week);
        List<AllocationRow> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (com.chronos.navigator.allocate.Allocator.Line l : r.lines()) {
            AllocationRow a = new AllocationRow();
            a.week = week; a.nodeId = l.nodeId(); a.plannedH = l.hours(); a.reason = l.reason();
            a.status = "PROPOSED"; a.savedAt = now;
            rows.add(a);
        }
        // The week's capacity and what stayed free travel with it, under a reserved id.
        AllocationRow meta = new AllocationRow();
        meta.week = week; meta.nodeId = WEEK_META; meta.plannedH = r.capacityH();
        meta.reason = String.format(java.util.Locale.ROOT, "%.2f|%.2f|%d", r.freeH(), r.overH(), r.millis());
        meta.status = "PROPOSED"; meta.savedAt = now;
        rows.add(meta);
        dao.putAllocation(rows);
    }

    /** Reserved node id of the row carrying a week's capacity, free hours and overrun. */
    public static final String WEEK_META = "#week";

    public List<AllocationRow> latestAllocation() { return dao.latestAllocation(); }

    public List<AllocationRow> allocation(String week) { return dao.allocation(week); }

    /** Marks a week accepted, with any hours the user changed; returns the rows as applied. */
    public List<AllocationRow> accept(String week, Map<String, Double> edits) {
        List<AllocationRow> rows = dao.allocation(week);
        long now = System.currentTimeMillis();
        for (AllocationRow r : rows) {
            if (WEEK_META.equals(r.nodeId)) { r.status = "ACCEPTED"; continue; }
            Double h = edits == null ? null : edits.get(r.nodeId);
            if (h != null && Math.abs(h - r.plannedH) > 1e-9) { r.plannedH = h; r.status = "EDITED"; }
            else r.status = "ACCEPTED";
            r.savedAt = now;
        }
        if (edits != null)
            for (Map.Entry<String, Double> e : edits.entrySet()) {
                if (rows.stream().anyMatch(r -> r.nodeId.equals(e.getKey())) || e.getValue() <= 0) continue;
                AllocationRow a = new AllocationRow();
                a.week = week; a.nodeId = e.getKey(); a.plannedH = e.getValue(); a.reason = "added by you";
                a.status = "EDITED"; a.savedAt = now;
                rows.add(a);
            }
        dao.putAllocation(rows);
        List<AllocationRow> applied = new ArrayList<>();
        for (AllocationRow r : rows) if (!WEEK_META.equals(r.nodeId)) applied.add(r);
        return applied;
    }

    // -------------------------------------------------------------- bridge

    private static final String BACKUP = "bridge.backup.";

    /** Each Chronos goal's own floor and ceiling, kept the first time a week was applied to it. */
    public Map<String, com.chronos.navigator.bridge.Bridge.Backup> backups() {
        Map<String, com.chronos.navigator.bridge.Bridge.Backup> out = new java.util.LinkedHashMap<>();
        for (ParamRow r : dao.paramsLike(BACKUP)) {
            Param p = fromRow(r);
            String id = p.key.substring(BACKUP.length());
            Double floor = p.get("floor"), ceiling = p.get("ceiling");
            out.put(id, new com.chronos.navigator.bridge.Bridge.Backup(id,
                floor == null || floor < 0 ? null : floor, ceiling == null || ceiling < 0 ? null : ceiling));
        }
        return out;
    }

    public void saveBackups(Map<String, com.chronos.navigator.bridge.Bridge.Backup> backups) {
        for (com.chronos.navigator.bridge.Bridge.Backup b : backups.values()) {
            if (dao.param(BACKUP + b.goalId()) != null) continue;   // the first value is the user's own; keep it
            Param p = new Param(BACKUP + b.goalId(), Dist.POINT, Source.USER,
                "Your own weekly floor and daily ceiling for this goal, before the Navigator set them (-1: not set).",
                "floor", b.weeklyFloorH() == null ? -1 : b.weeklyFloorH(), "ceiling", b.maxPerDayH() == null ? -1 : b.maxPerDayH());
            p.updatedAt = System.currentTimeMillis();
            dao.putParam(toRow(p));
        }
    }

    public void dropBackups() { dao.dropParamsLike(BACKUP); }

    public boolean following() { return !dao.paramsLike(BACKUP).isEmpty(); }

    // ------------------------------------------------------------ day types

    public void putDayType(LocalDate date, String type, boolean anomaly, String suggested) {
        DayTypeRow r = new DayTypeRow();
        r.date = date.toString(); r.type = type; r.anomaly = anomaly; r.suggested = suggested;
        r.savedAt = System.currentTimeMillis();
        dao.putDayType(r);
    }

    public DayTypeRow dayType(LocalDate date) { return dao.dayType(date.toString()); }

    public List<DayTypeRow> dayTypes() { return dao.dayTypes(); }
}

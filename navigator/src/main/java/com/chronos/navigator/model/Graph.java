package com.chronos.navigator.model;

import com.chronos.navigator.model.Kinds.EdgeKind;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The whole goal graph: nodes, the edges built from them, the calendar, and the parameters. */
public class Graph {
    public String schemaVersion;
    public String title;
    public LocalDate created;
    public LocalDate updated;
    public LocalDate horizonEnd;
    public String timezone;

    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public final List<Edge> edges = new ArrayList<>();
    public final List<Regime> regimes = new ArrayList<>();
    public final Map<String, Param> params = new LinkedHashMap<>();

    /** policies.objective.risk_tolerance, 1 (cautious) .. 5. */
    public Integer riskTolerance;
    /** Nodes the consent policy names. */
    public final List<String> consentAppliesTo = new ArrayList<>();
    public Integer fineUntilWeeks, mediumUntilMonths;

    /** Everything outside the nodes and regimes, as the file had it. */
    public JsonObject raw;

    public Node node(String id) { return nodes.get(id); }

    public List<Edge> into(String id) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : edges) if (e.to().equals(id)) out.add(e);
        return out;
    }

    public List<Edge> outOf(String id) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : edges) if (e.from().equals(id)) out.add(e);
        return out;
    }

    public List<Edge> into(String id, EdgeKind kind) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : edges) if (e.to().equals(id) && e.kind() == kind) out.add(e);
        return out;
    }

    /** The calendar regime (never an overlay) a date falls in, or null. */
    public Regime calendarRegime(LocalDate d) {
        Regime best = null;
        for (Regime r : regimes)
            if (!r.isOverlay() && r.covers(d) && (best == null || r.from.isAfter(best.from))) best = r;
        return best;
    }

    /** Overlays running on a date. */
    public List<Regime> overlays(LocalDate d) {
        List<Regime> out = new ArrayList<>();
        for (Regime r : regimes) if (r.isOverlay() && r.covers(d)) out.add(r);
        return out;
    }

    public Regime regime(String id) {
        for (Regime r : regimes) if (r.id.equals(id)) return r;
        return null;
    }
}

package com.chronos.navigator.model;

import com.chronos.navigator.model.Kinds.EdgeKind;
import com.chronos.navigator.model.Kinds.NodeType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The graph read as a few main paths, each with its branches (your request of 6 Nov 2026: with
 * several ways to a goal the whole map is too crowded to read by eye). A path is a goal, the line of
 * nodes every way to it passes through, and the different ways in. The file may name them; a graph
 * that does not gets them from its structure: each end point is a path, each "any of" group a branch.
 * Whatever belongs to no path is listed last, so nothing is hidden.
 */
public final class Paths {
    private Paths() {}

    public record Branch(String id, String title, String note, List<String> nodes) {}

    public record Main(String id, String title, String why, String goal, List<String> stem, List<Branch> branches) {
        /** Every node on this path, its goal first. */
        public Set<String> all() {
            Set<String> out = new LinkedHashSet<>();
            if (goal != null) out.add(goal);
            out.addAll(stem);
            for (Branch b : branches) out.addAll(b.nodes());
            return out;
        }
    }

    public static final String OTHER = "OTHER";
    private static final int MOST_DERIVED = 8;

    public static List<Main> of(Graph g) {
        List<Main> out = g.raw != null && g.raw.has("paths") && g.raw.get("paths").isJsonArray() ? named(g) : new ArrayList<>();
        if (out.isEmpty()) out = derived(g);
        Set<String> placed = new LinkedHashSet<>();
        for (Main m : out) placed.addAll(m.all());
        List<String> rest = new ArrayList<>();
        for (Node n : g.nodes.values()) if (!placed.contains(n.id)) rest.add(n.id);
        if (!rest.isEmpty()) out.add(new Main(OTHER, "Everything else", null, null, rest, List.of()));
        return out;
    }

    // ------------------------------------------------------------- named in the file

    private static List<Main> named(Graph g) {
        List<Main> out = new ArrayList<>();
        for (JsonElement e : g.raw.getAsJsonArray("paths")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String goal = str(o, "goal");
            if (goal != null && g.node(goal) == null) goal = null;
            List<Branch> branches = new ArrayList<>();
            if (o.has("branches") && o.get("branches").isJsonArray())
                for (JsonElement b : o.getAsJsonArray("branches")) {
                    if (!b.isJsonObject()) continue;
                    JsonObject bo = b.getAsJsonObject();
                    List<String> nodes = ids(g, bo, "nodes");
                    if (!nodes.isEmpty()) branches.add(new Branch(str(bo, "id"), str(bo, "title"), str(bo, "note"), nodes));
                }
            List<String> stem = ids(g, o, "stem");
            if (goal == null && stem.isEmpty() && branches.isEmpty()) continue;
            String title = str(o, "title");
            out.add(new Main(str(o, "id"), title != null ? title : goal, str(o, "why"), goal, stem, branches));
        }
        return out;
    }

    private static List<String> ids(Graph g, JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray())
            for (JsonElement e : o.getAsJsonArray(key))
                if (e.isJsonPrimitive() && g.node(e.getAsString()) != null) out.add(e.getAsString());
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    // ------------------------------------------------------- read from the structure

    private static boolean leads(Edge e) {
        return e.kind() == EdgeKind.AND || e.kind() == EdgeKind.OR_GROUP;
    }

    /** What must come before a node on every way to it: AND links only, walked back. */
    private static void required(Graph g, String id, Set<String> into) {
        for (Edge e : g.into(id, EdgeKind.AND))
            if (g.node(e.from()) != null && into.add(e.from())) required(g, e.from(), into);
    }

    private static List<Main> derived(Graph g) {
        List<Node> ends = new ArrayList<>();
        for (Node n : g.nodes.values()) {
            if (n.type == NodeType.STREAM) continue;
            boolean leadsOn = g.outOf(n.id).stream().anyMatch(Paths::leads);
            boolean hasWayIn = g.into(n.id).stream().anyMatch(Paths::leads);
            if (!leadsOn && hasWayIn) ends.add(n);
        }
        ends.sort(Comparator.comparing(n -> n.deadline == null ? LocalDate.MAX : n.deadline));
        List<Main> out = new ArrayList<>();
        for (Node end : ends.subList(0, Math.min(MOST_DERIVED, ends.size()))) {
            Set<String> line = new LinkedHashSet<>();
            required(g, end.id, line);
            // Every "any of" group met on the way back is a choice of branches.
            List<Branch> branches = new ArrayList<>();
            List<String> toVisit = new ArrayList<>(line);
            toVisit.add(0, end.id);
            Set<String> seen = new LinkedHashSet<>();
            while (!toVisit.isEmpty()) {
                String at = toVisit.remove(0);
                if (!seen.add(at)) continue;
                Map<Integer, List<String>> groups = new LinkedHashMap<>();
                for (Edge e : g.into(at, EdgeKind.OR_GROUP))
                    if (g.node(e.from()) != null) groups.computeIfAbsent(e.group(), k -> new ArrayList<>()).add(e.from());
                for (List<String> group : groups.values()) {
                    Set<String> nodes = new LinkedHashSet<>(group);
                    for (String id : group) required(g, id, nodes);
                    nodes.removeAll(line);
                    if (nodes.isEmpty()) continue;
                    Node first = g.node(group.get(0));
                    branches.add(new Branch(at + "/" + first.id, first.shortName(), null, new ArrayList<>(nodes)));
                    toVisit.addAll(nodes);
                }
            }
            List<String> stem = new ArrayList<>(line);
            stem.sort(Comparator.comparing(id -> {
                LocalDate d = g.node(id).deadline;
                return d == null ? LocalDate.MAX : d;
            }));
            out.add(new Main(end.id, end.shortName(), null, end.id, stem, branches));
        }
        return out;
    }
}

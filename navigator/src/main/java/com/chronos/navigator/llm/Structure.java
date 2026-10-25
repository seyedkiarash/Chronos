package com.chronos.navigator.llm;

import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.GraphJson;
import com.chronos.navigator.model.GraphValidator;
import com.chronos.navigator.model.Issue;
import com.chronos.navigator.model.Node;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applies an approved proposal's structural operations to the stored nodes:
 * NODE_ADD appends a node, NODE_EDIT changes one — {@code set} fields,
 * {@code add_depends_on} ids, {@code rename} keys (the effort a broken-down
 * node keeps for the record). Pure: the rows passed in are not touched.
 */
public final class Structure {
    private Structure() {}

    public static List<JsonObject> apply(List<JsonObject> rows, List<Op> ops) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject r : rows) out.add(r.deepCopy());
        for (Op op : ops) {
            if (!op.isStructure() || op.node() == null) continue;
            if (Op.NODE_ADD.equals(op.kind())) {
                out.add(op.node().deepCopy());
                continue;
            }
            String id = op.node().get("id").getAsString();
            JsonObject target = null;
            for (JsonObject r : out) if (r.has("id") && r.get("id").getAsString().equals(id)) target = r;
            if (target == null) throw new IllegalStateException("No node " + id + " to change");
            JsonObject set = op.node().getAsJsonObject("set");
            if (set != null) for (Map.Entry<String, JsonElement> e : set.entrySet()) target.add(e.getKey(), e.getValue().deepCopy());
            JsonObject rename = op.node().getAsJsonObject("rename");
            if (rename != null) for (Map.Entry<String, JsonElement> e : rename.entrySet()) {
                JsonElement v = target.remove(e.getKey());
                if (v != null) target.add(e.getValue().getAsString(), v);
            }
            JsonArray add = op.node().getAsJsonArray("add_depends_on");
            if (add != null) {
                JsonArray deps = target.has("depends_on") && target.get("depends_on").isJsonArray()
                    ? target.getAsJsonArray("depends_on") : new JsonArray();
                Set<String> have = new HashSet<>();
                for (JsonElement d : deps) have.add(d.getAsString());
                for (JsonElement d : add) if (have.add(d.getAsString())) deps.add(d.getAsString());
                target.add("depends_on", deps);
            }
        }
        return out;
    }

    /** Why the nodes would no longer form an acceptable graph after the operations, or empty. */
    public static Optional<String> check(List<JsonObject> rows, List<Op> ops) {
        List<JsonObject> after;
        try {
            after = apply(rows, ops);
        } catch (RuntimeException e) {
            return Optional.of(e.getMessage());
        }
        Graph g = new Graph();
        Set<String> ids = new HashSet<>();
        for (JsonObject r : after) {
            Node n = GraphJson.node(r, new ArrayList<>());
            if (n.id == null) return Optional.of("A node has no id");
            if (!ids.add(n.id)) return Optional.of("Two nodes would share the id " + n.id);
            g.nodes.put(n.id, n);
        }
        g.edges.addAll(GraphJson.edges(g.nodes.values()));
        for (var e : g.edges)
            if (!g.nodes.containsKey(e.from()) || !g.nodes.containsKey(e.to()))
                return Optional.of("A link points at " + (g.nodes.containsKey(e.from()) ? e.to() : e.from()) + ", which is not in the graph");
        return GraphValidator.cycle(g).map(Issue::message);
    }

    /** The ids an approved expansion added, and the node it broke down; null when the ops are not an expansion. */
    public static String expandedNode(List<Op> ops) {
        for (Op op : ops) if (Op.NODE_EDIT.equals(op.kind()) && op.node() != null) return op.node().get("id").getAsString();
        return null;
    }
}

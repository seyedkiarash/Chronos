package com.chronos.navigator.llm;

import com.chronos.navigator.model.Kinds.Dist;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * One change a proposal would make.
 *
 * Most are a parameter written with source LLM ({@code kind} null or PARAM).
 * An expansion also changes the graph itself: NODE_ADD adds {@code node} (a
 * node as the goal-graph file writes one), NODE_EDIT changes the node named in
 * {@code node.id}. DISCUSS changes nothing; it is something to raise with
 * whoever owns the node. CHRONOS changes Chronos itself (see {@link ChangeSet}).
 *
 * @param before       the values it would replace, empty when there were none — shown as "before → after"
 * @param discussOnly  the node belongs to the advisor: shown as something to raise with them, never applied
 */
public record Op(String key, Dist dist, Map<String, Double> values, Map<String, Double> before,
                 String note, boolean discussOnly, String kind, JsonObject node) {

    public static final String PARAM = "PARAM", NODE_ADD = "NODE_ADD", NODE_EDIT = "NODE_EDIT", DISCUSS = "DISCUSS";
    /** A change to Chronos itself — a goal, an activity, sleep, pressure, a rest day — described by {@code node}; see {@link ChangeSet}. */
    public static final String CHRONOS = "CHRONOS";

    /** A parameter. */
    public Op(String key, Dist dist, Map<String, Double> values, Map<String, Double> before, String note, boolean discussOnly) {
        this(key, dist, values, before, note, discussOnly, PARAM, null);
    }

    public static Op discuss(String nodeId, String note) {
        return new Op("discuss." + nodeId, Dist.POINT, Map.of(), Map.of(), note, true, DISCUSS, null);
    }

    public static Op structure(String kind, String nodeId, JsonObject node, String note) {
        return new Op("node." + nodeId, Dist.POINT, Map.of(), Map.of(), note, false, kind, node);
    }

    public boolean isParam() { return kind == null || PARAM.equals(kind); }

    public boolean isStructure() { return NODE_ADD.equals(kind) || NODE_EDIT.equals(kind); }

    public boolean isChronos() { return CHRONOS.equals(kind); }

    public static Op chronos(String type, String subject, JsonObject what, Map<String, Double> before,
                             Map<String, Double> after, String note) {
        return new Op("chronos." + type + "." + subject, Dist.POINT, after, before, note, false, CHRONOS, what);
    }
}

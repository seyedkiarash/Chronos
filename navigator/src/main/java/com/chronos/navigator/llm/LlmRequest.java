package com.chronos.navigator.llm;

import com.google.gson.JsonObject;

/**
 * One question for the LLM, built from a compact snapshot of the graph — never
 * from the raw log. The answer is a proposal: it is validated against
 * {@link #target} and waits for the user before anything changes.
 *
 * @param task    ESTIMATE_PRIOR, REPLAN_SUGGEST, EXPAND_NODE, … (spec, "LLM layer")
 * @param target  what the answer may touch: kind (REGIME_GAP, NODE_EFFORT, NODE_PRIOR, REGIME_MIX),
 *                subject, and any bounds (a gap's dates)
 * @param system  the fixed instructions; identical across calls so it can be cached
 * @param user    the snapshot and the question
 * @param webSearch whether the model may search the web (sources are then recorded)
 */
public record LlmRequest(String task, JsonObject target, String system, String user, boolean webSearch) {

    public String targetKind() { return target.get("kind").getAsString(); }

    public String subject() { return target.has("subject") ? target.get("subject").getAsString() : null; }
}

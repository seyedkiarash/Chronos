package com.chronos.navigator.store;

import com.chronos.navigator.llm.LlmRequest;
import com.chronos.navigator.llm.Op;
import com.chronos.navigator.llm.ProposalValidator;
import com.chronos.navigator.model.Kinds.Source;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.store.NavEntities.LlmCallRow;
import com.chronos.navigator.store.NavEntities.ProposalRow;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The proposals inbox and the record of calls. A proposal changes nothing
 * until the user approves it; approving writes each operation as a parameter
 * whose source is LLM and whose note carries the reasoning and the pages used.
 */
public class NavProposals {
    private static final Gson GSON = new Gson();
    private final NavDao dao;

    public NavProposals(NavDao dao) { this.dao = dao; }

    /**
     * One proposal as the inbox shows it.
     *
     * @param title   an option's name, when one answer offered several (they share {@code callId})
     * @param trigger what woke the LLM: manual, prob_drop, expand_due, …
     * @param effects the change in P(by deadline) the app measured for it
     */
    public record View(String id, String status, String task, String subject, String summary, String rationale,
                       List<Op> ops, List<ProposalValidator.Source> sources, String reason, List<String> checks,
                       long createdAt, String title, String trigger, List<ProposalValidator.Effect> effects,
                       String callId, int version, String parentId, List<com.chronos.navigator.llm.Discuss.Turn> thread,
                       JsonObject crew) {

        /** A change set from the Navigator's crew: changes you approve one by one, Chronos included. */
        public boolean fromCrew() { return crew != null; }

        public boolean expansion() { return ops.stream().anyMatch(Op::isStructure); }

        /** Nothing to apply: findings, or things to raise with the advisor. */
        public boolean noteOnly() { return ops.stream().allMatch(Op::discussOnly); }
    }

    // ------------------------------------------------------------------ calls

    /** A call waiting to be sent (no network yet). task holds the question so it is rebuilt fresh when sent. */
    public LlmCallRow queue(String task, String trigger, String model) {
        LlmCallRow r = new LlmCallRow();
        r.id = UUID.randomUUID().toString();
        r.task = task;
        r.trigger = trigger;
        r.model = model;
        r.status = "QUEUED";
        r.createdAt = System.currentTimeMillis();
        dao.putLlmCall(r);
        return r;
    }

    public void put(LlmCallRow r) { dao.putLlmCall(r); }

    public List<LlmCallRow> queued() { return dao.queuedCalls(); }

    public List<LlmCallRow> calls() { return dao.llmCalls(); }

    public double spentSince(long ms) { return dao.costSince(ms); }

    /**
     * Answered calls of one provider since a moment. A call that failed (an
     * error, a provider's own limit, high demand) cost nothing and does not count.
     */
    public int answeredSince(long ms, String profileId) { return dao.answeredSince(ms, profileId); }

    // -------------------------------------------------------------- proposals

    /** Files the validated answer: PENDING when it passed, INVALID with its reason when not. */
    public ProposalRow file(String callId, LlmRequest req, ProposalValidator.Result v) {
        return file(callId, req, v, null, 1, List.of());
    }

    /**
     * @param parentId the proposal this one revises, after a conversation; null for a first answer
     * @param thread   the conversation so far, carried on to the newest version
     */
    public ProposalRow file(String callId, LlmRequest req, ProposalValidator.Result v, String parentId, int version,
                            List<com.chronos.navigator.llm.Discuss.Turn> thread) {
        return file(callId, req, v, parentId, version, thread, null);
    }

    /**
     * @param crew for a change set from the crew: its changes (groups of ops), what was refused,
     *             what each role said, the odds with their bands and the simulated week
     */
    public ProposalRow file(String callId, LlmRequest req, ProposalValidator.Result v, String parentId, int version,
                            List<com.chronos.navigator.llm.Discuss.Turn> thread, JsonObject crew) {
        ProposalRow p = new ProposalRow();
        p.id = UUID.randomUUID().toString();
        p.llmCallId = callId;
        p.ops = GSON.toJson(v.ops());
        p.status = v.ok() ? "PENDING" : "INVALID";
        p.summary = v.summary();
        JsonObject val = new JsonObject();
        val.addProperty("task", req.task());
        val.add("target", req.target());
        if (v.rationale() != null) val.addProperty("rationale", v.rationale());
        if (v.reason() != null) val.addProperty("reason", v.reason());
        val.add("checks", GSON.toJsonTree(v.checks()));
        val.add("sources", GSON.toJsonTree(v.sources()));
        if (v.title() != null) val.addProperty("title", v.title());
        if (v.effects() != null && !v.effects().isEmpty()) val.add("effects", GSON.toJsonTree(v.effects()));
        if (parentId != null) val.addProperty("parent", parentId);
        val.addProperty("version", version);
        if (thread != null && !thread.isEmpty()) val.add("thread", GSON.toJsonTree(thread));
        if (crew != null) val.add("crew", crew);
        p.validation = val.toString();
        p.createdAt = System.currentTimeMillis();
        dao.putProposal(p);
        return p;
    }

    public List<View> proposals() {
        List<View> out = new ArrayList<>();
        for (ProposalRow p : dao.proposals()) out.add(view(p));
        return out;
    }

    private static View view(ProposalRow p) {
        JsonObject val = p.validation == null ? new JsonObject() : GSON.fromJson(p.validation, JsonObject.class);
        List<Op> ops = p.ops == null ? List.of() : GSON.fromJson(p.ops, new TypeToken<List<Op>>() {}.getType());
        List<ProposalValidator.Source> sources = val.has("sources")
            ? GSON.fromJson(val.get("sources"), new TypeToken<List<ProposalValidator.Source>>() {}.getType())
            : List.of();
        List<String> checks = val.has("checks")
            ? GSON.fromJson(val.get("checks"), new TypeToken<List<String>>() {}.getType()) : List.of();
        JsonObject target = val.has("target") ? val.getAsJsonObject("target") : new JsonObject();
        List<ProposalValidator.Effect> effects = val.has("effects")
            ? GSON.fromJson(val.get("effects"), new TypeToken<List<ProposalValidator.Effect>>() {}.getType()) : List.of();
        return new View(p.id, p.status,
            val.has("task") ? val.get("task").getAsString() : null,
            target.has("subject") ? target.get("subject").getAsString() : null,
            p.summary, val.has("rationale") ? val.get("rationale").getAsString() : null,
            ops, sources, val.has("reason") ? val.get("reason").getAsString() : null, checks, p.createdAt,
            val.has("title") ? val.get("title").getAsString() : null,
            target.has("trigger") ? target.get("trigger").getAsString() : null, effects, p.llmCallId,
            val.has("version") ? val.get("version").getAsInt() : 1,
            val.has("parent") ? val.get("parent").getAsString() : null,
            val.has("thread") ? GSON.fromJson(val.get("thread"),
                new TypeToken<List<com.chronos.navigator.llm.Discuss.Turn>>() {}.getType()) : List.of(),
            val.has("crew") && val.get("crew").isJsonObject() ? val.getAsJsonObject("crew") : null);
    }

    /** The conversation on a proposal, replaced whole. */
    public void setThread(String id, List<com.chronos.navigator.llm.Discuss.Turn> thread) {
        ProposalRow p = dao.proposal(id);
        if (p == null) return;
        JsonObject val = p.validation == null ? new JsonObject() : GSON.fromJson(p.validation, JsonObject.class);
        val.add("thread", GSON.toJsonTree(thread));
        p.validation = val.toString();
        dao.putProposal(p);
    }

    /** A proposal a newer version replaced after a conversation. */
    public void markRevised(String id) {
        ProposalRow p = dao.proposal(id);
        if (p == null || !"PENDING".equals(p.status)) return;
        p.status = "REVISED";
        p.decidedAt = System.currentTimeMillis();
        dao.putProposal(p);
    }

    /**
     * The inbox's re-check found it no longer applies as things are now. It leaves the
     * inbox with why; {@link #restore} puts it back if you think otherwise.
     */
    public void markOutdated(String id, String why) {
        ProposalRow p = dao.proposal(id);
        if (p == null || !"PENDING".equals(p.status)) return;
        JsonObject val = p.validation == null ? new JsonObject() : GSON.fromJson(p.validation, JsonObject.class);
        val.addProperty("reason", why);
        val.addProperty("outdatedAt", System.currentTimeMillis());
        p.validation = val.toString();
        p.status = "OUTDATED";
        p.decidedAt = System.currentTimeMillis();
        dao.putProposal(p);
    }

    /** An outdated proposal back in the inbox, waiting for you again. */
    public void restore(String id) {
        ProposalRow p = dao.proposal(id);
        if (p == null || !"OUTDATED".equals(p.status)) return;
        JsonObject val = p.validation == null ? new JsonObject() : GSON.fromJson(p.validation, JsonObject.class);
        val.remove("reason");
        val.remove("outdatedAt");
        val.addProperty("rechecked", System.currentTimeMillis());
        p.validation = val.toString();
        p.status = "PENDING";
        p.decidedAt = 0;
        dao.putProposal(p);
    }

    /** Changes what is filed beside a proposal (its measured effect, a crew package's odds, when it was re-checked). */
    public void update(String id, java.util.function.Consumer<JsonObject> change) {
        ProposalRow p = dao.proposal(id);
        if (p == null) return;
        JsonObject val = p.validation == null ? new JsonObject() : GSON.fromJson(p.validation, JsonObject.class);
        change.accept(val);
        p.validation = val.toString();
        dao.putProposal(p);
    }

    /** When it was last looked at against the situation: its re-check, or else when it was filed. */
    public long lastLooked(String id) {
        ProposalRow p = dao.proposal(id);
        if (p == null) return 0;
        JsonObject val = p.validation == null ? new JsonObject() : GSON.fromJson(p.validation, JsonObject.class);
        return val.has("rechecked") ? Math.max(p.createdAt, val.get("rechecked").getAsLong()) : p.createdAt;
    }

    public LlmCallRow call(String id) { return id == null ? null : dao.llmCall(id); }

    public View view(String id) {
        ProposalRow p = dao.proposal(id);
        return p == null ? null : view(p);
    }

    /** Nodes with a breakdown waiting for you or approved: expand_due does not ask again for them. */
    public java.util.Set<String> expansionsInPlay() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (ProposalRow p : dao.proposals()) {
            if (!"PENDING".equals(p.status) && !"APPROVED".equals(p.status)) continue;
            View v = view(p);
            if (v.expansion() && v.subject() != null) out.add(v.subject());
        }
        return out;
    }

    /** Whether a question of this task and subject is waiting to be sent, so it is not queued twice. */
    public boolean queuedAlready(String task) {
        for (LlmCallRow c : dao.queuedCalls()) if (task.equals(c.task)) return true;
        return false;
    }

    /**
     * Approve or reject. Approving writes each operation not reserved for a
     * talk with the advisor; those are only acknowledged.
     */
    public void decide(String id, boolean approve, NavStore store) { decide(id, approve, store, null); }

    /**
     * @param ops the operations as finally applied, when the caller adjusted them (an expansion's
     *            activity names made unique against Chronos); null for the proposal's own
     */
    public void decide(String id, boolean approve, NavStore store, List<Op> ops) {
        ProposalRow p = dao.proposal(id);
        if (p == null || !"PENDING".equals(p.status)) return;
        View v = view(p);
        if (ops != null) { p.ops = GSON.toJson(ops); v = view(p); }
        if (approve) {
            List<Op> structure = new ArrayList<>();
            for (Op op : v.ops()) if (op.isStructure()) structure.add(op);
            if (!structure.isEmpty()) store.applyStructure(structure);
            StringBuilder refs = new StringBuilder();
            for (ProposalValidator.Source s : v.sources()) refs.append(refs.length() == 0 ? " Sources: " : ", ").append(s.url());
            long now = System.currentTimeMillis();
            for (Op op : v.ops()) {
                if (op.discussOnly() || !op.isParam()) continue;
                Param param = new Param();
                param.key = op.key();
                param.dist = op.dist();
                param.values.putAll(op.values());
                param.source = Source.LLM;
                param.note = op.note() + refs;
                param.updatedAt = now;
                store.putParam(param);
            }
        }
        p.status = approve ? "APPROVED" : "REJECTED";
        p.decidedAt = System.currentTimeMillis();
        dao.putProposal(p);
    }
}

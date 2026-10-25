package com.chronos.navigator.store;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Navigator's tables, beside Chronos's own in the same database. Chronos's
 * tables — above all the log — are only ever read from here, never written.
 *
 * Nodes and regimes are stored as the JSON object the goal-graph file wrote,
 * with the columns a query or a database browser needs lifted out beside it:
 * the file stays the reference format, and nothing it says is lost.
 */
public final class NavEntities {
    private NavEntities() {}

    /** The imported file, minus its nodes and regimes. Single row. */
    @Entity(tableName = "nav_graph")
    public static class GraphRow {
        @PrimaryKey public int id = 1;
        public String schemaVersion;
        public String title;
        public String fileName;
        public long importedAt;
        /** Meta, policies, user_state, … as the file had them. */
        public String json;
    }

    @Entity(tableName = "nav_nodes")
    public static class NodeRow {
        @PrimaryKey @NonNull public String id = "";
        public String type;
        public String owner;
        public String status;
        public String phase;
        public String title;
        public String json;
        public int position;
    }

    /** Derived from the nodes and rewritten with them; kept for queries. */
    @Entity(tableName = "nav_edges", primaryKeys = {"fromId", "toId", "kind", "grp"})
    public static class EdgeRow {
        @NonNull public String fromId = "";
        @NonNull public String toId = "";
        @NonNull public String kind = "";
        public int grp;
        public Double coef;
    }

    @Entity(tableName = "nav_regimes")
    public static class RegimeRow {
        @PrimaryKey @NonNull public String id = "";
        public String fromDate;
        public String toDate;
        public String json;
        public int position;
    }

    @Entity(tableName = "nav_params")
    public static class ParamRow {
        @PrimaryKey @NonNull public String key = "";
        public String dist;
        /** Named values as a JSON object: {"a":16,"b":4}. */
        public String vals;
        /** USER | DATA | LLM | EXPLICIT_PRIOR — never null. */
        @NonNull public String source = "";
        public String note;
        public long updatedAt;
    }

    /** A node's activity name pointed at an existing Chronos activity of another name. */
    @Entity(tableName = "nav_links")
    public static class LinkRow {
        /** nodeId|normalised activity name, see Requirements.linkKey. */
        @PrimaryKey @NonNull public String linkKey = "";
        public String activityName;
    }

    /** The one new daily input: what kind of day it was, ticked at the evening check-in. */
    @Entity(tableName = "nav_day_types")
    public static class DayTypeRow {
        @PrimaryKey @NonNull public String date = "";
        /** IDEAL | BUSY | STRESS */
        public String type;
        /** An unusual day: it is kept, but not learned from. */
        public boolean anomaly;
        /** What the app suggested before the user chose; null when it had no suggestion. */
        public String suggested;
        public long savedAt;
    }

    @Entity(tableName = "nav_forecasts")
    public static class ForecastRow {
        @PrimaryKey(autoGenerate = true) public long id;
        public long runAt;
        public long seed;
        public int runs;
        public String asOf;              // ISO date the simulation started from
        public long millis;              // how long it took on the phone
        public String json;              // anything else worth keeping
    }

    @Entity(tableName = "nav_forecast_nodes", primaryKeys = {"forecastId", "nodeId"})
    public static class ForecastNodeRow {
        public long forecastId;
        @NonNull public String nodeId = "";
        /** P(finished by its deadline); null for a node without one. */
        public Double pByDeadline;
        public String p10, p50, p90;     // ISO dates, null when not reached in most runs
        public Double critical;
    }

    @Entity(tableName = "nav_allocations", primaryKeys = {"week", "nodeId"})
    public static class AllocationRow {
        @NonNull public String week = "";    // ISO date the week starts
        @NonNull public String nodeId = "";
        public double plannedH;
        public String reason;
        /** PROPOSED | ACCEPTED | EDITED */
        public String status;
        public long savedAt;
    }

    /** One trigger's hysteresis and cooldown. Key is kind|node. */
    @Entity(tableName = "nav_triggers")
    public static class TriggerRow {
        @PrimaryKey @NonNull public String id = "";
        public String kind;
        public String node;
        public Double value;
        public long firedAt;
        public long cooldownUntil;
        /** False after firing until the reset condition holds (prob_drop: back above 0.70). */
        public boolean armed = true;
    }

    @Entity(tableName = "nav_proposals")
    public static class ProposalRow {
        @PrimaryKey @NonNull public String id = "";
        public String llmCallId;
        public String ops;               // JSON array of operations
        /** PENDING | APPROVED | REJECTED | INVALID */
        public String status;
        public String validation;        // why it failed, or the checks it passed
        public String summary;
        public long createdAt;
        public long decidedAt;
    }

    /** Audit and cost control for every call that left the phone. */
    @Entity(tableName = "nav_llm_calls")
    public static class LlmCallRow {
        @PrimaryKey @NonNull public String id = "";
        public String task;
        public String trigger;
        public String requestHash;
        public String model;
        public int tokensIn, tokensOut;
        public double costUsd;
        public String sources;           // JSON array of URLs
        /** QUEUED | SENT | DONE | FAILED */
        public String status;
        public String error;
        public long createdAt;
        public long doneAt;
    }
}

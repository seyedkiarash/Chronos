package com.chronos.navigator.model;

/** The closed vocabularies of the goal graph. Spelled as in goal-graph JSON, upper-cased. */
public final class Kinds {
    private Kinds() {}

    public enum NodeType {
        /** Open-ended, paced by a weekly floor and cap. Maps to an OPEN Chronos goal. */
        STREAM,
        /** A level 0..10 with a target; effort is hours per level point. */
        SKILL,
        /** Bounded work with a lognormal effort. Maps to a CLOSED Chronos goal when it has units. */
        TASK,
        /** Reached when its dependencies are satisfied or its condition holds. */
        MILESTONE,
        /** Decided outside the user — an admission, an offer, consent. Has a Beta success prior. */
        GATE,
        /** A choice among options, resolved by the user at decide_by. */
        DECISION,
        TERMINAL,
        /** A container whose children an outside authority assigns. Scheduled, never restructured. */
        EXTERNAL_TRACK
    }

    /**
     * Who decides a node's scope. Higher authority wins: the system schedules an
     * advisor's node but never changes it, and pauses its own suggestions first
     * when time is short.
     */
    public enum Owner {
        ADVISOR(3), USER(2), SYSTEM_SUGGESTED(1), EXTERNAL(0);

        public final int authority;
        Owner(int authority) { this.authority = authority; }
    }

    /**
     * Where a number came from. Every parameter has exactly one; a required
     * parameter without one stops the engine with a named error instead of
     * running on a silent default.
     */
    public enum Source {
        /** Typed or confirmed by the user. */
        USER,
        /** Learned from the log. */
        DATA,
        /** Proposed by the LLM, validated, and approved by the user. */
        LLM,
        /** A stated assumption — visible, editable, and labelled as an assumption. */
        EXPLICIT_PRIOR
    }

    public enum EdgeKind {
        /** {@code depends_on}: the target needs this one finished. */
        AND,
        /** {@code any_of}: one of the target's groups must be finished in full. */
        OR_GROUP,
        /** {@code soft_depends_on}: helps the target, never blocks it. */
        SOFT,
        /** {@code contributes_to}: progress here raises the target's value or odds. */
        CONTRIBUTES,
        /** {@code dual_credit_from}: hours spent here also count toward the target skill. */
        CREDIT,
        /** {@code children}: this node belongs to the target container. */
        CHILD
    }

    public enum DayType { IDEAL, BUSY, STRESS }

    public enum Dist { BETA, LOGNORMAL, NORMAL, DIRICHLET, POINT }
}

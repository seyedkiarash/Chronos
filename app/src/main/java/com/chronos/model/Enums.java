package com.chronos.model;

public final class Enums {
    private Enums() {}

    /** Goal work earns value; upkeep earns none but restores capacity. */
    public enum ActivityKind { GOAL, MAINTENANCE }

    /** A closed goal is finite and dated. An open one is paced by a weekly floor. */
    public enum GoalMode { CLOSED, OPEN }

    public enum CommitmentKind { FIXED, VARIABLE }

    /** Where a leg of a trip may sit relative to the away items it wraps. */
    public enum LegMode { PINNED, FLEXIBLE, NONE }

    /** AUTO lets the sequencer choose how many sessions a day gets. */
    public enum BlocksMode { AUTO, FIXED }

    /** SLEEP: one night, filed on the morning it ended. No layer fits the body clock to it. */
    /** CLASS: how a class session left you, mind and body apart (id class|date|commitment|slot). */
    public enum LogKind { SESSION, CHECKIN, TRAVEL, SLEEP, CLASS }
}

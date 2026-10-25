package com.chronos.navigator.model;

/**
 * Something wrong with, or missing from, the graph — named, never papered over.
 *
 * @param fix     which form can clear it, so the screen can link straight to it
 * @param subject what the form edits: a node id, a parameter key, a regime id,
 *                or for an uncovered stretch of calendar "from..to"
 * @param message the problem in the user's terms
 */
public record Issue(Severity severity, Fix fix, String subject, String message) {

    public enum Severity {
        /** The file cannot be imported as it stands. */
        INVALID,
        /** Imported, but the layers will not run until this is answered. */
        BLOCKING,
        WARNING
    }

    public enum Fix {
        NONE,
        /** A node names a Chronos activity that does not exist: create it in Setup or link an existing one. */
        LINK_ACTIVITY,
        /** A node that takes hours has no effort estimate. */
        NODE_EFFORT,
        /** A gate or a milestone that nothing decides has no success prior. */
        NODE_PRIOR,
        /** A required parameter is missing or incomplete. */
        PARAM,
        /** Some stretch of the calendar has no regime. */
        REGIME_GAP,
        /** A regime has no mix of day types. */
        REGIME_MIX,
        /** A regime's dates were an assumption and it is time to confirm them. */
        REGIME_CONFIRM
    }

    public static Issue invalid(String subject, String message) {
        return new Issue(Severity.INVALID, Fix.NONE, subject, message);
    }
}

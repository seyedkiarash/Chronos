package com.chronos.navigator.estimate;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * How alike two activities are, so a new one starts from the ones like it (your request of 8 Nov
 * 2026): a new course from the courses before it, not from your habit over everything. Two things
 * count — the words their names share (Jaccard over words of three letters or more, any script) and
 * whether they serve the same goal. Nothing in common: 0, and the overall habit stays the start.
 */
public final class Similarity {
    private Similarity() {}

    /** Weight of names that are word for word the same. */
    public static final double NAME = 3;
    /** Weight of serving the same goal (one node of the graph, one group of goals in Chronos). */
    public static final double SAME_GOAL = 2;

    private static final Set<String> COMMON = Set.of("the", "and", "for", "with", "from", "into", "our", "your",
        "study", "studying", "work", "session", "sessions");

    /** The words of a name that say what it is about. */
    public static Set<String> words(String name) {
        Set<String> out = new HashSet<>();
        if (name == null) return out;
        for (String w : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
            if (w.length() >= 3 && !COMMON.contains(w)) out.add(w);
        return out;
    }

    /** 0 for nothing in common, up to {@link #NAME} + {@link #SAME_GOAL}. */
    public static double score(String a, String b, boolean sameGoal) {
        Set<String> x = words(a), y = words(b);
        double jaccard = 0;
        if (!x.isEmpty() && !y.isEmpty()) {
            Set<String> both = new HashSet<>(x);
            both.retainAll(y);
            Set<String> any = new HashSet<>(x);
            any.addAll(y);
            jaccard = both.size() / (double) any.size();
        }
        return NAME * jaccard + (sameGoal ? SAME_GOAL : 0);
    }
}

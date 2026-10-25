package com.chronos.navigator.model;

import com.chronos.navigator.model.Issue.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * The goal-graph file to a stored graph: read, validate, seed the constants the
 * spec states. A file with any INVALID issue is refused whole — half a graph is
 * worse than the graph you had.
 */
public final class GraphImport {
    private GraphImport() {}

    /** @param graph null when the file was refused */
    public record Result(Graph graph, List<Issue> invalid) {
        public boolean ok() { return graph != null && invalid.isEmpty(); }
    }

    public static Result read(String json, long now) {
        GraphJson.Read read = GraphJson.read(json);
        List<Issue> invalid = new ArrayList<>();
        for (Issue i : read.issues()) if (i.severity() == Severity.INVALID) invalid.add(i);
        if (read.graph() == null) return new Result(null, invalid);
        invalid.addAll(GraphValidator.validate(read.graph()));
        if (!invalid.isEmpty()) return new Result(null, invalid);
        ParamRegistry.seed(read.graph(), now);
        for (Param p : read.graph().params.values()) if (p.updatedAt == 0) p.updatedAt = now;
        return new Result(read.graph(), invalid);
    }
}

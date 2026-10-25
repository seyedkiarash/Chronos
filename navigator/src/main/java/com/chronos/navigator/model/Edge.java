package com.chronos.navigator.model;

import com.chronos.navigator.model.Kinds.EdgeKind;

/**
 * Always drawn from the node that comes first to the node that needs it:
 * prerequisite to dependent, contributor to receiver, child to container. So
 * "the graph has no cycle" is one check over every edge, whatever its kind.
 *
 * @param group for {@link EdgeKind#OR_GROUP}, which alternative of the target this edge belongs to; else -1
 * @param coef  for CONTRIBUTES and CREDIT, the coefficient the file gave; else null
 */
public record Edge(String from, String to, EdgeKind kind, int group, Double coef) {}

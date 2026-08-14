/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

/**
 * Phase 7c — root of the mesh's typed exception hierarchy. Extends
 * {@link RuntimeException} so callers can either catch specific subtypes
 * or let mesh errors propagate unchecked (matching the existing
 * {@code RuntimeException} handling in the REST layer + tests).
 *
 * <p>Subtypes carry a {@code queryId} where meaningful — helps operators
 * correlate a failure to the mesh logs / metrics for that query.</p>
 */
public class MeshException extends RuntimeException {

    private final String queryId;

    public MeshException(String message) {
        this(message, null, null);
    }

    public MeshException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public MeshException(String message, String queryId, Throwable cause) {
        super(message, cause);
        this.queryId = queryId;
    }

    /** The query this error is scoped to, or {@code null} for
     *  planner/config errors that fail before a query is assigned. */
    public String queryId() { return queryId; }
}

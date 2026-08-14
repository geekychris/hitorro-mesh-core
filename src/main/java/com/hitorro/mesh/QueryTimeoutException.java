/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

/**
 * A query's deadline (phase 7b) expired before it completed, OR a driver
 * polling call ran out of budget waiting for a row.
 *
 * <p>Distinct from {@link MeshException} — timeouts are usually operational
 * (slow agent, oversized query) rather than programming errors, and the REST
 * layer maps them to HTTP 408 rather than 500.</p>
 */
public class QueryTimeoutException extends MeshException {
    public QueryTimeoutException(String message, String queryId) {
        super(message, queryId, null);
    }
}

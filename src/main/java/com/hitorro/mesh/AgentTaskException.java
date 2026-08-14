/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

/**
 * An agent reported an ERROR ResultMessage for one of this query's tasks —
 * SQL compile failure at the agent, missing local table, unreadable
 * partition file, jvssql runtime error, etc. The message forwarded from
 * the agent is preserved verbatim; the driver just wraps it with the
 * queryId so operators can correlate.
 */
public class AgentTaskException extends MeshException {
    public AgentTaskException(String message, String queryId) {
        super(message, queryId, null);
    }
}

/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

/**
 * NATS-style subject naming for the mesh. Kept in one place so agents and driver
 * can't drift out of sync.
 *
 * <p>Layout:</p>
 * <pre>
 *   mesh.agent.heartbeat.&lt;agentId&gt;      agents publish here every N seconds
 *   mesh.agent.task.&lt;agentId&gt;           driver publishes a task assigned to a specific agent
 *   mesh.query.result.&lt;queryId&gt;.&lt;pkey&gt;  agent streams JVS result rows for a partition
 *   mesh.query.control.&lt;queryId&gt;         driver publishes lifecycle events (cancel etc.)
 * </pre>
 *
 * <p>All subjects are ASCII, dot-separated, no wildcards in the produced form.
 * Consumers may subscribe to wildcards ({@code mesh.agent.heartbeat.>} etc.)
 * — the wildcard characters live in the caller, not here.</p>
 */
public final class Subjects {

    private Subjects() {}

    public static final String HEARTBEAT_PREFIX = "mesh.agent.heartbeat.";
    public static final String TASK_PREFIX      = "mesh.agent.task.";
    public static final String RESULT_PREFIX    = "mesh.query.result.";
    public static final String CONTROL_PREFIX   = "mesh.query.control.";
    public static final String SHUFFLE_PREFIX   = "mesh.query.shuffle.";

    /** {@code mesh.agent.heartbeat.<agentId>} — one subject per agent. */
    public static String heartbeat(String agentId) {
        return HEARTBEAT_PREFIX + agentId;
    }

    /** {@code mesh.agent.task.<agentId>} — one subject per agent. Driver picks which agent. */
    public static String task(String agentId) {
        return TASK_PREFIX + agentId;
    }

    /**
     * {@code mesh.query.result.<queryId>.<partitionKey>} — one subject per (query, partition).
     * Driver subscribes to the wildcard {@code mesh.query.result.<queryId>.>} to
     * merge streams from every partition.
     */
    public static String result(String queryId, String partitionKey) {
        return RESULT_PREFIX + queryId + "." + partitionKey;
    }

    /** {@code mesh.query.result.<queryId>.>} — wildcard for driver-side merge. */
    public static String resultsForQuery(String queryId) {
        return RESULT_PREFIX + queryId + ".>";
    }

    /** {@code mesh.query.control.<queryId>} — cancel, pause, resume. */
    public static String control(String queryId) {
        return CONTROL_PREFIX + queryId;
    }

    /** Wildcard for all heartbeats — driver subscribes here to build the live agent set. */
    public static String allHeartbeats() {
        return HEARTBEAT_PREFIX + ">";
    }

    /**
     * {@code mesh.query.shuffle.<queryId>.<bucket>} — stage-0 workers publish
     * shuffled rows here, keyed by hash of the shuffle key columns. One
     * combine worker subscribes per bucket. Phase 2.5.1.
     *
     * <p>Note: we reuse {@link ResultMessage} on this subject. ROW carries a
     * shuffled data row; EOS carries the stage-0 partitionKey and signals
     * "no more rows from this partition for this bucket" — the combine
     * worker counts distinct partitionKeys until it hits the expected
     * upstream count. No separate control message class needed.</p>
     */
    public static String shuffle(String queryId, int bucket) {
        return SHUFFLE_PREFIX + queryId + "." + bucket;
    }
}

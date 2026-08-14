/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Envelope for one message on the {@code mesh.query.result.<queryId>.<pkey>} subject.
 * Either carries a row (from the agent's local SQL execution) or a completion sentinel.
 *
 * <p>Kind {@link Kind#ROW}: {@code row} is the JVS document as JsonNode.<br>
 * Kind {@link Kind#EOS}: end-of-stream from this partition. No more rows for this task.<br>
 * Kind {@link Kind#ERROR}: agent-side failure; {@code errorMessage} carries the diagnostic.</p>
 *
 * <p>The driver merges rows from all partitions into a single result stream until
 * every dispatched partition has sent an EOS (or ERROR).</p>
 */
public record ResultMessage(
        Kind kind,
        String taskId,
        String partitionKey,
        JsonNode row,           // ROW only
        String errorMessage,    // ERROR only
        long sequence           // 0-based per (task, partition) for debugging
) {
    public enum Kind { ROW, EOS, ERROR }

    public static ResultMessage row(String taskId, String partitionKey, JsonNode row, long seq) {
        return new ResultMessage(Kind.ROW, taskId, partitionKey, row, null, seq);
    }

    public static ResultMessage eos(String taskId, String partitionKey, long finalSeq) {
        return new ResultMessage(Kind.EOS, taskId, partitionKey, null, null, finalSeq);
    }

    public static ResultMessage error(String taskId, String partitionKey, String msg) {
        return new ResultMessage(Kind.ERROR, taskId, partitionKey, null, msg, -1);
    }
}

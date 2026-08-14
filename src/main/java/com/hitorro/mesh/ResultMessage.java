/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Envelope for one message on the {@code mesh.query.result.<queryId>.<pkey>} subject.
 *
 * <p>Kind {@link Kind#ROW}: {@code row} is the JVS document as JsonNode.<br>
 * Kind {@link Kind#EOS}: end-of-stream from this partition. No more rows for this task.<br>
 * Kind {@link Kind#ERROR}: agent-side failure; {@code errorMessage} carries the diagnostic.<br>
 * Kind {@link Kind#WATERMARK}: streaming heartbeat carrying the partition's current
 *   max observed event-time in {@code watermarkMs}. Used by phase-6d.2 multi-partition
 *   streaming aggregate to advance window closure even for idle partitions.</p>
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
        long sequence,          // 0-based per (task, partition) for debugging
        long watermarkMs        // WATERMARK only; 0 for other kinds
) {
    public enum Kind { ROW, EOS, ERROR, WATERMARK }

    /** Back-compat overload used by older callers that don't emit watermarks. */
    public ResultMessage(Kind kind, String taskId, String partitionKey,
                         JsonNode row, String errorMessage, long sequence) {
        this(kind, taskId, partitionKey, row, errorMessage, sequence, 0L);
    }

    public static ResultMessage row(String taskId, String partitionKey, JsonNode row, long seq) {
        return new ResultMessage(Kind.ROW, taskId, partitionKey, row, null, seq, 0L);
    }

    public static ResultMessage eos(String taskId, String partitionKey, long finalSeq) {
        return new ResultMessage(Kind.EOS, taskId, partitionKey, null, null, finalSeq, 0L);
    }

    public static ResultMessage error(String taskId, String partitionKey, String msg) {
        return new ResultMessage(Kind.ERROR, taskId, partitionKey, null, msg, -1, 0L);
    }

    /**
     * Phase 6d.2.1 — streaming watermark heartbeat. Agent publishes these
     * periodically from a streaming task; the multi-partition combine at
     * the driver uses them to advance window closure for partitions that
     * emit rows rarely (or never).
     */
    public static ResultMessage watermark(String taskId, String partitionKey, long watermarkMs) {
        return new ResultMessage(Kind.WATERMARK, taskId, partitionKey, null, null, 0L, watermarkMs);
    }
}

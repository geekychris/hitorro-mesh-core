/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

/**
 * Control-plane signal sent by the driver on {@link Subjects#control(String)}
 * when a query should stop. Agents subscribe to the control-subject wildcard
 * globally and interrupt any worker threads running tasks for the matching
 * {@code queryId}.
 *
 * <p>Streaming iterators (e.g. {@code InMemoryStreamingTable}) respond to
 * thread interruption by unblocking their {@code take()}, ending the scan.
 * The scan loop then publishes EOS to the result subject as usual, so the
 * driver's cleanup path is identical to a naturally-terminating query.</p>
 *
 * <p>Phase 6c.2.</p>
 */
public record CancelMessage(String queryId, String reason) {
    public CancelMessage(String queryId) { this(queryId, "client-closed"); }
}

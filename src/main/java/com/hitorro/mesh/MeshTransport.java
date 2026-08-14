/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

import java.util.function.Consumer;

/**
 * The single messaging SPI the driver and agent depend on. Two implementations:
 *
 * <ul>
 *   <li>{@link InMemoryMeshTransport} — same-JVM pub/sub. Used by
 *       {@code hitorro-mesh-examples} and unit tests. Zero external deps.</li>
 *   <li>NatsMeshTransport (in a future {@code hitorro-mesh-nats} module) — JetStream-backed.
 *       Same interface; the driver and agent don't change.</li>
 * </ul>
 *
 * <p><b>Design decision:</b> we intentionally keep this narrower than NATS's
 * full surface. No request/reply, no queue groups, no explicit ACKs. Everything
 * the driver needs (dispatch by agent, merge results by wildcard) is expressed
 * as fire-and-forget publish + wildcard subscribe. Rationale: keeps
 * InMemoryMeshTransport 60 lines instead of 600, and forces us to design
 * around subject naming instead of transport-specific features. When we add
 * JetStream persistence in phase 2, it will be a separate durability flag on
 * publish, not a rewrite of this SPI.</p>
 */
public interface MeshTransport extends AutoCloseable {

    /** Publish a message. Fire-and-forget. */
    void publish(String subject, byte[] payload);

    /**
     * Subscribe to a subject or wildcard pattern. Handler is invoked on the
     * transport's own thread; do not block. The returned handle unsubscribes.
     */
    Subscription subscribe(String subjectOrPattern, Consumer<byte[]> handler);

    @Override
    void close();

    interface Subscription extends AutoCloseable {
        @Override void close();
    }
}

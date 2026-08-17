/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh;

/**
 * Control-plane signal from the driver telling agents to install a MinIO /
 * S3 adapter at runtime — no restart. Published on
 * {@link Subjects#agentControlBroadcast()} right after the driver
 * hot-wires its own adapter, so one click on <b>Start MinIO</b> in the
 * driver UI extends the whole mesh: driver + every live agent.
 *
 * <p>Agent-side handling: register a {@code MinioProtocolAdapter} with
 * {@code BaseFileSystem.addProtocolAdapter}. Existing calls that were
 * failing with "no BaseFile adapter for s3://…" now succeed. In
 * particular, {@code NdjsonLocalTable} / {@code ParquetLocalTable}
 * built from an {@code s3://} URI via
 * {@link RegisterTableMessage} start reading successfully.</p>
 *
 * <p>Fields:</p>
 * <ul>
 *   <li>{@code endpoint} — e.g. {@code http://localhost:9000}.</li>
 *   <li>{@code bucket} — default bucket for bucketless URIs.</li>
 *   <li>{@code accessKey} / {@code secretKey} — the MinIO root user /
 *       password OR AWS IAM key / secret. Sent over the same NATS
 *       transport the rest of the mesh runs on; secure it with TLS + auth
 *       (see NatsSecurityProperties) if you're not on localhost.</li>
 *   <li>{@code ssl} — TLS to the endpoint.</li>
 * </ul>
 */
public record EnableS3Message(
        String endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        boolean ssl) {

    public EnableS3Message {
        if (endpoint == null || endpoint.isBlank())
            throw new IllegalArgumentException("EnableS3Message.endpoint is required");
        if (bucket == null || bucket.isBlank())
            throw new IllegalArgumentException("EnableS3Message.bucket is required");
    }
}

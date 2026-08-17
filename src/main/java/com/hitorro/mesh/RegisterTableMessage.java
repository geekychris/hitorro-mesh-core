/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh;

import java.util.Map;

/**
 * Control-plane signal from the driver telling agents to load a table
 * at runtime, without a mesh restart. Published on
 * {@link Subjects#agentControlRegisterTable()} so every live agent
 * picks it up and installs the same table.
 *
 * <p>Two shapes:</p>
 * <ul>
 *   <li><b>File-backed</b> ({@code format = "ndjson"} or {@code "parquet"})
 *       — the {@code uri} points at the file. Agents build
 *       {@code NdjsonLocalTable} / {@code ParquetLocalTable}.</li>
 *   <li><b>Streaming</b> ({@code format = "kafka"} or {@code "nats"}) —
 *       {@code sourceConfig} carries the source parameters
 *       (bootstrap-servers/topic/… for Kafka; url/stream/subject/… for
 *       NATS JetStream). Agents build the corresponding
 *       {@code KafkaStreamingLocalTable} / {@code NatsJetStreamLocalTable}.
 *       {@code uri} is unused for streams.</li>
 * </ul>
 *
 * <p>Semantics per field:</p>
 * <ul>
 *   <li>{@code name} — logical table name that appears in SQL FROM.</li>
 *   <li>{@code typeJson} — inline JVS type definition; agents parse via
 *       {@code Type.init(json)}.</li>
 *   <li>{@code uri} — file location for file-backed formats. Empty for
 *       streaming.</li>
 *   <li>{@code format} — {@code "ndjson"} (default) / {@code "parquet"}
 *       / {@code "kafka"} / {@code "nats"}.</li>
 *   <li>{@code broadcast} — {@code true} means every agent installs it as
 *       a broadcast table (partitionKey = null). {@code false} means a
 *       distributed table where each agent claims the same
 *       {@code partitionKey}.</li>
 *   <li>{@code partitionKey} — required when {@code broadcast = false};
 *       ignored otherwise.</li>
 *   <li>{@code sourceConfig} — streaming-source parameters as a plain
 *       string map. Null for file-backed formats.</li>
 * </ul>
 *
 * <p>Backward compatible: older 6-arg messages from pre-streaming driver
 * versions deserialize with {@code sourceConfig = null} — the agent
 * installer only reads it when the format is streaming.</p>
 */
public record RegisterTableMessage(
        String name,
        String typeJson,
        String uri,
        String format,
        boolean broadcast,
        String partitionKey,
        Map<String, String> sourceConfig,
        String targetAgentId) {

    /** 6-arg back-compat overload — no sourceConfig, no target-agent filter. */
    public RegisterTableMessage(String name, String typeJson, String uri,
                                String format, boolean broadcast, String partitionKey) {
        this(name, typeJson, uri, format, broadcast, partitionKey, null, null);
    }

    /** 7-arg back-compat — no target-agent filter (any agent installs). */
    public RegisterTableMessage(String name, String typeJson, String uri,
                                String format, boolean broadcast, String partitionKey,
                                Map<String, String> sourceConfig) {
        this(name, typeJson, uri, format, broadcast, partitionKey, sourceConfig, null);
    }

    public RegisterTableMessage {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("RegisterTableMessage.name is required");
        if (typeJson == null || typeJson.isBlank())
            throw new IllegalArgumentException("RegisterTableMessage.typeJson is required");
        if (format == null || format.isBlank()) format = "ndjson";
        boolean streaming = "kafka".equalsIgnoreCase(format) || "nats".equalsIgnoreCase(format);
        if (!streaming && (uri == null || uri.isBlank())) {
            throw new IllegalArgumentException(
                    "RegisterTableMessage.uri is required for format=" + format);
        }
        if (streaming && (sourceConfig == null || sourceConfig.isEmpty())) {
            throw new IllegalArgumentException(
                    "RegisterTableMessage.sourceConfig is required for format=" + format);
        }
        if (!broadcast && (partitionKey == null || partitionKey.isBlank()))
            throw new IllegalArgumentException(
                    "RegisterTableMessage.partitionKey is required for non-broadcast tables");
    }
}

/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh;

/**
 * Control-plane signal from the driver telling agents to load a table
 * at runtime, without a mesh restart. Published on
 * {@link Subjects#agentControlBroadcast()} so every live agent picks
 * it up and installs the same table with the same URI.
 *
 * <p>Fits the "SELECT → file → SELECT * FROM &lt;name&gt;" loop — the
 * driver runs an export via {@code /mesh/queries/write}, then fan-outs
 * this message so the new file becomes queryable on every agent
 * without an operator restart.</p>
 *
 * <p>Semantics per field:</p>
 * <ul>
 *   <li>{@code name} — logical table name that appears in SQL FROM.</li>
 *   <li>{@code typeJson} — inline JVS type definition (Jackson-serialised);
 *       agents parse via {@code Type.fromJson(typeJson)}.</li>
 *   <li>{@code uri} — where the data lives. {@code file:/…} works only for
 *       agents that share that path (dev + single-node); {@code s3://…}
 *       and {@code hdfs://…} route through BaseFile so every agent with
 *       the right adapter can read it.</li>
 *   <li>{@code format} — {@code "ndjson"} (default) or {@code "parquet"}.
 *       Agents route to the right {@code LocalTable} implementation.</li>
 *   <li>{@code broadcast} — {@code true} means every agent installs it as
 *       a broadcast table (partitionKey = null). {@code false} means a
 *       distributed table where each agent claims the same
 *       {@code partitionKey}; suitable for a single-partition scan
 *       served by everyone (partition-key = "all").</li>
 *   <li>{@code partitionKey} — required when {@code broadcast = false};
 *       ignored otherwise.</li>
 * </ul>
 *
 * <p>Subject: {@link Subjects#agentControlBroadcast()}. Every agent
 * subscribes to the wildcard {@code mesh.agent.control.>} at boot.</p>
 */
public record RegisterTableMessage(
        String name,
        String typeJson,
        String uri,
        String format,
        boolean broadcast,
        String partitionKey) {

    public RegisterTableMessage {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("RegisterTableMessage.name is required");
        if (uri == null || uri.isBlank())
            throw new IllegalArgumentException("RegisterTableMessage.uri is required");
        if (typeJson == null || typeJson.isBlank())
            throw new IllegalArgumentException("RegisterTableMessage.typeJson is required");
        if (format == null || format.isBlank()) format = "ndjson";
        if (!broadcast && (partitionKey == null || partitionKey.isBlank()))
            throw new IllegalArgumentException(
                    "RegisterTableMessage.partitionKey is required for non-broadcast tables");
    }
}

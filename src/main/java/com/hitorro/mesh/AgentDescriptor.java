/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

import java.util.Set;

/**
 * What an agent tells the driver about itself. Capabilities are just tags —
 * the driver matches them against a task's {@code requiredCapabilities} to
 * decide who can run what.
 *
 * <p>Suggested capability conventions (not enforced — they're just strings):</p>
 * <ul>
 *   <li>{@code jvssql} — can run jvssql sub-plans (every agent should advertise this)</li>
 *   <li>{@code table:&lt;name&gt;} — has read access to table {@code name}</li>
 *   <li>{@code partition:&lt;table&gt;:&lt;key&gt;} — holds a specific partition of a table</li>
 *   <li>{@code kvstore:&lt;path&gt;} — has a local kvstore at path</li>
 *   <li>{@code lucene-index:&lt;name&gt;} — has a local Lucene index</li>
 *   <li>{@code gpu}, {@code arch:arm64}, {@code os:linux} — hardware/OS tags</li>
 * </ul>
 */
public record AgentDescriptor(
        String agentId,
        Set<String> capabilities,
        long startedAtMillis
) {
    public boolean has(String capability) {
        return capabilities.contains(capability);
    }

    public boolean hasAll(Iterable<String> required) {
        for (String c : required) if (!capabilities.contains(c)) return false;
        return true;
    }
}

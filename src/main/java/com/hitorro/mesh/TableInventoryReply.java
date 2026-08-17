/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh;

import java.util.List;

/**
 * Agent → driver reply carrying a snapshot of every table that agent
 * currently holds, both boot-time and runtime-installed. Sent in
 * response to a {@link TableInventoryRequest}.
 *
 * <p>Each entry:</p>
 * <ul>
 *   <li>{@code name} — SQL FROM identifier.</li>
 *   <li>{@code partitionKey} — {@code null} for broadcast, otherwise
 *       the partition this agent holds.</li>
 *   <li>{@code source} — {@code "boot"} (from AgentProperties) or
 *       {@code "runtime"} (from RuntimeTableRegistry).</li>
 *   <li>{@code uri} — best-effort source URI, or {@code null} if
 *       the entry doesn't know it (boot-time entries that came from
 *       Kafka/NATS streaming sources).</li>
 * </ul>
 */
public record TableInventoryReply(String agentId, List<Entry> tables) {

    public record Entry(String name, String partitionKey, String source, String uri) { }
}

/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

/**
 * Published by an agent to {@link Subjects#heartbeat(String)} every N seconds.
 * The driver's {@code LiveAgentRegistry} uses the arrival of these messages to
 * build its "who's alive" view.
 */
public record HeartbeatMessage(
        AgentDescriptor agent,
        long timestampMillis,
        long activeTaskCount
) {}

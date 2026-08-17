/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh;

/**
 * Symmetric to {@link RegisterTableMessage} — control-plane signal that
 * every live agent should REMOVE a runtime-registered table. Published
 * on {@link Subjects#agentControlUnregisterTable()}.
 *
 * <p>For broadcast tables, agents installed the table under two
 * partition keys ({@code null} and {@code "broadcast"}); the unregister
 * removes both. For distributed runtime tables, the unregister removes
 * exactly the one entry.</p>
 *
 * <p>Also written as a tombstone to the agent-side journal so a
 * subsequent boot doesn't resurrect the table.</p>
 */
public record UnregisterTableMessage(String name, String partitionKey) {
    public UnregisterTableMessage {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("UnregisterTableMessage.name is required");
    }
}

/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh;

/**
 * Driver → agents request for their current runtime table inventory.
 * Agents respond with a {@link TableInventoryReply} on the reply
 * subject encoded in {@code replyId}: agents publish on
 * {@code mesh.agent.control.inventory-reply.<replyId>.<agentId>};
 * the driver subscribes to that wildcard for the request's lifetime.
 *
 * <p>Built on plain publish/subscribe because {@link MeshTransport}
 * deliberately doesn't expose NATS's native request/reply — see the
 * design note on the transport SPI. The {@code replyId} correlates a
 * single "who has this table?" question with the fan-in of agent
 * responses, so multiple concurrent inventory queries don't collide.</p>
 */
public record TableInventoryRequest(String replyId) { }

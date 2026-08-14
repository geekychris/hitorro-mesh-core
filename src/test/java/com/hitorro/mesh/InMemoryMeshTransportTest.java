/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMeshTransportTest {

    @Test
    void wildcardMatch_rules() {
        // exact
        assertThat(InMemoryMeshTransport.matches("a.b.c", "a.b.c")).isTrue();
        assertThat(InMemoryMeshTransport.matches("a.b.c", "a.b.d")).isFalse();
        // single-token *
        assertThat(InMemoryMeshTransport.matches("a.*.c", "a.b.c")).isTrue();
        assertThat(InMemoryMeshTransport.matches("a.*.c", "a.b.d")).isFalse();
        assertThat(InMemoryMeshTransport.matches("a.*.c", "a.b.b.c")).isFalse();
        // tail >
        assertThat(InMemoryMeshTransport.matches("a.>", "a.b")).isTrue();
        assertThat(InMemoryMeshTransport.matches("a.>", "a.b.c.d")).isTrue();
        assertThat(InMemoryMeshTransport.matches("a.>", "b.a")).isFalse();
        // mixed
        assertThat(InMemoryMeshTransport.matches("mesh.query.result.q1.>", "mesh.query.result.q1.p3")).isTrue();
        assertThat(InMemoryMeshTransport.matches("mesh.query.result.q1.>", "mesh.query.result.q2.p3")).isFalse();
    }

    @Test
    void pubSub_deliversToMatchingSubscribers() throws Exception {
        try (InMemoryMeshTransport t = new InMemoryMeshTransport()) {
            AtomicInteger q1Count = new AtomicInteger();
            AtomicInteger q2Count = new AtomicInteger();
            AtomicInteger allCount = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(4);   // 1 (q1) + 1 (q2) + 2 (all)

            t.subscribe("mesh.query.result.q1.>", b -> { q1Count.incrementAndGet(); latch.countDown(); });
            t.subscribe("mesh.query.result.q2.>", b -> { q2Count.incrementAndGet(); latch.countDown(); });
            t.subscribe("mesh.query.result.>",    b -> { allCount.incrementAndGet(); latch.countDown(); });

            t.publish("mesh.query.result.q1.p1", new byte[]{1});
            t.publish("mesh.query.result.q2.p1", new byte[]{2});

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(q1Count.get()).isEqualTo(1);
            assertThat(q2Count.get()).isEqualTo(1);
            assertThat(allCount.get()).isEqualTo(2);
        }
    }

    @Test
    void subjects_naming() {
        assertThat(Subjects.heartbeat("mac02")).isEqualTo("mesh.agent.heartbeat.mac02");
        assertThat(Subjects.task("mac02")).isEqualTo("mesh.agent.task.mac02");
        assertThat(Subjects.result("q1", "shard-3")).isEqualTo("mesh.query.result.q1.shard-3");
        assertThat(Subjects.resultsForQuery("q1")).isEqualTo("mesh.query.result.q1.>");
    }
}

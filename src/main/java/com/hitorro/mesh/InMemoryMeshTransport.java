/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Same-JVM transport for demos and tests. Supports NATS-style single-token
 * wildcards {@code *} and the tail wildcard {@code >}.
 *
 * <p><b>Dispatch is synchronous</b> — handlers run on the publishing thread
 * before {@link #publish} returns. This preserves per-subject message order
 * (a ROW published before an EOS is definitely handled before the EOS,
 * regardless of thread scheduling). Callers are expected to enqueue and
 * return promptly from their handlers; if they don't, they'll back-pressure
 * the publisher — which is the right shape for an in-memory transport.
 * NATS-backed transport can safely use async delivery because JetStream
 * preserves ordering per subject.</p>
 *
 * <p>Not for production. No persistence, no backpressure signaling, no
 * queue groups.</p>
 */
public final class InMemoryMeshTransport implements MeshTransport {

    private final List<Entry> subscribers = new CopyOnWriteArrayList<>();

    public InMemoryMeshTransport() {}

    @Override
    public void publish(String subject, byte[] payload) {
        for (Entry e : subscribers) {
            if (matches(e.pattern, subject)) {
                try { e.handler.accept(payload); }
                catch (Throwable ignore) { /* isolate one bad subscriber */ }
            }
        }
    }

    @Override
    public Subscription subscribe(String subjectOrPattern, Consumer<byte[]> handler) {
        Entry e = new Entry(subjectOrPattern, handler);
        subscribers.add(e);
        return () -> subscribers.remove(e);
    }

    @Override
    public void close() {
        subscribers.clear();
    }

    /** NATS wildcard match: {@code *} matches one token, {@code >} matches one or more trailing tokens. */
    static boolean matches(String pattern, String subject) {
        List<String> pt = tokens(pattern);
        List<String> st = tokens(subject);
        int i = 0;
        for (; i < pt.size(); i++) {
            String p = pt.get(i);
            if (p.equals(">")) return true;   // matches rest
            if (i >= st.size()) return false;
            if (p.equals("*")) continue;
            if (!p.equals(st.get(i))) return false;
        }
        return i == st.size();
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '.') {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private record Entry(String pattern, Consumer<byte[]> handler) {}
}

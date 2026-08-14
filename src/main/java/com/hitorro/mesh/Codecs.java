/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson codec used by both driver and agent for every protocol type.
 * One mapper, one place, no drift.
 */
public final class Codecs {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Codecs() {}

    public static byte[] encode(Object v) {
        try { return MAPPER.writeValueAsBytes(v); }
        catch (Exception e) { throw new RuntimeException("mesh encode failed", e); }
    }

    public static <T> T decode(byte[] bytes, Class<T> type) {
        try { return MAPPER.readValue(bytes, type); }
        catch (Exception e) { throw new RuntimeException("mesh decode failed for " + type.getSimpleName(), e); }
    }
}

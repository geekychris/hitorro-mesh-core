/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

import com.hitorro.util.json.JsonMappers;
import tools.jackson.databind.ObjectMapper;

/**
 * Jackson codec used by both driver and agent for every protocol type.
 * One mapper, one place, no drift. It is hitorro-core's shared mapper with
 * Jackson 2 defaults, so 4.x nodes exchange the same bytes as 3.x nodes.
 */
public final class Codecs {

    public static final ObjectMapper MAPPER = JsonMappers.json();

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

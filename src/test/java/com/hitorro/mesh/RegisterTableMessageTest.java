/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation + serialization tests for {@link RegisterTableMessage},
 * covering both the file-backed and streaming shapes.
 */
class RegisterTableMessageTest {

    private static final String TYPE_JSON =
            "{\"name\":\"t\",\"fields\":[{\"name\":\"k\",\"type\":\"core_string\"}]}";

    // ---- File-backed formats ----

    @Test
    void fileBacked_ndjson_valid() {
        RegisterTableMessage m = new RegisterTableMessage(
                "t", TYPE_JSON, "file:/x.ndjson", "ndjson", true, null);
        assertThat(m.format()).isEqualTo("ndjson");
        assertThat(m.uri()).isEqualTo("file:/x.ndjson");
        assertThat(m.sourceConfig()).isNull();
    }

    @Test
    void fileBacked_missingUri_throws() {
        assertThatThrownBy(() -> new RegisterTableMessage(
                "t", TYPE_JSON, null, "ndjson", true, null))
                .hasMessageContaining("uri is required");
    }

    @Test
    void fileBacked_missingTypeJson_throws() {
        assertThatThrownBy(() -> new RegisterTableMessage(
                "t", null, "file:/x", "ndjson", true, null))
                .hasMessageContaining("typeJson is required");
    }

    @Test
    void nonBroadcast_missingPartitionKey_throws() {
        assertThatThrownBy(() -> new RegisterTableMessage(
                "t", TYPE_JSON, "file:/x", "ndjson", false, null))
                .hasMessageContaining("partitionKey is required");
    }

    // ---- Streaming formats ----

    @Test
    void kafka_missingSourceConfig_throws() {
        assertThatThrownBy(() -> new RegisterTableMessage(
                "t", TYPE_JSON, "", "kafka", true, null))
                .hasMessageContaining("sourceConfig is required");
    }

    @Test
    void nats_missingSourceConfig_throws() {
        assertThatThrownBy(() -> new RegisterTableMessage(
                "t", TYPE_JSON, "", "nats", true, null, null))
                .hasMessageContaining("sourceConfig is required");
    }

    @Test
    void streaming_missingUri_isOk() {
        RegisterTableMessage m = new RegisterTableMessage(
                "t", TYPE_JSON, "", "kafka", true, null,
                Map.of("bootstrap-servers", "localhost:9092",
                       "group-id", "g", "topic", "events"));
        assertThat(m.format()).isEqualTo("kafka");
        assertThat(m.sourceConfig()).containsKey("bootstrap-servers");
    }

    // ---- Serialization / back-compat ----

    @Test
    void sixArgOverload_forwards_toSevenArg_withNullSourceConfig() {
        RegisterTableMessage m = new RegisterTableMessage(
                "t", TYPE_JSON, "file:/x", "ndjson", true, null);
        assertThat(m.sourceConfig()).isNull();
    }

    @Test
    void jacksonRoundTrip_fileBacked_preservesFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RegisterTableMessage orig = new RegisterTableMessage(
                "t", TYPE_JSON, "file:/x", "parquet", true, null);
        String json = mapper.writeValueAsString(orig);
        RegisterTableMessage round = mapper.readValue(json, RegisterTableMessage.class);
        assertThat(round).isEqualTo(orig);
    }

    @Test
    void jacksonRoundTrip_streaming_preservesSourceConfig() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RegisterTableMessage orig = new RegisterTableMessage(
                "t", TYPE_JSON, "", "kafka", true, null,
                Map.of("bootstrap-servers", "b:9092", "group-id", "g", "topic", "z"));
        String json = mapper.writeValueAsString(orig);
        RegisterTableMessage round = mapper.readValue(json, RegisterTableMessage.class);
        assertThat(round.format()).isEqualTo("kafka");
        assertThat(round.sourceConfig()).containsEntry("topic", "z");
    }

    @Test
    void jacksonRoundTrip_oldSixFieldPayload_deserialises_sourceConfigNull() throws Exception {
        // Simulate a message from a pre-streaming driver — no sourceConfig field.
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"name\":\"t\",\"typeJson\":" + mapper.writeValueAsString(TYPE_JSON)
                + ",\"uri\":\"file:/x\",\"format\":\"ndjson\","
                + "\"broadcast\":true,\"partitionKey\":null}";
        RegisterTableMessage round = mapper.readValue(json, RegisterTableMessage.class);
        assertThat(round.name()).isEqualTo("t");
        assertThat(round.sourceConfig()).isNull();
    }

    @Test
    void unknownFormat_stillConstructs_agentSideValidation() {
        // Message class doesn't gate on format string (agent installer does).
        // So "foo" constructs — the agent will reject with "unknown format".
        RegisterTableMessage m = new RegisterTableMessage(
                "t", TYPE_JSON, "file:/x", "foo", true, null);
        assertThat(m.format()).isEqualTo("foo");
    }
}

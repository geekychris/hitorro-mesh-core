package com.hitorro.mesh;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the mesh protocol's wire format to what hitorro-mesh-core 3.x (Jackson 2.18) sent, so
 * 4.x drivers and agents can talk to 3.x ones during a rolling upgrade.
 *
 * <p>{@code jackson2-wire-golden.txt} was produced by encoding the same messages with the 3.x
 * sources of this repo compiled against Jackson 2.18.2 (generator:
 * {@code ~/hitorro-j3/tools/Jackson2MeshWireGolden.java.txt}). Jackson 3's own defaults would
 * sort record components alphabetically; {@link Codecs#MAPPER} must not.
 */
class MeshWireFormatTest {

    private static Map<String, String> golden;

    @BeforeAll
    static void load() throws Exception {
        golden = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                MeshWireFormatTest.class.getResourceAsStream("jackson2-wire-golden.txt"), StandardCharsets.UTF_8))) {
            r.lines().forEach(l -> {
                int i = l.indexOf('=');
                golden.put(l.substring(0, i), l.substring(i + 1));
            });
        }
    }

    private static String enc(Object v) {
        return new String(Codecs.encode(v), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> messages() {
        Set<String> caps = new LinkedHashSet<>(List.of("sql", "lucene"));
        AgentDescriptor ad = new AgentDescriptor("agent-1", caps, 1700000000123L);
        Map<String, String> sc = new LinkedHashMap<>();
        sc.put("topic", "z");
        sc.put("group-id", "g");
        JsonNode row = Codecs.MAPPER.readTree("{\"id\":7,\"name\":\"n\",\"score\":1.5,\"tags\":[\"a\"],\"nil\":null}");
        ShuffleSpec ss = new ShuffleSpec(List.of("k1", "k2"), 4, "mesh.query.shuffle.q1");
        CombineSpec cs = new CombineSpec("mesh.query.shuffle.q1.0", 2, "combined");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("AgentDescriptor", ad);
        m.put("CancelMessage", new CancelMessage("q1"));
        m.put("EnableS3Message", new EnableS3Message("http://s3:8333", "b", "ak", "sk", false));
        m.put("HeartbeatMessage", new HeartbeatMessage(ad, 1700000000456L, 3));
        m.put("RegisterTableMessage", new RegisterTableMessage("t", "{\"name\":\"x\"}", "file:/x", "ndjson", true, null, sc, "agent-1"));
        m.put("RegisterTableMessage.6", new RegisterTableMessage("t", "{}", "file:/x", "parquet", false, "p0"));
        m.put("ResultMessage.row", ResultMessage.row("task-1", "p0", row, 5));
        m.put("ResultMessage.eos", ResultMessage.eos("task-1", "p0", 9));
        m.put("ResultMessage.error", ResultMessage.error("task-1", "p0", "boom"));
        m.put("ResultMessage.watermark", ResultMessage.watermark("task-1", "p0", 1700000000789L));
        m.put("ShuffleSpec", ss);
        m.put("CombineSpec", cs);
        m.put("TaskDescriptor.full", TaskDescriptor.create("q1", "task-1", 1, null, null, "SELECT 1", new TreeSet<>(caps), "res", ss, cs, null));
        m.put("TaskDescriptor.pipeline", TaskDescriptor.pipelineNode("q1", "task-2", new TreeSet<>(caps), "res", "kind: x"));
        m.put("TableInventoryRequest", new TableInventoryRequest("r1"));
        m.put("TableInventoryReply", new TableInventoryReply("agent-1", List.of(new TableInventoryReply.Entry("t", "p0", "file", "file:/x"))));
        m.put("UnregisterTableMessage", new UnregisterTableMessage("t", null));
        return m;
    }

    @Test
    @DisplayName("every message type encodes to the bytes 3.x sent (field order, nulls, NON_NULL, enums, nested JsonNode)")
    void encodesLike3x() {
        messages().forEach((k, v) -> assertThat(enc(v)).as(k).isEqualTo(golden.get(k)));
    }

    @Test
    @DisplayName("every message 3.x sent decodes to an equal message")
    void decodes3xMessages() {
        messages().forEach((k, v) -> {
            Object back = Codecs.decode(golden.get(k).getBytes(StandardCharsets.UTF_8), v.getClass());
            if (v instanceof ResultMessage) {
                // "row":null decodes to a NullNode (as with Jackson 2): compare the JSON
                assertThat(enc(back)).as(k).isEqualTo(golden.get(k));
            } else {
                // Sets decode to HashSet (as with Jackson 2): compare values, not re-encoded order
                assertThat(back).as(k).isEqualTo(v);
            }
        });
    }

    @Test
    @DisplayName("a message from an older peer with fewer fields still decodes")
    void olderPeer() {
        String json = "{\"queryId\":\"q1\",\"taskId\":\"t\",\"stage\":0,\"sqlPlan\":\"SELECT 1\",\"requiredCapabilities\":[]}";
        TaskDescriptor td = Codecs.decode(json.getBytes(StandardCharsets.UTF_8), TaskDescriptor.class);
        assertThat(td.queryId()).isEqualTo("q1");
        assertThat(td.pipelineNodeSpec()).isNull();
    }
}

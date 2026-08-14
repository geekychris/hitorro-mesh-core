/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * A unit of work the driver hands to a specific agent.
 *
 * <h3>Three shapes</h3>
 *
 * <table>
 *   <caption>Task shapes by stage / spec fields</caption>
 *   <tr><th>Stage</th><th>{@code shuffleSpec}</th><th>{@code combineSpec}</th><th>Behavior</th></tr>
 *   <tr><td>0</td><td>null</td>     <td>null</td>     <td>Phase 1/2: run SQL over the local partition, publish result rows to {@code resultSubject}.</td></tr>
 *   <tr><td>0</td><td>non-null</td> <td>null</td>     <td>Phase 2.5.1 sink: run SQL over the local partition, hash-partition output rows across {@link ShuffleSpec#buckets} shuffle bucket subjects. Publish an EOS to each bucket at end.</td></tr>
 *   <tr><td>1</td><td>null</td>     <td>non-null</td> <td>Phase 2.5.1 combine worker: subscribe to a single bucket, buffer rows until {@link CombineSpec#expectedUpstreamPartitions} distinct upstream partitions have EOS'd, then run the combine SQL over the buffer and publish combined output rows to {@code resultSubject}.</td></tr>
 * </table>
 *
 * <p>The pushed-down plan is carried as <b>SQL text</b>, not a serialized
 * Calcite RelNode. Rationale: round-trips through the existing jvssql
 * parser cleanly (RelJson has quirks), human-debuggable in NATS logs,
 * and lets driver + agent run different jvssql versions.</p>
 *
 * <p>{@link JsonInclude.Include#NON_NULL} keeps phase-1/2 messages tiny —
 * neither {@code shuffleSpec} nor {@code combineSpec} appears on the wire
 * for a plain scan/aggregate.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskDescriptor(
        String queryId,
        String taskId,
        int stage,
        String sourceTable,           // stage-0 only; null for stage-1 combine tasks
        String partitionKey,          // stage-0 only
        String sqlPlan,               // partial SQL (stage-0) or combine SQL (stage-1)
        Set<String> requiredCapabilities,
        String resultSubject,         // final output (stage-0 non-shuffled, or stage-1 combined)
        ShuffleSpec shuffleSpec,      // stage-0 optional: hash output into bucket subjects
        CombineSpec combineSpec       // stage-1 only: which bucket + expected upstream partitions
) {
    /** Phase-1/2 convenience — plain stage-0 leaf, no shuffle. */
    public TaskDescriptor(String queryId, String taskId, int stage, String sourceTable, String partitionKey,
                          String sqlPlan, Set<String> requiredCapabilities, String resultSubject) {
        this(queryId, taskId, stage, sourceTable, partitionKey, sqlPlan,
                requiredCapabilities, resultSubject, null, null);
    }

    /** Jackson: older messages without shuffleSpec/combineSpec still deserialize. */
    @JsonCreator
    public static TaskDescriptor create(
            @JsonProperty("queryId")              String queryId,
            @JsonProperty("taskId")               String taskId,
            @JsonProperty("stage")                int stage,
            @JsonProperty("sourceTable")          String sourceTable,
            @JsonProperty("partitionKey")         String partitionKey,
            @JsonProperty("sqlPlan")              String sqlPlan,
            @JsonProperty("requiredCapabilities") Set<String> requiredCapabilities,
            @JsonProperty("resultSubject")        String resultSubject,
            @JsonProperty("shuffleSpec")          ShuffleSpec shuffleSpec,
            @JsonProperty("combineSpec")          CombineSpec combineSpec) {
        return new TaskDescriptor(queryId, taskId, stage, sourceTable, partitionKey, sqlPlan,
                requiredCapabilities, resultSubject, shuffleSpec, combineSpec);
    }
}

/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

import java.util.List;

/**
 * Metadata for a stage-1 combine task.
 *
 * <p>Phase 2.5.1 combines had a single shuffle input (all upstream stage-0
 * tasks emit the same shape). Phase 4b shuffle-hash join has TWO inputs
 * (left + right sides of the join), each with its own subject namespace,
 * upstream partition count, and jvssql table alias.</p>
 *
 * <p>{@link InputSource} captures one such input. A combine task subscribes
 * to every listed input's subject, buffers rows per input, tracks EOS by
 * partitionKey per input, and runs the task's {@code sqlPlan} once every
 * input has received EOS from all its declared upstream partitions.</p>
 *
 * <p>Convenience constructors for the phase-2.5.1 single-input case keep
 * existing call sites unchanged.</p>
 *
 * <p><b>Phase 4c.1 addition — {@link #joinKind}:</b> combine workers use
 * this to decide whether an empty input can short-circuit the whole
 * combine. INNER: short-circuit on any empty side. LEFT: only if left
 * empty. RIGHT: only if right empty. FULL: only if both empty. NONE
 * (aggregate combines): any-empty short-circuit — same as INNER.</p>
 */
public record CombineSpec(
        List<InputSource> inputs,
        JoinKind joinKind
) {
    /** How the combine should treat empty inputs. See class javadoc. */
    public enum JoinKind { NONE, INNER, LEFT, RIGHT, FULL }

    /** Back-compat: no join semantics (aggregate combine or single-input case). */
    public CombineSpec(List<InputSource> inputs) {
        this(inputs, JoinKind.NONE);
    }

    /** Phase-2.5.1 convenience: single input, no join semantics. */
    public CombineSpec(String shuffleInputSubject, int expectedUpstreamPartitions, String combineInputTable) {
        this(List.of(new InputSource(shuffleInputSubject, expectedUpstreamPartitions, combineInputTable, null)),
                JoinKind.NONE);
    }

    /** Phase-2.5.1 accessor — returns the first input's subject (for readability of old call sites). */
    public String shuffleInputSubject() {
        return inputs.get(0).shuffleInputSubject();
    }

    public int expectedUpstreamPartitions() {
        return inputs.get(0).expectedUpstreamPartitions();
    }

    public String combineInputTable() {
        return inputs.get(0).combineInputTable();
    }

    /**
     * One shuffle input feeding the combine.
     *
     * @param shuffleInputSubject     bucket-specific subject to subscribe to
     * @param expectedUpstreamPartitions how many distinct upstream partitionKeys must EOS before this input is complete
     * @param combineInputTable       the jvssql table alias the combine SQL uses for this input's rows
     * @param schemaTypeJson          nullable — JSON serialization of this input's JVS type.
     *                                Present for shuffle-hash joins so the combine worker can
     *                                register an empty-but-typed stream when a bucket has no
     *                                rows on this side (needed for OUTER JOIN null-padding).
     *                                When null, combine synthesizes the type from the first
     *                                buffered row — sufficient for aggregate combines and
     *                                INNER joins with a non-empty buffer.
     */
    public record InputSource(
            String shuffleInputSubject,
            int expectedUpstreamPartitions,
            String combineInputTable,
            String schemaTypeJson
    ) {
        /** Back-compat overload: no schema hint (phase-2.5.1 aggregate combines). */
        public InputSource(String shuffleInputSubject, int expectedUpstreamPartitions, String combineInputTable) {
            this(shuffleInputSubject, expectedUpstreamPartitions, combineInputTable, null);
        }
    }
}

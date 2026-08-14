/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Describes how the receiving stage-0 task should hash-partition its output
 * rows across N destination subjects for phase-2.5.1 distributed shuffle,
 * and for phase-4b shuffle-hash join.
 *
 * <p>A task whose {@link TaskDescriptor#shuffleSpec()} is {@code null}
 * behaves the phase-1/2 way: rows go to a single {@code resultSubject}.</p>
 *
 * <p>A task with a non-null {@code ShuffleSpec} hashes each output row's
 * {@code keyColumns} into one of {@code buckets} destination subjects. The
 * subject naming depends on whether this is a plain shuffle (phase 2.5.1)
 * or a shuffle-join (phase 4b):</p>
 *
 * <ul>
 *   <li>{@code sideLabel == null} → {@code <shuffleSubjectPrefix>.<bucket>}
 *       (phase 2.5.1 — single-input combine)</li>
 *   <li>{@code sideLabel != null} → {@code <shuffleSubjectPrefix>.<sideLabel>.<bucket>}
 *       (phase 4b — combine consumes multiple sides, one per label)</li>
 * </ul>
 *
 * <p>Each task also publishes an EOS to every bucket it targets when done,
 * so the combine worker knows when all upstream partitions have finished
 * for its bucket.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShuffleSpec(
        List<String> keyColumns,           // hash-partition by these columns
        int buckets,                       // destination bucket count (N)
        String shuffleSubjectPrefix,       // {@code mesh.query.shuffle.<queryId>}
        String sideLabel                   // nullable — "left"/"right" for shuffle-join
) {
    /** Phase 2.5.1 convenience — no side label. */
    public ShuffleSpec(List<String> keyColumns, int buckets, String shuffleSubjectPrefix) {
        this(keyColumns, buckets, shuffleSubjectPrefix, null);
    }

    /** {@code <prefix>.<bucket>} or {@code <prefix>.<sideLabel>.<bucket>} — one subject per bucket. */
    public String subjectFor(int bucket) {
        if (bucket < 0 || bucket >= buckets) {
            throw new IllegalArgumentException("bucket " + bucket + " out of range [0," + buckets + ")");
        }
        return sideLabel == null
                ? shuffleSubjectPrefix + "." + bucket
                : shuffleSubjectPrefix + "." + sideLabel + "." + bucket;
    }
}

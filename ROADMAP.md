# Hitorro Mesh Roadmap

Where the mesh is going, in the order things ship. Each phase is
independently useful — you can stop at any of them and still have a
working distributed SQL system. Phase-1 and phase-2 have already
shipped; the rest is planning.

## ✅ Phase 1 — Distributed filter + projection (shipped)

`SELECT ... WHERE ... project` across partitioned tables. Union at
driver. Aggregates rejected at plan time. This is what "distributed
scan" gives you: any query jvssql handles locally on one partition,
the mesh handles across N.

**Where:** [`hitorro-mesh-driver/QueryDispatcher.java`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryDispatcher.java) `submitSimple(...)`.

## ✅ Phase 2 — Distributed GROUP BY with combiner-at-driver (shipped)

`SELECT gcols, AGG(...) FROM t [WHERE ...] GROUP BY gcols` where
`AGG ∈ {COUNT, SUM, MIN, MAX}` (all associative + commutative). Planner
splits into partial-per-partition + combine-at-driver. Correct global
answers for these aggregate shapes.

**Where:** [`QueryPlanner.TwoStagePlan`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryPlanner.java) + `QueryDispatcher.submitTwoStage(...)`.

**Cap:** combiner runs on the driver. Fine for group cardinality up to
millions of distinct keys. Beyond that, phase 2.5's distributed combine
is the right answer.

## ✅ Phase 2.5 / 2.5.1 — Distributed combiner (shipped)

Combine step moved off the driver, distributed across a second-stage
worker pool partitioned by group key. Same correctness as
combiner-at-driver, no more "combine fits in driver RAM" cap.

**Enable per-cluster:**
```yaml
hitorro:
  mesh:
    driver:
      shuffle-width: 4      # 0 = combiner-at-driver (phase 2, default)
```

`shuffle-width=0` keeps the phase-2 behavior (deterministic default,
zero coordination overhead). Bump to N > 0 to enable distributed
combine — the dispatcher spins up N stage-1 combine workers on
jvssql-capable agents (round-robin), stage-0 partition output is
hashed by group key into N shuffle bucket subjects, each combine
worker consumes its bucket and runs the combine SQL locally.

**Where the code lives:**
- Protocol DTOs: [`ShuffleSpec`](src/main/java/com/hitorro/mesh/ShuffleSpec.java), [`CombineSpec`](src/main/java/com/hitorro/mesh/CombineSpec.java)
- Subject naming: `Subjects.shuffle(queryId, bucket)`
- Placement math: [`ShufflePlacement`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/ShufflePlacement.java) with 8-test coverage
- Agent shuffle sink + combine worker: [`TaskExecutor`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/TaskExecutor.java) branches on `shuffleSpec != null` / `combineSpec != null`
- Driver dispatch: [`QueryDispatcher.submitTwoStageShuffle(...)`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryDispatcher.java)
- End-to-end tests: [`DistributedShuffleTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/DistributedShuffleTest.java) — same test cases as [`DistributedAggregateTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/DistributedAggregateTest.java) but with shuffle-width > 0

## ✅ Phase 3 — AVG, SELECT DISTINCT, HAVING (shipped)

Rounds out the standard SQL aggregate surface. All three land as
`QueryPlanner` rewrites — no new operators, no new protocol pieces:

- **`AVG(x)`** — decomposed to two partial columns (`SUM(x)` + `COUNT(x)`),
  combined as `1.0 * SUM(sums) / SUM(counts)`. Multi-partial machinery
  needed for AVG makes the future addition of moments (VARIANCE, STDDEV)
  a straightforward extension.
- **`SELECT DISTINCT cols FROM t`** — rewritten to
  `SELECT cols FROM t GROUP BY cols` with no aggregate. Combine step
  naturally deduplicates across partitions.
- **`HAVING`** — extracted from the source SQL, aggregate references
  substituted with their combine expressions, applied at the combine step.
  Example: `HAVING COUNT(*) > 10` becomes `HAVING SUM(__c0__) > 10`.

**Where:**
- Planner refactor: [`QueryPlanner.java`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryPlanner.java) — sealed `Plan` hierarchy, `PartialColumn` supports multi-partial aggregates, HAVING rewrite via source-agg → combine-expr map, `SELECT DISTINCT` detection with parenthesis-aware SELECT-list splitting
- 7 tests: [`Phase3AggregatesTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/Phase3AggregatesTest.java) covering AVG (single group / multi group / with COUNT), SELECT DISTINCT (with and without WHERE), HAVING (on COUNT and on SUM)

**Deferred to phase 3.5:**
- `COUNT(DISTINCT x)` — needs a distinct set per shuffle bucket, not just a count
- `VARIANCE`, `STDDEV`, other moments — analogous to AVG, multi-partial
- `SELECT DISTINCT` combined with `WHERE`/`GROUP BY` shape corners
- Double/decimal source columns in SUM/MIN/MAX — currently synthesized as long

## ✅ Phase 4a — Broadcast JOIN (shipped)

Small dimension tables replicated to every agent — pre-loaded at agent
startup via `hitorro.mesh.agent.broadcast-tables`. Queries can JOIN
against them; the join executes per-partition at each agent, composing
transparently with WHERE / GROUP BY / HAVING / shuffle.

**Enable:**
```yaml
# agent config
hitorro:
  mesh:
    agent:
      broadcast-tables:
        - name: langs
          type-json-resource: file:/opt/hitorro/types/langs.json
          ndjson-file: file:/opt/hitorro/data/langs.ndjson

# driver config
hitorro:
  mesh:
    driver:
      broadcast-tables:
        - langs
```

Every agent must pre-load every registered broadcast table — deploy-time
invariant. If an agent is missing one, its tasks fail with a clear
jvssql error at query time.

**Why on every agent (not driver-shipped inline):**
- No NATS payload size limits per query
- Data local to the join = fast
- Broadcast update = redeploy agents (rare for dimension tables)

**Where the code lives:**
- Agent: [`AgentConfig.broadcastTables()`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/AgentConfig.java) + [`TaskExecutor.engineWithBroadcasts()`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/TaskExecutor.java)
- Driver: [`DistributedTableRegistry.registerBroadcast(...)`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/DistributedTableRegistry.java) + `QueryPlanner.validateJoins(...)`
- Planner strips table qualifiers from GROUP BY columns because jvssql returns null for qualified group refs — worked around, not fixed upstream
- 4-test [`BroadcastJoinTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/BroadcastJoinTest.java) covers plain join, join+where, join+group-by (combiner-at-driver), join+group-by+shuffle

**Deferred to phase 4c.1:**
- Multiple broadcast joins in one query — should work but not explicitly tested

## ✅ Phase 4c — OUTER JOIN against broadcast tables (shipped)

`LEFT / RIGHT / FULL OUTER JOIN` against a broadcast dimension table
works transparently — jvssql handles OUTER semantics locally per
partition, the driver unions the padded rows as usual. **Zero planner
or dispatcher changes** were needed; the existing `JOIN_TABLE` regex
uses `\bJOIN` which matches inside `LEFT JOIN` / `RIGHT JOIN` too.

**Where:**
- 3 tests: [`OuterJoinTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/OuterJoinTest.java) — LEFT JOIN preserves unmatched left rows with null-padded right; INNER JOIN baseline drops them; LEFT JOIN composes with GROUP BY (unmatched rows still counted per lang)

**Deferred to phase 4c.1:**
- **Shuffle-hash OUTER JOIN** (fact × fact with LEFT/RIGHT/FULL) — needs
  schema info in `CombineSpec` because the current empty-bucket
  short-circuit is only correct for INNER JOIN. For OUTER, a bucket
  with one empty side must still emit the non-empty side with padded
  nulls, which requires knowing both sides' schemas even when one is
  empty. Ship as a follow-up.

## ✅ Phase 4b — Shuffle-hash JOIN (shipped)

Fact × fact join between two distributed tables. Both sides are hashed
by their join key into the same N-bucket grid; per-bucket combine
workers subscribe to both sides, buffer, then run the original JOIN SQL
locally with both tables registered as jvssql streams.

**Enable:** works automatically when the query joins two registered
distributed tables. Uses the same `hitorro.mesh.driver.shuffle-width` knob
as phase-2.5.1 combine — always uses at least 1 combine worker for a
shuffle-join (setting it to 0 doesn't fall back to combiner-at-driver
because you can't join-at-driver without shipping both sides' data).

**Wire protocol changes:**
- `ShuffleSpec` gains an optional `sideLabel` — `"left"` or `"right"` —
  which routes to `<prefix>.<sideLabel>.<bucket>` instead of `<prefix>.<bucket>`
- `CombineSpec` refactors to a list of `InputSource` entries so a combine
  worker can consume multiple shuffle inputs. Phase 2.5.1 uses a
  single-element list; phase 4b uses two.

**Where:**
- Wire DTOs: [`ShuffleSpec`](src/main/java/com/hitorro/mesh/ShuffleSpec.java) with `sideLabel`, [`CombineSpec.InputSource`](src/main/java/com/hitorro/mesh/CombineSpec.java) list
- Agent: [`TaskExecutor.runCombine`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/TaskExecutor.java) now supports multi-input combines
- Planner: [`QueryPlanner.ShuffleJoinPlan`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryPlanner.java) new sealed variant + `tryPlanShuffleJoin(...)` detection
- Dispatcher: [`QueryDispatcher.submitShuffleJoin(...)`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryDispatcher.java) — N combine tasks with 2 inputs + stage-0 scans on both sides labeled left/right
- 3 tests: [`ShuffleJoinTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/ShuffleJoinTest.java) — basic INNER JOIN, wider shuffle, skewed distribution

**Design decisions:**
- **Combine SQL is the ORIGINAL query verbatim** — both tables register into the combine engine's jvssql, the JOIN executes locally. No SQL rewriting for the join itself, only for the per-side scan tasks (which are just `SELECT * FROM <table>`).
- **Empty-bucket short-circuit** now covers "any input empty" — for INNER JOIN, an empty side means no matches → empty output. OUTER-aware short-circuit shipped in [phase 4c.1](#-phase-4c1--shuffle-hash-left-outer-join-shipped).
- **MVP scope: no WHERE / GROUP BY / HAVING** on top of shuffle-join. Phase 4b.1 relaxes this to allow WHERE; GROUP BY + HAVING still require a two-stage combine and are deferred.

## ✅ Phase 4c.1 — Shuffle-hash OUTER JOIN (shipped)

Extends shuffle-hash join (phase 4b) to `LEFT / RIGHT / FULL OUTER JOIN`
between two distributed tables. Unmatched rows on either side come
through with a null-padded counterpart even when the corresponding
bucket has zero rows.

**What's new:**
- **`CombineSpec.JoinKind`** enum (`INNER`, `LEFT`, `RIGHT`, `FULL`, `NONE`)
  ships in the wire spec. Combine workers use it to decide whether an
  empty bucket collapses the whole join.
- **`CombineSpec.InputSource.schemaTypeJson`** — nullable JSON serialization
  of each input's JVS type. The dispatcher fills it in for shuffle-join
  inputs so the combine worker can register an empty-but-typed stream on
  the empty side. jvssql then null-pads that side's columns per OUTER
  semantics. Aggregate combines (phase 2.5.1) leave it null and fall back
  to first-row schema synthesis.
- **`QueryPlanner.JOIN_ON` regex** captures the join kind (INNER / LEFT /
  RIGHT / FULL) — `LEFT [OUTER] JOIN`, `RIGHT [OUTER] JOIN`, `FULL [OUTER]
  JOIN`, and bare `JOIN` all recognized.
- **Kind-aware short-circuit** in `TaskExecutor.canShortCircuit(...)` —
  INNER short-circuits on either side empty; LEFT only if left is empty;
  RIGHT only if right; FULL only if both.
- **jvssql `HashJoinIterator` extended** to support RIGHT and FULL
  (originally INNER+LEFT only). Tracks matched right rows in an
  identity-keyed set during the probe phase; after the left iterator
  exhausts, walks the hash and emits unmatched right rows null-padded.
  `combine(...)` now null-checks the left side too, symmetric with the
  existing right-null handling.

**Where:**
- Wire: [`CombineSpec`](src/main/java/com/hitorro/mesh/CombineSpec.java) — added `JoinKind` enum + `InputSource.schemaTypeJson`
- Type serialization: [`Type.getNode()`](../hitorro-jsontypesystem/src/main/java/com/hitorro/jsontypesystem/Type.java) exposes the underlying JSON for wire transport
- Planner: [`QueryPlanner`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryPlanner.java) — extended `JOIN_ON` regex, `ShuffleJoinPlan.joinKind()`
- Dispatcher: [`QueryDispatcher.submitShuffleJoin`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryDispatcher.java) — ships each side's Type as JSON in the InputSource, passes JoinKind in CombineSpec
- Agent: [`TaskExecutor.runCombine`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/TaskExecutor.java) — kind-aware short-circuit, uses schemaTypeJson to build the Type when present
- jvssql executor: [`Executor.HashJoinIterator`](../hitorro-jvssql/src/main/java/com/hitorro/jvssql/exec/Executor.java) — matched-right-rows set + trailing walk for RIGHT/FULL; symmetric null-side handling in `combine(...)`
- 3 new jvssql tests: [`ReferenceTableAndJoinTest`](../hitorro-jvssql/src/test/java/com/hitorro/jvssql/ReferenceTableAndJoinTest.java) — RIGHT with unmatched rights, FULL with both unmatched sides, RIGHT with multi-match left
- 4 mesh tests: [`ShuffleOuterJoinTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/ShuffleOuterJoinTest.java) — LEFT with unmatched left rows, LEFT with wider shuffle (more empty buckets), RIGHT, FULL

**Design decisions:**
- **Ship the full Type JSON**, not just column names. Reusing the existing
  `type.init(node)` deserialization on the combine side means we get the
  full JVS type semantics for free (nested types, primitive types, etc.),
  and any future Type additions propagate without wire changes.
- **JoinKind on CombineSpec, not TaskDescriptor** — semantically it's a
  combine-worker concern (only combine workers need to know about join
  semantics). Aggregate combines pass `JoinKind.NONE` and fall through
  to the any-empty short-circuit path.
- **Identity-keyed matched-rights set** in jvssql. Rows are unique
  `JsonNode` references from the input iterator, so identity comparison
  avoids the value-equality collision that would falsely deduplicate two
  distinct right rows with the same content. Only allocated when the
  join actually needs it (RIGHT or FULL).
- **Left-null symmetry in `combine(...)`**. The original `combine`
  null-checked the right side (for LEFT outer's null-padding) but not
  the left. Making both sides symmetric was a one-line change that
  unlocked RIGHT/FULL without touching the surrounding structure.

## ✅ Phase 4b.1 — Shuffle-hash JOIN + WHERE (shipped)

WHERE clauses now compose with shuffle-hash JOINs (INNER + all OUTER
variants). Previously the planner rejected any shuffle-join with a
WHERE, forcing users to filter downstream or fall back to broadcast.

**How it works:** The combine SQL is the ORIGINAL query verbatim — it
already carried the WHERE, jvssql was already applying it locally per
bucket. The old guard was strictly conservative; removing it was a
one-line change plus tests to prove correctness across INNER + LEFT
OUTER combinations.

**Coverage added:**
- Single-side predicate: `WHERE events.action = 'view'`
- Multi-side predicate: `WHERE docs.title = X AND events.action = Y`
- LEFT OUTER + WHERE: the null-padded-right rows are correctly rejected
  by predicates that reference the null side — matches SQL semantics
  (`null = 'view'` is unknown → not-in-result)

**Where:**
- Planner: [`QueryPlanner.tryPlanShuffleJoin`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryPlanner.java) — removed the `WHERE_CLAUSE` guard
- 3 new tests: [`ShuffleJoinTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/ShuffleJoinTest.java) — INNER+WHERE, INNER+multi-side WHERE, LEFT+WHERE

**Still deferred:**
- **WHERE pushdown to per-side scans** — bandwidth optimization: only
  ship rows that satisfy the single-side portion of the WHERE. Requires
  robust AND-decomposition of the WHERE plus per-side-qualifier
  detection. Not needed for correctness; deferred as follow-up.

## ✅ Phase 4b.2 — Shuffle-hash JOIN + GROUP BY / aggregates (shipped)

GROUP BY + aggregates (`COUNT`, `SUM`, `MIN`, `MAX`, `AVG`) now compose
with shuffle-hash JOIN between two distributed tables. Same aggregate
math as phase 2's two-stage plan, layered onto the shuffle-join
per-bucket combine.

**Execution shape (3 stages):**
1. Stage 0 — scan both sides, hash-partition by join key into N buckets
   (same as phase 4b)
2. Stage 1 — per-bucket combines run the **partial SQL** (JOIN +
   partial-aggregate) and emit rows keyed by GROUP BY cols with
   `__cN__` partial aggregate columns
3. Stage 2 — driver-side final combine reduces per-bucket partials to
   the user's final aggregates via jvssql (same `__mesh_partial__`
   ephemeral table pattern as phase 2)

**AVG decomposes** to `SUM/COUNT` pairs at the partial stage; the driver
combine reconstructs `1.0 * SUM(sum_partial) / SUM(cnt_partial)`. Same
math as phase 2 → phase 2.5.1 — the machinery is reused verbatim.

**What's new:**
- **`ShuffleJoinAggregatePlan`** — new sealed plan variant that carries
  BOTH shuffle-join fields (leftTable, rightTable, keys, joinKind) AND
  two-stage fields (partialSql, combineSql, groupColumns, partialColumns)
- **`QueryPlanner.tryPlanShuffleJoin`** — when GROUP BY + aggregate
  present in a fact×fact join, delegates to the existing `planTwoStage`
  helper for partial/combine SQL construction, then wraps the result in
  the new plan variant
- **`QueryDispatcher.submitShuffleJoinAggregate`** — new dispatch path.
  Stage 0-1 identical to `submitShuffleJoin` (shuffle both sides, per-bucket
  combines) except combines run `partialSql` instead of the original.
  After stage 1 EOS, drain partials at the driver and apply
  `combineSql` via jvssql — same shape as `submitTwoStage`
- **`synthesizePartialType(groupCols, partialCols, Type...)`** — refactored
  to accept multiple source types. Group-col lookup falls through both
  left and right table types (a group col from either side resolves).

**Where:**
- Planner: [`QueryPlanner.ShuffleJoinAggregatePlan`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryPlanner.java) + `tryPlanShuffleJoin` handles GROUP BY path
- Dispatcher: [`QueryDispatcher.submitShuffleJoinAggregate`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryDispatcher.java) — 3-stage execution
- Type helper: `synthesizePartialType(...)` now varargs on source types
- 4 new tests: [`ShuffleJoinTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/ShuffleJoinTest.java) — INNER + GROUP BY docs.id + COUNT, INNER + GROUP BY events.action (right-side col), wide shuffle + GROUP BY, GROUP BY + WHERE composition

**Design decisions:**
- **Reuse `planTwoStage` verbatim.** The partial-SQL rewrite (aggregate
  decomposition + auto-alias for function-call group cols + HAVING
  rewriting) is IDENTICAL to phase 2 — the only difference is the FROM
  clause carries a JOIN. `planTwoStage`'s existing `extractJoinChain(sql)`
  handles that automatically since phase 4b.1.
- **Driver-side final combine, not distributed.** MVP path uses
  `submitTwoStage`'s driver-collect pattern. Distributed final combine
  (shuffling partials by GROUP BY key to a second bucket grid) is a
  follow-up when partial cardinality gets large enough to matter.
- **Multi-source type lookup, not type merging.** Group cols keep their
  qualifier-stripped names (`docs.lang` → `lang`); the type lookup
  walks left + right table types in order, taking the first hit. Names
  colliding across both sides is a query-author responsibility (would
  break `GROUP BY lang` in jvssql too); the mesh doesn't try to
  disambiguate.
- **HAVING follows naturally.** Phase 2's `rewriteHaving` substitutes
  original aggregate texts with combine expressions; reused here without
  changes. HAVING clauses on shuffle-join+agg queries work out of the box.

**Small closer (shipped alongside 4b.2):**
- **Global aggregate over a JOIN** — `SELECT COUNT(*) FROM docs JOIN
  events` (no GROUP BY) reduces to a single row across all buckets.
  `planTwoStage` already handled the empty-groupCols case; only a
  latent comma bug in the partial/combine SQL construction had to be
  fixed (previously never triggered since phase 2 only entered with
  GROUP BY present). 2 additional tests cover single-bucket and
  wider-shuffle correctness.

## ✅ Phase 4b.2.1 — WHERE pushdown to per-side scans (shipped)

Bandwidth optimization for shuffle-hash JOIN queries with a WHERE
clause. Single-side conjuncts now apply at the per-side scan tasks
BEFORE rows are shuffled, so filtered-heavily queries pay dramatically
less network bandwidth. Combines still evaluate the full WHERE
(redundant but correct — extra CPU per surviving row, no wrong results).

**Decomposition safe-subset:**
- Top-level (`paren-depth 0`) AND-split — conjuncts inside parens stay grouped
- Each conjunct must reference ONE side's qualifiers exclusively
  (checked via `qualifier.column` regex — the qualifier must match
  the target side's table name)
- Predicates with no qualifiers (e.g. `1 = 1`) aren't pushed — would
  be evaluated uselessly per row on both sides
- Top-level OR bails entirely — can't split `A OR B` into per-side
  pieces without changing semantics

**Where:**
- Helper: [`QueryPlanner.computeSidePushdown(sql, tableName)`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryPlanner.java) — returns the join-friendly WHERE for one side
- Supporting: `splitTopLevelAnd`, `hasTopLevelOr`, `referencesOnlyTable` (all `package-private static` for testability)
- Dispatcher: [`QueryDispatcher.dispatchSideScans`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryDispatcher.java) gains a `pushdownWhere` parameter; both `submitShuffleJoin` and `submitShuffleJoinAggregate` compute per-side pushdown from the original / partial SQL and pass through
- 19 unit tests: [`QueryPlannerPushdownTest`](../hitorro-mesh-driver/src/test/java/com/hitorro/mesh/driver/QueryPlannerPushdownTest.java) — every decomposition rule covered offline; existing E2E tests (which now run with pushdown) prove correctness end-to-end

**Design decisions:**
- **Safe-subset MVP, not full predicate rewriting.** Only pure conjunctive
  WHERE clauses with clean per-side attribution get pushed. Anything more
  elaborate (OR, no qualifiers, mixed refs) falls through unchanged —
  correct behavior at the cost of the bandwidth savings. Sound over speed.
- **Combines re-apply the full WHERE**, not just the non-pushed conjuncts.
  Cheap correctness insurance: if the pushdown extraction has any bug
  the combine still produces the right answer (extra CPU per row, no
  wrong results). Only expose bugs via bandwidth regression tests, not
  correctness ones — much easier to notice + debug.
- **`computeSidePushdown` is `public`, not private.** Callers in the
  dispatcher need it; nothing else does. Exposed to make the wiring
  visible from outside the QueryPlanner file, since pushdown is a
  semantic contract users may reason about ("does my WHERE reduce
  shuffle bandwidth?").

**Still deferred (phase 4b.3):**
- **Distributed final combine** — shuffle partials by group-key to a
  second combine grid instead of returning to driver. Matters when
  cardinality of groups × partial-cols gets large enough to strain
  driver memory.

## 🔵 Phase 2.5 — Original design writeup (kept for reference)

**Goal:** move the combine off the driver, distribute it across a
second-stage worker pool partitioned by group key.

**Design:**

```
partition-1 (agent-us)                    combine-worker-0
  scans docs where lang=en            ─┐   subscribes mesh.query.shuffle.<q>.0
  emits {lang=en, __c0__=3}      ─────┴─►  aggregates {lang=en, c0=<sum>}
                                             publishes to mesh.query.result.<q>.0
partition-2 (agent-eu)
  scans docs where lang=en            ─┐
  emits {lang=fr, __c0__=1}      ─────┼─►  combine-worker-1
  emits {lang=de, __c0__=1}      ─────┘   subscribes mesh.query.shuffle.<q>.1
```

Each partial-stage worker publishes its output rows to
`mesh.query.shuffle.<queryId>.<bucket>` where
`bucket = hash(row[shuffleKeys]) mod N`. Each combine worker consumes
one bucket, does the local aggregate, publishes to the standard result
subject that the driver already listens on. Driver merges as before.

**New protocol pieces:**
- `ShuffleSpec { keyColumns, buckets, shuffleSubjectPrefix, partitionDoneSubject }` on `TaskDescriptor`
- `ShuffleDone { partitionKey, rowCount }` control message per bucket so the combine worker knows when all upstream partitions have finished for it
- New agent code path: `TaskExecutor` in shuffle mode hashes each output row and publishes to the right bucket instead of the result subject
- New driver dispatch step: after partial tasks are assigned, assign N combine tasks (any live jvssql-capable agent, load-balanced) with the second-stage SQL

**Correctness:** identical to combiner-at-driver for associative+commutative aggregates. Different agents may host partial and combine — that's the whole point.

**Scope for the first slice (phase 2.5.0, this iteration):**
- Add `ShuffleSpec` as an optional (null) field on `TaskDescriptor`
- Add a `chooseShuffleWidth(...)` helper on the driver that picks N based on live agent count
- Wire it as unused capacity — no behavior change yet, tests pin down the field is present and the placement math works
- Ship the design doc (this file's Phase 2.5 section) so anyone can pick up implementation

**Phase 2.5.1 (next iteration):**
- Agent-side shuffle sink (hash → bucket subject)
- Driver-side combine dispatch (second-stage tasks)
- End-to-end test: same distributed GROUP BY query, but combine happens on workers, not driver
- Preserves current combiner-at-driver as a fallback when `hitorro.mesh.driver.shuffle-width=0` (deterministic behavior for tests)

## ⚪ Phase 3 — AVG, DISTINCT, HAVING

Once shuffle is in place, these are straightforward:

- **AVG(x)** → planner decomposes to `SUM(x) / COUNT(x)`, both associative
- **COUNT(DISTINCT x)** → shuffle by x, per-bucket set-cardinality, driver sums
- **HAVING** → apply as a post-combine WHERE at the driver or at the combine worker

## ⚪ Phase 4 — Distributed JOIN

Two flavors, in order of feasibility:

1. **Broadcast join** (small right side): driver broadcasts the small table to every agent via NATS pub/sub, agents run local jvssql joins. Cheap, works for reference-table joins.
2. **Shuffle-hash join** (both sides large): partition both sides by join key on the same shuffle grid, per-bucket workers do local hash joins. Requires the phase-2.5 shuffle machinery.

## ✅ Phase 5a — LIMIT (shipped)

Driver-side row cap applied to every plan shape (simple, two-stage,
shuffle-join). The dispatcher extracts `LIMIT N` from the SQL before
planning, plans against the stripped SQL, and caps the returned
`QueryHandle` at N rows.

**Correctness first, optimization later:** MVP does the LIMIT at
driver-consumption time — agents still run to completion, ship all rows
that match, driver ignores excess. Wasteful for large partitions with
small limits. Phase 5b will push LIMIT into per-side scans (each agent
returns at most N × safety-multiplier).

**Where:**
- Planner: `QueryPlanner.parseLimit(...)` + `stripLimit(...)`
- Dispatcher: extracts before planning, passes stripped SQL, wraps handle
- Handle: `QueryHandle.withLimit(long)` caps `nextRow()`
- 5 tests: [`LimitTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/LimitTest.java) — simple LIMIT, LIMIT+WHERE, LIMIT>total, LIMIT 0, LIMIT after GROUP BY

## ✅ Phase 5b — ORDER BY (shipped)

Distributed sort. Agents keep ORDER BY in their per-partition SQL so
jvssql sorts each partition locally; the driver full-buffers all rows
and applies a global comparator. LIMIT composes with it (top-N works).

**Comparator semantics:**
- Multi-column with per-column ASC/DESC
- NULL sorts LAST for ASC, FIRST for DESC (SQL standard)
- Numeric-vs-numeric compares numerically (avoids `"10" < "9"` trap)
- Mixed-type or string columns compare lexicographically as fallback
- Qualified column names (`docs.id`) stripped to bare (`id`) to match the flat schema of returned rows

**Where:**
- Planner: `QueryPlanner.parseOrderBy(sql)` + `stripOrderBy(sql)` + `OrderKey`
- Dispatcher: extracts ORDER BY at top of `submit()`; ORDER BY stays in the agent-side SQL, extracted spec drives `OrderComparator` at the driver; new `submitSimpleSorted(...)` path
- 8 tests: [`OrderByTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/OrderByTest.java) — ASC / DESC / WHERE + ORDER BY / ORDER BY + LIMIT / multi-column / string sort / ORDER BY + GROUP BY / ORDER BY on aggregate column with LIMIT (top-N-by-count)

## ✅ Phase 5b.1 — ORDER BY composes with every plan (shipped)

ORDER BY moved to a **generic post-processing step** on the base
`QueryHandle`. Every plan shape gets it for free — simple scans,
two-stage aggregates (combiner-at-driver AND distributed combine),
shuffle-hash joins. The old `submitSimpleSorted` special-case is gone;
one `applyOrderBy(handle, orderBy)` helper handles all of them.

**Enables the classic analytics query**:
```sql
SELECT lang, COUNT(*) FROM docs GROUP BY lang ORDER BY c0 DESC LIMIT 3
```

**Where:**
- `QueryDispatcher.submit()` — applies sort after any plan dispatch, before LIMIT
- `QueryDispatcher.applyOrderBy()` — collects the base handle, sorts, wraps a fresh preloaded queue
- Same `OrderComparator` from phase 5b

## ✅ Phase 5b.2 — N-way merge sort (shipped)

`SimplePlan + ORDER BY` now uses a **streaming N-way merge** instead of
full-buffer sort. Driver memory is O(N partitions) instead of O(total
rows) — every partition sorts locally (via ORDER BY pushdown from phase
5b.3), driver keeps one head-row per partition in a priority queue,
picks the smallest, refills.

**Composes with LIMIT** — merge iterator terminates naturally after the
handle's `remaining` counter hits zero, so top-N doesn't drain every
partition; each side just needs to ship rows until we've picked our N.

**Where:**
- New `RowSource` functional interface (pluggable per-row poll) on `QueryHandle` — replaces the hard-coded queue-based iteration. Queue and merge are both `RowSource` implementations.
- `QueryDispatcher.submitSimpleMerged(...)` — dispatches per-partition scan tasks that route rows to per-partition queues; wraps a `mergeIterator(...)` that peeks heads via a `PriorityQueue<PartCursor>`.
- `pollUntilRowOrDone(...)` handles the per-partition wait: block on the partition's queue with a short poll, recheck the shared error ref between polls, return null when the partition is drained and marked done.
- TwoStagePlan / ShuffleJoin paths still full-buffer sort via the existing `applyOrderBy` — combine output lands on a single result subject, per-partition boundary doesn't apply there.

**No new tests needed** — the existing 8 `OrderByTest` cases exercise the merge path transparently. Real-NATS smoke also passes with top-4 sorted across 2 partitions.

## ✅ Phase 5b.3 — LIMIT pushdown (shipped)

When plan is `SimplePlan` and `LIMIT N` is present, driver appends
`LIMIT N` (and any ORDER BY) to the per-agent SQL. Each partition
returns at most N rows to the driver; driver applies its own LIMIT N
to pick the global top-N. Massive bandwidth win when N is small.

- **No pushdown for TwoStagePlan / ShuffleJoin** — those need every
  partial-aggregate group at the combine step, so truncating mid-flight
  would produce wrong final aggregates. Driver-side LIMIT still applies.
- **ORDER BY + LIMIT is pushed together** — agents sort locally + LIMIT N,
  driver merges the per-partition top-Ns. Correct global top-N with
  bounded network transfer.
- **Where:** `QueryDispatcher.submit()` appends the clauses to `plannerSql`
  before calling `submitSimple(...)`; `serializeOrderBy(...)` puts
  `OrderKey` list back into SQL form.
- **2 tests:** [`LimitPushdownTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/LimitPushdownTest.java) — a `CountingTable` observes that agents scan ≤ N rows when there's no ORDER BY; the ORDER BY case verifies correctness (agents must still read every row to sort, but driver-received row count is bounded).

## ✅ Phase 6a — Streaming source foundation (shipped)

Long-lived queries over an unbounded source. The primitive:
[`InMemoryStreamingTable`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/InMemoryStreamingTable.java) —
a `LocalTable` whose `openScan()` returns an iterator that blocks on
`hasNext()` until a row is pushed or the stream is stopped.

**Zero wire-protocol / dispatch changes.** The existing agent scan path
and driver dispatch handle the streaming source transparently — rows
flow to the result subject as they arrive, the QueryHandle yields them
as they land. `.stop()` on the source injects a poison pill that
unblocks the iterator, agent publishes EOS, driver handle sees end of
stream.

**Where:**
- [`InMemoryStreamingTable`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/InMemoryStreamingTable.java) — blocking-queue backed `LocalTable`
- 2 tests: [`StreamingScanTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/StreamingScanTest.java) — pushes rows mid-flight, verifies real-time delivery, tests WHERE filter on streams
- No changes needed in `TaskExecutor`, `QueryDispatcher`, or any wire DTO

## ✅ Phase 6b — Real streaming source bindings (shipped)

Two thin adapter modules that expose Kafka topics and NATS JetStream
subjects as `LocalTable`s.

- **`hitorro-mesh-streaming-kafka`** —
  [`KafkaStreamingLocalTable`](../hitorro-mesh-streaming-kafka/src/main/java/com/hitorro/mesh/streaming/kafka/KafkaStreamingLocalTable.java)
  wraps `hitorro-streams-kafka`'s `KafkaSource`. Agents that hold Kafka
  partitions register one per topic × partition, mesh queries `SELECT ...
  FROM <topic>` and rows flow as they're produced.
- **`hitorro-mesh-streaming-nats`** —
  [`NatsJetStreamLocalTable`](../hitorro-mesh-streaming-nats/src/main/java/com/hitorro/mesh/streaming/nats/NatsJetStreamLocalTable.java)
  wraps `hitorro-streams-nats`'s `NatsJetStreamSource`. Uses durable
  consumers so agent restart doesn't lose offset position.

**Both are ~30-line adapters** — the phase-6a foundation (blocking
scan iterator) and phase-6c.1/6c.2 (SSE + cancel) do all the work.
Agents already handle streaming sources transparently. Zero wire /
dispatcher / planner changes.

**Optional modules** — pull them in via Maven dep only where actually
needed. Keeps the base mesh-agent from dragging in the Kafka client
(~9MB) or the full jnats/JetStream surface unless deployers opt in.

**Deploy pattern:**
```java
KafkaSource kafka = KafkaSource.builder()
        .bootstrapServers("kafka-broker:9092")
        .groupId("mesh-agent-us")
        .topic("events")
        .build();
KafkaStreamingLocalTable table = new KafkaStreamingLocalTable(
        "events", eventsType, "us", kafka);

AgentConfig cfg = new AgentConfig(agentId, capabilities,
        Duration.ofSeconds(2), List.of(table));
```

Config-driven agent-app support shipped as [phase 6b.1](#-phase-6b1--config-driven-streaming-tables-shipped)
below — deployers no longer need to write Java to use Kafka / NATS
streaming tables.

## ✅ Phase 6b.1 — Config-driven streaming tables (shipped)

The agent-app can now declare Kafka and NATS JetStream streaming tables
directly in YAML — no Java glue needed. Each `tables:` entry picks its
source by which nested block is present:

```yaml
hitorro:
  mesh:
    agent:
      tables:
        - name: docs                         # batch — existing path
          partition-key: shard-3
          type-json-resource: file:/config/docs-type.json
          ndjson-file: file:/data/docs/shard-3.ndjson
        - name: events                       # streaming — Kafka
          partition-key: shard-3
          type-json-resource: file:/config/events-type.json
          kafka:
            bootstrap-servers: kafka:9092
            group-id: mesh-agent-shard-3
            topic: events
            auto-offset-reset: latest
        - name: metrics                      # streaming — NATS JetStream
          partition-key: shard-3
          type-json-resource: file:/config/metrics-type.json
          nats:
            url: nats://nats.mesh:4222
            stream: METRICS
            subject: metrics.shard-3.>
            durable-name: mesh-agent-shard-3
```

**What's added:**
- `AgentProperties.KafkaSourceConfig` + `NatsJetStreamSourceConfig` —
  mirror the underlying `KafkaSource.Builder` / `NatsJetStreamSource.Builder`.
  Only the essentials are required; the rest fall through to builder defaults.
- `MeshAgentApplication.loadTables` — dispatches on which of
  `ndjson-file` / `kafka` / `nats` is set. Exactly one must be present
  per entry (loader validates and fails fast with a clear message).
- Streaming adapter modules become regular deps of agent-app so
  YAML-driven config works out of the box. Fat-jar grows by ~11MB
  (kafka-clients dominates).

**No wire / dispatcher changes** — the adapters were already `LocalTable`
implementations; this just moves construction from Java into YAML.
Broadcast tables can also be Kafka/NATS-backed via the same knobs on
`broadcast-tables:`.

## ✅ Phase 6c.1 — SSE result endpoint (shipped)

`GET /mesh/queries/stream?sql=<url-encoded>` returns a Server-Sent Events
stream. One `data:` event per result row, plus:

- `event: opened` (first) — carries `queryId` + `assignedAgents`
- `event: row` (many) — one per result row, `data:` is the JSON row
- `event: complete` (last) — carries `queryId` + `rowCount`
- `event: error` — on plan-time or runtime errors

```bash
curl -N 'http://localhost:8085/mesh/queries/stream?sql=SELECT+id+FROM+docs+WHERE+lang%3D%27en%27'
```

Composes with the phase-6a streaming source — rows flow to the client
in real time as they're pushed at the source. Batch queries stream in
arrival order then complete.

**Client disconnect closes the QueryHandle** (unsubscribes from the
result subject on the driver). For batch queries this stops the driver
from receiving more rows; for streaming sources the agents keep
producing until the source stops — cancel-through-to-agents is phase 6c.2.

**Where:**
- REST: `MeshRestController.stream(...)` — SseEmitter + background worker pull loop
- No wire-protocol / dispatch changes needed — sits on top of the existing `QueryHandle.nextRow()`
- Smoke coverage: `mesh-smoke.sh` asserts opened → 4 row events → complete for the canonical WHERE query

## ✅ Phase 6c.2 — Cancel-through-to-agents (shipped)

`QueryHandle.close()` publishes a `CancelMessage` on
`mesh.query.control.<queryId>`. Every agent subscribes to the control-
subject wildcard globally, tracks worker Futures per queryId, and
interrupts them on cancel. Streaming iterators respond to interruption
(the phase-6a `InMemoryStreamingTable.openScan()` iterator catches
`InterruptedException` and terminates), the scan loop exits cleanly,
agent publishes EOS.

**Wire additions:**
- [`CancelMessage`](src/main/java/com/hitorro/mesh/CancelMessage.java) — DTO on `mesh.query.control.<queryId>`
- No other protocol changes

**Where:**
- Agent-side subscription: [`MeshAgent`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/MeshAgent.java) subscribes to `Subjects.CONTROL_PREFIX + ">"` at start
- Task-Future tracking: [`TaskExecutor`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/TaskExecutor.java) keeps `Map<queryId, List<Future>>`, drains entries when tasks finish, cancels-with-interrupt on control message
- Driver-side publish: [`QueryHandle.close()`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryDispatcher.java) publishes `CancelMessage` before closing subscription. Only agent-facing handles carry a transport reference; post-processing handles (from `applyOrderBy` / combiner-at-driver) don't need to publish because their agents already terminated.
- 2 tests: [`StreamingCancelTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/StreamingCancelTest.java) — handle close interrupts agent-side stream + fresh query afterward works; cancel on already-finished batch query is safe no-op

**Design decisions:**
- **Global control subscription** — one wildcard subscription per agent for the lifetime of the process. Cheaper than per-task subscribing and matches heartbeats' subject-wildcard pattern.
- **Best-effort cancel** — the driver publishes and forgets. If NATS drops the message or the agent is briefly disconnected, the streaming query keeps running until natural termination. Production-grade retry / durable cancel is deferred (would use JetStream for the control subject).
- **CancellationException handled silently at agent** — a task cancelled by the driver isn't a failure worth reporting on the wire. Otherwise we'd flood the result subject with error messages the driver already knows about.

## ✅ Phase 6d — Windowed aggregation (shipped)

Function-call group columns work. `SELECT WIN_START(event_time, 60000),
COUNT(*) FROM events GROUP BY WIN_START(event_time, 60000)` distributes
correctly — each agent buckets its partition into windows via jvssql,
combine step (either at driver or via shuffle) sums the per-partition
counts per window.

**Planner changes:**
- `HAS_GROUP_BY` regex broadened from character-class to `.+?` so
  parentheses (function calls) come through
- `parseGroupCols` uses parenthesis-aware `splitTopLevel` so
  `WIN_START(a, 60000), dept` splits into two tokens correctly
- New `GroupCol(partialExpr, combineName)` record — simple column refs
  use bare name for both (no regression); function-call cols get
  auto-alias `g0`, `g1`, ... so the combine step can reference them by
  identifier
- `TwoStagePlan.groupColumns()` now carries the combine names
  (`g0`, `dept`, ...) — those are what appear in the client output row

**Where:** [`QueryPlanner`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryPlanner.java)
`planTwoStage(...)` — see the phase-6d block that builds the group plan.

**3 tests:** [`WindowedAggregationTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/WindowedAggregationTest.java) —
tumbling-window count, window + dept multi-key, window + ORDER BY.

**Composes with everything:** shuffle, LIMIT, ORDER BY, HAVING — the
group column is `g0` at the combine step regardless of whether it came
from `lang` or `WIN_START(x, N)`.

**Streaming windowed aggregation over a live source** (long-lived query
with watermark-driven emission) needs windowed combine that fires as
watermarks advance — that's phase 6d.1, not this batch-shape MVP.

## ✅ Phase 7a — TLS + auth for NATS transport (shipped)

Production hardening for cross-JVM mesh clusters. The NATS transport
now accepts a full TLS + authentication config so mesh agents and
drivers can talk to a secured broker.

**What's new:**
- **`NatsSecurity`** immutable record in `hitorro-mesh-nats` carries
  username/password, token, .creds file path, TLS flag, and
  truststore/keystore paths (with passwords + optional store type). All
  fields nullable; `NatsSecurity.none()` is the "no auth, no TLS" default.
- **`NatsMeshTransport.openUrl(url, security)`** — new overload that
  applies TLS + auth via jnats `Options.Builder`. Existing single-arg
  `openUrl(url)` preserved for BC (calls the new overload with
  `NatsSecurity.none()`).
- **Custom SSL context** built on demand from PKCS12 / JKS keystore +
  truststore pairs (`NatsSecurity.buildSslContext()`). Bare `tls: true`
  falls back to `Options.Builder.secure()` — JVM default trust store,
  works for public-CA-signed brokers.
- **`NatsSecurityProperties`** — mutable Spring-binding companion to
  the immutable record (records don't play well with
  `@ConfigurationProperties`). Converts to `NatsSecurity` via
  `toSecurity()`.
- **`hitorro.mesh.agent.nats-security`** and
  **`hitorro.mesh.driver.nats-security`** — matching YAML blocks on
  both apps. Set the same block on every mesh peer for a coherent
  secured cluster.

**Config example — agent connecting to a secured broker with mTLS + .creds:**
```yaml
hitorro:
  mesh:
    agent:
      transport: nats
      nats-url: tls://nats.mesh.internal:4222
      nats-security:
        credentials-file: /var/run/nats/agent.creds
        tls: true
        trust-store-path: /etc/pki/ca.p12
        trust-store-password: ${TRUST_STORE_PW}
        key-store-path: /etc/pki/agent-client.p12
        key-store-password: ${KEY_STORE_PW}
```

**Auth precedence** (transport picks the strongest configured):
1. `credentials-file` (nkey + JWT via .creds)
2. `token`
3. `username` + `password`

**TLS activation** (any of):
- Explicit `tls: true`
- Any truststore/keystore path set
- URL scheme `tls://` (jnats auto-picks)

**Where:**
- Core: [`NatsSecurity`](../hitorro-mesh-nats/src/main/java/com/hitorro/mesh/nats/NatsSecurity.java), [`NatsSecurityProperties`](../hitorro-mesh-nats/src/main/java/com/hitorro/mesh/nats/NatsSecurityProperties.java)
- Transport: [`NatsMeshTransport.openUrl(url, security)`](../hitorro-mesh-nats/src/main/java/com/hitorro/mesh/nats/NatsMeshTransport.java) — new 2-arg factory + private `applySecurity(Options.Builder, NatsSecurity)`
- Apps: [`AgentProperties`](../hitorro-mesh-agent-app/src/main/java/com/hitorro/mesh/agent/app/AgentProperties.java), [`DriverProperties`](../hitorro-mesh-driver-app/src/main/java/com/hitorro/mesh/driver/app/DriverProperties.java) — new `nats-security` block; `MeshAgentApplication` + `MeshDriverApplication` pass it through to the transport factory and log the resolved auth mode
- 5 tests: [`NatsSecurityTest`](../hitorro-mesh-nats/src/test/java/com/hitorro/mesh/nats/NatsSecurityTest.java) — empty config, tls-only, custom-material detection, real PKCS12 SSLContext build, properties → record roundtrip

**Design decisions:**
- **Auth precedence ladder, not `oneof` guard.** Users can leave stale
  weaker-auth fields in YAML without breaking — the transport just picks
  the strongest configured option. Documented in the `NatsSecurity`
  javadoc so future readers know which wins.
- **`Options.Builder.credentialPath(String)`, not `Nats.credentials(...)`.**
  jnats 2.20.5 has both; the builder method keeps the security
  configuration next to the rest of the connection knobs.
- **Ship the empty-config path unchanged.** Every existing deployment
  (in-memory transport, dev NATS without auth) continues to work
  bit-identically. TLS/auth is opt-in on top.
- **Log the resolved auth mode**, not the credentials themselves. The
  startup log shows `auth=creds-file|token|user/pass|none` so operators
  can verify the mesh peer picked up their config, without leaking
  secrets to the log.

## ✅ Phase 6d.1 — Watermark-driven windowed streaming (shipped, MVP)

Long-lived windowed aggregate queries over streaming sources. Windows
close and emit incrementally as the watermark advances past their end
time — no waiting for end-of-scan, no buffering-forever driver combine.

**Execution shape:**
1. `DistributedTable.streamConfig()` non-null declares a streaming source
   → `DistributedTableRegistry.streamingTableNames()` picks it up
2. `QueryPlanner.plan(...)` detects "windowed aggregate over streaming
   source" (GROUP BY has `WIN_START`/`WIN_END`/`WIN_HOP_STARTS` +
   source name is in `streamingTables`) → returns
   `StreamingSimplePlan` BEFORE the batch two-stage detector
3. `QueryDispatcher.submitStreamingSimple` dispatches ONE scan task
   with the ORIGINAL SQL verbatim
4. `TaskExecutor.registerLocalSource` (new shared helper) sees
   `LocalTable.streamConfig()` non-null and calls
   `builder.registerStream(name, iter, type, streamConfig)` — jvssql
   then auto-swaps to the incremental `StreamingAggregate` executor
5. Windows close as watermark (derived from event-time observations
   in `WatermarkFilter`/`WatermarkTracker`) advances past their end;
   per-window rows flow to the driver via regular ROW messages
6. Query terminates on cancel or when the source signals end
   (e.g. `InMemoryStreamingTable.stop()`)

**Wire protocol changes:** NONE. Existing ROW/EOS/ERROR is sufficient
— the "streaming" nature is fully encapsulated in the agent's
StreamConfig registration.

**MVP scope — single-partition sources only.** Multi-partition
streaming aggregate would need incremental cross-partition combine
(aggregating per-window partial rows from each partition as they
arrive). That's phase 6d.2. Dispatcher rejects multi-partition with
a clear message pointing at the deferred phase.

**Where:**
- Agent: [`LocalTable.streamConfig()`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/LocalTable.java) default method + [`InMemoryStreamingTable`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/InMemoryStreamingTable.java) constructor overloads for event-time
- Agent: [`TaskExecutor.registerLocalSource`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/TaskExecutor.java) — shared helper that passes StreamConfig to jvssql when non-null; reused across broadcasts, plain scans, shuffled scans
- Driver: [`DistributedTable.streamConfig()`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/DistributedTable.java) + [`DistributedTableRegistry.streamingTableNames()`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/DistributedTableRegistry.java)
- Planner: [`QueryPlanner.StreamingSimplePlan`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryPlanner.java) sealed variant + `tryPlanStreamingAggregate(...)` + `WINDOW_FUNC` regex; `plan(...)` overload accepts `streamingTables`
- Dispatcher: [`QueryDispatcher.submitStreamingSimple`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryDispatcher.java) — single-partition guard + reuses `submitSimple` for the actual scan
- 2 tests: [`WindowedStreamingTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/WindowedStreamingTest.java) — closed windows emit incrementally as watermark advances (proves rows arrive BEFORE stream stops), multi-partition rejection

**Design decisions:**
- **Reuse jvssql's streaming aggregate verbatim.** All the hard math
  (WatermarkTracker, per-window state, `closeReadyWindows`) already
  lives in jvssql. The mesh's only job is to (a) know the source is
  streaming and (b) register it that way. Cleanest possible layering.
- **No wire protocol changes.** Windowed rows are just regular
  aggregate rows arriving at their own cadence — the transport
  doesn't need to know they're "windowed". Keeps the mesh's SPI narrow.
- **Single-partition MVP.** Multi-partition needs cross-partition
  combine (per-window incremental) which is a substantial addition
  — dispatch reject with a clear phase-6d.2 pointer beats trying to
  wire something half-correct.
- **`StreamingSimplePlan`, not a flag on SimplePlan.** The planner
  contract (single-agent, no combine, no EOS-buffering) is
  meaningfully different. Sealed variant makes the pattern-match
  exhaustive and reads more clearly than a nullable-config plan.
- **Cancel = close = source-stop.** All three terminate the scan
  cleanly via the existing cancel/interrupt path — no new
  termination logic needed.

**Still deferred:**
- **Streaming joins** — windowed joins where both sides advance
  independently.
- **HAVING over streaming windows** — same combine issue as agg.
- **User-alias preservation through combine** — combined windowed
  output uses internal aliases (`g0`, `c0`) instead of the user's
  `AS ws`/`AS n`. Same limitation as phase-2 combine. Not fixed here.

## ✅ Phase 6d.2 — Multi-partition streaming aggregate (shipped)

Extends phase 6d.1 to multi-partition streaming sources. Each partition
runs the partial aggregate via jvssql streaming (per-window emission),
driver reduces per-window partials across partitions incrementally as
they arrive.

**Plan variant refactor:** `StreamingSimplePlan` (single-partition-only,
carried just the original SQL) → `StreamingWindowedPlan` (carries
original SQL AND partial/combine SQL). Dispatcher picks execution shape
based on `table.partitions().size()` — single-partition takes the
phase-6d.1 path (original SQL, driver forwards); multi-partition takes
the new path (partial SQL per partition, incremental cross-partition
combine at driver).

**Advance-past close detection.** No wire-level watermark plumbing —
window closure is inferred from per-partition emission order. jvssql's
streaming aggregate emits window {@code W}'s row only after its watermark
advances past {@code W}'s end, so partition {@code P} emitting for
window {@code W} implies {@code P.watermark >= W + windowSize}. A window
{@code W} is considered globally emittable when
{@code min(latest_emitted_window per partition) >= W}. TreeMap-backed
buffer walks in ascending order; first not-yet-closed window terminates
the sweep (no later entry can be closed either).

**Combine math.** When a window closes, its buffered partial rows are
fed into a fresh jvssql engine (registered as `__mesh_partial__`) that
runs the combine SQL — same reducers as phase-2 (SUM for COUNT/SUM, MIN
for MIN, MAX for MAX, ratio for AVG). Output rows push onto the user's
result queue.

**Termination.** On EOS from every partition, remaining buffered
windows flush unconditionally (`drainAll=true`) then the driver emits
its final EOS. On cancel/close, same path via `QueryHandle.close()`
publishing `CancelMessage`.

**MVP caveats:**
- **Sparse-emitter stall** — mitigated by phase-6d.2.1 watermark
  heartbeats (see below). Pure-idle partitions (zero events ever)
  still stall — needs system-time-based idle detection, deferred.
- **User-alias loss** — combine output uses internal aliases
  (`g0`, `c0`) instead of user's `AS ws`/`AS n`. Same limitation
  as phase-2 combiner-at-driver.

## ✅ Phase 6d.2.1 — Watermark heartbeats (shipped)

Fixes the sparse-emitter stall in phase 6d.2. A streaming scan task
now spawns a background heartbeat thread that publishes WATERMARK
messages every 200ms carrying the partition's max observed event-time.
The multi-partition combine uses these to advance window closure even
for partitions whose watermark has crossed boundaries WITHOUT emitting
rows in the intervening windows.

**Wire protocol addition:**
- `ResultMessage.Kind.WATERMARK` — new message kind
- `ResultMessage.watermarkMs` — long, 0 for non-watermark messages
- Static factory: `ResultMessage.watermark(taskId, partitionKey, wm)`

**Agent side:**
- New [`WatermarkTracker`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/WatermarkTracker.java) —
  wraps a scan iterator to observe {@code event_time} on each row;
  exposes running max as {@link #current()}. Atomic CAS-loop update
  so it's safe to read from the heartbeat thread.
- `TaskExecutor.runScanPlain` — for streaming sources, wraps the scan,
  starts a `ScheduledExecutorService` heartbeat firing every 200ms.
  Cancels on scan completion. Publishes only when at least one row
  has been observed (skips MIN_VALUE sentinel).

**Driver side:**
- `submitStreamingMulti` handles a new WATERMARK case in its result
  subscription. Extracts `windowSize` from `WIN_START(field, N)` in
  the plan's original SQL via `extractWindowSize(sql)` (once at
  dispatch, not per-message).
- Converts watermark → highest-closed-window-start via
  `wm >= size ? ((wm - size) / size) * size : MIN_VALUE`
- Updates `partitionLatestWindow[pk] = max(current, latestClosed)`;
  triggers `emitClosedWindows` on advance.

**Where:**
- Wire: [`ResultMessage`](src/main/java/com/hitorro/mesh/ResultMessage.java) — WATERMARK kind + `watermarkMs` field + `watermark(...)` factory
- Agent: [`WatermarkTracker`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/WatermarkTracker.java) (new) + `TaskExecutor.runScanPlain` heartbeat wiring
- Driver: `QueryDispatcher.submitStreamingMulti` — WATERMARK case + `extractWindowSize` helper
- 1 new test: `WindowedStreamingTest.watermarkHeartbeats_unblockWindowClosureForSparseEmitter` — p1 events span windows 0 and 480k, p2 only events at 480k. Window 0 closes via p2's heartbeat (not via row emission) BEFORE streams stop.

**Design decisions:**
- **Publish only after first event observed** (default behaviour;
  overridden by phase 6d.2.2 below when idle-timeout is set).
- **200ms interval.** Small enough that windows close snappily,
  large enough that heartbeat traffic stays cheap. Configurable via
  `TaskExecutor.WATERMARK_INTERVAL_MS` if a deployment needs to tune.
- **Extract windowSize at dispatch, not per-message.** Regex on the
  plan's original SQL runs once when the subscription is set up; the
  per-message handler is a tight math expression.
- **Single ScheduledExecutorService per agent.** Streaming tasks
  aren't so numerous that per-task schedulers are worth it; sharing
  one small pool keeps thread count bounded.
- **Windowed formula: `((wm - size) / size) * size`.** Handles all
  boundary cases (`wm == size` closes window 0; `wm < size` returns
  MIN_VALUE sentinel meaning "no closed window yet").

## ✅ Phase 6d.2.2 — Idle-timeout watermarks (shipped)

Closes the pure-idle stall documented in phase 6d.2. A partition with
zero events (or extended quiescence) now advances its watermark based
on wall-clock time, so it never blocks global window emission
indefinitely.

**Two rules folded into `WatermarkTracker.currentWithIdle()`:**
1. **Never observed anything, been running > timeout**: return
   `system_now - idleTimeoutMs`. Assumes event-time is roughly
   aligned with wall-clock (real-time streams).
2. **Observed at least once, then went quiet > timeout**: return
   `observed_max + (elapsed_since_last_observation - timeout)`.
   The excess idle time is added to the last known watermark —
   as if event-time flowed at real-time rate during the silence.

**Configurable via `-Dhitorro.mesh.watermark.idle-timeout-ms`.**
Default 30 seconds — long enough that momentary quiescence doesn't
drift watermarks incorrectly, short enough that truly-idle partitions
unblock global window emission within a reasonable window. Set to a
very large value (or `Long.MAX_VALUE`) to disable — useful for backfill
scenarios where event-time is arbitrary (not aligned with wall-clock).

**Where:**
- Agent: [`WatermarkTracker`](../hitorro-mesh-agent/src/main/java/com/hitorro/mesh/agent/WatermarkTracker.java) — added `idleTimeoutMs` constructor param + `currentWithIdle()` method + `lastObservationSystemMs` volatile tracking
- Agent: `TaskExecutor.watermarkIdleTimeoutMs()` reads the system property; heartbeat scheduler calls `tracker.currentWithIdle()` (was `.current()`)
- 9 unit tests: [`WatermarkTrackerTest`](../hitorro-mesh-agent/src/test/java/com/hitorro/mesh/agent/WatermarkTrackerTest.java) — basic observation, monotonic max, default-disabled idle, never-observed idle advance, observed-then-idle advance, re-observation resets timer, missing event-time field ignored

**Design decisions:**
- **Idle timeout disabled by default in the `(String eventTimeField)`
  constructor** (`Long.MAX_VALUE`). Existing tests + backfill paths
  keep the old behaviour without touching them. Only the mesh agent's
  streaming scan path opts in via the system-property lookup.
- **`volatile lastObservationSystemMs`, not atomic.** Read by heartbeat
  thread, written by scan thread; monotonic long field, benign races
  produce slightly-stale timestamps that self-correct on next scan.
  Atomic would work too but is overkill for the read-check-decide
  pattern.
- **System property for the timeout, not a full config object.** Test
  and deployment can override without any API surface; the default
  matches the "30-second idle bound" convention common in streaming
  systems.
- **Wall-clock assumption is opt-in.** The default 30s is fine for
  real-time streams. For backfill, set the property very large. The
  javadoc calls out this assumption prominently.
- **Skip integration-test — timing-sensitive.** Unit-test the tracker
  logic offline; trust the phase-6d.2.1 heartbeat plumbing test to
  cover the wire-up. An integration test with real time-based idle
  would be flaky and add maintenance cost with no correctness gain.

**Where:**
- Planner: [`QueryPlanner.StreamingWindowedPlan`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryPlanner.java) with both original + partial/combine fields; `tryPlanStreamingAggregate` delegates to `planTwoStage` for the partial/combine rewrite
- Dispatcher: [`QueryDispatcher.submitStreamingMulti`](../hitorro-mesh-driver/src/main/java/com/hitorro/mesh/driver/QueryDispatcher.java) — per-partition scan dispatch + `emitClosedWindows` incremental combine + `reduceOneWindow` jvssql invocation per emit; `submitStreamingSingle` preserves the phase-6d.1 path
- 1 new test: `WindowedStreamingTest.multiPartitionStream_combinesPerWindowAcrossPartitions` — two partitions push into shared windows, verify combined counts across partitions and unconditional flush on EOS

**Design decisions:**
- **Single plan variant, dispatcher branches.** The planner can't know
  partition count without extra API surface; carrying both SQL flavors
  in one plan lets the dispatcher decide at runtime with zero planner-
  API growth. Trade-off: the plan carries some fields unused in the
  single-partition path — small memory hit, big simplicity win.
- **No wire protocol changes.** Same ROW/EOS/ERROR message kinds as
  every other query shape. Watermark propagation would require a new
  message kind — worth it if idle-partition stall becomes a real
  problem, deferred until then.
- **Advance-past heuristic, not min-watermark.** Without explicit
  watermark propagation, we infer watermark from emission order. Works
  when every partition eventually emits at least one row per window
  (or advances past it). Falls short for truly idle partitions —
  documented as MVP caveat.
- **TreeMap for buffered windows.** Ordered iteration lets the emit
  loop short-circuit at the first non-closed window (later windows
  can't be closed if an earlier one isn't). O(log N) insert/remove
  vs. O(N) scan-then-sort per emit.
- **`ws <= minLatest`, not `<`.** A window {@code W} is closed once
  every partition has emitted for {@code W} itself (not strictly
  later). Off-by-one there would starve the common case where all
  partitions emit for the same window and then stop.

## ⚪ Phase 7 — Storage tiers as first-class LocalTable variants

Beyond NDJSON, agents should be able to serve partitions from:

| Source          | LocalTable variant                        | When to use                     |
|-----------------|-------------------------------------------|---------------------------------|
| NDJSON file     | `NdjsonLocalTable` (shipped)              | Demos, small datasets           |
| RocksDB kvstore | `KvStoreLocalTable`                       | Point lookups + prefix scans    |
| Lucene index    | `LuceneLocalTable`                        | Full-text WHERE clauses         |
| Mongo           | `MongoLocalTable`                         | Existing Mongo deployments      |
| Kafka topic     | `KafkaLocalTable` (streaming)             | Log analytics                   |
| Basefile (S3)   | `BasefileLocalTable`                      | Cold analytics over S3 shards   |

Each variant plugs into the existing `LocalTable` interface. No mesh
protocol change required — the ergonomics live in the agent-app's
Spring config.

## ⚪ Phase 8 — Reliability, security, observability

- **JetStream for shuffle** — plain pub/sub is fine when partial rows can be re-derived by re-running a task. Distributed combine can lose intermediate rows on network glitches; JetStream's durable subjects with per-consumer ACK guarantee delivery.
- **Task retry** — driver notices when a partition hasn't EOSed within timeout, re-dispatches to another capable agent.
- **TLS + credentials** — same story as everyone else: `nats://` → `tls://` + creds file, mesh doesn't own the policy.
- **Prometheus metrics** — actuator already exposes JVM + web metrics; add `mesh_query_duration`, `mesh_shuffle_bytes`, `mesh_agent_active_tasks` counters. Cheap.
- **Distributed tracing** — propagate the queryId through NATS headers so a Jaeger/Tempo view can walk driver→agent→shuffle→combine spans.

## Not on the roadmap

Things we've deliberately decided NOT to build:

- **Automatic partitioning / rebalancing.** Partitions are declared by
  the deployer (Orion YAML, K8s Helm values). Balancing a shard onto a
  new node is a deploy-time change, not a runtime decision. Keeps the
  mesh small.
- **A driver HA layer.** Restart the driver — clients retry, in-flight
  queries fail with a clean error. If you need HA, run two drivers
  behind a load balancer; each is stateless.
- **A cost-based optimizer.** jvssql's Calcite already does rule-based
  optimization within one partition; the mesh dispatches the same plan
  to N partitions. Cross-partition cost estimation is a huge undertaking
  and pays off only past 100+ node clusters — not our current scale.
- **Custom serialization.** Everything on the wire is Jackson JSON.
  A binary format (Arrow / Protobuf) would be faster but is a big change
  for a workload we're not yet CPU-bound on.

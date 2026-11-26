# Network Ledger V1: Format, Validation, and Display

This document defines the V1 group network ledger: its file format, strict reading rules, the
validation and exact metrics derived from it, the independent Python checker, and the offline
report projection. Per-file V2, storage V3, and online assignment each have their own contracts
(see the [capability matrix](<DATAFLOW_CAPABILITY_MATRIX.md>)) and are not interchangeable with V1.

## 1. Interfaces and export

`NetworkLedgerCodec` encodes and strictly decodes the ledger and reconstructs immutable evidence;
`ExperimentArtifactWriter` writes the complete bundle; `ExperimentArtifactValidator` checks the
ledger content and the run context.

```java
String json = NetworkLedgerCodec.encode(report.getNetworkEvidence());
NetworkLedgerCodec.Decoded checked = NetworkLedgerCodec.decode(json);
NetworkRunEvidence frozen = checked.getEvidence();
NetworkRunMetrics recomputed = checked.getMetrics();
```

```java
// config has already selected a supported fluid model, a static mapper/dispatch, and a matching platform.
SimulationConfig observed = config.toBuilder()
    .networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(50000)).build();
SimulationReport report = new SimulationRunner().run(observed, platform);
ExperimentArtifactWriter.ExperimentArtifacts files =
    ExperimentArtifactWriter.write(report, outputDirectory, "run");
ExperimentArtifactValidator.ValidationResult result =
    ExperimentArtifactValidator.validate(files.getManifest());
Path ledger = result.getNetworkLedger(); // null when OFF; unreferenced files are never guessed at
```

ON writes the usual metrics and events plus `run.network-ledger.json`, registers its role, digest, and
size in the manifest, and writes the manifest last. OFF writes the usual three files with no
`networkEvidence` field and no network role. Readers trust only manifest roles; unreferenced
ledgers left in a reused directory are ignored, not deleted.

- A network semantic or context error fails before the directory is created or any file changes.
- Each file is replaced atomically and the manifest is written last; this is not a multi-file
  transaction, and a mid-way I/O failure is caught by the later content checks.
- `ExperimentManifestWriter.writeJson` alone must not drop an ON ledger; use the full-bundle export.
- The v4 `networkEvidence` object must coexist with the `network-ledger` role; null, false, an OFF
  object, or an illegal budget is not read as OFF.
- Artifact sizes and event sequence numbers are exact longs.
- Topology validation covers same-edge, cross-pod, explicit placement, and reduced-core cases and
  does not allocate a whole topology for an untrusted large `k`.

## 2. Document header

- schema: `workflowsim-network-ledger-v1`
- Artifact role: `network-ledger`, file name `<runId>.network-ledger.json`
- flowUnit: `V1_PARENT_OR_EXTERNAL_GROUP`
- units: time=`SIMULATION_SECONDS`, bytes=`MODELED_BYTES`, rate=`BYTES_PER_SECOND`
- numericProfile: `BINARY64_SCALAR_MAXMIN_8ULP_CAPPED_1E_MINUS12_V1`
- accountingVersion: `EXACT_BINARY64_INPUT_DECIMAL_V1`
- evidence: configuration, model kind, raw `traceSnapshot`, immutable bindings, `engineCreated`,
  and optionally the aggregated `inputDemand` observations.
- metrics: `NetworkRunMetrics` recomputed from the reconstructed evidence; the summary submitted
  in the document is not trusted.

The schema contains no working directory, source paths, or wall-clock values, and does not change
the manifest-v4, metrics-v2, or events-v1 formats.

## 3. Strict reading

1. Reject duplicate keys, lenient JSON, trailing content, missing or unknown fields, wrong enums,
   and strings or booleans in place of numbers.
2. Nullable fields must be present explicitly. The CAPACITY identity is empty; other event
   identities are required, with exactly one non-null payload matching the type.
3. int/long identities and budgets are read as exact integers, never through double; the budget
   is positive and the mode is `FLUID_GROUP_LEDGER_V1`.
4. binary64 trace fields are finite; a non-zero value that underflows to 0 is rejected. Spellings
   such as `4.9e-324` and `5e-324` are compared by binary64 value.
5. BigDecimal aggregates compare as exact decimals and may exceed `Double.MAX_VALUE`; a signed
   accounting difference is neither rejected as a negative flow nor absolute-valued.
6. Nesting depth is capped at 64, and number token length, precision, and scale at 4096. The
   writer enforces the same limits. This is not a memory cap on the whole trace.
7. Immutable DTO factories are invoked explicitly; Gson default values are never relied on.
8. Trace semantics and capacity certificates are validated; retained STARTs correlate exactly
   with bindings, input-count partitions are checked, and every metrics field is recomputed.
9. Extra unretained bindings in truncated evidence are not filtered silently;
   `engineCreated=false` cannot hide non-empty records, times, or input observations.

`parseDocument(String)` provides a strict, bounded lexical JSON tree that keeps exact numeric
types; it does not replace the semantic validation of `decode`. Object member order is
irrelevant; array order (events, paths, Task lists) is preserved.

## 4. Capture states

- `COMPLETE`: all records are retained. Flows may still be active in a mid-run snapshot.
- `TRUNCATED`: a deterministic prefix is retained; it supports no whole-run traffic, mean-FCT, or
  utilization claim.
- `certifiedThroughSequence` marks the stable boundary up to which the full assignment certificate
  holds. The tail may be inside a rate-change block, a service batch, the completion queue, or a
  pending window.
- For a truncated tail only observed syntax and necessary bounds are checked; the checker neither
  proves that an unknown suffix exists nor reconstructs discarded history.
- Validation is internal consistency, not cryptographic authenticity under a consistent rewrite.
  Configuration, bindings, main events, and provenance are checked jointly at the artifact layer.
- Flows are `PARENT_GROUP_V1` or `EXTERNAL_GROUP_V1`, not per-file flows. Their source tag
  `MODELED_CONSTRAINED_SOURCE` is not the per-file replica chosen by the nominal estimator.

## 5. Exact derived metrics

```java
NetworkRunEvidence evidence = report.getNetworkEvidence(); // null when OFF
if (evidence != null) {
    NetworkRunMetrics runMetrics = NetworkRunMetrics.calculate(evidence);
    NetworkTraceMetrics metrics = runMetrics.getTransferMetrics();
    TransferTraceValidator.Result checked = metrics.getValidation();
    if (metrics.isAvailable()) { // only a COMPLETE capture publishes whole-capture totals
        BigDecimal service = metrics.getServicedBalanceDeltaBytes();
    }
}
```

`NetworkTraceMetrics.calculate` runs the independent verifier and reads the frozen snapshot; it
touches no live engine. Under `EXACT_BINARY64_INPUT_DECIMAL_V1` record doubles are represented
exactly as BigDecimal and multiplied and summed without rounding.

| Quantity | Definition |
| --- | --- |
| `admittedPayloadBytes` | Demand bytes B over all START records |
| `servicedBalanceDeltaBytes` | Exact sum of before − after, D |
| `modeledRateAreaBytes` | Exact sum of rate × recorded elapsed, A; never replaced by a clock difference |
| `completionResidualBytes` | Numeric removal R at completion; not network service |
| `remainingLedgerBytes` | Balance Q not yet completed |
| `rateAreaMinusBalanceDeltaBytes` | Signed A − D, never absolute-valued or clamped |
| `completedDemandBytes` | Raw demand of completed flows; not the serviced volume |

Every flow satisfies `B = D + R + Q` exactly; `A = D` is never asserted (a large balance minus
0.5 may round to the same double, giving D = 0 and A = 0.5).

- FCT is kept per admission ordinal even when an external ID is reused; an active flow has a null
  completion time (censored sample).
- Effective FCT, observed FCT, and notification lag are kept separately. A zero effective FCT may
  carry positive service; no infinite throughput is emitted.
- Means are averaged in DECIMAL128 then converted to double; p95 is nearest-rank on integers.
- OFF and TRUNCATED keep diagnostics but publish no byte totals, FCT samples, or utilization;
  these are unavailable, not zero. COMPLETE with zero flows has a true zero byte total and no
  FCT or utilization samples.
- Resource integrals are charged with the repeated weights of the original paths; their sum is
  not delivered bytes.
- Utilization is `boundedRateArea / capacityArea`; capacity area is counted once per
  positive-elapsed service batch, global idle gaps are excluded, and registered but unoccupied
  resources carry zero load.
- A never-constrained SOURCE has flow area but no utilization denominator. Traffic before a
  resource registers capacity does not enter that resource's utilization numerator.

### Locality observations

Locality is observed, not inferred from demand minus service. The input loop performs one
local-replica check and reuses that boolean for both the skip branch and the counter; it does not
query the catalog again or change admission, release, or replica policy.

`NetworkInputDemandTracker` keeps a fixed number of long/BigDecimal aggregates scoped to
`V1_GROUP_INPUT_REFERENCES`: repeated references within a parent group, references from
different Job attempts, and zero-byte references all count. It is not a unique-file count or a
cache model.

- `requiredReferenceBytes` sums observed file sizes exactly; `localReferenceBytes` sums skipped
  references; `transferableReferenceBytes` covers the rest. Counts and bytes each partition exactly.
- No references means no hit-rate denominator; a zero-byte reference counts toward the reference
  count but not the byte denominator.
- `NetworkRunMetrics` reports reference-count and byte hit rates separately, plus
  `admittedMinusTransferableReferenceBytes`: the signed difference between V1 double-arithmetic
  group admission and exact non-local reference bytes, not a locality gain (`1e16+1` within one
  group yields a −1 or +1 rounding difference with zero local references).
- Counters keep aggregating after the trace budget is exhausted, but TRUNCATED publishes no
  locality metrics.
- Legacy `NetworkRunEvidence.capture` overloads without observations report
  `INPUT_COUNTERS_UNAVAILABLE`; no zero hit rate is fabricated.

## 6. Validated trace syntax

1. Record sequence is contiguous 1..N; each START's `admissionOrdinal` is contiguous. An external
   ID may be reused only after the earlier flow completes.
2. Each record has exactly one payload matching its type, with legal numbers, identities,
   resource lists, and time domains.
3. CAPACITY may be registered or overwritten only while no flow is active.
4. A START carries the new flow's final initial rate, followed by RATE_CHANGE records of older
   flows in admission order; a later rate change cannot revise that initial rate.
5. A positive-elapsed step carries exactly one SERVICE per flow active before the step, in
   admission order. Batches are delimited by the active-flow count, not by timestamps or record
   types.
6. Elapsed, before/after, and internal boundaries are re-derived with the original binary64
   formulas; the recorded elapsed is kept, not replaced by `end − start`.
7. The completion queue follows the relative/ULP byte tolerances and the earliest-flow forced
   zeroing rule; the residual is not serviced traffic.
8. Survivor rate changes follow the completion queue; capacity and the max-min certificate are
   checked after the full rate-change block, so serialization transients are not overloads.
9. Integration continues while a flow is known active within the observed horizon; a complete EOF
   closes every required phase, with the active state consistent with `engineTime`.

Numerical boundaries that must be preserved:

- Start and end clocks of a positive-elapsed interval may be identical; zero FCT is not zero service.
- A zero-elapsed iteration emits no SERVICE but may complete flows; the forced-zeroing residual
  may exceed the usual completion tolerance.
- Two adjacent COMPLETE records at the same time may come from different iterations, and
  completion order need not follow admission order.
- With `q = Double.MIN_VALUE` and no resources, X=`192q@128` and Y=`64q@64` admitted at 0 and
  advanced to `2q` first service once and complete Y, then complete X with zero elapsed, leaving
  residual `64q`. This is the expected behavior.

### Max-min bottleneck certificate

Each stable assignment is checked for:

- every rate finite, positive, and not above its nominal cap;
- every finite resource's weighted load within capacity, repeated keys charged per occurrence;
- every flow not limited by its cap having at least one occupied saturated resource on which no
  co-user has a higher rate, within the numerical profile.

The last rule rejects feasible but unfair assignments (capacity 100, two identical demands at
75/25) and histories where capacity was not reclaimed after competitors completed. Resources
without registered capacity are never bottlenecks; a SOURCE's infinite default budget emits
neither Infinity nor a utilization.

### Numerical profile

`BINARY64_SCALAR_MAXMIN_8ULP_CAPPED_1E_MINUS12_V1`:

- Service and time formulas compare exactly in binary64; positive and negative zero clocks are equal.
- Capacity loads are computed from exact BigDecimal representations of record doubles.
- Capacity budget: `min(8 * (occupancyTerms + 1) * ulp(capacity), capacity * 1e-12)`.
- Rate comparisons use the same relative cap, with the ULP term from the active-flow count and the
  larger compared rate.
- This is neither the input-byte completion tolerance nor a global time epsilon.

The relative cap matters: a bare multi-ULP budget can exceed the whole capacity at subnormal
scale, where the legacy engine can allocate `2q + 2q` against capacity `3q`. The verifier rejects
such certificates; the legacy engine is not modified to pass them.

### Truncated tail

- Exactly the budgeted number of records is retained and `droppedCount` is positive; filling the
  budget without dropping a record is COMPLETE.
- Fixed flows are newly admitted flows, older flows whose rate changes were processed, and older
  flows already passed in sequence.
- The fixed subset's weighted-load lower bound must fit capacity, and every fixed uncapped flow
  must still have a feasible bottleneck.
- A variable flow's upper bound combines its nominal cap, compared rates, remaining capacity on
  each occupied resource, fixed occupancy, and repeated weights; the allocator is never invoked.
- If no legal pending rate change remains, the assignment must be closed and certified.
- Determined missing service batches and completion queues, plus records each surviving flow needs
  in the observed window, count toward the minimum `droppedCount`.
- If only the snapshot watermark advanced, only the conservative minimum obligation is computed;
  unknown history is not replayed.

## 7. Artifact-level checks

The pure codec holds no Job results, VM placements, or main events. Artifact validation also
checks configuration, actual VM placement, Job/Task identities, source and deterministic paths,
platform capacity, Job-ready, input-completion and Job-start times, and per-Job group counts.
Input reference counts agree with the main events; positively transferring groups do not exceed
non-local references; each Job attempt has at most one V1 external-input group. Referenced bytes
are checked against the double grouping quantities within explicit accumulation bounds; the
permitted ±1 grouping-rounding difference is not evidence of locality.

Legacy v4 tools may check only the extra role's digest and size. ON evidence must use the current
validation and rerun chain, which compares the sidecar field by field under `/networkLedger/...`
(see the [rerun contract](<../experiments/RERUN_DIFF_CONTRACT.md>)).

## 8. Python checker

[verify-network-ledger.py](<../../scripts/verify-network-ledger.py>) uses only the Python standard
library (3.9+), invokes no JVM, solver, or network service, and writes nothing.

```bash
# recommended: complete bundle plus run context
python3 -B scripts/verify-network-ledger.py path/to/result.manifest.json --json

# a single sidecar; does not attest the enclosing run
python3 -B scripts/verify-network-ledger.py path/to/result.network-ledger.json --json
python3 -B scripts/verify-network-ledger.py path/to/result.file-lifecycle.json --json
python3 -B scripts/verify-network-ledger.py path/to/result.storage-lifecycle.json --json
```

Manifest mode recognizes only registered artifact roles and checks relative paths, digests, sizes,
configuration, and context; an unreferenced sidecar cannot turn OFF into ON.

| Input contract | Checked | Not claimed |
|---|---|---|
| V1 group ledger | Record syntax, capacity and max-min certificates, exact accounting, derived metrics, context | Per-file provenance, TCP replay |
| File lifecycle V2 | File plans, sources and paths, replication and visibility, input and CPU causality | Per-interval fluid service |
| Storage lifecycle V3 | V2 checks plus constrained SOURCE, commit waiting, output obligations, context | Storage consistency protocols, eviction, faults |
| Online target assignment | Bindings with full lifecycle, PE/MI, CPU reservations, nominal input bounds, logged scores | Exact in-flight balances, future contention, optimality |

Online checks always report `liveProgressReplayed=false`. The plain OFF path for legacy modes
checks only manifest version, artifact path, digest, and size, and the switch/role relation, and reports
`contextualRunChecked=false`; use the Java validator for full configuration validation.

| Status | Meaning |
|---|---|
| `VALID_COMPLETE` | Complete capture passed within the declared scope |
| `VALID_PREFIX` | Truncated V1 prefix satisfied its necessary constraints; `completeCaptureCertified=false` |
| `DISABLED` | An allowed unrecorded mode passed its basic checks; not certified zero traffic |
| `EVIDENCE_INVALID` | Inconsistent format, references, numbers, or semantics; non-zero exit |

All four checkers share one core, [_network_audit.py](<../../scripts/_network_audit.py>), which
dispatches to [_file_lifecycle_audit.py](<../../scripts/_file_lifecycle_audit.py>),
[_storage_lifecycle_audit.py](<../../scripts/_storage_lifecycle_audit.py>), and
[_dataflow_assignment_audit.py](<../../scripts/_dataflow_assignment_audit.py>). Their independence
is from the Java production path, not from each other.

- Times, rates, and balances follow the binary64 scalar rules; integer and decimal aggregates
  never pass through double. V1 uses `Fraction.from_float` for exact accumulation.
- Max-min assignments are verified by bottleneck certificates, not by rerunning an allocator.
- V2/V3 rebuild logical files, provenance, paths, and lifecycles independently; online actions are
  checked against the pre-request state.
- Duplicate keys, non-standard constants, type spoofing, missing fields, excessive depth,
  out-of-range numbers, directory escapes, and NUL file names are rejected.

General study statistics are handled by the [study audit tooling](<../../scripts/STUDY_AUDIT.md>).

## 9. Offline report display

The Workbench run report and `Workbench report <manifest> <new-report.html>` use the same loading
chain: artifact references, content, lifecycle or ledger, and run context are validated, then
display projections are built from the frozen results. The browser reads no neighboring files,
runs no simulation, and needs no service or CDN. Inconsistent artifacts are rejected before the
report file is created. Usage is in the [Workbench guide](<../getting-started/WORKBENCH.md>).

| Panel | Content |
|---|---|
| V1 network ledger | Group-level service, effective/observed FCT, notification lag, resource accounting, locality |
| File lifecycle V2 | File sources, paths, merges, settlement, visibility |
| Storage lifecycle V3 | Input reads, SOURCE waiting, output write-back, CPU and output tails |
| Online target assignment | INITIAL/RETRY_REUSE per attempt, selected VM, candidate scores, observation summaries |

Displayed states are distinct: **unrecorded** (not zero traffic); **complete with zero positive
replication**; **complete capture** (may be a snapshot with active work); **V1 truncated prefix**
(prefix and diagnostics only, no whole-run summaries); **no valid result** (previous tables are
cleared; a declared online mode without a projection displays as invalid).

Identities, ordinals, counts, times, rates, ratios, and exact accounting values are converted to
exact strings before `JSON.parse`; JavaScript number conversion and locale formatting are never
applied to them.

| Projection | Preview cap |
|---|---|
| V1 | 64 flows and 64 resources, 128 events |
| V2 | 64 replications and 64 resources, 128 events |
| V3 | 64 replications, resources, and Jobs each, 128 events |
| Online actions | 64 actions, 12 candidates per action |
| Nested Task/path lists | 12 entries; labels at most 256 UTF-16 units without splitting surrogate pairs |

Each preview keeps total, shown, and omitted counts; trimming never changes the capture status. A
selected candidate is looked up in the full candidate set even beyond the preview cap.

- Balance deltas, rate areas, residuals, outstanding balances, path occupancy, and reference
  locality are shown separately, never merged into delivered traffic.
- Effective, observed, CPU, and output completion times keep their own bases.
- The online panel shows `BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1` and
  `liveProgressReplayed=false`.
- A component-level projection without run-context validation keeps `contextValidated=false`.
- Embedded JSON escapes HTML characters and U+2028/U+2029; DOM content uses `textContent`.
- The validated manifest snapshot and decode results are reused; files are not re-read for display.

The [browser checker](<../../scripts/verify-report.cjs>) checks layout, toggles, exact cells,
preview counts, and the absence of extra files and HTTP requests; the
[counterexample check](<../../scripts/test-report-checker.cjs>) breaks text, numeric types, panels,
scope statements, and caps and must fail. Commands are in the
[build guide](<../getting-started/BUILD.md>).

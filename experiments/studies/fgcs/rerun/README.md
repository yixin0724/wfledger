# FGCS Manifest-Guided Rerun/Reconstruction Protocol

## Purpose and non-claims

This study addresses the second-round RQ1 reproducibility criticism with a **direct
manifest-guided reconstruction, re-simulation, and exact core-diff** experiment. It calls the real
`org.workflowsim.experiments.rerun.RerunDiffExecutor.execute(Path, Path)` implementation on real manifest-v4
bundles. It does not use mocks, a scalar-only substitute, or a test-only reimplementation of the differ.

This protocol is **not event replay**. Recorded events are evidence to validate and compare; they are not fed back
into CloudSim to drive execution. For each runnable case, the existing executor validates the original bundle,
resolves and identity-checks its DAX input, rebuilds the recorded configuration and platform, invokes the standard
`SimulationRunner`, validates the newly written evidence, and then compares original and rerun JSON cores.

The inspected tree has no `RerunDiffService` type. The integration entry point is therefore the existing public
`RerunDiffExecutor.execute(...)` API itself; this study does not invent a service layer or claim to have called one.

The normative comparison and verdict rules are in
`docs/experiments/RERUN_DIFF_CONTRACT.md`. Expected outcomes below are fixed before execution from that contract and
from the hand-derived parser/runtime-length equation. They are never inferred from a rerun report.

## Controlled evidence

The driver creates a new four-task DAX under its empty output directory. Task runtimes are 10, 20, 30, and 40
seconds; each non-root Task consumes its parent's named output. It writes two genuine baselines directly through
`SimulationRunner` and `ExperimentArtifactWriter`:

- coherent shared-file V2 physics with a complete `FILE_LIFECYCLE_V2` sidecar;
- coherent bounded-storage V3 physics with `sourceStorage` and a complete `FILE_STORAGE_LIFECYCLE_V3` sidecar.

Both use one DAX, two pinned SPACE_SHARED VMs, LOCAL file access, RANDOM planning plus STATIC dispatch, NONE
clustering, and the FGCS base seed/configuration. Simulations and reruns execute serially through the standard
runner/session boundary. No frozen dataset, production rerun class, manuscript file, or existing evidence bundle is
modified. Every case also records before/after hashes to check that the executor left its original bundle unchanged.

Run from the repository root with an empty or nonexistent output directory:

```bash
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fgcs.rerun.ManifestGuidedRerunStudy \
  '-Dexec.args="paper/tmp/fgcs-round2/rerun"'
```

With no argument, the default output is `paper/tmp/fgcs-round2/rerun`. The driver refuses a nonempty output
rather than mixing runs. A nonzero count in `summary.unexpectedOutcomes` makes `main` fail after the full matrix and
its machine-readable diagnostics have been written.

## Contract-derived case matrix

The **Expected** column is the protocol hypothesis, not a measured result. Actual verdicts, exit codes, failure
reasons, divergence pointers, and expectation matches are emitted only by execution into `results.json`.

| Case ID | Controlled operation and contract-derived reason | Expected |
|---|---|---|
| `genuine-v2-control` | Unmodified real V2 writer output; same-code reconstruction and re-simulation must preserve all non-exempt JSON values | `IDENTICAL_CORE` |
| `genuine-v3-control` | Unmodified real V3 writer output, including `sourceStorage` and complete storage lifecycle | `IDENTICAL_CORE` |
| `relocated-v2-input-path` | Put the run at `<study>/runs/original` and a byte-identical input at `<study>/inputs/chain.dax`; tier 1 verifies identity before path substitution. The three path-text changes are volatile, not semantic | `IDENTICAL_CORE`, verified tier 1, and all three required volatile path notes |
| `volatile-v3-manifest-metadata` | Change only root `runtime.osName` and `provenance.core.codeSource`; those root subtrees are exempt while V3 core and sidecars remain exact | `IDENTICAL_CORE` with both required volatile notes |
| `semantic-v2-runtime-scale` | Change `runtimeScale` from the declared baseline 1.0 to 2.0 but retain the unit-scale result/events/lifecycle. The parser computes nonzero Task length as `runtimeSeconds × runtimeReferenceMips × runtimeScale`, so the rebuilt run must change effective lengths and downstream timing core values | `DIVERGED`, including `/result/tasks/0/effectiveExecutionLengthMi` |
| `evidence-invalid-v2-makespan` | Advance simulation end and the sole terminal event by 0.5 s while retaining stale `makespanSeconds`; then repair every artifact hash/size. Manifest-v4 metric recomputation rejects the stale metric before re-simulation | `EVIDENCE_INVALID`, with `makespanSeconds` in the reason |
| `input-v2-hash-mismatch` | Resolve a same-size DAX with its last byte flipped while retaining the original input SHA-256 declaration; the first located regular file must fail identity verification and stop probing | `INPUT_HASH_MISMATCH`, with expected and actual identities in the details |
| `input-v3-unresolved` | Align all path fields to a unique nonexistent absolute path; no study-input or rebasing candidate applies | `INPUT_UNRESOLVED`, retaining the tier-2 candidate in the details |
| `reconstruction-v3-unsupported-vm-scheduler` | Replace `platform.vms[0].schedulerMode` with `NOT_A_SCHEDULER_MODE`; current platform rebuilding must refuse rather than substitute another scheduler mode | `RECONSTRUCTION_REJECTED`, naming the unsupported label |

The runtime-scale expectation is hand-derived, not a golden value copied from an implementation run. With the
baseline `runtimeReferenceMips=1000`, for example, a 10-second Task has 10,000 MI at scale 1.0 and 20,000 MI at scale
2.0. The test requires the semantic pointer and verdict; it does not manufacture agreement by editing the rerun or
adding an exemption.

The invalid-makespan case deliberately has valid post-mutation artifact integrity declarations. Its expected rejection
is therefore semantic validation, not stale-hash detection. Input failures and reconstruction refusal are negative
classifications, not successful consistency checks, and must not produce a purported successful rerun manifest.

## Exact comparison domain and exemptions

`EvidenceCoreDiffer` treats every non-whitelisted field as core by default. In particular, the comparison includes:

- manifest configuration, physical platform and resource declarations, input identities, workflow profile/graph,
  Job/Task/VM/workflow results, actual placements, and V2/V3 file plans;
- all non-exempt metrics, including computed simulation quantities;
- event count and array order, every event field, and all non-exempt event attributes;
- artifact roles, reference paths, array order, and integrity declarations;
- complete declared network/file/storage sidecar contents, including sidecar `runtime` and `provenance` namespaces;
- online assignment configuration, compute requests, assignment events, action attributes, nominal bounds, and scores
  when applicable.

JSON **values**, not file bytes, are compared. Object member order is irrelevant; array order and length are
preserved. Numbers use exact decimal-value equality: `1000`, `1000.0`, and `1e3` are equal, but comparison is not
through `double`. There is **no epsilon, relative tolerance, statistical tie threshold, floating-point rounding
allowance, or timing tolerance** for simulation values. Missing, JSON null, strings, booleans, and numbers remain
distinct except for the contract's separately specified historical energy compatibility rule below.

Only these actual paths are volatile/exempt and their differences are still recorded:

| Scope | Exempt fields |
|---|---|
| Root identity/environment | Every subtree of `/provenance` and `/runtime` |
| Algorithm-description identity | `/configuration/algorithmContract` |
| Input path text | `/configuration/workflowPaths/<i>`, `/inputs/<i>/path` |
| Workflow-result path text | `/result/workflowOutcomes/<i>/path` |
| Artifact byte digest declarations | `/artifacts/<i>/sha256`, `/artifacts/<i>/sizeBytes` |
| Metrics wall-clock totals | `/metrics/totalSchedulingDecisionWallClockNanos`, `/metrics/totalPlanningDecisionWallClockNanos` |
| Event wall-clock attributes | `/events/<i>/attributes/decisionElapsedNanos`, `/events/<i>/attributes/planningDecisionElapsedNanos` |

Important limits to those exemptions:

- Input SHA-256, byte count, format, version, normalization, order, and array length remain core. Path relocation is
  legal only after identity verification.
- `schedulingAlgorithm`, `planningAlgorithm`, `executionSemantics`, physical parameters, budgets, and dataflow
  assignment declarations remain core; exempting `algorithmContract` does not exempt actual algorithms.
- Artifact **paths** are not exempt. The artifact hash/size exemption does not exempt sidecar contents.
- Root `/runtime` and `/provenance` exemptions do not propagate into a V1/V2/V3 sidecar.
- New fields are core by default. This study adds no whitelist entry.

The contract separately unifies absent/JSON-null historical per-VM power leaves or `/energyMetrics` with a present
canonical all-zero declaration. This is not a numeric tolerance and never exempts nonzero energy. Fresh powered
baselines in this focused matrix do not exercise that historical compatibility case.

A truncated V1 ledger, when applicable in another study, limits an identical verdict to retained records and aggregate
observations. Complete V2/V3 lifecycle comparison here is not a fluid-service-area, packet, real-hardware, or
real-storage certificate.

## Why this differs from scalar worktree reproduction

A scalar worktree reproduction typically starts from a currently written configuration, runs the current worktree
again, and compares selected numbers such as makespan or energy. That can establish limited repeatability, but it can
miss lost input identity, incomplete platform/configuration restoration, changed graph or placement, reordered or
altered events, and corrupted lifecycle evidence. It also commonly treats validation or input-resolution failures as
absent scalars rather than explicit scientific outcomes.

This protocol instead starts from the **retained validated manifest and its declared artifacts**. It checks input
hash/size, rebuilds the recorded physical and algorithmic configuration without silent substitution, creates a new
complete evidence bundle, and compares the entire non-exempt JSON core. It records `EVIDENCE_INVALID`,
`INPUT_UNRESOLVED`, `INPUT_HASH_MISMATCH`, and `RECONSTRUCTION_REJECTED` as distinct outcomes. Thus an identical
makespan alone cannot be misreported as complete reproduction.

`IDENTICAL_CORE` remains bounded: it applies to the stated input, platform, current code, recording mode, and exact
comparison domain. It does not prove real-cluster fidelity, independent authentication of the evidence-generating
implementation, or reproducibility for untested configurations.

## Machine-readable output

The output layout is:

```text
<output>/
  results.json
  evidence/
    inputs/chain.dax
    genuine-v2/result.*
    genuine-v3/result.*
    cases/...
  executor/<case-id>/
    rerun-report.json
    rerun-report.md
    rerun/result.*                 # only when execution produced a new valid bundle
```

`results.json` has schema `fgcs-manifest-guided-rerun-results-v1`. Every case records:

- `caseId`, genuine-control/probe kind, V2/V3 family, mutation, mutation-integrity handling, and applicable scope;
- the contract rationale plus structured required pointers/failure fragments/input tier;
- expected and actual verdict, actual exit code, verdict match, diagnostic expectation match, and unmet expectations;
- full core and volatile divergence projections, input resolution, failure reason/details, code identity, and coverage
  notes;
- original manifest SHA-256/size, each declared artifact's actual and declared SHA-256/size, declared input identities,
  rerun evidence hashes when present, and both executor-report hashes;
- whether the original evidence hashes stayed unchanged.

The root contains separate RQ1 genuine-control and overall summaries, expected/actual verdict counts, and explicit
coverage boundaries. No actual verdict is prefilled in this README.

## Focused tests and integration

```bash
mvn -q -pl :workflowsim-workbench -am \
  -Dtest=ManifestGuidedRerunStudyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The test class runs the complete real-driver matrix once in a temporary directory, then checks both genuine controls,
path relocation, volatile metadata, semantic divergence, every negative verdict, required diagnostic anchors, result
round-trip, hash records, and the absence of a successful rerun bundle after classified failures. It uses no mocks and
does not depend on optional historical output being present.

No POM or package-script change is needed: the driver and test are under the existing `experiments` source roots.
The only integration step is to run the Maven command above serially, retain `results.json` together with
`evidence/` and `executor/`, and cite measured counts from that generated file rather than from the protocol's
expected matrix.

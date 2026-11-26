# Rerun and Exact Difference Comparison

This capability answers: **for a valid piece of manifest v4 evidence, once it has been re-run under the current code, do the non-exempt JSON core quantities agree?** It is the rebuilding, re-simulation, and diff checking of a single run — not event replay, checkpoint resumption, a cache hit, or the automatic re-execution of an entire study.

Report regeneration is also distinct from the rerun: Workbench's `report` only generates HTML from validated evidence and does not execute the simulator, while the rerun here re-executes the standard `SimulationRunner`. Model applicability is covered in the [algorithm contract](<../algorithms/CONTRACTS.md>) and the recording requirements in the [reproducibility guide](<REPRODUCIBILITY.md>).


## Command and Input Conventions

Run from the project root, replacing the placeholder paths:

```bash
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.rerun.RerunDiffExecutor \
  '-Dexec.args="/absolute/original-run" "/absolute/new-rerun-output"'
```

The [CLI](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/RerunDiffExecutor.java>) accepts, and only accepts, the original run directory and an output directory. The output must not exist or must be empty and should be independent of the original evidence; no new artifacts are written back into the original run.

The [evidence reader](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/RerunEvidenceReader.java>) prefers `result.manifest.json`; if that is absent, it accepts the single `*.manifest.json` in the directory and refuses when no candidate or more than one candidate exists. Metrics, events, and the declared optional sidecars are located through manifest references, not by guessing file names to fill the gaps.

Only manifest v4 is supported. v2/v3 can be read by the corresponding integrity validators but cannot be silently upgraded here for a rerun. A standalone manifest does not replace a complete bundle; a declared sidecar that is missing or invalid results in `EVIDENCE_INVALID`.

## Execution and Snapshot Boundaries

```text
read the original bundle and validate it
  → locate the files by input identity
  → rebuild the configuration and platform from the validated manifest
  → re-simulate with the current standard runner
  → write and validate the new evidence
  → compare the core quantities exactly
  → emit the JSON and Markdown verdict
```

The reader uses the manifest snapshot returned by the validator; the unvalidated pre-read tree is not taken as the basis for rebuilding. This snapshot is not a CloudSim checkpoint and not a filesystem snapshot: the manifest tree of `RerunEvidence` is used under a read-only convention, while metrics/events/sidecars are still read from their paths at comparison time. Callers must not modify the tree, and the contents of the original directory must also remain unchanged during validation and the rerun; this tool provides no concurrent-write isolation or archival attestation.

## Input Resolution Rules

[RerunInputResolver](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/RerunInputResolver.java>) preserves the following order:

1. If the run sits at `<study>/runs/<run>`, try `<study>/inputs/<original file name>`.
2. Try the input path recorded in the manifest.
3. If the path can be re-based on the original `provenance.execution.workingDirectory`, it is relocated to the **current process working directory**. This is not automatic discovery of the repository root; that is why this guide requires launching from the project root.

As soon as a regular file is found at any level, its SHA-256 and byte count are verified immediately; on mismatch the result is `INPUT_HASH_MISMATCH`, and no other candidate file may be probed to paper over the inconsistency. If every candidate misses, the result is `INPUT_UNRESOLVED`, and the report keeps the candidates that were tried. This CLI has no additional `--input-root` option; the options of the standalone Python auditors cannot be carried over.

Input order, hashes, sizes, formats, versions, and normalization information still take part in the core comparison. The path-text exemption can only be applied after this identity-verification chain; it is not a license to substitute inputs at will.

## Configuration Rebuilding Rules

[ManifestConfigRebuilder](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/ManifestConfigRebuilder.java>) translates the preserved configuration and platform: algorithms, file systems, seeds, runtime conversion, the kernel tick, the arrival table, deadline, matrices, failures/overheads, Host/VM/prices, topology, and storage. Input paths switch to the identity-verified resolution result; nulls and optional fields follow the schema/compatibility rules, and no new study parameters are guessed from the current user environment. The SHARED storage rate `platform.storage.maxTransferRateMbPerSecond` is read as a finite positive binary64; an integer literal such as `15` and `15.0` rebuild to the same double and compare equal.

- The nine physical models are rebuilt from their recorded kind and parameters; V2/V3 must not be rewritten as V1, and an unrecorded run must not replace an enabled capture.
- Absent `networkEvidence` explicitly means OFF; when present, its mode and budget are restored. A present `dataflowAssignment` is likewise restored in full, not ignored on the grounds that the algorithm contract declares it identity information.
- The V3 `sourceStorage` must be present and match the model; a coherent topology must additionally satisfy the numeric domain. The rebuilt VM→Host pre-check mapping must equal the recorded values.
- When a parameter, enum, or combination is no longer accepted by the current code, the report is `RECONSTRUCTION_REJECTED`; algorithms, models, or capacities are never swapped in silently. Unrecorded external-policy processes or arbitrary Java objects are not restored from the manifest.
- The current execution writes `executionSemantics = WORK_CONSERVING_TASK_EXECUTION_V3`. Evidence that lacks the field or declares the historical `WORK_CONSERVING_TASK_EXECUTION_V2` may still be rebuilt, but this does not restore an old engine: the identity difference is reported as a core divergence even if some values coincide (see [execution identity](<REPRODUCIBILITY.md#execution-identity>)). No whitelist entry covers it.

Input, rebuilding, or run failures must not be treated as consistency success. Classified simulation-execution exceptions and exhausted retry budgets also map to `RECONSTRUCTION_REJECTED`, with the cause noting the execution phase; this is an incompatible result that needs to be exposed. When the rebuilt platform violates a construction-time law (for example per-VM carbon intensities without a power model), the `IllegalArgumentException`, `IllegalStateException`, or `SimulationConfigurationException` raised during execution is reported as `RECONSTRUCTION_REJECTED` with both report files written.

## Network, Lifecycle, and Online Actions

| Recording mode | Validation and capture requirements | Exact-comparison namespace |
| --- | --- | --- |
| OFF | No optional sidecars; the coherent file plan and the V3 storage declarations still remain | The corresponding manifest core fields |
| `FLUID_GROUP_LEDGER_V1` | The roles, hashes, contents, and run context of the V1 group ledger; a truncated prefix may be explicitly marked | Everything under `/networkLedger/...` |
| `FILE_LIFECYCLE_V2` | The V2 file lifecycle and run context; only complete captures are accepted | Everything under `/fileLifecycle/...` |
| `FILE_STORAGE_LIFECYCLE_V3` | The V3 storage lifecycle, commit/output obligations, and run context; only complete captures are accepted | Everything under `/storageLifecycle/...` |

The three sidecar roles are mutually exclusive and cannot substitute for one another. The `dataflowPlan` of V2/V3-OFF runs and V3's `platform.sourceStorage` are still core quantities. Storage validation distinguishes the CPU return from the commitment of all outputs, including zero-byte files and unused sinks, and checks the applicable failure/retry causality; it does not thereby claim that the probabilistic failure process or the random numbers have been independently replayed.

The online-assignment configuration, the `dataflowComputeRequests`, the `DATAFLOW_VM_ASSIGNED` events, and all their action attributes are core quantities. Online attestation requires the complete matching V2/V3 lifecycles; its scope is the assignment, the nominal bounds, and the recorded scores — not exact live-flow balances, future contention, or global optimality. Even if a changed score would still pass the boundary checks, the actual rerun must still compare exactly and report the diff.

When the V1 ledger on either side is TRUNCATED, the report adds `networkEvidenceCoverageNote`: even an `IDENTICAL_CORE` only says that the retained records and the aggregated observations agree; an unknown suffix cannot be attested. When both sides are OFF or the capture is complete, this placeholder field is not added. The complete V2/V3 lifecycle is likewise not a per-interval fluid service-area certificate.

Format and certification scope are in the [V1 ledger](<../advanced/NETWORK_LEDGER.md>), [V2 lifecycle](<../advanced/FILE_LIFECYCLE_FORMAT.md>), [V3 storage](<../advanced/STORAGE_DATAFLOW_CONTRACT.md>), and [online assignment](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_CONTRACT.md>) documents.

## Comparison Rules

[EvidenceCoreDiffer](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/EvidenceCoreDiffer.java>) treats **all non-whitelisted fields** as core by default; it does not merely compare makespan:

- The manifest's configuration, platform, input identity, workflow profile/graph, Job/Task/VM/workflow results, actual placement, and the file plan and online compute requests where present.
- All non-exempt metrics fields; the entry count and order of events, all event fields, and the non-exempt attributes.
- Artifact roles, reference paths, array order, and integrity declarations; the complete contents of the optional sidecars.

What is compared is the **exact equality of JSON values**, not byte equality of the file text: object key order is irrelevant, while array order and length are preserved; numbers are compared by their exact decimal values, so `1000`, `1000.0`, and `1e3` are equal, but values are not first converted to double and no epsilon is used. Strings, booleans, null, and missing members remain distinct. The implementation is [ExactJsonValues](<../../experiments/src/main/java/org/workflowsim/experiments/common/ExactJsonValues.java>).

Network/lifecycle content preserves numeric types through strict lexical reading; long numbers must not degrade into strings or lose diffs to double rounding. Adjacent integers above 2^53, genuinely tiny decimal changes, and adjacent floating-point values receive no tolerance. Tie thresholds from statistical analysis must not be borrowed to loosen the comparison here.

### Fixed Volatile-Field Whitelist

Only the following actual paths are exempted and have their diffs recorded; `<i>` denotes an array index:

| Scope | Exempted fields |
| --- | --- |
| Root identity/environment subtrees | All subtrees of `/provenance` and `/runtime` |
| Algorithm-description identity | `/configuration/algorithmContract` |
| Input path text | `/configuration/workflowPaths/<i>`, `/inputs/<i>/path` |
| Workflow-result path text | `/result/workflowOutcomes/<i>/path` |
| Artifact byte digests | `/artifacts/<i>/sha256`, `/artifacts/<i>/sizeBytes` |
| Metrics wall-clock totals | `/metrics/totalSchedulingDecisionWallClockNanos`, `/metrics/totalPlanningDecisionWallClockNanos` |
| Event wall-clock attributes | `/events/<i>/attributes/decisionElapsedNanos`, `/events/<i>/attributes/planningDecisionElapsedNanos` |

The lengths of core arrays such as inputs and results, the input identity, the `schedulingAlgorithm` / `planningAlgorithm` in the configuration, `executionSemantics`, the physical parameters, and the recorded budgets are not whitelisted. Sidecar namespaces do not inherit the root `/runtime` and `/provenance` exemptions; the artifact hash/size exemption does not let sidecar contents escape comparison either. New fields are core by default and cannot be added to the volatile rules casually.

The bit-exact comparison defined by this contract takes the manifest/metrics artifacts as its domain; exact equality of fields such as makespan within that domain already constitutes rerun consistency. The marker events `SIMULATION_TERMINATED` and `JOB_EXECUTION_STARTED` (see the [reproducibility contract](<REPRODUCIBILITY.md>)) are core data. Evidence written without them differs from a current rerun in the event array; regenerate such evidence instead of exempting the old event shapes.

Object member names are first encoded per RFC 6901 (`~`→`~0`, `/`→`~1`); the pointers are then assembled and classified against the whitelist. The literal key `configuration/algorithmContract` has the path `/configuration~1algorithmContract`, not a nested identity object; `provenance/extra` works the same way — names must not be used to sneak past the classification.

The reader may accept an original bundle not named `result`, but the new rerun always writes `result.*`; artifact reference paths of the original bundle can therefore produce a legitimate core divergence. **Artifact paths carry no additional exemption**, and such diffs must not be ignored to obtain `IDENTICAL_CORE`.

### Identity Information Reported Separately

The report retains both sides' code identity (Git commit) and `algorithmContract`. When the code identities are known to differ, a notice flags the change, but this does not substitute for the input/configuration/result comparison. An identical core only says that this piece of evidence is consistent within the scope of this comparison; it does not prove that other inputs, platforms, or future versions agree as well, and consistency must not be presumed when identity is unavailable.

## Report and Exit Codes

```text
rerun-output/
  rerun-report.json
  rerun-report.md
  rerun/
    result.manifest.json
    result.metrics.json
    result.events.jsonl
    ...declared matching sidecars
```

The report contains the `verdict`, the input resolution, JSON pointers and both-side values for the core divergences, `volatileFieldsNoted`, identity information, and applicable coverage notes. Divergences are compared on their full values; displayed values longer than 120 characters are truncated and annotated with the original length, without turning a diff into equality. Metric divergences duplicated between the manifest and the metrics sidecar are deduplicated.

| verdict | CLI exit code | Meaning |
| --- | ---: | --- |
| `IDENTICAL_CORE` | 0 | All non-exempt JSON core quantities are exactly identical, still subject to capture/model scope limits |
| `DIVERGED` | 1 | At least one core diff; the divergence list is non-empty |
| `INPUT_UNRESOLVED` | 2 | The input cannot be resolved |
| `INPUT_HASH_MISMATCH` | 3 | An input matched but its SHA/size does not |
| `RECONSTRUCTION_REJECTED` | 4 | The current code rejects the rebuilding or a classified simulation execution |
| `EVIDENCE_INVALID` | 5 | The original bundle is invalid, a declared sidecar is invalid, or it is not v4 |

The artifact validator recomputes the registered metric families from the manifest's own result records for v4 evidence, so a metrics value that contradicts the result section (for example a consistently rewritten `makespanSeconds`) is `EVIDENCE_INVALID` before any rerun. The rerun remains the check for fully consistent forgeries and for condition tampering that changes the recomputed metrics.

Usage/output-directory errors are 64, unexpected exceptions are 70. Classified failures still write a diagnostic report but yield no successful new-run evidence; usage errors or unexpected I/O failures do not guarantee a complete report, and any files left behind must not be taken as a valid bundle. A failure of the Maven compile phase means this CLI verdict was never reached.

Validation coverage of the current chain is in the [core comparison tests](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/EvidenceCoreDifferTest.java>), the [rerun integration tests](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/RerunDiffExecutorIntegrationTest.java>), and the [online-action rerun tests](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/DataflowAssignmentRerunIntegrationTest.java>). They verify behavior with explicit fixtures and do not take optional local historical outputs as proof that the current run succeeded.

Study-protocol integrity and the actual rerun are different checks; see [protocol identities](<CAMPAIGNS.md#protocol-identities>). Retain the original evidence, the new run, and the diff report; old fields, protocol identity, or golden values must not be modified to erase a genuine divergence.

### Registered-algorithm evidence

Rerunning evidence produced by a registry-labeled algorithm resolves
`configuration.registeredSchedulingAlgorithm` through the SPI registry and fails fast when the
label is not registered on the rerunning classpath. Behavior anchors: the
[registered powered rerun chain](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/RegisteredPoweredRerunIntegrationTest.java>)
and the [axis-reject locks](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/RegisteredAxesRejectIntegrationTest.java>).
A registered run with a failure model or task-cost matrix the provider's declaration rejects is
refused at the registry gate before any simulation happens.

### Absent versus zero energy fields

Current manifests always carry the per-VM power leaves (`/platform/vms/N/idlePowerWatts`, `/platform/vms/N/maxPowerWatts`) and the `/energyMetrics` section. Evidence without them compares an *absent* key against a **canonical zero** on the rerun side, and these two states are **semantically equal**:

- the equivalence applies only to the two pointers above and the whole `/energyMetrics` section, only when one side is absent — **an explicit JSON `null` reads as absent** (absent, JSON null, and canonical `0.0` are the three "undeclared" forms) — and only when every number on the present side is exactly `0.0` (verified recursively, including the `vmEnergyJoules` map);
- **nonzero energy is never exempt**: powered originals that lose energy on rerun stay `DIVERGED`, and any nonzero-vs-nonzero mismatch stays a core divergence;
- this is not a whitelist entry: the values remain core quantities.

Configuration rebuilding forwards both power fields from the manifest into the rebuilt `VmSpec` (missing keys read as 0.0; negative or non-numeric values are rejected explicitly). Reruns of powered evidence therefore reproduce the energy metrics bit-exactly: same engine, same seed, identical busy intervals, identical joules. Behavior anchors: [energy rerun compatibility tests](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/EnergyRerunCompatibilityIntegrationTest.java>) and the rebuilder round-trip test.

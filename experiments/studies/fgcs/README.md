# FGCS Study Protocol

This study supports the manuscript *WfLedger: An Auditable Discrete-Event Simulator for
Scientific Workflow Scheduling* (Future Generation Computer Systems). Java drivers live under
`experiments/src/main/java/org/workflowsim/experiments/fgcs/` with tests in the matching test
tree; this directory holds the protocol ([protocol.json](<protocol.json>),
[overhead/protocol.json](<overhead/protocol.json>)) and the upstream differential
([differential/](<differential/compare.py>)). Generated results go to the ignored `paper/tmp/`
workspace.

The study measures controlled simulator behavior and evidence-production cost. It makes no
production-cluster, universal-ranking, or cross-model equivalence claim.

## Matrix

| Study | Design | Rows |
|---|---|---:|
| External differential | 3 hand-derived DAG shapes and 3 real DAX families; static HEFT and online Min-Min; two seeds | 24 runs, 12 paired cases |
| Sensitivity | Montage 50, CyberShake 50, Inspiral 50; VM count 1/2/4/8; speed ratio 1/2/4; storage 1/15/100 MB/s; static HEFT and ready-batch Min-Min | 216 |
| Energy | Five DAX families; GreenHEFT, MOHEFT, default HEFT, MIN_POWER, EDP_HEURISTIC, DVFS slack, non-monotonic-tier counterexamples | 36 |
| Communication/storage ablation | Pre-execution transfer, endpoint contention, Fat-tree, coherent-file V2, bounded-storage V3, topology and file-system switches | 18 |
| Failure recovery | Three hand-derived DAGs and three DAX families; disabled controls, failure/retry cases, enabled-no-injection controls | 12 |
| Evidence overhead | Montage 50/1000/5000; manifest, metrics, events in every mode, optional lifecycle sidecar, Java/Python validation; five fresh-JVM measurements per scenario | 45 |
| Fixed-capacity sensitivity | Three workflows; 500 MIPS total per VM count; three static planners and three online schedulers | 540 |
| Common-deadline energy | Five workflows × five deadline factors × three policies, plus five default references | 80 |
| Manifest-guided reconstruction | Genuine V2/V3, volatile metadata, semantic divergence, invalid evidence, input failures, reconstruction refusal | 9 cases |
| Semantic contracts | Control-ready scoring and data gating, tiered energy with unused VM and drain, two-checkpoint CRCH | 3 runs |
| Same-layer static planners | Seven DAX instances; HEFT, CPOP, PEFT on the baseline platform | 21 |
| Adversarial evidence | Two genuine V2/V3 bundles and nine semantic corruptions with recomputed integrity fields | 11 bundles |
| Model microbenchmarks | Progressive filling, replica visibility, output drain, CRCH retry | 6 runs |

Static planning and online scheduling are separate decision layers; rows from different physical
models or power groups are not pooled.

## Execution

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-fgcs-matrix.py --repetitions 5 --output paper/tmp/fgcs-round2
```

`--standard-only` runs the 306 non-overhead rows, `--overhead-only` the 45 overhead rows, and
`--verify-only` checks retained results. Each overhead scenario runs in its own JVM with one
unreported warm-up and five measured repetitions. `scripts/run-fgcs-revision.py` executes and
verifies the second-round studies under `paper/tmp/fgcs-round2/`.

Clean-checkout acceptance runs the Maven gate, the matrix, and every driver in a detached worktree:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/reproduce-fgcs-clean.py \
  --worktree /private/tmp/fgcs-clean-reproduction-round2 --reference paper/tmp/fgcs-round2
```

Timing, heap, and evidence-byte fields are environment observations, not identity requirements.

[protocol.json](<protocol.json>) (`FGCS_ROUND2_MATRIX_V4`) declares
`executionSemantics = WORK_CONSERVING_TASK_EXECUTION_V3`. The runners reject any
`result.manifest.json` that declares another identity or none, and record it in
`run-metadata.json` / `revision-metadata.json`. The "V2/V3" labels of the reconstruction and
adversarial rows refer to coherent dataflow models, not to the execution identity.

`scripts/package-fgcs-artifact.py --validate` builds a versioned local package: it relocates
declared input paths into the archive namespace without changing input bytes, revalidates
source/derived/sidecar mappings, runs the Java and Python validators, the differential comparison,
and portable V2/V3 `RerunDiffExecutor` cases. Inside the package, `python3 verify-artifact.py
--semantic` repeats those checks.

## Input populations

Sensitivity, energy, and differential runs use classic Pegasus DAX instances, not production
traces; small chain/fan/independent DAGs are hand-derived fixtures; the 5000-task overhead input is
the five-copy scale composite (see [baseline studies](<../../../docs/experiments/BASELINE_STUDIES.md#5000-task-composite-inputs>)).

Bounded-storage V3 needs an unambiguous producer per file, and several DAX files repeat file names.
For lifecycle overhead only, `FgcDatasets.normalizeForStorage` creates a **derived unique-producer
input**: each producer output gets a unique name, matching parent inputs are rewritten, and
multiple matching parent outputs become separate input declarations. Runtimes, edges, and output
sizes are preserved. A `*.derivation.json` sidecar records source and derived identities, counts,
and the rule. Derived inputs are not interchangeable with raw DAX inputs. `smallDag` keeps the
original bytes as `raw-<kind>.dax` and never normalizes in place.

## Overhead measurement

All overhead modes use the same derived input, LOCAL coherent-storage V3, RANDOM planning, STATIC
dispatch, and storage platform. OFF writes a zero-byte event stream, events mode adds ordinary
events, lifecycle mode adds the V3 sidecar. Java validation runs in every mode; Python validation
runs for OFF and lifecycle and is reported as NA for event-only bundles.

The record separates simulation, serialization, in-memory metric audit, and serialized Java and
Python validation; artifact bytes are split into manifest, metrics, events, and sidecar. Sampled
total heap use is reported separately from the non-simultaneous heap-pool peak sum. Timing reports
median, range, quartiles, IQR, and paired mode deltas. In OFF mode, event-dependent waiting and
slowdown are null with a reason, not zero.

Every reported row needs a completed workflow, equal logical and successful task counts, and
recomputation residuals within tolerance. A configuration error, incomplete run, validator failure,
or event-cap truncation stops the affected matrix.

## Sub-studies

### Upstream differential

`differential/` compares 12 paired cases of the current exporter with WorkflowSim 1.0 at the commit
pinned in [upstream-lock.json](<differential/upstream-lock.json>). It is a narrow differential
observation, not a correctness oracle.

| Condition | Current | Upstream |
|---|---|---|
| Cases | chain, fan, independent, Montage 50, CyberShake 50, SIPHT 60 × two scheduler labels | Same DAX bytes |
| Online scheduler | `READY_BATCH_MINMIN` or `STATIC` | legacy `MINMIN`; `STATIC` direct |
| STATIC planner | `SHARED_STORAGE_HEFT` | classic `HEFT` with point-to-point communication |
| Data | `SHARED`, `legacyWorkflowsimV1()` | original `SHARED` |
| Platform | four SPACE_SHARED VMs at 500/1000/2000/4000 MIPS, IDs 1–4 | same MIPS, IDs 0–3 (normalized +1) |
| Other | NONE clustering, zero overhead, seed label `s7` | NONE clustering, zero overhead |

[compare.py](<differential/compare.py>) derives its expectations from the registered matrix and the
DAX graphs: exact task coverage, success status on both sides, valid VM IDs and times, and no child
starting before its parent Job finishes (tolerance `1e-7` s). Makespans, mappings, and emission
orders are reported, not required to match: the HEFT rows compare different planner semantics, so a
difference there is a semantic observation, not a defect. Makespan is the maximum compute-Job
`jobFinish`.

```bash
# verify retained observations (no network, Maven, or Java)
PYTHONDONTWRITEBYTECODE=1 python3 experiments/studies/fgcs/differential/compare.py \
  --observations paper/tmp/experiments/differential/upstream-observations.json \
  --output experiments/studies/fgcs/differential/comparison.json
PYTHONDONTWRITEBYTECODE=1 python3 experiments/studies/fgcs/differential/test_compare.py -v

# full reproduction into a new or empty work directory (network and build)
PYTHONDONTWRITEBYTECODE=1 python3 experiments/studies/fgcs/differential/compare.py \
  --reproduce --work-dir paper/tmp/fgcs-differential-reproduction
```

Reproduction downloads the pinned archive (or uses `FGCS_UPSTREAM_ARCHIVE`), checks its size and
digest against the lock file, compiles upstream sources with
[UpstreamRunner.java.txt](<differential/UpstreamRunner.java.txt>), runs
`UpstreamDifferentialExporter`, and invokes each upstream case in a fresh JVM.
`scripts/prepare-fgcs-differential.py` is the packaging step that regenerates the observations and
`comparison.json`.

### Same-layer static planners

`StaticPlannerBaselineStudy` runs `SHARED_STORAGE_HEFT`, `_CPOP`, and `_PEFT` on Montage 50/100,
CyberShake 50/100, SIPHT 60, Inspiral 50, and Epigenomics 46 with the platform of
`MakespanBaselineComparison.platform()` (four VMs at 500–2000 MIPS, prices 1/2/4/8 per second,
15 MB/s storage, seed 7): 21 runs, reported with mapping, makespan, waiting, slowdown, energy,
cost, and audit residuals.

```bash
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fgcs.baseline.StaticPlannerBaselineStudy
```

### Semantic contracts

`SemanticContractBenchmarks` (test `SemanticContractBenchmarksTest`) runs three real simulations
whose expected values are hand-derived from the
[online assignment](<../../../docs/advanced/ONLINE_DATAFLOW_ASSIGNMENT_CONTRACT.md>),
[coherent dataflow](<../../../docs/advanced/COHERENT_DATAFLOW_CONTRACT.md>),
[storage dataflow](<../../../docs/advanced/STORAGE_DATAFLOW_CONTRACT.md>), and
[energy](<../../../docs/advanced/ENERGY_DIMENSION_DESIGN.md>) contracts and the
[CRCH contract](<../../../docs/algorithms/CONTRACTS.md#crch-synchronized-checkpoint-recovery-r-paper-11-2026-11-08>).
All workflows arrive at 100 s.

| Case | Hand-derived expectation |
|---|---|
| `control-ready-target-score-binding-and-data-gate` | At t=100 the 10 s parent selects fast VM 9 (score 110) over slow VM 3 (120); the 20 s filler also selects VM 9 (130 vs 140). At t=110 the child scores fast `max(110,130)+1=131`, slow `max(110+1,110)+2=113`, binds VM 3, transfers 1 MB at 1 MB/s during [110,111], and executes during [111,113]. Task and Job bindings agree |
| `two-tier-default-idle-unused-vm-and-terminal-drain` | The 2000 MI task runs on the 500 MI/s low tier during [100,104]; the unused 1 MB output drains at 0.5 MB/s during [104,106], so simulation end is 106 and terminal drain 2 s. The 110 MI stage-in Job takes 0.11 s on the 1000 MI/s default tier. Default-tier idle 10 W over [0,106] on both VMs; used VM adds `(30−10)·4=80 J` and `(100−10)·0.11=9.9 J`: used 1149.9 J, unused 1060 J, total 2209.9 J, mean 1104.95 J, fleet peak 200 W, EDP 234249.4 J·s. The low tier's 1 W idle discriminates selected-tier idle from default-tier idle |
| `crch-two-checkpoints-and-third-checkpoint-boundary` | The 3000 MI root runs at 1000 MI/s from t=100 with `λ=1 s`, `γ=0.1 s`: checkpoints after 1 s and 2 s of progress, so the attempt executes `3000+2·100=3200 MI`; no third checkpoint. A deterministic failure at 102.5 (Weibull shape 1e300, scale 102.5) ends the attempt after both checkpoints (at 1.1 s and 2.2 s): 2000 MI saved on VM 3, 1000 MI remain. Resuming on VM 3 costs 1.0 s versus 3.2 s on VM 8, so the retry runs on VM 3 over [102.5,103.5]; no restore term; the child becomes ready at 103.5 |

`FailureRecoveryStudy` (test `FailureRecoveryStudyTest`) classifies every row from its report:
`EXERCISED_FAILURE_RETRY` (at least one failed Job and one retry, workflow complete),
`ENABLED_NO_INJECTION_CONTROL` (model enabled, zero failures), or `FAILURE_DISABLED_CONTROL`.
Failures target depth 1 on the fastest initial VM with a periodic stream (Weibull shape 1e300,
period 5 s; SIPHT 1000 s), recorded in `protocol.json` as `recovery.injection`.

```bash
mvn -q -pl :workflowsim-workbench -am \
  -Dtest=SemanticContractBenchmarksTest,FailureRecoveryStudyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fgcs.contracts.SemanticContractBenchmarks
```

### Model microbenchmarks

`ModelMicrobenchmarks` (test `ModelMicrobenchmarksTest`) runs six simulations with hand-derived
expectations:

| Case | Expectation |
|---|---|
| Two-flow bottleneck | 2 MB and 6 MB flows share a 1 MB/s store read at 0.5 MB/s each; the small flow ends at 4 s, the large is refilled to 1 MB/s and ends at 8 s. Independent paths end at 2 s and 6 s. A fixed capacity/n model would predict 4 s and 12 s |
| Replica visibility / CPU barrier | A child needs 1 MB and 4 MB inputs on independent 1 MB/s paths; one data-ready release occurs at the slower settlement (4 s) and CPU start cannot precede it |
| Required output write-back | An unused 1 MB output takes 2 s at a 0.5 MB/s store write; CPU return does not wait, simulation end does. The V2 control has no output copy and zero drain |
| CRCH retry | The 2000 MI parent with one checkpoint executes 2100 MI over [0.21, 2.31); a deterministic failure at 1.5 saves 1000 MI on VM 3; the retry runs 1000 MI on VM 3 over [1.5, 2.5); the child is released only after the successful return |

Network expectations use `COPY_ADMITTED.observedTime` and `COPY_SETTLED.payload.effectiveTime`.
The driver removes a stale `results.json` before running and fails on any invalid fixture or
mismatch.

```bash
mvn -q -pl :workflowsim-workbench -am \
  -Dtest=ModelMicrobenchmarksTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fgcs.microbench.ModelMicrobenchmarks
```

### Manifest-guided reconstruction

`ManifestGuidedRerunStudy` (test `ManifestGuidedRerunStudyTest`) calls the real
`RerunDiffExecutor.execute(Path, Path)` on genuine manifest-v4 bundles from a four-task chain
(runtimes 10/20/30/40 s, two VMs, LOCAL, RANDOM + STATIC). It is re-simulation plus exact core diff
under the [rerun contract](<../../../docs/experiments/RERUN_DIFF_CONTRACT.md>), not event replay.

| Case | Expected |
|---|---|
| `genuine-v2-control`, `genuine-v3-control` | `IDENTICAL_CORE` |
| `relocated-v2-input-path` (byte-identical input under `<study>/inputs/`) | `IDENTICAL_CORE` with three volatile path notes |
| `volatile-v3-manifest-metadata` (`runtime.osName`, `provenance.core.codeSource`) | `IDENTICAL_CORE` with two volatile notes |
| `semantic-v2-runtime-scale` (`runtimeScale` 1.0 → 2.0; a 10 s Task goes from 10000 to 20000 MI) | `DIVERGED` at `/result/tasks/0/effectiveExecutionLengthMi` |
| `evidence-invalid-v2-makespan` (end advanced 0.5 s, stale metric, integrity fields repaired) | `EVIDENCE_INVALID` naming `makespanSeconds` |
| `input-v2-hash-mismatch` (last byte flipped) | `INPUT_HASH_MISMATCH` |
| `input-v3-unresolved` | `INPUT_UNRESOLVED` |
| `reconstruction-v3-unsupported-vm-scheduler` | `RECONSTRUCTION_REJECTED` |

```bash
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fgcs.rerun.ManifestGuidedRerunStudy \
  '-Dexec.args="paper/tmp/fgcs-round2/rerun"'
```

The output must be empty or absent; `results.json` (schema
`fgcs-manifest-guided-rerun-results-v1`) records expected and actual verdicts per case, and `main`
fails when `summary.unexpectedOutcomes` is non-zero.

### Adversarial evidence

`AdversarialEvidenceStudy` (test `AdversarialEvidenceStudyTest`) corrupts genuine V2/V3 bundles
semantically, then repairs every artifact integrity field, event sequence, and counter, and runs the
Java `ExperimentArtifactValidator` and `scripts/verify-network-ledger.py`. Expected verdicts are
fixed from the [file lifecycle](<../../../docs/advanced/FILE_LIFECYCLE_FORMAT.md>) and
[storage](<../../../docs/advanced/STORAGE_DATAFLOW_CONTRACT.md>) contracts.

| Case | Java | Python |
|---|---|---|
| `v2-parent-before-child-request` | reject | reject |
| `v2-task-identity-mismatch` | reject | reject |
| `v2-source-provenance-mismatch` | reject | reject |
| `v2-reordered-decision-dispatch` | accept | reject |
| `v2-missing-simulation-terminal` | reject | accept |
| `v2-makespan-metric-mismatch` | reject | accept |
| `v2-energy-metric-out-of-scope` | accept | accept |
| `v3-output-settlement-missing` | reject | reject |
| `v3-source-provenance-mismatch` | reject | reject |

Accept entries are declared coverage boundaries: the Java path does not check equal-time
decision/dispatch order, the Python lifecycle path does not enforce the terminal marker or recompute
makespan, and neither path recomputes energy (that is `verify-study-evidence.py`'s job). They do not
make corrupted evidence valid.

```bash
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fgcs.verification.AdversarialEvidenceStudy
```

## Non-claims

No exhaustive model coverage, complete paper-corpus reproduction, packet-level network fidelity,
universal energy-policy ranking, or equivalence between raw and derived inputs is claimed. Only
protocols, the compact differential comparison, adapters, and verifier tests are source-controlled;
a clean reproduction must regenerate the results.

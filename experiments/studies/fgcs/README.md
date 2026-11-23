# FGCS Study Protocol

## Scope

This study supports the manuscript **WfLedger: An Auditable Discrete-Event Simulator for Scientific Workflow Scheduling**, prepared for Future Generation Computer Systems. It separates experiment drivers from manuscript sources: Java drivers and tests belong to the Maven `experiments` module, this directory retains the protocol, and generated results remain under the ignored `paper/tmp/` workspace.

The study measures controlled simulator behavior and evidence-production costs. It does not claim production-cluster calibration, universal algorithm optimality, or equivalence between different data-movement and planner semantics.

## Matrix

| Study | Design | Required rows |
|---|---|---:|
| External differential | 3 hand-derived DAG shapes and 3 real DAX families, static HEFT and online Min-Min, two current seeds | 24 current runs; 12 paired reference cases |
| Sensitivity | Montage 50, CyberShake 50, Inspiral 50; VM count 1/2/4/8; speed ratio 1/2/4; storage rate 1/15/100 MB/s; static HEFT and ready-batch Min-Min | 216 |
| Energy | Five real DAX families; GreenHEFT, MOHEFT, default HEFT, MIN_POWER, EDP_HEURISTIC, DVFS slack, and non-monotonic-tier counterexamples | 36 |
| Communication/storage ablation | Pre-execution transfer without contention, endpoint contention, Fat-tree, coherent-file V2, bounded-storage V3, topology and file-system switches | 18 |
| Failure recovery | Three hand-derived DAGs and three real DAX families; disabled controls, actual failure/retry cases, and explicitly labeled enabled-no-injection controls | 12 |
| Evidence overhead | Montage 50/1000/5000; common manifest/metrics/event stream in every mode, optional lifecycle sidecar, serialized Java/Python validation; balanced order and five fresh-JVM measurements per scenario | 45 |
| Fixed-capacity sensitivity | Three workflows; fixed total 500 MIPS per VM count; three static planners and three online schedulers; duplicate one-VM ratios removed | 540 |
| Common-deadline energy | Five workflows × five deadline factors × three policies, plus five default references; identical deadline and feasibility rules per cell | 80 |
| Manifest-guided reconstruction | Genuine V2/V3, volatile metadata, semantic divergence, invalid evidence, input failures, and reconstruction refusal | 9 verdict cases |
| New semantic contracts | Control-ready candidate scoring/data gating, absolute tiered energy with unused VM/drain, and two-checkpoint CRCH boundary | 3 simulations |
| Same-layer static planners | Seven classic DAX instances; HEFT, CPOP, PEFT under the original baseline platform | 21 |
| Adversarial evidence | Two genuine V2/V3 bundles and nine semantic corruptions with valid recomputed hashes | 11 bundles / 18 corruption observations |
| Model microbenchmarks | Progressive filling, replica visibility, output drain, and deterministic CRCH retry | 6 simulations / 4 contracts |

Static planning and online ready-job scheduling are separate decision layers. Rows from different physical models or power groups are not pooled as a single algorithm ranking.

## Execution

From the repository root with JDK 17+ and Maven 3.6.3+:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-fgcs-matrix.py --repetitions 5 --output paper/tmp/fgcs-round2
```

Use `--standard-only` for the 306 non-overhead rows or `--overhead-only` for the 45 overhead rows. Use `--verify-only` to check retained results without rerunning them. Every overhead scenario runs in its own JVM, with one unreported warm-up and five measured repetitions. Simulations are serial within each JVM.

The standard drivers are under `experiments/src/main/java/org/workflowsim/experiments/fgcs/`; their tests are under the corresponding test tree. The Workbench JSON option `simulation.eventRecordingEnabled` defaults to `true` and must not be `false` together with lifecycle/network evidence capture.

For the final clean-checkout acceptance, use `PYTHONDONTWRITEBYTECODE=1 python3 scripts/reproduce-fgcs-clean.py --worktree /private/tmp/fgcs-clean-reproduction-round2 --reference paper/tmp/fgcs-round2`. It runs the full Maven gate, the matrix, and every second-round driver in a detached worktree. Comparisons cover the current matrix, static and fixed-capacity sensitivity, common-deadline energy, all 78 original baseline/scale rows, adversarial bundles, analytic contracts, and manifest-guided reconstruction verdicts. Timing, heap, and evidence-byte fields are environment observations, not cross-directory identity requirements. Logs belong to `paper/tmp/clean-reproduction-round2/`; older reports remain historical records.

## Revision evidence and controlled overhead

The second-round protocol adds fixed-capacity and common-deadline studies, manifest-guided reconstruction, and new control-ready/energy/recovery contracts. `scripts/run-fgcs-revision.py` executes and verifies every study under `paper/tmp/fgcs-round2/`. The adversarial results distinguish expected rejection, genuine acceptance, and explicit coverage-boundary acceptance; they are not summarized as universal validator coverage. Reconstruction is re-simulation plus exact core diff, not event replay.

`scripts/package-fgcs-artifact.py --validate` creates a versioned local package. It records original manifest hashes, relocates declared input paths into the archive namespace without changing input bytes, and resolves every packaged input from the extracted root with explicit size/SHA-256 checks. Source/derived/sidecar mappings are revalidated to reject stale derivation files. Java/Python bundle validators, a normalized full differential comparison, and actual portable V2/V3 `RerunDiffExecutor` cases run before packaging. `validation/portable-rerun/` retains those reports. Inside the package, `python3 verify-artifact.py --semantic` repeats integrity, input-identity, derivation, lifecycle, and differential checks.

The revised overhead matrix uses the same derived unique-producer input, LOCAL coherent-storage V3, RANDOM planner, STATIC dispatch, and storage platform for OFF, ordinary events, and full lifecycle modes. Every mode writes a common manifest, metrics sidecar, and events JSONL; OFF has a zero-byte event stream, events adds ordinary events, and lifecycle adds the V3 sidecar. Serialized Java validation runs in every mode, while Python validation is measured for OFF and lifecycle and explicitly NA for event-only bundles. The earlier matrix that changed inputs, file systems, planners, and physical models is not used as recording-cost evidence.

The older 18-row communication/storage matrix is smoke coverage, not a quantitative ablation of execution effects. The model microbenchmarks provide the discriminating evidence: progressive-filling service times, per-file visibility/CPU barriers, required-output terminal drain, and deterministic CRCH retry semantics.

## Differential evidence

The upstream adapter, lock file, comparison table, and mutation tests are retained in `experiments/studies/fgcs/differential/`. The upstream implementation is not bundled into the project. `scripts/prepare-fgcs-differential.py` is a required pipeline step before packaging: it regenerates `upstream-observations.json` and `comparison.json` from the pinned upstream archive and the current exporter. The comparison normalizes the reference's zero-based VM IDs to the current one-based IDs and checks task completion and successful parent/child Job envelopes independently of algorithm mapping.

Upstream HEFT uses classic point-to-point communication estimates. The current `SHARED_STORAGE_HEFT` planner uses shared-storage semantics. Therefore different mappings or runtimes on real workflows are semantic/planning observations, not proof of a defect. The small hand-derived HEFT cases and online Min-Min cases provide the narrowest comparison; no upstream result is an unconditional oracle.

## Input populations

The sensitivity, energy, and legacy differential runs use classic Pegasus characterization DAX instances, not production execution traces. Small chain/fan/independent DAGs are hand-derived controlled fixtures. The 5000-task overhead input is the existing five-copy scale fixture, not a newly collected real trace.

`smallDag` preserves the original bytes as `raw-<kind>.dax` before producing the normalized input, so the derivation sidecar always refers to distinct raw and derived identities; the output is never normalized in place. The bounded-storage V3 runtime requires an unambiguous producer identity. Several original DAX files contain repeated file names or inconsistent declarations. For lifecycle overhead only, `FgcDatasets.normalizeForStorage` creates a **derived unique-producer input**: each producer output receives a unique name, matching parent inputs are rewritten, and multiple matching parent outputs are explicitly expanded into separate input declarations. Runtime values, DAG edges, and producer output sizes are retained; file identity and the input population are adapted. A sidecar `*.derivation.json` records source and derived SHA-256 hashes, counts, and the rule. These derived inputs are not interchangeable with the raw DAX population and must not be used for direct legacy-versus-V3 performance rankings.

## Measurements and stopping conditions

Every reported row must have a successfully completed workflow, equal logical and successful task counts, and independent recomputation residuals within the driver tolerance. OFF runs must retain zero events; enabled ordinary-event and lifecycle runs must retain nonempty event streams. Lifecycle runs must produce nonempty evidence artifacts. Any configuration error, incomplete run, validator failure, or event-cap truncation stops the affected matrix rather than silently dropping a case.

The overhead record distinguishes simulation, serialization and size accounting, in-memory metric audit, serialized Java validation, and serialized Python validation where applicable. Artifact bytes are split into manifest, metrics, event stream, and lifecycle sidecar. A sampled total-heap-used approximation is reported separately from the non-simultaneous heap-pool peak sum; neither is a minimum-memory requirement. Timing distributions include median, range, quartiles, IQR, and paired mode deltas. OFF event-dependent waiting and slowdown values are null with an availability reason rather than numeric zero.

## Retention and non-claims

Generated results, evidence sidecars, logs, normalized inputs, and compilation artifacts are retained locally under `paper/tmp/fgcs-round2/`; older directories are historical references. Only protocols, compact differential comparisons, source adapters, and verifier tests are source-controlled. A clean reproduction must regenerate the reports and validate the retained evidence; an old result file is not itself proof of correctness.

The study does not claim exhaustive model coverage, a complete paper-corpus reproduction, real-network packet fidelity, a universal energy policy ranking, or equivalence between raw and unique-producer-derived inputs.

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
| Failure recovery | Three hand-derived DAGs and three real DAX families; disabled failure versus single-VM CRCH failure/retry | 12 |
| Evidence overhead | Montage 50/1000/5000; recording OFF, ordinary events, and complete V3 lifecycle; one warm-up plus five measured runs per scenario | 45 |
| Same-layer static planners | Seven classic DAX instances; HEFT, CPOP, PEFT under the original baseline platform | 21 |
| Adversarial evidence | Two genuine V2/V3 bundles and nine semantic corruptions with valid recomputed hashes | 11 bundles / 18 corruption observations |
| Model microbenchmarks | Progressive filling, replica visibility, output drain, and deterministic CRCH retry | 6 simulations / 4 contracts |

Static planning and online ready-job scheduling are separate decision layers. Rows from different physical models or power groups are not pooled as a single algorithm ranking.

## Execution

From the repository root with JDK 17+ and Maven 3.6.3+:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-fgcs-matrix.py --repetitions 5
```

Use `--standard-only` for the 306 non-overhead rows or `--overhead-only` for the 45 overhead rows. Use `--verify-only` to check retained results without rerunning them. Every overhead scenario runs in its own JVM, with one unreported warm-up and five measured repetitions. Simulations are serial within each JVM.

The standard drivers are under `experiments/src/main/java/org/workflowsim/experiments/fgcs/`; their tests are under the corresponding test tree. The Workbench JSON option `simulation.eventRecordingEnabled` defaults to `true` and must not be `false` together with lifecycle/network evidence capture.

For the final clean-checkout acceptance, use `PYTHONDONTWRITEBYTECODE=1 python3 scripts/reproduce-fgcs-clean.py --worktree /tmp/fgcs-clean-reproduction --reference paper/tmp/fgcs-revision`. It runs the full Maven gate and matrix in a detached worktree, then compares core results with `scripts/compare-fgcs-results.py`. Timing and evidence-byte fields are environment observations, not cross-directory identity requirements.

## Revision evidence and controlled overhead

The revised protocol adds same-layer static planning, adversarial artifact validation, and discriminating model microbenchmarks. `scripts/run-fgcs-revision.py` executes and verifies those studies under `paper/tmp/fgcs-revision/`. The adversarial results distinguish expected rejection, genuine acceptance, and explicit coverage-boundary acceptance; they are not summarized as universal validator coverage.

The revised overhead matrix uses the same derived unique-producer input, LOCAL coherent-storage V3, RANDOM planner, STATIC dispatch, and storage platform for OFF, ordinary events, and full lifecycle modes. It changes only recording configuration within each task size. The earlier matrix that also changed raw/derived inputs, file systems, planners, and physical models is retained only as historical material and is not used as isolated recording-cost evidence.

The older 18-row communication/storage matrix is smoke coverage, not a quantitative ablation of execution effects. The model microbenchmarks provide the discriminating evidence: progressive-filling service times, per-file visibility/CPU barriers, required-output terminal drain, and deterministic CRCH retry semantics.

## Differential evidence

The upstream adapter, lock file, comparison table, and mutation tests are retained in `experiments/studies/fgcs/differential/`. The upstream implementation is not bundled into the project. The comparison normalizes the reference's zero-based VM IDs to the current one-based IDs and checks task completion and successful parent/child Job envelopes independently of algorithm mapping.

Upstream HEFT uses classic point-to-point communication estimates. The current `SHARED_STORAGE_HEFT` planner uses shared-storage semantics. Therefore different mappings or runtimes on real workflows are semantic/planning observations, not proof of a defect. The small hand-derived HEFT cases and online Min-Min cases provide the narrowest comparison; no upstream result is an unconditional oracle.

## Input populations

The sensitivity, energy, and legacy differential runs use classic Pegasus characterization DAX instances, not production execution traces. Small chain/fan/independent DAGs are hand-derived controlled fixtures. The 5000-task overhead input is the existing five-copy scale fixture, not a newly collected real trace.

The bounded-storage V3 runtime requires an unambiguous producer identity. Several original DAX files contain repeated file names or inconsistent declarations. For lifecycle overhead only, `FgcDatasets.normalizeForStorage` creates a **derived unique-producer input**: each producer output receives a unique name, matching parent inputs are rewritten, and multiple matching parent outputs are explicitly expanded into separate input declarations. Runtime values, DAG edges, and producer output sizes are retained; file identity and the input population are adapted. A sidecar `*.derivation.json` records source and derived SHA-256 hashes, counts, and the rule. These derived inputs are not interchangeable with the raw DAX population and must not be used for direct legacy-versus-V3 performance rankings.

## Measurements and stopping conditions

Every reported row must have a successfully completed workflow, equal logical and successful task counts, and independent recomputation residuals within the driver tolerance. OFF runs must retain zero events; enabled ordinary-event and lifecycle runs must retain nonempty event streams. Lifecycle runs must produce nonempty evidence artifacts. Any configuration error, incomplete run, validator failure, or event-cap truncation stops the affected matrix rather than silently dropping a case.

The overhead record distinguishes simulation nanoseconds, artifact-write nanoseconds, independent audit nanoseconds, event count, evidence bytes, pre-run heap usage, observed heap-pool peak usage, and current-thread allocated bytes. These are JVM observations on the declared host, not calibrated hardware measurements. Timing distributions are reported across repetitions; no deterministic algorithm is assigned a p-value from identical reruns.

## Retention and non-claims

Generated results, evidence sidecars, logs, normalized inputs, and compilation artifacts are retained locally under `paper/tmp/experiments/` and `paper/tmp/fgcs-revision/`. Only the protocol, compact differential comparison, source adapter, and verifier tests are source-controlled. A clean reproduction must regenerate the reports and validate the retained evidence; an old result file is not itself proof of correctness.

The study does not claim exhaustive model coverage, a complete paper-corpus reproduction, real-network packet fidelity, a universal energy policy ranking, or equivalence between raw and unique-producer-derived inputs.

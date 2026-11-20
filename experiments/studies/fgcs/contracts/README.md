# FGCS round-two semantic-contract oracles

## Scope and evidence rule

This protocol closes three semantic-coverage gaps with **actual serial `SimulationRunner` executions**. It supplements, rather than modifies, the existing model microbenchmarks and aggregate FGCS matrices. It makes no production-cluster calibration, algorithm-ranking, or cross-layer comparison claim.

Every expected number is hand-derived from a project contract or conservation law before execution. No observed simulator aggregate is copied into an expectation. All three workflows arrive at declared simulation time 100 s, which makes their analytic intervals absolute without treating bootstrap event cadence as a physical service time.

The driver is `org.workflowsim.experiments.fgcs.contracts.SemanticContractBenchmarks`; its focused test is `SemanticContractBenchmarksTest`. Contract sources are:

- `docs/advanced/ONLINE_DATAFLOW_ASSIGNMENT_CONTRACT.md`
- `docs/advanced/COHERENT_DATAFLOW_CONTRACT.md`
- `docs/advanced/STORAGE_DATAFLOW_CONTRACT.md`
- `docs/advanced/ENERGY_DIMENSION_DESIGN.md`
- the CRCH section of `docs/algorithms/CONTRACTS.md`

## Cases and absolute oracles

| Case | Real-runner path | Hand-derived expectation |
|---|---|---|
| `control-ready-target-score-binding-and-data-gate` | `CONTROL_READY_ONLINE_ASSIGNMENT_V1` / coherent-file V2 / the production `DataflowVmAssigner` | At t=100, the 10 s parent selects fast VM 9 (score 110) over slow VM 3 (120). The 20 s filler sees the parent reservation: fast `max(100,110)+20=130`, slow 140, so it also selects VM 9. At parent completion t=110, the child sees the started filler reservation through t=130: fast `max(110,130)+1=131`; slow `max(110+1,110)+2=113`. The child must bind VM 3, transfer 1 MB at 1 MB/s during [110,111], become data-ready at 111, and execute during [111,113]. The worse fast score 131 is a negative candidate oracle. Task and Job bindings must agree. |
| `two-tier-default-idle-unused-vm-and-terminal-drain` | Coherent-storage V3 with explicit online STATIC binding, two identical declared two-tier VMs, `MIN_POWER`, frozen metrics and `EnergyAccounting` | The 2000 MI task uses the 500 MI/s low tier during [100,104]. The unused 1 MB output drains at 0.5 MB/s during [104,106], so simulation end is 106 and terminal drain is 2 s. The contract-generated 110 MI stage-in Job contributes 0.11 s on the 1000 MI/s default tier. Default-tier idle is 10 W over [0,106] on **both** VMs. The used VM adds low-tier dynamic `(30-10)*4=80 J` and stage-in dynamic `(100-10)*0.11=9.9 J`; expected used energy is 1149.9 J, unused energy 1060 J, total 2209.9 J, mean 1104.95 J, fleet peak 200 W, and EDP 234249.4 J·s. Both tiers are exercised. The low tier declares 1 W idle to discriminate incorrect use of selected-tier idle instead of default-tier idle. The unused VM has zero busy seconds. |
| `crch-two-checkpoints-and-third-checkpoint-boundary` | CRCH, fixed seed 7, VM/depth-keyed `FAILURE_VM_JOB`, one real failed parent and retry | The 3000 MI root runs at 1000 MI/s during [100,103.0]. Checkpoint admission uses cumulative compute plus one 0.1 s overhead: 1.1 and 2.1 s fit, but 3.1 s does not. Exactly two checkpoints save 2000 MI; 1000 MI remains and 100 MI recovery overhead gives 1100 MI retry work. The retry moves from VM 3 to VM 8, executes 1.1 s, and finishes at 104.1. Only its successful return may release the child. The zero value for `thirdCheckpointCommitted` is a negative boundary oracle. |

Control-ready assignment and repeated/boundary recovery are both supported by the existing real-runner configuration surface; no core-semantic blocker or production change is needed. One supported-domain constraint matters for reproduction: a 0.001 s exponential stream needs more than `FailureGenerator`'s 50 cache extensions to cover a task starting at t=100. The fixture therefore uses a deterministic 0.01 s root-depth stream, which stays inside that boundary; it does not alter the 3.0 s checkpoint envelope.

## Recovery-matrix repair

`FailureRecoveryStudy` retains its 12 paired rows but no longer treats an enabled configuration as proof of recovery. CRCH selects the fastest idle VM, so the former VM-3-only generator could remain unused for an entire chain or dependency-heavy workflow. The study now targets depth 1 on fastest initial VM 4, uses one-based depth-indexed generator rows (slot zero is padding), and leaves retry destinations on long-scale generators.

Every row is classified from its actual report:

- `EXERCISED_FAILURE_RETRY` (`-crch-failure`): at least one failed Job and one retry are mandatory, their counts must agree, and the workflow must complete.
- `ENABLED_NO_INJECTION_CONTROL` (`-crch-no-injection-control`): the failure model remains enabled, but both failed-Job and retry counts are zero. The fixed-seed run is repeated under this explicit label after a zero-injection probe; it is never presented as exercised recovery.
- `FAILURE_DISABLED_CONTROL` (`-no-failure`): injection is disabled and both counts are zero.

The study JSON includes `recoveryClassification`, `failureModelEnabled`, `failedJobs`, and `retryJobsCreated`. A partial failure/retry outcome or an invalid control aborts the study. This classification is especially important for chain, Montage, and CyberShake pairs whose disabled/enabled event counts may legitimately be identical.

## Fail-fast validity and evidence layout

The default output directory is the ignored `paper/tmp/fgcs-round2/contracts/` workspace. The driver deletes a stale `results.json` before execution and writes a passing document only after all expectations pass. Missing assignment actions, wrong candidate sets or bindings, a local rather than remote gating input, missing output drain, wrong tier attribution, missing failure/retry, premature child release, incomplete workflows, and truncated lifecycle evidence are fatal.

Output files are:

- `results.json`: three case objects containing `expected`, `observed`, a textual `derivation`, and relative JSON-pointer `evidence` references.
- `control-ready.events.json`: all three full assignment actions and the child input/readiness/CPU events.
- `tiered-energy.events.json`: model-derived task window, output admission/settlement, termination, declared intervals, and observed accounting values.
- `crch-two-checkpoints.events.json`: failed and retry task windows, failure/retry/return ordering, and child release.
- `inputs/`: the three deterministic hand-authored DAX fixtures.

The focused test independently repeats the analytic assertions against the in-memory reports, resolves every evidence pointer (including array indices), checks selected pointed values, and verifies that retained `results.json` equals the returned document.

## Execution

From the repository root with JDK 17+ and Maven 3.6.3+:

```bash
mvn -q -pl :workflowsim-workbench -am \
  -Dtest=SemanticContractBenchmarksTest,FailureRecoveryStudyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The extra Surefire property only prevents reactor modules without those test names from rejecting the selection. Generate the compact semantic-contract evidence with:

```bash
mvn -q -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fgcs.contracts.SemanticContractBenchmarks
```

An optional first argument selects another fresh output directory. Generated inputs and observations are not source-controlled protocol files.

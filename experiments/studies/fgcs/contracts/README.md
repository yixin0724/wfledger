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
| `crch-two-checkpoints-and-third-checkpoint-boundary` | CRCH, fixed seed 7, VM/depth-keyed `FAILURE_VM_JOB`, one real failed parent and retry | FGCS-R5 (2026-11-24) re-derivation. The 3000 MI root runs at 1000 MI/s from t=100 with `λ = 1 s`, `γ = 0.1 s`. Checkpoints are taken after 1 s and 2 s of progress but not at completion, so the attempt executes `3000 + 2·100 = 3200 MI` over [100, 103.2); a third checkpoint is never planned (`thirdCheckpointPlanned = 0`, negative boundary oracle). Checkpoint k completes `k·1.1 s` into compute. The deterministic VM 3 root-depth arrival at 102.5 (Weibull shape 1e300, scale 102.5) ends the attempt 2.5 s into compute: checkpoints at 1.1 and 2.2 s save 2000 MI on VM 3 and 1000 MI remains. Resuming on VM 3 costs 1000 MI with no checkpoint (1.0 s); VM 8 would re-execute 3200 MI (3.2 s), so the retry stays on VM 3 over [102.5, 103.5]. No restore term is charged. Only the successful return may release the child, which becomes ready at 103.5. |

Control-ready assignment and repeated/boundary recovery are both supported by the existing real-runner configuration surface; no core-semantic blocker or production change is needed. Since FGCS-R5 (2026-11-24) a failure ends its attempt at the first failure arrival inside the compute window, so the fixture's failure instant is an input of the oracle. The root-depth stream is therefore deterministic: a Weibull shape of 1e300 makes every inverse-CDF sample round to exactly the scale (102.5 s), so the only arrival before the second (205 s) lies inside the root's compute window. The former random 0.01 s stream would end the attempt before any checkpoint.

## Recovery-matrix repair

`FailureRecoveryStudy` retains its 12 paired rows but no longer treats an enabled configuration as proof of recovery. CRCH selects the fastest idle VM, so the former VM-3-only generator could remain unused for an entire chain or dependency-heavy workflow. The study now targets depth 1 on fastest initial VM 4, uses one-based depth-indexed generator rows (slot zero is padding), and leaves retry destinations on long-scale generators. FGCS-R5 (2026-11-24): because failures now end attempts mid-execution, the injected stream is periodic (Weibull shape 1e300, period 5 s; Sipht keeps its conservative 1000 s period). The former 0.01 s and 1 s random streams ended every VM 4 root attempt before its first 1.1 s checkpoint and exhausted the fixed retry budget of 20; a 2 s period still exhausted it on the real-DAX rows. The period is a fixture parameter recorded in `protocol.json` (`recovery.injection`), not an expected value.

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

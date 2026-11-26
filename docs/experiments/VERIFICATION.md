# Verification and Validation

This document states what the platform verifies, which test or command fails when a verified
property breaks, which expected-value sources are legal, and what is deliberately out of scope.
It covers code verification and evidence verification only. Reproducibility and internal
consistency are never presented as real-platform validity, and the ledger **does not produce a
single credibility score**: capability and results are reported separately, and a failed or
unexamined case cannot be averaged away.

Method references: [NASA-STD-7009B](https://standards.nasa.gov/standard/NASA/NASA-STD-7009),
[Sargent's V&V framework](https://doi.org/10.1057/jos.2012.20), and
[ASME V&V 20](https://www.asme.org/codes-standards/find-codes-standards/standard-for-verification-and-validation-in-computational-fluid-dynamics-and-heat-transfer).
They are methodological references, not a claim of standards certification.

## 1. Legal expected-value sources

An expected value in a test must come from one of the following classes. Copying production
output into an expectation is forbidden; the only exception is an explicitly labeled golden
regression, whose job is to detect drift, not to prove correctness.

| Class | Meaning and permitted claim |
|---|---|
| `PAPER` | A primary-literature table, figure, or formula with an independent hand derivation; supports its stated semantic domain only. Record DOI/page and transcription provenance |
| `CONTRACT` | A value derived from the current project contract: units, state transitions, fail-fast rules |
| `INVARIANT` | A conservation, partition, non-overlap, ordering, capacity, or identity law derived independently of the implementation |
| `REFERENCE_IMPLEMENTATION` | A fixed-commit upstream or independent implementation, restricted to its declared model intersection; see [reference implementation](<REFERENCE_IMPLEMENTATION.md>) |
| `GOLDEN_REGRESSION` | A labeled frozen result used only to detect drift; its original input and protocol must still be recorded |

Real-system observations additionally require the platform, scheduler, clustering, network,
WMS, data-collection procedure, and measurement uncertainty. A workflow instance's real
provenance alone does not supply these conditions.

## 2. Case record and assessment states

Every research claim or benchmark case must be reviewable from these fields:

| Field | Required meaning |
|---|---|
| `caseId` | Stable ASCII identifier; never reused for a different protocol |
| `intendedUse` | The decision or scientific question the evidence supports, and its criticality |
| `codeIdentity` | Git commit of the code that produced the evidence |
| `protocolIdentity` | Protocol logical ID and bytes; a revised protocol receives a new identity |
| `inputIdentity` | Input paths, order, formats, sizes, normalization, and population label |
| `referentIdentity` | Paper, analytic model, reference implementation, or real-system observation |
| `verificationMethod` | Hand derivation, contract, invariant, reference differential, rerun, or independent recomputation |
| `validationPoint` | Exact input, platform, model, configuration, and output variables examined |
| `metricAndThreshold` | Comparison metric and acceptance criterion, registered before execution |
| `expectedValueSource` | One of the legal classes in §1 |
| `result` | Outcome, uncertainty or sampling boundary, and evidence location |
| `coverage` | Conditions checked and conditions not checked |
| `warnings` | Declaration-only fields, unquantified uncertainty, and forbidden extrapolations |
| `review` | Reviewer, independence, disposition, and accepted residual risk |

`CAPABILITY` covers data pedigree, code verification, validation, technical review, and process
management. `RESULTS` covers use assessment, input pedigree, uncertainty characterization,
robustness, and review of the use. Each is reported independently.

| State | Meaning |
|---|---|
| `VERIFIED` | The named check passed at the stated validation point; not a broader validity claim |
| `DIVERGED` | A comparison failed and needs a disposition before the claim can be used |
| `NOT_ESTABLISHED` | Required evidence or referent is missing; no positive claim is allowed |
| `NOT_APPLICABLE` | Outside the declared use, with a written reason |
| `ACCEPTED_RISK` | A known limitation retained explicitly, with scope and consequence |

A missing or `NOT_ESTABLISHED` case is not a passing case.

## 3. Verified anchors

| Property | Class | Tests | Mutants |
|---|---|---|---|
| HEFT ten-task example (ranks, first placement, non-overlap, dependency timing) | `PAPER` | `LocalHeftPlanningAlgorithmPaperTableTest` | — |
| SHARED HEFT/CPOP on the Topcuoglu 2002 DAG with zero communication (ranks, critical path, windows, 33.21 s makespan) | `PAPER` (adapted) | `SharedStorageTopcuogluZeroCommunicationGoldenTest` | — |
| PEFT Figure 1 / Tables 5–6 | `PAPER` | `LocalPeftPrimarySourcePaperTest`; provenance in [peft-paper-example.SOURCE.md](<../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md>) | — |
| Braun independent-task ECT table (MCT, MinMin, MaxMin, Sufferage, MET, OLB) | `PAPER` | `StaticIndependentGoldenTableTest` | — |
| Small-DAG planning reference (topological DP, exhaustive paths, permutation invariance) | `INVARIANT` | `PlanningDagReferencePropertyAuditTest` | — |
| Energy and carbon accounting | `INVARIANT` | `EnergyConservationLawTest`, `CarbonAccountingTest`, Python energy/carbon auditors | — |
| Max-min transfer service (caps, bottlenecks, duplicate resources, order/scale invariance) | `INVARIANT` | `MaxMinTransferTest` | — |
| CloudSim 3.0.3 event order and Space-Shared work | `REFERENCE_IMPLEMENTATION` | `CloudSimKernelReferenceTest`, `CloudletSchedulerReferenceTest` | M24, M25 |
| Legacy WorkflowSim SHARED transfer envelope | `REFERENCE_IMPLEMENTATION` | `ControlledSharedStorageIndependentReferenceTest` | M26 |
| Classic DAX and Montage input structure (§6) | `CONTRACT` | `ClassicDaxStructuralTest`, `MontageWfInstancesStructuralTest`, `WfCommonsRuntimeReferenceTest` | M27 |
| WfFormat 1.5/1.6 schema gate | `CONTRACT` | `WfCommonsJsonParserValidationTest` | M23 |
| Exact tied-rank Wilcoxon (actual average ranks; SciPy reference when available) | `INVARIANT` | `PairedWilcoxonExternalReferenceTest` | M28 |
| Kernel tick at 0.1, 0.05, 0.025 s | `CONTRACT` | `SimulationTickSensitivityTest` | M29 |
| Campaign summary recomputed from validated per-run evidence | `INVARIANT` | `ExperimentCampaignSummaryVerifier` tests | M22 |
| Dataflow state profile semantic fingerprints (no timing threshold) | `CONTRACT` | `DataflowStateProfileSmokeTest` | M31 |
| CaWoSched (16 variants, communication activities, exact DP, timed dispatch) | `PAPER` / `CONTRACT` | `CaWoSchedPlannerTest`, `CaWoSchedAdapterTest`, `TimedPlanFrameworkTest`, `CommunicationEnhancedPlanTest`, `CaWoSchedExactOptimizerTest`, `CaWoSchedTimedExecutionIntegrationTest` | M32–M35 |
| CWS RankMin-RR (Figure 1 DAG, 5.21 → 4.21 direction) | `PAPER` | `CwsDagRankTest`, `CwsRankMinRoundRobinSchedulingAlgorithmTest`, `CwsFigureOneIntegrationTest` | M36, M61 |
| NACO GA/HEFT + DNC | `PAPER` / `CONTRACT` | `NacoDncEvaluatorTest`, `NacoGaHeftOptimizerTest`, `NacoGaHeftIntegrationTest` | M37, M38, M62 |
| Reshi-C/M | `PAPER` | `ReshiSchedulingAlgorithmTest`, `ReshiIntegrationTest`, `ReshiPredictionErrorModelTest` | M39, M60 |
| GreenHEFT | `PAPER` | `GreenHeftPlannerTest`, `GreenHeftIntegrationTest`, `EnergyPlannerRuntimeMirrorIntegrationTest` | M40, M56, M57 |
| MOHEFT | `PAPER` / `CONTRACT` | `MoHeftPlannerTest`, `MoHeftIntegrationTest`, `EnergyPlannerRuntimeMirrorIntegrationTest` | M41, M42 |
| IC-PCP | `PAPER` / `CONTRACT` | `IcpcpPlannerTest`, `IcpcpIntegrationTest` | M43, M44 |
| EBPSM (SFTD, Algorithm 3 budget update, cache preference, budget gate) | `PAPER` / `CONTRACT` | `EbpsmBudgetDistributorTest`, `EbpsmBudgetLedgerTest`, `EbpsmSchedulingAlgorithmTest`, `EbpsmIntegrationTest` | M46, M47, M63, M71 |
| DVFS slack allocation | `PAPER` / `CONTRACT` | `DvfsSlackOptimizerTest`, `DvfsSlackIntegrationTest`, `DvfsSlackRuntimeMirrorIntegrationTest` | M48, M54, M55 |
| CRCH checkpoint recovery (saved work, VM-local credit, mid-execution failure) | `PAPER` | `CrchRegistryTest`, `CrchSchedulingAlgorithmTest`, `CrchIntegrationTest` | M49, M50, M64, M67, M69, M70, M72–M75 |
| DHEFT | `PAPER` | `DheftPlannerTest`, `DheftIntegrationTest` | M51, M52, M58 |
| Cross-paper comparison scenes (§5) | `CONTRACT` | `CrossPaperComparisonTest` | M53 |
| Cross-family metric recomputation (§5) | `PAPER` / `CONTRACT` / `INVARIANT` | `AlgorithmMetricAuditTest` | M54–M65 |
| Experiment matrix and retry dependency gate (§5) | `INVARIANT` / `PAPER` | `AlgorithmExperimentAuditTest`, `CrchIntegrationTest` | M66, M67 |
| Large-scale and campaign makespans | `GOLDEN_REGRESSION` | `LargeWorkflowScaleRegressionTest`, `FatTreeCampaignGoldenIntegrationTest` | — |

The registered mutants and their killer tests are listed in the [mutation gate](<../advanced/MUTATION_GATE.md>).

## 4. Evidence verification

### Evidence dimensions

Each dimension has a positive case that must pass and tamper cases that must be refused.

| Dimension | Positive | Tampering refused |
|---|---|---|
| Energy conservation | `CarbonAccountingTest.intensityUnitLawHoldsOnARealRun`; recomputation in `verify-study-evidence.py` | `CarbonAccountingTest` corruption cases; auditor self-test `energy-bad` |
| Carbon (intensity) | `CarbonAccountingTest.tieredPlatformIntensityRidesTheConservedPerTierEnergy` | Tampered `carbon.totalGrams`, `perVmCarbonGrams`, or intensity |
| Carbon (budget windows) | `CarbonAccountingTest.tieredPlatformBudgetCarbonUsesThePerTierFleetPowerCurve`, `mixedFleetBudgetCarbonMixesTieredAndFlatContributions` | Tampered `window.greenWatts` or `totalCarbonExcessWattSeconds` |
| DVFS per-tier ledger | Auditor per-tier recomputation | Tampered `tier[0].mips`, `tier[0].maxPowerWatts`, `job.tierMips`; self-test bundles `tier-speed-stale`, `tier-undeclared` |
| Network ledger | `verify-network-ledger.py` on producer fixtures | `test-network-ledger.py` negative fixtures |
| File lifecycle V2 | Fixture verification through `_file_lifecycle_audit` | `test-file-lifecycle.py` negative cases |
| Storage lifecycle V3 | Fixture verification through `_storage_lifecycle_audit` | `test-storage-lifecycle.py` negative cases |
| Event stream | Ordering, sequence, and count checks plus the declared `events.revisionContract` | Out-of-order time, duplicate sequence, blank line, wrong `eventCount` or sequence bounds, unknown contract; with the contract declared, a stream missing `SIMULATION_TERMINATED` or execution markers |
| Simulation metrics v4 | Java validator recomputation and `verify-study-evidence.py` derived metrics | Consistently tampered `makespanSeconds` or `meanJobVmQueueWaitingTimeSeconds`, shifted terminal clock, broken start derivation, uncovered executed Job |
| Dataset indexes | `check-dataset-indexes.py` on the standard checkout | Wrong recorded path or size, missing retained file, family index size disagreeing with the top-level index, unindexed file, count drift |

### Rerun verdicts under tampering

Measured against a real bundle with `RerunDiffExecutor`; the verdicts are defined in the
[rerun contract](<RERUN_DIFF_CONTRACT.md>).

| Tampering | Verdict | Exit |
|---|---|---|
| `configuration.vmCount`, `schema`, artifact digest or size, missing artifact, `workflowProfile.taskCount`, consistently tampered `metrics.makespanSeconds` | `EVIDENCE_INVALID` | 5 |
| Input identity | `INPUT_HASH_MISMATCH` | 3 |
| `platform.vms[].mips`, `energyMetrics`, scheduling label, `rootSeed` on a stochastic workload | `DIVERGED` | 1 |
| Malformed tier table, unknown `frequencyPolicy`, windowless deferral label | `RECONSTRUCTION_REJECTED` | 4 |
| Unchanged evidence | `IDENTICAL_CORE` | 0 |

`SeedSensitivityRerunIntegrationTest` proves the seed row both ways: a stochastic failure/retry
workload reruns `IDENTICAL_CORE`, and changing the recorded seed diverges. A tampered seed on a
deterministic workload cannot diverge, because the rerun reproduces the recorded conditions.

### Cross-language field sweep

A real bundle (intensity model, and tiered plus budget model) is mutated one field at a time; the
whole run directory, including the events sidecar, is copied, and `verify-study-evidence.py`
must reject. The sweep covers energy, metrics, Job intervals and identities, carbon grams and
excess, window and tier tables, intensity, event count, and sequence bounds. No mutation of a
field that feeds a recomputed quantity survives.

The three evidence sidecars are swept the same way through their own auditors:

| Sidecar | Mutated fields |
|---|---|
| Network ledger | Admitted payload, serviced balance, modeled rate area, dropped trace event, engine time, empty bindings, schema, accounting version |
| File lifecycle V2 | Retained-record count, dropped event, certificate scope, observation watermark |
| Storage lifecycle V3 | Retained-record count, dropped event, certificate scope, missing `sourceStorage` |
| Manifest and events | Dropped event, flipped `networkEvidence.mode`, artifact reference of role `network-ledger` |

Two fields are declaration-only and cannot be cross-checked from a bundle: the `taskIds` of a
stage-in envelope (empty by construction) and `platform.vms[].pes` (enters no audited formula for
single-PE workloads). See [reproducibility](<REPRODUCIBILITY.md>).

## 5. Algorithm and metric audits

### Metric recomputation

`AlgorithmMetricAudit` recomputes, independently of the production path, the simulation-end
bounds, the logical-completion tail, flat and tiered energy, and runtime processing cost.
`AlgorithmMetricAuditTest` runs it for SHARED and LOCAL baselines, GreenHEFT, MOHEFT, IC-PCP,
DHEFT, NACO, CaWoSched, CWS, Reshi-C/M, EBPSM, CRCH, and DVFS slack. Degenerate scenes in which
all algorithms produce the same mapping are not accepted as evidence of equivalence.

- `makespanSeconds` is the simulation-end clock, not the finish time of the last compute Job.
- `terminalLifecycleTailSeconds = makespanSeconds − logicalTaskCompletionSeconds` when available.
- Flat energy per VM is `P_idle × makespan + (P_max − P_idle) × busy`; tiered energy charges
  default-tier idle power over the full makespan plus each assigned tier's dynamic busy power.
  Peak power uses the maximum declared tier power.
- Runtime cost is CPU-envelope cost plus declared-file bandwidth cost under the configured
  `DATACENTER` or `VM` cost model. It is neither a provider invoice nor IC-PCP's planner cost,
  which is read directly from the planner schedule.
- SHARED and LOCAL physical models, and planner and runtime objectives, are never merged into
  one population.

### Experiment matrix

`AlgorithmExperimentAuditTest` runs real `SimulationRunner` experiments across DAG shapes, VM
resources, file sizes, failure modes, and seeds, and checks invariants and metamorphic relations
rather than the plausibility of a makespan.

| Group | Scenarios | Algorithms | Seeds | Runs |
|---|---|---|---:|---:|
| SHARED | Reverse-ID chain, fan-out/fan-in with duplicate OUTPUT, independent tasks, large files | SHARED_HEFT, GreenHEFT, MOHEFT, IC-PCP | 7, 11 | 32 |
| LOCAL | Same DAG and resource families | LOCAL_HEFT, DHEFT, NACO GA/HEFT | 7, 11 | 24 |
| Online | Reverse-ID chain, fan-out/fan-in, independent tasks | CWS, Reshi-C, Reshi-M | 7, 11 | 18 |
| Special | Carbon slack/pressure, DVFS tiers, CRCH failure, EBPSM staggered workflows | Algorithm-specific | 7 | 4 |

Every run checks successful completion, simulation-end bounds, the logical-completion tail,
flat and tiered energy, peak power, runtime cost, and that a child starts only after its
parents' successful attempts. Residual thresholds are `< 1e-6` in native units. Metamorphic
cases double task work and check the direction of makespan and energy, and verify that IC-PCP
rejects an infeasible deadline. `CrchIntegrationTest` checks that a failed parent's child starts
only after the successful retry and that the recovery window matches its hand derivation.

### Cross-paper comparison scenes

`CrossPaperComparisonTest` runs compatible algorithm subsets on one two-task chain (100 bytes of
data, `runtimeReferenceMips=1.0`, `randomSeed=7`, two VMs with declared costs and heterogeneous
power, explicit `CostModel.VM`). SHARED uses `LEGACY_WORKFLOWSIM_V1`; LOCAL uses
`PRE_EXECUTION_TRANSFER_DELAY_V1`. The test records task-to-VM mappings, recomputes each VM's
flat-power energy with a `< 1e-6 J` residual, attaches IC-PCP planner cost directly from the
planner, and requires at least three distinct SHARED mappings so that an all-identical comparison
cannot pass. Identical metrics are expected whenever two algorithms choose the same mapping; they
are not evidence of algorithm equivalence. The LOCAL scene (LOCAL_HEFT, DHEFT) is
non-discriminating; DHEFT's bandwidth and parent-finish semantics are verified by
`DheftPlannerTest`.

## 6. Application input verification

| Population | Source | Verification scope |
|---|---|---|
| Classic DAX | 20 Pegasus DAX inputs under `datasets/dax` (the `dax/heft` paper fixture is not counted) | XML job count, parser task count, DAG validity, input format |
| Montage WfInstances | [WfInstances v1.5](https://zenodo.org/records/12510982), 17 JSON instances under `datasets/wfinstances/v1.5/pegasus/montage` | Specification/execution task counts, DAG, runtime-to-MI conversion, file identity and bytes, schema version, explicitly missing execution fields |

- `ClassicDaxStructuralTest` checks all 20 classic DAX files against job counts computed
  independently from the XML namespace.
- `MontageWfInstancesStructuralTest` checks the one Montage file tracked in a standard checkout
  (58 tasks and the declared missing fields).
- `MontageExtendedCorpusTest` checks all 17 Montage files but runs only under the
  `extended-corpus` Maven profile, after the optional local snapshot and its index are prepared
  (`make extended-corpus`). The default gate excludes `*ExtendedCorpusTest`.
- `WfCommonsJsonParserValidationTest` covers 1.5/1.6 acceptance, rejection of unknown versions
  and fields, and Nextflow `repo`/`runName` provenance.

The parser uses `runtimeInSeconds` and `sizeInBytes` directly. With
`runtimeReferenceMips=1000` and `runtimeScale=1` each task length is
`max(100, floor(runtimeInSeconds × 1000))` MI; file sizes remain bytes. The Montage snapshot has
no `coreCount`, `readBytes`, or `writtenBytes`; these values are unknown, not zero, and are not
inferred from file sizes. The three input families stay separate (see
[datasets](<../../datasets/README.md>)).

## 7. Gate commands

```bash
mvn -Pjavadoc clean verify                                          # unit, integration, coverage, javadoc
make quality-gate                                                   # Maven + Python + docs + language + datasets
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py   # auditor self-tests
python3 -B scripts/check-dataset-indexes.py                         # dataset indexes (paths and sizes)
python3 -B scripts/mutation-gate.py --all-modules --output <new>.json   # registered mutants
```

Command details are in the [build guide](<../getting-started/BUILD.md>).

## 8. Boundaries

- `REAL-PLATFORM`: `NOT_ESTABLISHED`. No production-cluster referent with platform, scheduler,
  clustering, network, WMS, and measurement uncertainty exists; structural input checks and
  agreement with another simulator are not empirical validation.
- No claim of exact paper-table reproduction, full-corpus coverage, global optimality, complete
  Pareto coverage, or real-network packet fidelity.
- Registered mutants are semantic canaries, not an exhaustive mutation score.
- Golden regressions detect drift only. Frozen evidence supports its registered evidence
  contract, not regeneration under the current code.
- The four Python contract auditors (V1 network, V2 file lifecycle, V3 storage, online
  assignment) share one core, `_network_audit.py`. Their independence is independence from the
  Java production path, not from each other.
- The exact Wilcoxon path is limited to 25 effective nonzero differences; the normal
  approximation keeps its continuity and tie limitations.
- Tick is a declared model parameter, not a numerical-convergence or network-latency calibration.
- Five bidirectional Java package pairs remain (data/network, data.v2/utils,
  experiment.registry/scheduling, experiment.registry/utils, failure/utils);
  `PackageDependencyGuardTest` locks the inventory so cycles cannot grow.
- Budget windows are outside the Workbench v1 surface: `CARBON_WINDOW_DEFER` is rejected at
  Workbench validation and is available through the `SimulationRunner` API.
- Any semantic, input, referent, platform, or metric change reopens the affected cases.

## 9. Change discipline

1. Register the intended use, metric, threshold, and referent before running a new study.
2. Prefer a paper, contract, or invariant over a golden. If a golden is unavoidable, label it
   `GOLDEN_REGRESSION` in the test and in §3.
3. Write the failing test first for a new verification rule or defect fix, and register a
   semantic canary for a new research-critical path, demonstrating its kill red-first.
4. Preserve original inputs, protocols, and evidence. Audit only fresh restorations and write
   reports to new paths; never edit an archive to make a new implementation appear compatible.
5. Record differences instead of hiding them through renamed labels, edited goldens, or new
   whitelist exemptions.
6. Performance evidence also needs environment, JVM, heap, scale, sampling, semantic
   fingerprints, and copy counts. Timing is descriptive and never substitutes for correctness.
